package fr.degert.fricamgate

import org.junit.Assert.*
import org.junit.Test

class NotificationOptionsTest {
    @Test fun categoryUsesObjectTypeNotRecognizedName() {
        val policy = NotificationPolicy(categories = setOf("person", "animal"))
        assertTrue(policy.accepts("front", "person", 600))
        assertTrue(policy.accepts("front", "dog", 600))
        assertFalse(policy.accepts("front", "car", 600))
        assertEquals("vehicle", notificationCategory("truck"))
    }
    @Test fun cameraSelectionCanBeEmptyOrAll() {
        assertTrue(NotificationPolicy().accepts("any", "person", 0))
        assertFalse(NotificationPolicy(cameras = emptySet()).accepts("any", "person", 0))
        assertTrue(NotificationPolicy(cameras = setOf("front")).accepts("front", "person", 0))
    }
    @Test fun schedulesHandleMidnightAndEndExclusive() {
        assertTrue(minuteInWindow(23 * 60, 22 * 60, 7 * 60))
        assertTrue(minuteInWindow(6 * 60, 22 * 60, 7 * 60))
        assertFalse(minuteInWindow(7 * 60, 22 * 60, 7 * 60))
        val policy = NotificationPolicy(activeHours = true, activeStart = 9 * 60, activeEnd = 18 * 60,
            quietHours = true, quietStart = 12 * 60, quietEnd = 14 * 60)
        assertTrue(policy.accepts("front", "person", 13 * 60))
        assertTrue(policy.silent(13 * 60))
        assertFalse(policy.accepts("front", "person", 20 * 60))
    }
    @Test fun cooldownBoundaries() {
        val policy = NotificationPolicy(cooldownSeconds = 60)
        assertFalse(policy.cooldownAllows(60999, 1000))
        assertTrue(policy.cooldownAllows(61000, 1000))
        assertTrue(policy.cooldownAllows(1000, 0))
    }
}
