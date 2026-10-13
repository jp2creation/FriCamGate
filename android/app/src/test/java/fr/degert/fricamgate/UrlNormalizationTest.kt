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
        assertEquals("0.2.13", extractVersion("v0.2.13-kotlin-debug"))
        assertEquals(1, compareVersions("0.2.13", "0.2.12"))
        assertEquals(0, compareVersions("0.2.10", "v0.2.10-kotlin-debug"))
    }

    @Test
    fun buildsCameraFeatureTogglePaths() {
        assertEquals("/api/Entree%20suivi/set/audio", cameraSetFeaturePath("Entree suivi", "audio"))
        assertEquals("/api/allee/set/recordings", cameraSetFeaturePath("allee", "recordings"))
    }

    @Test
    fun buildsRecordingVodPaths() {
        assertEquals(
            "/vod/Entree%20suivi/sub/start/100/end/145/master.m3u8",
            recordingVodPath("Entree suivi", 100, 145, "sub")
        )
        assertEquals(
            "/vod/devant/start/100/end/145/master.m3u8",
            recordingVodPath("devant", 100, 145)
        )
    }

    @Test
    fun centersTimelineRecordingWindowAroundFocus() {
        val window = timelineRecordingWindow(1_000.0)

        assertEquals(980L, window.startSec)
        assertEquals(1_025L, window.endSec)
        assertEquals(20_000L, window.seekMs)
    }

    @Test
    fun placesTimelinePlayheadNearTopQuarter() {
        assertEquals(300, timelinePlayheadOffset(1_200))
    }
}
