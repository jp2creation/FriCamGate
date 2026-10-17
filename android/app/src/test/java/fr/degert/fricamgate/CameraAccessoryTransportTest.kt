package com.nvr.camnory

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CameraAccessoryTransportTest {
    private val states = """[{"entity_id":"siren.fixture","state":"off","attributes":{"supported_features":3}},
        {"entity_id":"light.fixture","state":"on","attributes":{}}]"""

    @Test fun discoveryOnlyReadsAndNeverSendsAServiceCommand() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(states)); server.start()
            HomeAssistantClient(server.url("/").toString(), "fixture-token").use { client ->
                assertEquals(2, client.accessories().size)
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertEquals("/api/states", request.path)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun explicitCommandTargetsOnlyOneAllowlistedEntityOnTheMockServer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(states)); server.enqueue(MockResponse().setBody("[]")); server.start()
            HomeAssistantClient(server.url("/").toString(), "fixture-token").use { client ->
                client.setAccessory("siren.fixture", false)
                assertEquals("GET", server.takeRequest().method)
                val command = server.takeRequest()
                assertEquals("POST", command.method)
                assertEquals("/api/services/siren/turn_off", command.path)
                assertEquals("siren.fixture", org.json.JSONObject(command.body.readUtf8()).getString("entity_id"))
                assertEquals("Bearer fixture-token", command.getHeader("Authorization"))
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun alarmPanelsScriptsAndMultipleTargetsAreRejectedBeforeNetworkAccess() {
        MockWebServer().use { server ->
            server.start()
            HomeAssistantClient(server.url("/").toString(), "fixture-token").use { client ->
                listOf("alarm_control_panel.home", "script.alarm", "siren.a,siren.b", "all").forEach {
                    assertTrue(runCatching { client.setAccessory(it, true) }.isFailure)
                }
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test fun unknownStateAndMissingSirenCapabilityNeverSendCommands() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("""[{"entity_id":"siren.offline","state":"unavailable","attributes":{"supported_features":3}},
                {"entity_id":"siren.unsupported","state":"off","attributes":{"supported_features":0}}]""")) }
            server.start()
            HomeAssistantClient(server.url("/").toString(), "fixture-token").use { client ->
                assertTrue(runCatching { client.setAccessory("siren.offline", true) }.isFailure)
                assertTrue(runCatching { client.setAccessory("siren.unsupported", true) }.isFailure)
                repeat(2) { assertEquals("GET", server.takeRequest().method) }
                assertEquals(2, server.requestCount)
            }
        }
    }
}
