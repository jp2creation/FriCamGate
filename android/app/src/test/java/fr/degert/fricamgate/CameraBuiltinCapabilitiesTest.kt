package com.nvr.camnory

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class CameraBuiltinCapabilitiesTest {
    @Test fun continuousOnlyPtzIsNotMisrepresentedAsFiniteMovement() {
        val state = frigatePtzState(org.json.JSONObject("""{"features":["pt","zoom"],"presets":[]}"""))
        assertFalse(state.available)
        assertTrue(frigatePtzState(org.json.JSONObject("""{"features":["pt-r-fov","zoom-r"],"presets":[]}""")).zoom)
    }
    @Test fun onlyUniqueEntitiesOfTheCameraDeviceAndIntegrationAreEligible() {
        val registry = JSONArray("""[
            {"entity_id":"camera.front","device_id":"cam1","platform":"ring"},
            {"entity_id":"light.front","device_id":"cam1","platform":"ring"},
            {"entity_id":"siren.front","device_id":"cam1","platform":"ring"},
            {"entity_id":"siren.house","device_id":"house","platform":"alarm"},
            {"entity_id":"light.unrelated","device_id":"cam1","platform":"other"} ]""")
        assertEquals(setOf("light.front", "siren.front"), cameraBuiltinEntityIds("camera.front", registry))
        assertTrue(cameraBuiltinEntityIds("camera.unknown", registry).isEmpty())
    }
    @Test fun ambiguousRelaysDisabledEntitiesAndMultipleCameraDevicesStayUnavailable() {
        val registry = JSONArray("""[
            {"entity_id":"camera.front","device_id":"cam1","platform":"ring"},
            {"entity_id":"light.one","device_id":"cam1","platform":"ring"},
            {"entity_id":"light.two","device_id":"cam1","platform":"ring"},
            {"entity_id":"siren.disabled","device_id":"cam1","platform":"ring","disabled_by":"user"},
            {"entity_id":"switch.relay","device_id":"cam1","platform":"ring"} ]""")
        assertTrue(cameraBuiltinEntityIds("camera.front", registry).isEmpty())
        registry.put(org.json.JSONObject("""{"entity_id":"camera.second","device_id":"cam1","platform":"ring"}"""))
        assertTrue(cameraBuiltinEntityIds("camera.front", registry).isEmpty())
    }
}
