package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class CameraAccessoryTest {
    @Test fun onlyExplicitLightAndSirenDomainsAreAccepted() {
        assertTrue(validCameraAccessory("light.front"))
        assertTrue(validCameraAccessory("siren.front"))
        listOf("switch.front", "alarm_control_panel.home", "script.alarm", "light.front,light.other", "light../siren").forEach {
            assertFalse(validCameraAccessory(it))
        }
    }
    @Test fun discoveryDoesNotTreatUnknownStatesAsOff() {
        val items = parseCameraAccessories(org.json.JSONArray("""[
            {"entity_id":"light.front","state":"on","attributes":{"friendly_name":"Front light"}},
            {"entity_id":"siren.front","state":"unavailable","attributes":{}},
            {"entity_id":"alarm_control_panel.home","state":"armed_away","attributes":{}}
        ]"""))
        assertEquals(2, items.size)
        assertEquals(true, items.first { it.entityId == "light.front" }.enabled)
        assertNull(items.first { it.entityId == "siren.front" }.enabled)
    }
}
