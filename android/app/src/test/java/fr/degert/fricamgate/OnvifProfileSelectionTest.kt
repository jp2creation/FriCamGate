package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class OnvifProfileSelectionTest {
    private val profiles = listOf(OnvifProfile("main", "Main", "H264"), OnvifProfile("sub", "Sub", "H264"))
    @Test fun remembersSelectedProfile() { assertEquals("main", preferredOnvifProfile(profiles, "main").token) }
    @Test fun oldAndStaleSelectionsFallBackToAvailableProfile() {
        assertEquals("sub", preferredOnvifProfile(profiles, null).token)
        assertEquals("sub", preferredOnvifProfile(profiles, "removed").token)
    }
}
