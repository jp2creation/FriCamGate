package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class RuntimeReliabilityTest {
    @Test fun reconnectBackoffIsBoundedButDoesNotGiveUp() {
        assertEquals(1200L, streamRetryDelay(0))
        assertEquals(2400L, streamRetryDelay(1))
        assertEquals(30000L, streamRetryDelay(10000))
    }
    @Test fun resumesOnlyTheSamePreviouslyPlayingInstance() {
        val gate = PlaybackResumeGate<Any>()
        val player = Any()
        gate.remember(player, true)
        assertTrue(gate.consume(player))
        assertFalse(gate.consume(player))
        gate.remember(player, false)
        assertFalse(gate.consume(player))
        gate.remember(player, true)
        assertFalse(gate.consume(Any()))
    }
    @Test fun eventQueryEncodesNamesAndIncludesServerSideFilters() {
        val query = EventSearch(camera = "front yard", name = "Céline & Bob", kind = EventKind.Person, hours = 24)
            .query(200000.0)
        assertTrue(query.contains("cameras=front+yard"))
        assertTrue(query.contains("sub_labels=C%C3%A9line+%26+Bob"))
        assertTrue(query.contains("labels=person"))
        assertTrue(query.contains("after=113600.0"))
    }
}
