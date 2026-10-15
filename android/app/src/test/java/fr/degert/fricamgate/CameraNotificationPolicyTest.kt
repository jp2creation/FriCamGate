package com.nvr.camnory

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraNotificationPolicyTest {
    @Test
    fun onlyNotifiesNewUnseenVisibleEventsOnce() {
        val candidates = listOf(
            CameraAlert("old", "front", "person", 99.0),
            CameraAlert("seen", "front", "person", 110.0),
            CameraAlert("hidden", "back", "car", 111.0),
            CameraAlert("new", "front", "Bob", 112.0),
            CameraAlert("new", "front", "Bob", 112.0)
        )
        assertEquals(listOf("new"), newCameraAlerts(candidates, 100.0, setOf("back"), setOf("seen")).map { it.id })
    }

    @Test
    fun keepsEventsAtCursorBoundaryAndOrdersThem() {
        val candidates = listOf(
            CameraAlert("later", "front", "person", 120.0),
            CameraAlert("boundary", "front", "person", 100.0)
        )
        assertEquals(listOf("boundary", "later"), newCameraAlerts(candidates, 100.0, emptySet(), emptySet()).map { it.id })
    }
}
