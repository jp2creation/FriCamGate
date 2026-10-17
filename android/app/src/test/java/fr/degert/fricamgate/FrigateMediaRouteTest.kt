package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class FrigateMediaRouteTest {
    private val server = FrigateServer("one", "Fixture", "https://example.test/nvr", "", "secret", localRoute =
        LocalServerRoute("http://192.168.0.20:8971/local", "192.168.0.0/24"))

    @Test fun localHlsSegmentsReturnToCanonicalRoute() {
        val result = requireNotNull(canonicalFrigateMediaSource(listOf(server), "http://192.168.0.20:8971/local/vod/segment.m4s?start=12"))
        assertEquals(server, result.first)
        assertEquals("https://example.test/nvr/vod/segment.m4s?start=12", result.second.toString())
    }
    @Test fun canonicalMediaStaysUnchanged() {
        assertEquals("https://example.test/nvr/vod/master.m3u8", canonicalFrigateMediaSource(listOf(server), "https://example.test/nvr/vod/master.m3u8")?.second.toString())
    }
    @Test fun foreignOriginsAndSiblingPathsAreRejected() {
        for (url in listOf("https://evil.test/nvr/vod/a", "https://example.test/nvr-other/a",
            "http://192.168.0.20:8971/local-other/a", "http://192.168.0.21:8971/local/a", "https://user:pass@example.test/nvr/a")) {
            assertNull(url, canonicalFrigateMediaSource(listOf(server), url))
        }
    }
    @Test fun ambiguousLocalAliasIsRejected() {
        assertNull(canonicalFrigateMediaSource(listOf(server, server.copy(id = "two", baseUrl = "https://other.test/nvr")), "http://192.168.0.20:8971/local/vod/a"))
    }
}
