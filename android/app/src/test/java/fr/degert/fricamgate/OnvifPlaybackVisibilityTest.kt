package com.nvr.camnory

import org.junit.Assert.assertEquals
import org.junit.Test

class OnvifPlaybackVisibilityTest {
    @Test fun scrollingOffscreenPausesInsteadOfDestroyingTheSession() {
        assertEquals(OnvifPlaybackVisibility.Pause, onvifPlaybackVisibility(true, true, true, false))
    }
    @Test fun returningToTheViewportResumesPlayback() {
        assertEquals(OnvifPlaybackVisibility.Play, onvifPlaybackVisibility(true, true, true, true))
    }
    @Test fun navigationAndBackgroundReleaseEvenWhenTheCameraIsOnscreen() {
        assertEquals(OnvifPlaybackVisibility.Release, onvifPlaybackVisibility(false, true, true, true))
        assertEquals(OnvifPlaybackVisibility.Release, onvifPlaybackVisibility(false, true, true, false))
    }
    @Test fun detachedOrHiddenViewsReleaseTheirResources() {
        assertEquals(OnvifPlaybackVisibility.Release, onvifPlaybackVisibility(true, false, true, false))
        assertEquals(OnvifPlaybackVisibility.Release, onvifPlaybackVisibility(true, true, false, false))
    }
    @Test fun repeatedScrollCyclesNeverRequestSessionRelease() {
        assertEquals(listOf(OnvifPlaybackVisibility.Play, OnvifPlaybackVisibility.Pause, OnvifPlaybackVisibility.Play,
            OnvifPlaybackVisibility.Pause, OnvifPlaybackVisibility.Play),
            listOf(true, false, true, false, true).map { onvifPlaybackVisibility(true, true, true, it) })
    }
}
