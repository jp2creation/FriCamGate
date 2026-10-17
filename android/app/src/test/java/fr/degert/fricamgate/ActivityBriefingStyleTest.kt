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

    @Test fun legacyVisibilityIsPreservedDuringMigration() {
        ActivityBriefingStyle.values().forEach { style ->
            assertEquals(ActivityBriefingStyle.Hidden, ActivityBriefingStyle.fromPreference(style.preference, false))
            assertEquals(style, ActivityBriefingStyle.fromPreference(style.preference, true))
        }
        assertEquals(ActivityBriefingStyle.Hidden, ActivityBriefingStyle.fromPreference(null, false))
        assertEquals(ActivityBriefingStyle.Floating, ActivityBriefingStyle.fromPreference(null, true))
    }

    @Test fun allFiveChoicesRoundTripWithUniqueLabelsAndKeys() {
        val styles = ActivityBriefingStyle.values()
        assertEquals(5, styles.size)
        assertEquals(5, styles.map { it.preference }.toSet().size)
        assertEquals(5, styles.map { it.titleRes }.toSet().size)
        styles.forEach { assertEquals(it, ActivityBriefingStyle.fromPreference(it.preference)) }
    }
}
