package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class EventFilterCountTest {
    @Test fun defaultSearchHasNoActiveFilters() { assertEquals(0, EventSearch().activeFilterCount) }
    @Test fun countsEachCriterionOnce() {
        assertEquals(4, EventSearch("front", "Céline", EventKind.Vehicle, 24).activeFilterCount)
        assertEquals(1, EventSearch(kind = EventKind.Animal).activeFilterCount)
        assertEquals(1, EventSearch(hours = 168).activeFilterCount)
    }
    @Test fun draftChangesDoNotMutateAppliedSearch() {
        val applied = EventSearch(camera = "front")
        val draft = applied.copy(kind = EventKind.Person)
        assertEquals(1, applied.activeFilterCount)
        assertEquals(2, draft.activeFilterCount)
        assertFalse(applied.query(1000.0).contains("labels="))
        assertTrue(draft.query(1000.0).contains("labels=person"))
    }
}
