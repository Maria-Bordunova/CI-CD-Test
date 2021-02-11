package com.qonversion.android.sdk

import android.app.Activity
import android.app.Application
import android.util.Pair
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.SkuDetails
import com.qonversion.android.sdk.ad.AdvertisingProvider
import com.qonversion.android.sdk.billing.*
import com.qonversion.android.sdk.converter.GooglePurchaseConverter
import com.qonversion.android.sdk.converter.PurchaseConverter
import com.qonversion.android.sdk.dto.QLaunchResult
import com.qonversion.android.sdk.dto.QPermission
import com.qonversion.android.sdk.dto.products.QProduct
import com.qonversion.android.sdk.dto.eligibility.QEligibility
import com.qonversion.android.sdk.dto.offerings.QOfferings
import com.qonversion.android.sdk.extractor.SkuDetailsTokenExtractor
import com.qonversion.android.sdk.logger.Logger
import com.qonversion.android.sdk.storage.PurchasesCache
import com.qonversion.android.sdk.storage.LaunchResultCache

class QProductCenterManager internal constructor(
    private val context: Application,
    private val repository: QonversionRepository,
    private val logger: Logger,
    private val purchasesCache: PurchasesCache,
    private val launchResultCache: LaunchResultCache
) : QonversionBillingService.PurchasesListener {

    private var listener: UpdatedPurchasesListener? = null
    private val isLaunchingFinished: Boolean
        get() = launchError != null || launchResult != null
    private var isProductsLoaded: Boolean = false

    private var forceLaunchRetry: Boolean = false

    private var isProductsLoadingFailed: Boolean = false

    private var skuDetails = mapOf<String, SkuDetails>()

    private var launchResult: QLaunchResult? = null
    private var launchError: QonversionError? = null

    private var productsCallbacks = mutableListOf<QonversionProductsCallback>()
    private var permissionsCallbacks = mutableListOf<QonversionPermissionsCallback>()
    private var experimentsCallbacks = mutableListOf<QonversionExperimentsCallback>()
    private var purchasingCallbacks = mutableMapOf<String, QonversionPermissionsCallback>()

    private var installDate: Long = 0

    private var converter: PurchaseConverter<Pair<SkuDetails, Purchase>> =
        GooglePurchaseConverter(SkuDetailsTokenExtractor())

    @Volatile
    lateinit var billingService: BillingService
        @Synchronized set
        @Synchronized get

    @Volatile
    lateinit var consumer: Consumer
        @Synchronized set
        @Synchronized get

    init {
        installDate = context.packageManager.getPackageInfo(
            context.packageName,
            0
        ).firstInstallTime.milliSecondsToSeconds()
    }

    // Public functions

    fun onAppForeground() {
        handlePendingPurchases()
    }

    fun setUpdatedPurchasesListener(listener: UpdatedPurchasesListener) {
        this.listener = listener
    }

    fun launch(
        callback: QonversionLaunchCallback? = null
    ) {
        val adProvider = AdvertisingProvider()
        val launchCallback: QonversionLaunchCallback = getLaunchCallback(callback)

        adProvider.init(context, object : AdvertisingProvider.Callback {
            override fun onSuccess(advertisingId: String) {
                continueLaunchWithPurchasesInfo(advertisingId, launchCallback)
            }

            override fun onFailure(t: Throwable) {
                continueLaunchWithPurchasesInfo(callback = launchCallback)
            }
        })
    }

    fun loadProducts(
        callback: QonversionProductsCallback
    ) {
        productsCallbacks.add(callback)
        if (!isProductsLoaded || !isLaunchingFinished) {
            return
        }

        executeProductsBlocks()
    }

    fun offerings(
        callback: QonversionOfferingsCallback
    ) {
        loadProducts(object : QonversionProductsCallback {
            override fun onSuccess(products: Map<String, QProduct>) =
                executeOfferingCallback(callback)

            override fun onError(error: QonversionError) = callback.onError(error)
        })
    }

    fun experiments(
        callback: QonversionExperimentsCallback
    ) {
        experimentsCallbacks.add(callback)

        if (!isLaunchingFinished) {
            return
        }

        if (launchResult != null) {
            executeExperimentsBlocks()
        } else {
            launch()
        }
    }

    fun checkTrialIntroEligibilityForProductIds(
        productIds: List<String>,
        callback: QonversionEligibilityCallback
    ) {
        loadProducts(object : QonversionProductsCallback {
            override fun onSuccess(products: Map<String, QProduct>) {
                val storeIds = products.map { it.value.skuDetail?.sku }.filterNotNull()
                repository.eligibilityForProductIds(
                    storeIds,
                    installDate,
                    object : QonversionEligibilityCallback {
                        override fun onSuccess(eligibilities: Map<String, QEligibility>) {
                            val resultForRequiredProductIds =
                                eligibilities.filter { it.key in productIds }
                            callback.onSuccess(resultForRequiredProductIds)
                        }

                        override fun onError(error: QonversionError) = callback.onError(error)
                    })
            }

            override fun onError(error: QonversionError) = callback.onError(error)
        })
    }

    private fun executeOfferingCallback(callback: QonversionOfferingsCallback) {
        val offerings = getActualOfferings()
        if (offerings != null) {
            offerings.availableOfferings.forEach { offering ->
                addSkuDetailForProducts(offering.products)
            }
            callback.onSuccess(offerings)
        } else {
            val error = launchError ?: QonversionError(QonversionErrorCode.OfferingsNotFound)
            callback.onError(error)
        }
    }

    private fun getActualOfferings(): QOfferings? {
        var offerings = launchResult?.offerings
        if (launchResult == null) {
            val cachedLaunchResult = launchResultCache.load()
            cachedLaunchResult?.let { offerings = it.offerings }
        }
        return offerings
    }

    private fun addSkuDetailForProducts(products: Collection<QProduct>) {
        products.forEach { product ->
            product.skuDetail = skuDetails[product.storeID]
        }
    }

    fun purchaseProduct(
        context: Activity,
        id: String,
        oldProductId: String?,
        @BillingFlowParams.ProrationMode prorationMode: Int?,
        callback: QonversionPermissionsCallback
    ) {
        if (launchError != null) {
            launch(object : QonversionLaunchCallback {
                override fun onSuccess(launchResult: QLaunchResult) {
                    if (isProductsLoaded && !isProductsLoadingFailed) {
                        processPurchase(context, id, oldProductId, prorationMode, callback)
                    } else {
                        productsCallbacks.add(object : QonversionProductsCallback {
                            override fun onSuccess(products: Map<String, QProduct>) {
                                processPurchase(context, id, oldProductId, prorationMode, callback)
                            }

                            override fun onError(error: QonversionError) {
                                callback.onError(error)
                            }
                        })
                    }
                }

                override fun onError(error: QonversionError) {
                    callback.onError(error)
                }
            })
        } else {
            processPurchase(context, id, oldProductId, prorationMode, callback)
        }
    }

    private fun processPurchase(
        context: Activity,
        id: String,
        oldProductId: String?,
        @BillingFlowParams.ProrationMode prorationMode: Int?,
        callback: QonversionPermissionsCallback
    ) {
        val product: QProduct? = productForID(id)
        val oldProduct: QProduct? = productForID(oldProductId)

        if (product?.storeID == null) {
            callback.onError(QonversionError(QonversionErrorCode.ProductNotFound))
            return
        }

        val purchasingCallback = purchasingCallbacks[product.storeID]
        purchasingCallback?.let {
            logger.release("purchaseProduct() -> Purchase with id = $id is already in progress. This one call will be ignored")
            return
        }

        val skuDetail = skuDetails[product.storeID]
        val oldSkuDetail = skuDetails[oldProduct?.storeID]
        if (skuDetail != null) {
            purchasingCallbacks[product.storeID] = callback
            billingService.purchase(context, skuDetail, oldSkuDetail, prorationMode)
        } else {
            val launchResult = launchResult
            if ((isProductsLoaded && !isProductsLoadingFailed) || launchResult == null) {
                val error = QonversionError(QonversionErrorCode.SkuDetailsError)
                callback.onError(error)
                return
            }

            loadStoreProductsIfPossible(
                launchResult,
                onLoadCompleted = { skuDetailsList ->
                    val sku = skuDetailsList.find { detail ->
                        detail.sku == product.storeID
                    }
                    if (sku != null) {
                        purchasingCallbacks[product.storeID] = callback
                        billingService.purchase(context, sku)
                    }
                }, onLoadFailed = { error ->
                    callback.onError(error.toQonversionError())
                })
        }
    }

    fun checkPermissions(
        callback: QonversionPermissionsCallback
    ) {
        permissionsCallbacks.add(callback)

        if (!isLaunchingFinished) {
            return
        }

        executePermissionsBlock()
    }

    fun restore(callback: QonversionPermissionsCallback? = null) {
        billingService.queryPurchasesHistory(onQueryHistoryCompleted = { historyRecords ->
            consumer.consumeHistoryRecords(historyRecords)
            val purchaseHistoryRecords = historyRecords.map { it.historyRecord }
            repository.restore(
                installDate,
                purchaseHistoryRecords,
                object : QonversionLaunchCallback {
                    override fun onSuccess(launchResult: QLaunchResult) {
                        updateLaunchResult(launchResult)
                        callback?.onSuccess(launchResult.permissions)
                    }

                    override fun onError(error: QonversionError) {
                        forceLaunchRetry = true
                        callback?.onError(error)
                    }
                })
        },
            onQueryHistoryFailed = {
                callback?.onError(it.toQonversionError())
            })
    }

    fun syncPurchases() {
        restore()
    }

    override fun onPurchasesCompleted(purchases: List<Purchase>) {
        handlePurchases(purchases)
    }

    override fun onPurchasesFailed(purchases: List<Purchase>, error: BillingError) {
        if (purchases.isNotEmpty()) {
            purchases.forEach { purchase ->
                val purchaseCallback = purchasingCallbacks[purchase.sku]
                purchasingCallbacks.remove(purchase.sku)
                purchaseCallback?.onError(error.toQonversionError())
            }
        } else {
            purchasingCallbacks.values.forEach {
                it.onError(error.toQonversionError())
            }
            purchasingCallbacks.clear()
        }
    }

    // Private functions

    private fun configurePurchaseInfo(
        skuDetails: Map<String, SkuDetails>,
        purchases: List<Purchase>
    ): List<com.qonversion.android.sdk.entity.Purchase> {
        val result = mutableListOf<com.qonversion.android.sdk.entity.Purchase>()

        purchases.forEach {
            val skuDetail = skuDetails[it.sku]
            if (skuDetail != null) {
                val purchaseInfo = Pair.create(skuDetail, it)
                val purchase = converter.convert(purchaseInfo)
                result.add(purchase)
            }
        }

        return result
    }

    private fun configureSkuDetails(skuDetails: List<SkuDetails>): Map<String, SkuDetails> {
        val formattedData = mutableMapOf<String, SkuDetails>()
        skuDetails.forEach {
            formattedData[it.sku] = it
        }

        return formattedData
    }

    private fun continueLaunchWithPurchasesInfo(
        advertisingId: String? = null,
        callback: QonversionLaunchCallback?
    ) {
        billingService.queryPurchases(
            onQueryCompleted = { purchases ->
                if (purchases.isEmpty()) {
                    repository.init(
                        installDate = installDate,
                        idfa = advertisingId,
                        callback = callback
                    )
                    return@queryPurchases
                }

                billingService.getSkuDetailsFromPurchases(
                    purchases,
                    onCompleted = { skuDetails ->
                        val formattedSkuDetails: Map<String, SkuDetails> =
                            configureSkuDetails(skuDetails)
                        val purchasesInfo = configurePurchaseInfo(formattedSkuDetails, purchases)
                        repository.init(installDate, advertisingId, purchasesInfo, callback)
                    },
                    onFailed = {
                        repository.init(
                            installDate = installDate,
                            idfa = advertisingId,
                            callback = callback
                        )
                    })
            },
            onQueryFailed = {
                repository.init(
                    installDate = installDate,
                    idfa = advertisingId,
                    callback = callback
                )
            })
    }

    private fun getLaunchCallback(callback: QonversionLaunchCallback?): QonversionLaunchCallback {
        return object : QonversionLaunchCallback {
            override fun onSuccess(launchResult: QLaunchResult) {
                updateLaunchResult(launchResult)
                launchError = null

                loadStoreProductsIfPossible(launchResult)

                executePermissionsBlock()
                executeExperimentsBlocks()
                handleCachedPurchases()

                callback?.onSuccess(launchResult)
            }

            override fun onError(error: QonversionError) {
                isProductsLoaded = true
                isProductsLoadingFailed = true
                launchResult = null
                launchError = error

                executePermissionsBlock()
                executeProductsBlocks()

                callback?.onError(error)
            }
        }
    }

    private fun updateLaunchResult(launchResult: QLaunchResult) {
        this@QProductCenterManager.launchResult = launchResult
        launchResultCache.save(launchResult)
        forceLaunchRetry = false
    }

    private fun loadStoreProductsIfPossible(launchResult: QLaunchResult) {
        loadStoreProductsIfPossible(launchResult, null, null)
    }

    private fun loadStoreProductsIfPossible(
        launchResult: QLaunchResult,
        onLoadCompleted: ((products: List<SkuDetails>) -> Unit)?,
        onLoadFailed: ((error: BillingError) -> Unit)?
    ) {
        val productStoreIds = launchResult.products.values.mapNotNull {
            it.storeID
        }.toSet()

        if (!productStoreIds.isNullOrEmpty() && (!isProductsLoaded || isProductsLoadingFailed)) {
            billingService.loadProducts(productStoreIds,
                onLoadCompleted = { details ->
                    isProductsLoaded = true
                    isProductsLoadingFailed = false

                    val formattedDetails: Map<String, SkuDetails> = configureSkuDetails(details)
                    skuDetails = formattedDetails.toMutableMap()

                    executeProductsBlocks()

                    onLoadCompleted?.let { it(details) }
                },
                onLoadFailed = { error ->
                    isProductsLoaded = true
                    isProductsLoadingFailed = true
                    executeProductsBlocks(error.toQonversionError())
                    onLoadFailed?.let { it(error) }
                })
        } else {
            executeProductsBlocks()
        }
    }

    private fun handleCachedPurchases() {
        val cachedPurchases = purchasesCache.loadPurchases()
        cachedPurchases.forEach { purchase ->
            repository.purchase(installDate, purchase, object : QonversionLaunchCallback {
                override fun onSuccess(launchResult: QLaunchResult) {
                    updateLaunchResult(launchResult)
                    purchasesCache.clearPurchase(purchase)
                }

                override fun onError(error: QonversionError) {}
            })
        }
    }

    private fun executeExperimentsBlocks() {
        if (experimentsCallbacks.isEmpty()) {
            return
        }

        val callbacks = experimentsCallbacks.toList()
        experimentsCallbacks.clear()

        launchResult?.experiments?.let { experiments ->
            callbacks.forEach {
                it.onSuccess(experiments)
            }
        } ?: run {
            experimentsCallbacks.forEach {
                val error = launchError ?: QonversionError(QonversionErrorCode.LaunchError)
                it.onError(error)
            }
        }
    }

    private fun executeProductsBlocks(loadingStoreProductsError: QonversionError? = null) {
        if (productsCallbacks.isEmpty()) {
            return
        }

        val callbacks = productsCallbacks.toList()
        productsCallbacks.clear()

        loadingStoreProductsError?.let {
            callbacks.handleFailureProducts(it)
            return
        }

        handleProductsCallbacks(callbacks)
    }

    private fun executePermissionsBlock() {
        if (permissionsCallbacks.isEmpty()) {
            return
        }

        val callbacks = permissionsCallbacks.toList()
        permissionsCallbacks.clear()

        handlePermissionsCallbacks(callbacks)
    }

    private fun retryLaunch(
        onSuccess: (QLaunchResult) -> Unit,
        onError: (QonversionError) -> Unit
    ) {
        launch(object : QonversionLaunchCallback {
            override fun onSuccess(launchResult: QLaunchResult) = onSuccess(launchResult)
            override fun onError(error: QonversionError) = onError(error)
        })
    }

    private fun handleProductsCallbacks(callbacks: List<QonversionProductsCallback>) {
        launchResult?.let {
            callbacks.handleSuccessProducts()
        } ?: run {
            retryLaunch(
                onSuccess = {
                    callbacks.handleSuccessProducts()
                },
                onError = { launchError ->
                    val cachedLaunchResult = launchResultCache.load()

                    if (cachedLaunchResult == null) {
                        callbacks.handleFailureProducts(launchError)
                    } else {
                        loadStoreProductsIfPossible(cachedLaunchResult,
                            {
                                callbacks.handleSuccessProducts(cachedLaunchResult.products)
                            },
                            { billingError ->
                                callbacks.handleFailureProducts(billingError.toQonversionError())
                            })
                    }
                })
        }
    }

    private fun List<QonversionProductsCallback>.handleSuccessProducts(
        products: Map<String, QProduct>? = launchResult?.products
    ) {
        if (products != null) {
            addSkuDetailForProducts(products.values)

            this.forEach {
                it.onSuccess(products)
            }
        }
    }

    private fun List<QonversionProductsCallback>.handleFailureProducts(
        error: QonversionError
    ) {
        this.forEach {
            it.onError(error)
        }
    }

    private fun handlePermissionsCallbacks(
        callbacks: List<QonversionPermissionsCallback>
    ) {
        launchResult?.let {
            val permissions = launchResult?.permissions ?: mapOf()
            callbacks.handleSuccessPermissions(permissions)
        } ?: run {
            retryLaunch(
                { launchResult ->
                    callbacks.handleSuccessPermissions(launchResult.permissions)
                },
                { error ->
                    if (forceLaunchRetry) {
                        callbacks.handleFailurePermissions(error)
                    } else {
                        val cachedLaunchResult = launchResultCache.load()

                        if (cachedLaunchResult == null) {
                            callbacks.handleFailurePermissions(error)
                        } else {
                            callbacks.handleSuccessPermissions(cachedLaunchResult.permissions)
                        }
                    }
                })
        }
    }

    private fun List<QonversionPermissionsCallback>.handleSuccessPermissions(
        permissions: Map<String, QPermission>
    ) {
        this.forEach {
            it.onSuccess(permissions)
        }
    }

    private fun List<QonversionPermissionsCallback>.handleFailurePermissions(
        error: QonversionError
    ) {
        this.forEach {
            it.onError(error)
        }
    }

    private fun productForID(id: String?): QProduct? {
        return launchResult?.products?.get(id)
    }

    private fun handlePendingPurchases() {
        if (!isLaunchingFinished) return

        billingService.queryPurchases(
            onQueryCompleted = { purchases ->
                handlePurchases(purchases)
            },
            onQueryFailed = {

            }
        )
    }

    private fun handlePurchases(purchases: List<Purchase>) {
        consumer.consumePurchases(purchases, skuDetails)

        purchases.forEach { purchase ->
            val purchaseCallback = purchasingCallbacks[purchase.sku]
            purchasingCallbacks.remove(purchase.sku)

            if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
                purchaseCallback?.onError(QonversionError(QonversionErrorCode.PurchasePending))
                return@forEach
            }

            val skuDetail = skuDetails[purchase.sku] ?: return@forEach

            val purchaseInfo = Pair.create(skuDetail, purchase)
            purchase(purchaseInfo, object : QonversionLaunchCallback {
                override fun onSuccess(launchResult: QLaunchResult) {
                    updateLaunchResult(launchResult)

                    purchaseCallback?.onSuccess(launchResult.permissions) ?: run {
                        listener?.onPermissionsUpdate(launchResult.permissions)
                    }
                }

                override fun onError(error: QonversionError) {
                    purchaseCallback?.onError(error)
                    forceLaunchRetry = true
                }
            })
        }
    }

    private fun purchase(
        purchaseInfo: Pair<SkuDetails, Purchase>,
        callback: QonversionLaunchCallback
    ) {
        val purchase = converter.convert(purchaseInfo)
        repository.purchase(installDate, purchase, callback)
    }
}