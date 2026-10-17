package com.nvr.camnory

import org.junit.Assert.assertEquals
import org.junit.Test

class TvStreamPolicyTest {
    @Test fun prefersMainWithoutChangingMobileSelection() {
        assertEquals("front_main", TvStreamPolicy.select("front", linkedMapOf("Sub" to "front_sub", "Main" to "front_main")))
    }
    @Test fun retainsOnlyAvailableStream() {
        assertEquals("front_sub", TvStreamPolicy.select("front", mapOf("Sub" to "front_sub")))
        assertEquals("front", TvStreamPolicy.select("front", emptyMap()))
    }
    @Test fun skipsBlankAndPrefersNonDetectionStream() {
        assertEquals("front", TvStreamPolicy.select("front", linkedMapOf("Main" to "", "Detect" to "front_detect", "Normal" to "front")))
    }
}
