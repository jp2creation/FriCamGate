package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class HomeSourceFilterTest {
    private val sources = setOf("frigate:home", "frigate:office", "ha", "onvif")

    @Test fun allSourcesAreIncludedByDefault() {
        val filter = HomeSourceFilter(null, sources)
        assertNull(filter.selected)
        assertTrue(sources.all { filter.includes(it) })
    }

    @Test fun selectsAnIndividualServerRatherThanAllFrigateCameras() {
        val filter = HomeSourceFilter("frigate:office", sources)
        assertTrue(filter.includes("frigate:office"))
        assertFalse(filter.includes("frigate:home"))
        assertFalse(filter.includes("ha"))
        assertFalse(filter.includes("onvif"))
    }

    @Test fun standaloneProvidersCanBeSelectedIndependently() {
        assertTrue(HomeSourceFilter("ha", sources).includes("ha"))
        assertFalse(HomeSourceFilter("ha", sources).includes("onvif"))
        assertTrue(HomeSourceFilter("onvif", sources).includes("onvif"))
    }

    @Test fun removingASelectedServerRestoresAllSources() {
        val filter = HomeSourceFilter("frigate:removed", sources)
        assertNull(filter.selected)
        assertTrue(sources.all { filter.includes(it) })
    }
}
