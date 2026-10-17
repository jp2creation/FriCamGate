package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class ServerRouteStatusTest {
    @Test fun recordsActualSuccessfulAddressAndSeparatesProviders() {
        val status = ServerRouteStatus()
        status.record("https://server.example", "/api/version", true, true, "network-one")
        assertTrue(status.get("https://server.example", "/api/version")!!.local)
        assertTrue(status.get("https://server.example", "/api/version")!!.success)
        assertNull(status.get("https://server.example", "/api/"))
        status.record("https://server.example", "/api/version", false, true, "network-one")
        assertFalse(status.get("https://server.example", "/api/version")!!.local)
    }
    @Test fun failedAccessIsNotReportedAsConnected() {
        val status = ServerRouteStatus()
        status.record("fixture", "frigate", false, false, null)
        assertFalse(status.get("fixture", "frigate")!!.success)
    }
}
