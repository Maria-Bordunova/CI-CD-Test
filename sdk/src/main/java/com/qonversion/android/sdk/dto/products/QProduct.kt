package com.qonversion.android.sdk.dto.products

import com.android.billingclient.api.SkuDetails
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class QProduct(
    @Json(name = "id") val qonversionID: String,
    @Json(name = "store_id") val storeID: String?,
    @Json(name = "type") val type: QProductType,
    @Json(name = "duration") val duration: QProductDuration?
) {
    @Transient
    var skuDetail: SkuDetails? = null
        set(value) {
            prettyPrice = value?.price
            field = value
        }

    @Transient
    var prettyPrice: String? = null
}