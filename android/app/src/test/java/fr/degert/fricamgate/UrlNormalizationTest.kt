package com.nvr.camnory

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

    @Test
    fun expandsCameraPickerToFullWidthOnPortraitScreens() {
        assertEquals(1080, cameraPickerPopupWidth(1_080, 2_400, 720, 64))
    }

    @Test
    fun keepsCameraPickerFloatingOnLandscapeScreens() {
        assertEquals(720, cameraPickerPopupWidth(1_920, 1_080, 720, 64))
    }

    @Test
    fun buildsLiaChatUrlFromFrigateBaseUrl() {
        assertEquals("https://cam.degert.fr/chat", liaChatUrl("https://cam.degert.fr/api"))
    }

    @Test
    fun allowsOnlySameOriginChatNavigation() {
        assertEquals(true, isSameOriginUrl("https://cam.degert.fr/api", "https://cam.degert.fr/chat/thread"))
        assertEquals(false, isSameOriginUrl("https://cam.degert.fr/api", "https://example.com/chat"))
    }

    @Test
    fun cyclesStartupLoadingMessages() {
        assertEquals("Connexion au NVR...", loadingStageMessage(0))
        assertEquals("Chargement des cameras...", loadingStageMessage(1))
        assertEquals("Preparation du live...", loadingStageMessage(2))
        assertEquals("Connexion au NVR...", loadingStageMessage(3))
    }

    @Test
    fun keepsSettingsMenuRowsCompact() {
        assertEquals(68, settingsMenuRowHeightDp())
        assertEquals(12, settingsMenuCornerRadiusDp())
        assertEquals(44, settingsMenuTextEndPaddingDp(showChevron = true))
        assertEquals(18, settingsMenuTextEndPaddingDp(showChevron = false))
    }

    @Test
    fun swapsSameMomentCameraListAroundDisplayedCamera() {
        val cameraNames = listOf("reolink_134_suivi", "devant", "allee", "entree")

        assertEquals(
            listOf("reolink_134_suivi", "allee", "entree"),
            sameMomentCameraNames(cameraNames, displayedCameraName = "devant")
        )
        assertEquals(
            listOf("devant", "allee", "entree"),
            sameMomentCameraNames(cameraNames, displayedCameraName = "reolink_134_suivi")
        )
    }
}
