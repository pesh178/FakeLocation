package com.xposed.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationProviderSelectorTest {

    @Test
    fun orderedProviders_prefersGpsThenNetworkAndKeepsOtherProviders() {
        assertEquals(
            listOf("gps", "network", "fused"),
            LocationProviderSelector.orderedProviders(listOf("fused", "network", "gps"))
        )
    }

    @Test
    fun orderedProviders_removesDuplicates() {
        assertEquals(
            listOf("gps", "network"),
            LocationProviderSelector.orderedProviders(listOf("network", "gps", "gps", "network"))
        )
    }

    @Test
    fun activeProviders_keepsStandardProvidersAndDropsVendorStacks() {
        assertEquals(
            listOf("gps", "network", "passive"),
            LocationProviderSelector.activeProviders(listOf("fused", "network", "gps", "passive"))
        )
    }

    @Test
    fun activeProviders_keepsVendorProviderWhenNoStandardProviderIsEnabled() {
        assertEquals(listOf("fused"), LocationProviderSelector.activeProviders(listOf("fused")))
    }

    @Test
    fun coordinateParser_rejectsInvalidAndOutOfRangeValues() {
        assertEquals(34.7526, CoordinateParser.parse("invalid", "34.7526", -90.0, 90.0), 0.0)
        assertEquals(113.662, CoordinateParser.parse("181", "113.662", -180.0, 180.0), 0.0)
        assertEquals(-12.5, CoordinateParser.parse("-12.5", "0", -90.0, 90.0), 0.0)
    }

    @Test
    fun coordinateParser_validityMatchesSaveValidation() {
        assertTrue(CoordinateParser.isValid("34.7526", -90.0, 90.0))
        assertTrue(CoordinateParser.isValid("-180", -180.0, 180.0))
        assertFalse(CoordinateParser.isValid("90.0001", -90.0, 90.0))
        assertFalse(CoordinateParser.isValid("NaN", -90.0, 90.0))
        assertFalse(CoordinateParser.isValid("Infinity", -180.0, 180.0))
        assertFalse(CoordinateParser.isValid("", -90.0, 90.0))
        assertFalse(CoordinateParser.isValid(null, -90.0, 90.0))
    }

    @Test
    fun nmeaCoordinates_useFixedWidthDecimalMinutesAndAbsoluteValues() {
        assertEquals("3445.1560", com.xposed.hook.location.MockLocationHelper.getGPSLat(34.7526))
        assertEquals("11339.7200", com.xposed.hook.location.MockLocationHelper.getGPSLon(113.662))
        assertEquals("3445.1560", com.xposed.hook.location.MockLocationHelper.getGPSLat(-34.7526))
    }

    @Test
    fun nmeaCoordinates_keepFieldWidthAndCarryMinutesOnRounding() {
        // 分钟不足 10 与整度取值都必须补零到固定宽度
        assertEquals("3400.0000", com.xposed.hook.location.MockLocationHelper.getGPSLat(34.0))
        assertEquals("0000.0000", com.xposed.hook.location.MockLocationHelper.getGPSLat(0.0000001))
        assertEquals("00005.0000", com.xposed.hook.location.MockLocationHelper.getGPSLon(0.0833333333333))
        // 四舍五入到 60 分必须进位到下一度
        assertEquals("3500.0000", com.xposed.hook.location.MockLocationHelper.getGPSLat(34.9999999999))
        assertEquals("18000.0000", com.xposed.hook.location.MockLocationHelper.getGPSLon(179.999999999))
    }

    @Test
    fun nmeaChecksum_isComputedFromPayload() {
        assertEquals("\$GPVTG,0,T,0,M,0,N,0,K,A,*0F", com.xposed.hook.location.MockLocationHelper.checksum("\$GPVTG,0,T,0,M,0,N,0,K,A,"))
        assertEquals(
            "\$GPGSV,1,1,04,12,05,159,36,15,41,087,15,19,38,262,30,31,56,146,19,*59",
            com.xposed.hook.location.MockLocationHelper.checksum(
                "\$GPGSV,1,1,04,12,05,159,36,15,41,087,15,19,38,262,30,31,56,146,19,"
            )
        )
    }

    @Test
    fun locationConfig_keepsSeparatePackageCoordinates() {
        com.xposed.hook.location.LocationConfig.configure("package.a", 1.0, 2.0, 3, 4)
        com.xposed.hook.location.LocationConfig.configure("package.b", 5.0, 6.0, 7, 8)
        assertEquals(1.0, com.xposed.hook.location.LocationConfig.getValues("package.a")!!.latitude, 0.0)
        assertEquals(5.0, com.xposed.hook.location.LocationConfig.getValues("package.b")!!.latitude, 0.0)
    }
}
