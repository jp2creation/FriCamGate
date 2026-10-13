package fr.degert.fricamgate

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlNormalizationTest {
    @Test
    fun addsHttpSchemeWhenMissing() {
        assertEquals("http://cam.degert.fr", normalizeBaseUrl("cam.degert.fr"))
    }

    @Test
    fun stripsApiSuffixFromPastedUrls() {
        assertEquals("http://cam.degert.fr", normalizeBaseUrl("http://cam.degert.fr/api"))
        assertEquals("http://cam.degert.fr", normalizeBaseUrl("http://cam.degert.fr/api/login"))
    }

    @Test
    fun keepsReverseProxySubPath() {
        assertEquals("https://example.test/frigate", normalizeBaseUrl("https://example.test/frigate/api"))
    }

    @Test
    fun comparesReleaseVersionsWithDebugTags() {
        assertEquals("0.2.12", extractVersion("v0.2.12-kotlin-debug"))
        assertEquals(1, compareVersions("0.2.12", "0.2.11"))
        assertEquals(0, compareVersions("0.2.10", "v0.2.10-kotlin-debug"))
    }
}
