package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class TvHistoryPagesTest {
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
