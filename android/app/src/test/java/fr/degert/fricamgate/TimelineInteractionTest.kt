package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class TimelineInteractionTest {
    @Test fun playbackClockCannotOverrideAnUncommittedScroll() {
        val gate = TimelineInteractionGate()
        assertTrue(gate.followsPlayback)
        gate.beginScroll()
        assertFalse(gate.followsPlayback)
        gate.commitScroll()
        assertTrue(gate.followsPlayback)
    }

    @Test fun nextWindowAdvancesBeyondThePreviousWindow() {
        val window = timelineRecordingWindow(1000.0)
        val next = timelineRecordingWindow(window.endSec.toDouble())
        assertTrue(next.startSec > window.startSec)
        assertEquals(window.endSec * 1000, next.startSec * 1000 + next.seekMs)
    }
}
