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
}
