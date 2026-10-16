package com.nvr.camnory

import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class HomeAssistantClientTest {
    @Test fun addressAndEntityValidationRejectUnsafeInputs() {
        assertEquals("https://ha.example.com", homeAssistantBaseUrl("https://ha.example.com/"))
        assertEquals("http://192.168.0.2:8123", homeAssistantBaseUrl("http://192.168.0.2:8123"))
        for (address in listOf("http://ha.example.com", "https://user:secret@ha.example.com", "https://ha.example.com/?token=x", "file:///etc/passwd", "https://ha.example.com/#x")) {
            assertTrue(address, runCatching { homeAssistantBaseUrl(address) }.isFailure)
        }
        assertTrue(validHomeAssistantCamera("camera.front_door"))
        assertFalse(validHomeAssistantCamera("alarm_control_panel.home"))
        assertFalse(validHomeAssistantCamera("camera.front/../alarm"))
    }

    @Test fun onlyCameraEntitiesAreImportedAndOtherMetadataIsDiscarded() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[
              {"entity_id":"camera.front","state":"idle","attributes":{"friendly_name":"Devant","supported_features":2}},
              {"entity_id":"alarm_control_panel.home","state":"armed","attributes":{}},
              {"entity_id":"switch.siren","state":"on","attributes":{}},
              {"entity_id":"camera.bad/path","state":"idle","attributes":{}},
              {"entity_id":"camera.blink","state":"unavailable","attributes":{"friendly_name":"Blink"}}
            ]"""))
            server.start()
            HomeAssistantClient(server.url("/").toString(), "test-token").use { client ->
                val cameras = client.cameras()
                assertEquals(listOf("camera.blink", "camera.front"), cameras.map { it.entityId })
                assertFalse(cameras.first().available)
                assertEquals("Devant", cameras.last().name)
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("GET", request.method)
                assertEquals("/api/states", request.path)
                assertEquals("Bearer test-token", request.getHeader("Authorization"))
                assertFalse(client.toString().contains("test-token"))
            }
        }
    }

    @Test fun authFailureIsExplicitAndRedirectDoesNotLeakToken() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://outside.example/"))
            HomeAssistantClient(server.url("/").toString(), "test-token").use { client ->
                assertTrue(runCatching { client.cameras() }.exceptionOrNull() is HomeAssistantAuthException)
                assertTrue(runCatching { client.cameras() }.isFailure)
                assertTrue(runCatching { client.mediaUrl("https://outside.example/stream.m3u8") }.isFailure)
                assertTrue(runCatching { client.mediaUrl("file:///secret") }.isFailure)
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun websocketOnlyRequestsCapabilitiesAndCameraStream() {
        MockWebServer().use { server ->
            val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    webSocket.send("""{"type":"auth_required"}""")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = JSONObject(text)
                    messages.add(message.getString("type"))
                    if (message.getString("type") == "auth") {
                        webSocket.send("""{"type":"auth_ok"}""")
                    } else {
                        val result = if (message.getString("type") == "camera/capabilities")
                            JSONObject().put("frontend_stream_types", org.json.JSONArray(listOf("hls")))
                        else JSONObject().put("url", "/api/hls/test/master_playlist.m3u8")
                        webSocket.send(JSONObject().put("id", message.getInt("id")).put("type", "result")
                            .put("success", true).put("result", result).toString())
                    }
                }
            }))
            server.start()
            HomeAssistantClient(server.url("/").toString(), "test-token").use { client ->
                client.socket().use { socket ->
                    assertEquals(setOf("hls"), socket.capabilities("camera.front"))
                    assertTrue(socket.stream("camera.front").endsWith("master_playlist.m3u8"))
                }
            }
            assertEquals(listOf("auth", "camera/capabilities", "camera/stream"), messages)
        }
    }

    @Test fun rejectedWebsocketAuthenticationNeverSendsCameraCommands() {
        MockWebServer().use { server ->
            val types = java.util.concurrent.CopyOnWriteArrayList<String>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) { webSocket.send("""{"type":"auth_required"}""") }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    types.add(JSONObject(text).getString("type"))
                    webSocket.send("""{"type":"auth_invalid"}""")
                }
            }))
            server.start()
            HomeAssistantClient(server.url("/").toString(), "wrong-token").use { client ->
                client.socket().use { socket ->
                    assertTrue(runCatching { socket.capabilities("camera.front") }.exceptionOrNull() is HomeAssistantAuthException)
                }
            }
            assertEquals(listOf("auth"), types)
        }
    }
}
