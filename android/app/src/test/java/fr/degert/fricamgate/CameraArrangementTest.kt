package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class CameraArrangementTest {
    @Test fun mixesSourcesAndAppendsNewCameras() {
        assertEquals(listOf("ha", "onvif", "frigate", "new"),
            CameraArrangement.ordered(listOf("frigate", "onvif", "ha", "new"), listOf("ha", "missing", "onvif", "ha")))
    }

    @Test fun keysDoNotCollideAcrossServersSourcesOrSeparators() {
        val keys = listOf(
            CameraArrangement.key("frigate", "http://a", "front"),
            CameraArrangement.key("ha", "http://a", "front"),
            CameraArrangement.key("frigate", "http://b", "front"),
            CameraArrangement.key("a", "b:c", "d"),
            CameraArrangement.key("a:b", "c", "d"))
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun hiddenAndTemporarilyMissingCamerasKeepTheirPlace() {
        val saved = listOf("ha", "hidden", "onvif", "offline")
        val moved = CameraArrangement.move(saved, "onvif", "ha", false)
        assertEquals(listOf("onvif", "ha", "hidden", "offline"), moved)
        assertEquals(listOf("onvif", "ha", "hidden", "offline", "new"),
            CameraArrangement.ordered(listOf("ha", "hidden", "onvif", "offline", "new"), moved))
    }

    @Test fun dropAfterLastAndInvalidDropAreSafe() {
        assertEquals(listOf("b", "c", "a"), CameraArrangement.move(listOf("a", "b", "c"), "a", "c", true))
        assertEquals(listOf("a", "b"), CameraArrangement.move(listOf("a", "b"), "a", "absent", false))
        assertEquals(listOf("a", "b"), CameraArrangement.move(listOf("a", "b"), "a", "a", true))
        assertTrue(CameraArrangement.ordered(emptyList(), listOf("old")).isEmpty())
    }
}
