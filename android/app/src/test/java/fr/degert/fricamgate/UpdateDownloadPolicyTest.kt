package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class UpdateDownloadPolicyTest {
    @Test fun acceptsOnlyProjectReleaseApksOverHttps() {
        assertTrue(trustedUpdateUrl("https://github.com/jp2creation/FriCamGate/releases/download/v1/app-debug.apk"))
        assertFalse(trustedUpdateUrl("http://github.com/jp2creation/FriCamGate/releases/download/v1/a.apk"))
        assertFalse(trustedUpdateUrl("https://github.com.evil.test/jp2creation/FriCamGate/releases/download/v1/a.apk"))
        assertFalse(trustedUpdateUrl("https://github.com/other/app/releases/download/v1/a.apk"))
        assertFalse(trustedUpdateUrl("not a url"))
    }
    @Test fun progressHandlesUnknownAndInvalidSizes() {
        assertNull(updateDownloadPercent(10, -1))
        assertEquals(50, updateDownloadPercent(50, 100))
        assertEquals(100, updateDownloadPercent(200, 100))
        assertEquals(0, updateDownloadPercent(-10, 100))
    }
}
