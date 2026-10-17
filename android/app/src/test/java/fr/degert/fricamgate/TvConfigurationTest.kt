package com.nvr.camnory

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TvConfigurationTest {
    private fun fixture() = TvConfiguration(
        listOf(FrigateServer("one", "Fixture", "https://nvr.example", "test", "dummy-token")),
        listOf(OnvifSavedCamera("two", "Camera", "http://192.168.1.50/onvif/device_service", OnvifCredentials("test", "dummy-password"))),
        HomeAssistantConnection("https://ha.example", "dummy-ha-token", listOf(HomeAssistantCamera("camera.fixture", "Fixture HA", true))),
        listOf("key-two", "key-one"), setOf("key-hidden"))
    @Test fun configurationRoundTripPreservesConnectionsAndArrangementOnly() {
        val value = fixture()
        val copy = TvConfiguration.decode(value.encode())
        assertEquals(value.frigate, copy.frigate)
        assertEquals("dummy-password", copy.onvif.single().credentials.password)
        assertEquals("dummy-ha-token", copy.ha!!.token)
        assertEquals(value.order, copy.order)
        assertEquals(value.hidden, copy.hidden)
        assertFalse(copy.toString().contains("dummy"))
        assertEquals(setOf("version", "frigate", "onvif", "ha", "order", "hidden"), JSONObject(String(value.encode())).keys().asSequence().toSet())
    }
    @Test fun rejectsUnknownFieldsAndWrongSchema() {
        val o = JSONObject(String(fixture().encode()))
        assertTrue(runCatching { TvConfiguration.decode(o.put("command", "alarm").toString().toByteArray()) }.isFailure)
        o.remove("command"); o.put("version", 2)
        assertTrue(runCatching { TvConfiguration.decode(o.toString().toByteArray()) }.isFailure)
        assertTrue(runCatching { TvConfiguration.decode(ByteArray(TvConfiguration.MAX_BYTES + 1)) }.isFailure)
    }
    @Test fun rejectsInvalidEntityAndEmbeddedUrlCredentials() {
        val o = JSONObject(String(fixture().encode()))
        o.getJSONObject("ha").getJSONArray("cameras").getJSONObject(0).put("id", "alarm_control_panel.house")
        assertTrue(runCatching { TvConfiguration.decode(o.toString().toByteArray()) }.isFailure)
        val other = JSONObject(String(fixture().encode()))
        other.getJSONArray("frigate").getJSONObject(0).put("url", "https://user:password@nvr.example")
        assertTrue(runCatching { TvConfiguration.decode(other.toString().toByteArray()) }.isFailure)
    }
}
