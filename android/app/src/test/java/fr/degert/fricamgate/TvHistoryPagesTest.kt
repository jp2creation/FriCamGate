package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class TvHistoryPagesTest {
    @Test fun historyPrefetchesOnlyNearTheEndWithoutRetryLoops() {
        assertTrue(tvShouldLoadHistory(95, 100, true, false, false))
        assertFalse(tvShouldLoadHistory(94, 100, true, false, false))
        assertFalse(tvShouldLoadHistory(-1, 100, true, false, false))
        assertFalse(tvShouldLoadHistory(0, 0, true, false, false))
        assertFalse(tvShouldLoadHistory(99, 100, false, false, false))
        assertFalse(tvShouldLoadHistory(99, 100, true, true, false))
        assertFalse(tvShouldLoadHistory(99, 100, true, false, true))
    }

    @Test fun allTvGridsFitTheirRequestedRowsAndHaveIndependentPreferences() {
        assertEquals(listOf(1, 2, 4, 9, 16), TvGridLayout.values().map { it.columns * it.rows })
        for (layout in TvGridLayout.values()) {
            assertEquals(layout, TvGridLayout.fromPreference(layout.preference))
            val height = tvCameraTileHeight(800, 8, layout.columns, 32, layout.rows)
            assertTrue(height * layout.rows + 8 * (layout.rows - 1) <= 800)
        }
        assertEquals(TvGridLayout.Four, TvGridLayout.fromPreference(null))
        assertEquals(TvGridLayout.Four, TvGridLayout.fromPreference("invalid"))
    }
    @Test fun cinemaUsesOneFilmstripAndInboxUsesFiveRows() {
        assertEquals(5, tvHistoryColumns(false))
        assertEquals(1, tvHistoryColumns(true))
        assertEquals(1, tvHistoryRows(false, 1f))
        assertEquals(5, tvHistoryRows(true, 1f))
        assertEquals(4, tvHistoryRows(true, 1.2f))
    }
    @Test fun pagesNeverMixDaysAndRetainChronologicalOrder() {
        val items = (0..8).toList()
        assertEquals(listOf((0..5).toList(), listOf(6), listOf(7, 8)),
            tvHistoryPages(items, 6) { if (it < 7) "today" else "yesterday" })
    }

    @Test fun emptyAndPartialPagesNeedNoFakeFocusableTiles() {
        assertTrue(tvHistoryPages(emptyList<Int>(), 6) { "today" }.isEmpty())
        assertEquals(listOf(listOf(1, 2)), tvHistoryPages(listOf(1, 2), 5) { "today" })
    }
}
