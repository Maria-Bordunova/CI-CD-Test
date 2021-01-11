package com.qonversion.android.sdk

import android.app.Activity
import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.android.billingclient.api.BillingFlowParams
import com.google.firebase.messaging.RemoteMessage
import com.qonversion.android.sdk.di.QDependencyInjector
import com.qonversion.android.sdk.logger.ConsoleLogger
import com.qonversion.android.sdk.push.QAutomationDelegate
import com.qonversion.android.sdk.push.QAutomationManager

object Qonversion : LifecycleDelegate {

    private var userPropertiesManager: QUserPropertiesManager? = null
    private var attributionManager: QAttributionManager? = null
    private var productCenterManager: QProductCenterManager? = null
    private var automationManager: QAutomationManager? = null
    private var logger = ConsoleLogger()
    private var isDebugMode = false

    init {
        val lifecycleHandler = AppLifecycleHandler(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleHandler)
    }

    override fun onAppBackground() {
        userPropertiesManager?.forceSendProperties()
            ?: logLaunchErrorForFunctionName(object {}.javaClass.enclosingMethod?.name)
    }

    override fun onAppForeground() {
        productCenterManager?.onAppForeground()
    }

    /**
     * Launches Qonversion SDK with the given project key, you can get one in your account on https://dash.qonversion.io
     * @param context Application object
     * @param key project key to setup the SDK
     * @param observeMode set true if you are using observer mode only
     * @param callback - callback that will be called when response is received
     * @see [Observer mode](https://qonversion.io/docs/observer-mode)
     * @see [Installing the Android SDK](https://qonversion.io/docs/google)
     */
    @JvmStatic
    @JvmOverloads
    fun launch(
        context: Application,
        key: String,
        observeMode: Boolean,
        callback: QonversionLaunchCallback? = null
    ) {
        QDependencyInjector.buildAppComponent(context, key, isDebugMode)

        if (key.isEmpty()) {
            throw RuntimeException("Qonversion initialization error! Key should not be empty!")
        }

        val repository = QDependencyInjector.appComponent.repository()
        automationManager = QDependencyInjector.appComponent.automationManager()

        userPropertiesManager = QUserPropertiesManager(context, repository)
        attributionManager = QAttributionManager(repository)

        val factory = QonversionFactory(context, logger)
        productCenterManager = factory.createProductCenterManager(repository, observeMode)
        productCenterManager?.launch(callback)
    }

    /**
     * Make a purchase and validate that through server-to-server using Qonversion's Backend
     * @param context current activity context
     * @param id Qonversion product identifier for purchase
     * @param callback - callback that will be called when response is received
     * @see [Product Center](https://qonversion.io/docs/product-center)
     */
    @JvmStatic
    fun purchase(context: Activity, id: String, callback: QonversionPermissionsCallback) {
        productCenterManager?.purchaseProduct(context, id, null, null, callback)
            ?: logLaunchErrorForFunctionName(object {}.javaClass.enclosingMethod?.name)
    }

    /**
     * Update (upgrade/downgrade) subscription and validate that through server-to-server using Qonversion's Backend
     * @param context current activity context
     * @param productId Qonversion product identifier for purchase
     * @param oldProductId Qonversion product identifier from which the upgrade/downgrade will be initialized
     * @param callback - callback that will be called when response is received
     * @see [Product Center](https://qonversion.io/docs/product-center)
     */
    @JvmStatic
    fun updatePurchase(
        context: Activity,
        productId: String,
        oldProductId: String,
        callback: QonversionPermissionsCallback
    ) {
        productCenterManager?.purchaseProduct(context, productId, oldProductId, null, callback)
            ?: logLaunchErrorForFunctionName(object {}.javaClass.enclosingMethod?.name)
    }

    /**
     * Update (upgrade/downgrade) subscription and validate that through server-to-server using Qonversion's Backend
     * @param context current activity context
     * @param productId Qonversion product identifier for purchase
     * @param oldProductId Qonversion product identifier from which the upgrade/downgrade will be initialized
     * @param prorationMode proration mode
     * @param callback - callback that will be called when response is received
     * @see [Proration mode](https://developer.android.com/google/play/billing/subscriptions#proration)
     * @see [Product Center](https://qonversion.io/docs/product-center)
     */
    @JvmStatic
    fun updatePurchase(
        context: Activity,
        productId: String,
        oldProductId: String,
        @BillingFlowParams.ProrationMode prorationMode: Int?,
        callback: QonversionPermissionsCallback
    ) {
        productCenterManager?.purchaseProduct(
            context,
            productId,
            oldProductId,
            prorationMode,
            callback
        ) ?: logLaunchErrorForFunctionName(object {}.javaClass.enclosingMethod?.name)
    }

    /**
     * Return Qonversion Products in assoсiation with Google Play Store Products
     * If you get an empty SkuDetails be sure your products are correctly setted up in Google Play Store.
     * @param callback - callback that will be called when response is received
     * @see [Product Center](https://qonversion.io/docs/product-center)
     */
    @JvmStatic
    fun products(
        callback: QonversionProductsCallback
    ) {
        productCenterManager?.loadProducts(callback)
            ?: logLaunchErrorForFunctionName(object {}.javaClass.enclosingMethod?.name)
    }

    /**
     * Check user permissions based on product center details
     * @param callback - callback that will be called when response is received
     * @see [Product Center](https://qonversion.io/docs/product-center)
     */
    @JvmStatic
    fun checkPermissions(
        callback: QonversionPermissionsCallback
    ) {
        productCenterManager?.checkPermissions(callback)
            ?: logLaunchErrorForFunctionName(object {}.javaClass.enclosingMethod?.name)
    }

    /**
     * Restore user Products
     * @param callback - callback that will be called when response is received
     * @see [Product Center](https://qonversion.io/docs/product-center)
     */
    @JvmStatic
    fun restore(callback: QonversionPermissionsCallback) {
        productCenterManager?.restore(callback)
            ?: logLaunchErrorForFunctionName(object {}.javaClass.enclosingMethod?.name)
    }

    /**
     * This method will send all purchases to the Qonversion backend. Call this every time when purchase is handled by you own implementation.
     * @warning This function should only be called if you're using Qonversion SDK in observer mode.
     * @see [Observer mode](https://qonversion.io/docs/observer-mode)
     */
    @JvmStatic
    fun syncPurchases() {
        productCenterManager?.syncPurchases()
            ?: logLaunchErrorForFunctionName(object {}.javaClass.enclosingMethod?.name)
    }

    /**
     * Send your attribution data
     * @param conversionInfo map received by the attribution source
     * @param from Attribution source
     */
    @JvmStatic
    fun attribution(
        conversionInfo: Map<String, Any>,
        from: AttributionSource
    ) {
        attributionManager?.attribution(conversionInfo, from)
            ?: logLaunchErrorForFunctionName(object {}.javaClass.enclosingMethod?.name)
    }

    /**
     * Sets Qonversion reserved user properties, like email or one-signal id
     * @param key defined enum key that will be transformed to string
     * @param value property value
     */
    @JvmStatic
    fun setProperty(key: QUserProperties, value: String) {
        userPropertiesManager?.setProperty(key, value)
            ?: logLaunchErrorForFunctionName(object {}.javaClass.enclosingMethod?.name)
    }

    /**
     * Sets custom user properties
     * @param key custom user property key
     * @param value property value
     */
    @JvmStatic
    fun setUserProperty(key: String, value: String) {
        userPropertiesManager?.setUserProperty(key, value)
            ?: logLaunchErrorForFunctionName(object {}.javaClass.enclosingMethod?.name)
    }

    /**
     * Associate a user with their unique ID in your system
     * @param value your database user ID
     */
    @JvmStatic
    fun setUserID(value: String) {
        userPropertiesManager?.setUserID(value)
            ?: logLaunchErrorForFunctionName(object {}.javaClass.enclosingMethod?.name)
    }

    /**
     * You can set the flag to distinguish sandbox and production users.
     * To see the sandbox users turn on the Viewing test Data toggle on Qonversion Dashboard
     */
    @JvmStatic
    fun setDebugMode() {
        isDebugMode = true
    }

    /**
     *
     */
    @JvmStatic
    fun setAutomationDelegate(delegate: QAutomationDelegate) {
        automationManager?.automationDelegate = delegate
    }

    /**
     *
     */
    @JvmStatic
    fun setPushToken(token: String) {
        automationManager?.setPushToken(token)
            ?: logLaunchErrorForFunctionName(object {}.javaClass.enclosingMethod?.name)
    }

    /**
     *
     */
    @JvmStatic
    fun handlePushIfPossible(remoteMessage: RemoteMessage): Boolean {
        val isPossibleToHandlePush = automationManager?.handlePushIfPossible(remoteMessage)
        if (isPossibleToHandlePush == null) {
            logLaunchErrorForFunctionName(object {}.javaClass.enclosingMethod?.name)
            return false
        }

        return isPossibleToHandlePush
    }

    // Private functions

    private fun logLaunchErrorForFunctionName(functionName: String?) {
        logger.release("$functionName function can not be executed. It looks like launch was not called.")
    }
}


