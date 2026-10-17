package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class CameraPreloadPolicyTest {
    @Test fun tvPreloadKeepsHiddenConnectionsWarmButReleasesOnExit() {
        assertTrue(cameraConnectionActive(true, true, false, true))
        assertFalse(cameraConnectionActive(false, true, false, true))
        assertFalse(cameraConnectionActive(true, false, false, true))
        assertEquals(OnvifPlaybackVisibility.Pause, onvifPlaybackVisibility(true, true, false, false, true))
        assertEquals(OnvifPlaybackVisibility.Release, onvifPlaybackVisibility(false, true, false, false, true))
    }
    @Test fun phoneBehaviourAndVisiblePlaybackRemainUnchanged() {
        assertFalse(cameraConnectionActive(true, true, false, false))
        assertTrue(cameraConnectionActive(true, true, true, false))
        assertEquals(OnvifPlaybackVisibility.Release, onvifPlaybackVisibility(true, true, false, false))
        assertEquals(OnvifPlaybackVisibility.Play, onvifPlaybackVisibility(true, true, true, true, true))
    }
}
