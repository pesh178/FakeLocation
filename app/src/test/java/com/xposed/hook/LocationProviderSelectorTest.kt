package com.xposed.hook

import org.junit.Assert.assertEquals
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
    fun coordinateParser_rejectsInvalidAndOutOfRangeValues() {
        assertEquals(34.7526, CoordinateParser.parse("invalid", "34.7526", -90.0, 90.0), 0.0)
        assertEquals(113.662, CoordinateParser.parse("181", "113.662", -180.0, 180.0), 0.0)
        assertEquals(-12.5, CoordinateParser.parse("-12.5", "0", -90.0, 90.0), 0.0)
    }

    @Test
    fun nmeaCoordinates_useFixedWidthDecimalMinutesAndAbsoluteValues() {
        assertEquals("3445.1560", com.xposed.hook.location.MockLocationHelper.getGPSLat(34.7526))
        assertEquals("11339.7200", com.xposed.hook.location.MockLocationHelper.getGPSLon(113.662))
        assertEquals("3445.1560", com.xposed.hook.location.MockLocationHelper.getGPSLat(-34.7526))
    }

    @Test
    fun nmeaChecksum_isComputedFromPayload() {
        assertEquals("\$GPVTG,0,T,0,M,0,N,0,K,A,*0F", com.xposed.hook.location.MockLocationHelper.checksum("\$GPVTG,0,T,0,M,0,N,0,K,A,"))
    }

    @Test
    fun locationConfig_keepsSeparatePackageCoordinates() {
        com.xposed.hook.location.LocationConfig.configure("package.a", 1.0, 2.0, 3, 4)
        com.xposed.hook.location.LocationConfig.configure("package.b", 5.0, 6.0, 7, 8)
        assertEquals(1.0, com.xposed.hook.location.LocationConfig.getValues("package.a")!!.latitude, 0.0)
        assertEquals(5.0, com.xposed.hook.location.LocationConfig.getValues("package.b")!!.latitude, 0.0)
    }
}
