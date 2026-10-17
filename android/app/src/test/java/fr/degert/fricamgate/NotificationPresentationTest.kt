package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class NotificationPresentationTest {
    private fun event(id: String, label: String, camera: String = "front", time: Double = 100000.0) =
        NativeEvent(id, camera, label, label, "Bob", "12:00", time, time + 10, null)

    @Test fun filtersUseDetectionTypeRatherThanRecognizedName() {
        assertTrue(notificationMatchesKind(event("a", "person"), EventKind.Person))
        assertFalse(notificationMatchesKind(event("b", "car"), EventKind.Person))
        assertTrue(notificationMatchesKind(event("c", "truck"), EventKind.Vehicle))
        assertTrue(notificationMatchesKind(event("d", "horse"), EventKind.Animal))
        assertTrue(notificationMatchesKind(event("e", "other"), EventKind.Any))
    }

    @Test fun feedOmitsHiddenCamerasAndExpiredEventsAndSortsNewestFirst() {
        val feed = recentNotifications(listOf(event("old", "person", time = 1.0),
            event("hidden", "car", "hidden"), event("first", "cat"), event("second", "person", time = 100100.0)),
            setOf("front"), 100200.0)
        assertEquals(listOf("second", "first"), feed.map { it.id })
    }
}
