package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class RecordingContinuationTest {
    @Test fun oldRecordingContinuesAtItsEnd() {
        assertEquals(1000.0, nextRecordingFocus(1000, 1100.0)!!, 0.0)
    }
    @Test fun catchingUpNeverRequestsFutureMedia() {
        assertNull(nextRecordingFocus(1100, 1100.0))
        assertNull(nextRecordingFocus(1140, 1100.0))
        assertNull(nextRecordingFocus(1098, 1100.0))
    }
}
