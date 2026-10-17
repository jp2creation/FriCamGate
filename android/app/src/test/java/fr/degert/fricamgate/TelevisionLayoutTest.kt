package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class TelevisionLayoutTest {
    @Test fun televisionUsesTabletRailEvenWithSmallReportedMinimumWidth() {
        val tv = tabletLayoutPolicy(960, 540, 540, television = true)
        assertTrue(tv.tablet); assertTrue(tv.navigationRail)
        assertFalse(tabletLayoutPolicy(960, 540, 540).tablet)
    }
    @Test fun tvSidebarDoesNotSqueezeNarrowScreens() {
        assertEquals(0, tabletLayoutPolicy(800, 450, 450, television = true).activityWidthDp)
        assertEquals(320, tabletLayoutPolicy(1280, 720, 720, television = true).activityWidthDp)
    }
}
