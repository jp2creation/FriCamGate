package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class TimelineReturnAnimationTest {
    @Test fun aLongerReturnStaysVisibleForLonger() {
        assertTrue(timelineReturnDuration(4000, 500) > timelineReturnDuration(500, 500))
    }
    @Test fun durationIsBoundedForShortAndVeryLongHistories() {
        assertEquals(700L, timelineReturnDuration(0, 500))
        assertEquals(4500L, timelineReturnDuration(Int.MAX_VALUE, 1))
        assertTrue(timelineReturnDuration(100, 0) in 700L..4500L)
    }
}
