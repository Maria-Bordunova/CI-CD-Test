package com.qonversion.android.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.clearAllMocks
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QAttributionManagerTest {
    private val mockRepository = mockk<QonversionRepository>(relaxed = true)

    private lateinit var attributionManager: QAttributionManager

    @Before
    fun setUp() {
        clearAllMocks()

        attributionManager = QAttributionManager(mockRepository)
    }

    @Test
    fun attribution() {
        val key = "key"
        val value = "value"
        val conversionInfo = mutableMapOf<String, String>()
        conversionInfo[key] = value

        attributionManager.attribution(conversionInfo, AttributionSource.AppsFlyer)

        verify(exactly = 1) {
            mockRepository.attribution(conversionInfo, AttributionSource.AppsFlyer.id)
        }
    }
}