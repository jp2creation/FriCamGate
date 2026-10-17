package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class TabletLayoutPolicyTest {
    @Test fun landscapePhoneKeepsThePhoneLayout() {
        val layout = tabletLayoutPolicy(850, 390, 390)
        assertFalse(layout.tablet); assertFalse(layout.navigationRail); assertEquals(0, layout.activityWidthDp)
    }
    @Test fun portraitTabletHasTwoColumnsAndBottomNavigation() {
        val layout = tabletLayoutPolicy(800, 1280, 800)
        assertTrue(layout.tablet); assertFalse(layout.navigationRail); assertEquals(0, layout.activityWidthDp)
        assertEquals(HomeCameraLayout.TwoColumns, effectiveCameraLayout("column", null, layout.tablet))
    }
    @Test fun landscapeTabletShowsActivityBesideCameras() {
        val layout = tabletLayoutPolicy(1280, 800, 800)
        assertTrue(layout.navigationRail); assertEquals(320, layout.activityWidthDp)
    }
    @Test fun narrowWindowsDoNotSqueezeInAnActivitySidebar() {
        assertEquals(0, tabletLayoutPolicy(900, 600, 600).activityWidthDp)
        assertFalse(tabletLayoutPolicy(500, 800, 500).tablet)
    }
    @Test fun tabletChoiceNeverOverwritesThePhoneChoice() {
        assertEquals(HomeCameraLayout.Column, effectiveCameraLayout("column", "compact", false))
        assertEquals(HomeCameraLayout.Compact, effectiveCameraLayout("column", "compact", true))
    }
}
