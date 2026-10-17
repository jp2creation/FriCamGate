package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class ActivityBriefingStyleTest {
    @Test fun existingInstallKeepsFloatingShortcut() {
        assertEquals(ActivityBriefingStyle.Floating, ActivityBriefingStyle.fromPreference(null))
    }

    @Test fun invalidPreferenceFallsBackToFloating() {
        assertEquals(ActivityBriefingStyle.Floating, ActivityBriefingStyle.fromPreference("unknown"))
    }

    @Test fun allFourChoicesRoundTripWithUniqueLabelsAndKeys() {
        val styles = ActivityBriefingStyle.values()
        assertEquals(4, styles.size)
        assertEquals(4, styles.map { it.preference }.toSet().size)
        assertEquals(4, styles.map { it.titleRes }.toSet().size)
        styles.forEach { assertEquals(it, ActivityBriefingStyle.fromPreference(it.preference)) }
    }
}
