package fr.degert.fricamgate

import org.junit.Assert.*
import org.junit.Test

class TimelineGeometryTest {
    @Test fun ticksAreAlignedToRealMinutesNotTheOpeningTime() {
        assertEquals(960.0, timelineFirstTick(1007.0, 2), 0.0)
        assertEquals(100, timelineY(1007.0, 947.0, 40, 60))
    }
    @Test fun denseEventsSharePreviewWithoutChangingItsTime() {
        val times = listOf(1000.0, 990.0, 980.0, 800.0)
        val groups = timelinePreviewGroups(times, 90.0)
        assertEquals(listOf(listOf(0, 1, 2), listOf(3)), groups)
        assertEquals(40, timelineY(1000.0, times[groups[0][0]], 40, 60))
        assertTrue(timelinePreviewGroups(emptyList(), 90.0).isEmpty())
    }
    @Test fun timelineFollowsAccentByDefaultWithOptionalClassicBlue() {
        assertEquals(123, timelineColor(null, 123))
        assertEquals(456, timelineColor("accent", 456))
        assertEquals(0xff07b8ff.toInt(), timelineColor("blue", 123))
    }
}
