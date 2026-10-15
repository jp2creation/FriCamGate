package com.nvr.camnory

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EventNarrativeTest {
    @Test fun acknowledgmentHidesOnlyReadEventsAndAllowsNewOnes() {
        val facts = EventFacts("person", "Bob", null)
        val items = listOf(ActivityItem("read", "front", 10.0, facts), ActivityItem("new", "front", 20.0, facts))
        assertEquals(listOf("new"), unreadActivities(items, setOf("read")).map { it.id })
        assertTrue(unreadActivities(items, setOf("read", "new")).isEmpty())
        assertEquals(2, items.size)
    }
    @Test fun extractsNameWithoutScoreAndKeepsDescription() {
        val facts = EventFacts.fromJson(JSONObject("""{"label":"person","sub_label":["Céline",0.99],"data":{"description":"Une personne entre."}}"""))
        assertEquals("Céline", facts.name)
        assertEquals("Une personne entre.", facts.description)
    }
    @Test fun unknownPersonIsNotGivenAnIdentity() {
        val facts = EventFacts.fromJson(JSONObject("""{"label":"person","sub_label":null}"""))
        assertNull(facts.name)
        assertEquals(R.string.activity_person, facts.subjectRes)
    }
    @Test fun namedVehicleKeepsVehicleCategory() {
        val facts = EventFacts.fromJson(JSONObject("""{"label":"car","sub_label":"Céline"}"""))
        assertEquals(R.string.activity_vehicle, facts.subjectRes)
        assertFalse(facts.useNameAsSubject)
    }
    @Test fun groupsOnlyMatchingCameraObjectAndNameAndDeduplicatesIds() {
        val facts = EventFacts("person", "Bob", null)
        val rows = listOf(ActivityItem("a", "front", 10.0, facts), ActivityItem("a", "front", 10.0, facts),
            ActivityItem("b", "front", 20.0, facts), ActivityItem("c", "garden", 30.0, facts))
        val groups = groupActivities(rows)
        assertEquals(2, groups.size)
        assertEquals("garden", groups[0].latest.camera)
        assertEquals(2, groups[1].count)
        assertEquals("b", groups[1].latest.id)
    }
}
