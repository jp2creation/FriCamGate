package com.nvr.camnory

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CameraBuiltinTransportTest {
    @Test fun discoveryIsReadOnlyAndReassignedEquipmentCannotBeCommanded() {
        MockWebServer().use { server ->
            val device = AtomicReference("camera-device")
            val commands = AtomicInteger()
            val requestedTypes = java.util.concurrent.CopyOnWriteArrayList<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST") commands.incrementAndGet()
                    return when (request.path) {
                        "/api/websocket" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(ws: WebSocket, response: Response) { ws.send("""{"type":"auth_required"}""") }
                            override fun onMessage(ws: WebSocket, text: String) {
                                val msg = JSONObject(text); val type = msg.getString("type")
                                requestedTypes.add(type)
                                if (type == "auth") ws.send("""{"type":"auth_ok"}""")
                                else ws.send(JSONObject().put("type", "result").put("id", msg.getInt("id")).put("success", true)
                                    .put("result", JSONArray().put(JSONObject().put("entity_id", "camera.front").put("device_id", "camera-device").put("platform", "ring"))
                                        .put(JSONObject().put("entity_id", "siren.front").put("device_id", device.get()).put("platform", "ring"))).toString())
                            }
                        })
                        "/api/states" -> MockResponse().setBody("""[{"entity_id":"siren.front","state":"off","attributes":{"supported_features":3}}]""")
                        else -> MockResponse().setResponseCode(500)
                    }
                }
            }
            val client = HomeAssistantClient(server.url("/").toString(), "fixture-token")
            try {
                assertEquals("siren.front", client.cameraBuiltins("camera.front").single().entityId)
                assertEquals(0, commands.get())
                device.set("house-alarm")
                assertTrue(runCatching { client.setCameraBuiltin("camera.front", "siren.front", true) }.isFailure)
                assertEquals(0, commands.get())
                assertTrue(requestedTypes.all { it in setOf("auth", "config/entity_registry/list") })
            } finally { client.close() }
        }
    }

    @Test fun frigateDiscoveryNeverOpensACommandSocketAndRejectsUnsupportedDirections() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"features":["pt-r-fov"],"presets":[]}"""))
            val driver = FrigateCameraPtzDriver(FrigateServer("test", "Test", server.url("/").toString(), "user", "fixture-token"), "front")
            try {
                assertTrue(driver.discover().panTilt)
                val request = server.takeRequest()
                assertEquals("/api/front/ptz/info", request.path)
                assertEquals("GET", request.method)
                assertTrue(runCatching { driver.move("in") }.isFailure)
                assertTrue(runCatching { driver.move("siren") }.isFailure)
                assertEquals(1, server.requestCount)
            } finally { driver.close() }
        }
    }
    @Test fun frigateFiniteMovementUsesTheCameraTopicOnly() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"features":["pt-r-fov"],"presets":[]}"""))
            val command = AtomicReference<JSONObject>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(ws: WebSocket, text: String) { command.set(JSONObject(text)) }
                override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(code, null) }
            }))
            val driver = FrigateCameraPtzDriver(FrigateServer("test", "Test", server.url("/").toString(), "user", "fixture-token"), "front")
            try {
                driver.discover(); driver.move("left")
                assertEquals("front/ptz", command.get().getString("topic"))
                assertEquals("move_relative_-0.1_0_0", command.get().getString("payload"))
                assertEquals(2, server.requestCount)
            } finally { driver.close() }
        }
    }
}
