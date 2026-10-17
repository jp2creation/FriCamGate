package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class TvCameraGridPolicyTest {
    @Test fun fourTilesFitTwoCompleteRows() {
        val height = tvCameraTileHeight(380, 8, 2, 4)
        assertEquals(186, height)
        assertEquals(380, height * 2 + 8)
    }
    @Test fun oneColumnUsesOneWholeVideoPerViewport() {
        assertEquals(380, tvCameraTileHeight(380, 8, 1, 6))
    }
    @Test fun sparseAndNarrowViewportsStayValid() {
        assertEquals(150, tvCameraTileHeight(150, 8, 2, 1))
        assertEquals(1, tvCameraTileHeight(1, 8, 2, 4))
        assertEquals(186, tvCameraTileHeight(380, 8, 2, 9))
    }
}
