package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class CameraButtonAppearanceTest {
    @Test fun preferencesAreIndependent() {
        assertEquals(CameraButton.values().size, CameraButton.values().map { it.preference }.toSet().size)
    }
    @Test fun audioStateDoesNotChangeItsVisibilityPreference() {
        assertEquals(CameraButton.Sound, CameraButton.forIcon(R.drawable.ic_volume))
        assertEquals(CameraButton.Sound, CameraButton.forIcon(R.drawable.ic_volume_off))
    }
    @Test fun actionIconsUseExpectedPreferences() {
        assertEquals(CameraButton.Settings, CameraButton.forIcon(R.drawable.ic_settings))
        assertEquals(CameraButton.Fullscreen, CameraButton.forIcon(R.drawable.ic_fullscreen))
        assertEquals(CameraButton.Events, CameraButton.forIcon(R.drawable.ic_list))
        assertEquals(CameraButton.More, CameraButton.forIcon(R.drawable.ic_more_vertical))
        assertNull(CameraButton.forIcon(R.drawable.ic_refresh))
        // Opening all built-in controls must not depend solely on the light setting.
        assertNull(CameraButton.forIcon(R.drawable.ic_camera_light))
    }
}
