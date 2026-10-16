package com.nvr.camnory

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HomeAssistantInstrumentationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = HomeAssistantStore(context)
    private val files = listOf("home-assistant.enc", "onvif-cameras.enc")
    private lateinit var backups: Map<String, ByteArray?>
    private lateinit var preferences: Map<String, Map<String, *>>
    private val server = MockWebServer()
    private val commands = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val offerReceived = CountDownLatch(1)
    private var offer = ""
    private var streamTypes = emptyList<String>()

    @Before fun setup() {
        backups = files.associateWith { File(context.noBackupFilesDir, it).takeIf { it.exists() }?.readBytes() }
        preferences = listOf("fricamgate-native", "camgate-settings").associateWith { context.getSharedPreferences(it, 0).all }
        context.getSharedPreferences("fricamgate-native", 0).edit().clear().commit()
        context.getSharedPreferences("camgate-settings", 0).edit().clear().commit()
        OnvifCameraStore(context).save(emptyList()); store.save(null)
        val image = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.rgb(45, 74, 62))
            drawText("Camera de test", 40f, 180f, Paint().apply { color = Color.WHITE; textSize = 38f })
        }
        val bytes = java.io.ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        image.recycle()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                "/api/config" -> MockResponse().setBody("""{"cameras":{"front":{"enabled":true}}}""")
                "/api/events" -> MockResponse().setBody("[]")
                "/api/version" -> MockResponse().setBody("0.16.0")
                "/api/states" -> MockResponse().setBody("""[
                    {"entity_id":"camera.ring_test","state":"idle","attributes":{"friendly_name":"Ring test"}},
                    {"entity_id":"camera.blink_test","state":"idle","attributes":{"friendly_name":"Blink test"}},
                    {"entity_id":"switch.siren","state":"off","attributes":{}}
                ]""")
                "/api/camera_proxy/camera.ring_test", "/api/camera_proxy/camera.blink_test" ->
                    MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes))
                "/api/websocket" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) { webSocket.send("""{"type":"auth_required"}""") }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val value = JSONObject(text); val type = value.getString("type"); commands.add(type)
                        if (type == "auth") { webSocket.send("""{"type":"auth_ok"}"""); return }
                        val result = when (type) {
                            "camera/capabilities" -> JSONObject().put("frontend_stream_types", JSONArray(streamTypes))
                            "camera/webrtc/get_client_config" -> JSONObject().put("configuration", JSONObject().put("iceServers", JSONArray()))
                            else -> JSONObject()
                        }
                        webSocket.send(JSONObject().put("id", value.getInt("id")).put("type", "result")
                            .put("success", true).put("result", result).toString())
                        if (type == "camera/webrtc/offer") {
                            offer = value.getString("offer"); offerReceived.countDown()
                            webSocket.send(JSONObject().put("id", value.getInt("id")).put("type", "event")
                                .put("event", JSONObject().put("type", "session").put("session_id", "fixture-session")).toString())
                        }
                    }
                })
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }
    @After fun cleanup() {
        server.shutdown()
        backups.forEach { (name, bytes) ->
            val file = File(context.noBackupFilesDir, name)
            if (bytes == null) file.delete() else file.writeBytes(bytes)
        }
        preferences.forEach { (name, values) ->
            val editor = context.getSharedPreferences(name, 0).edit().clear()
            values.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>)
            } }
            editor.commit()
        }
    }
    private fun connection() = HomeAssistantConnection(server.url("/").toString().trimEnd('/'), "fixture-secret-token",
        listOf(HomeAssistantCamera("camera.ring_test", "Ring test", true)))
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun cards(activity: MainActivity) = MainActivity::class.java.getDeclaredField("homeAssistantCards")
        .apply { isAccessible = true }.get(activity) as List<*>
    private fun waitFor(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition()) { if (System.nanoTime() > end) fail("Timed out waiting for camera UI"); Thread.sleep(100) }
    }
    private fun screenshot(name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(300)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        context.openFileOutput(name, Context.MODE_PRIVATE).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun encryptedConnectionRoundTripAndLocalDisconnect() {
        store.save(connection())
        assertEquals("camera.ring_test", store.load()!!.cameras.single().entityId)
        assertFalse(File(context.noBackupFilesDir, "home-assistant.enc").readText(Charsets.ISO_8859_1).contains("fixture-secret-token"))
        store.save(null); assertNull(store.load()); assertEquals(0, server.requestCount)
    }

    @Test fun homeAssistantCoexistsWithFrigateAndOnvifWithoutReplacingSessions() {
        store.save(connection())
        OnvifCameraStore(context).save(listOf(OnvifSavedCamera("fixture", "ONVIF test", "http://127.0.0.1:1/onvif/device_service", OnvifCredentials("test", "test"))))
        val url = server.url("/").toString().trimEnd('/')
        context.getSharedPreferences("fricamgate-native", 0).edit().putString("baseUrl", url)
            .putString("username", "test").putString("token", "frigate-fixture-token").commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor {
                var ready = false
                scenario.onActivity { activity ->
                    val frigate = MainActivity::class.java.getDeclaredField("cameras").apply { isAccessible = true }.get(activity) as List<*>
                    val onvif = MainActivity::class.java.getDeclaredField("onvifCards").apply { isAccessible = true }.get(activity) as List<*>
                    ready = frigate.size == 1 && onvif.size == 1 && cards(activity).size == 1
                }
                ready
            }
            assertEquals("frigate-fixture-token", context.getSharedPreferences("fricamgate-native", 0).getString("token", null))
            assertEquals(1, OnvifCameraStore(context).load().size)
            assertEquals(1, store.load()!!.cameras.size)
        }
    }

    @Test fun connectionSelectsOnlyCamerasWithoutSendingServices() {
        val address = server.url("/").toString()
        ActivityScenario.launch(HomeAssistantActivity::class.java).use { scenario ->
            screenshot("ha-connection.png")
            scenario.onActivity { activity ->
                val all = views(activity.findViewById(android.R.id.content))
                val fields = all.filterIsInstance<EditText>()
                fields[0].setText(address); fields[1].setText("fixture-secret-token")
                all.filterIsInstance<CheckBox>().single { it.text == activity.getString(R.string.ha_allow_local_http) }.isChecked = true
                all.filterIsInstance<Button>().single { it.text == activity.getString(R.string.ha_select_cameras) }.performClick()
            }
            waitFor {
                var selected = false
                scenario.onActivity { activity -> selected = views(activity.findViewById(android.R.id.content)).filterIsInstance<CheckBox>().any { it.text == "Ring test" } }
                selected
            }
            screenshot("ha-camera-selection.png")
            scenario.onActivity { activity ->
                val all = views(activity.findViewById(android.R.id.content))
                val checks = all.filterIsInstance<CheckBox>()
                assertEquals(2, checks.size)
                checks.single { it.text == "Ring test" }.isChecked = true
                all.filterIsInstance<Button>().single { it.text == activity.getString(R.string.ha_save_selection) }.performClick()
            }
        }
        assertEquals("camera.ring_test", store.load()!!.cameras.single().entityId)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method); assertEquals("/api/states", request.path)
        assertEquals(1, server.requestCount)
    }

    @Test fun onboardingHomeAssistantRemainsInlineAndRequiresHttpConsent() {
        val address = server.url("/").toString()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                views(activity.findViewById(android.R.id.content)).filterIsInstance<android.widget.Spinner>().single().setSelection(2)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                root.findViewWithTag<EditText>("connection-ha-server").setText(address)
                root.findViewWithTag<EditText>("connection-ha-token").setText("fixture-secret-token")
                views(root).filterIsInstance<Button>().single { it.text == activity.getString(R.string.ha_select_cameras) }.performClick()
                assertTrue(root.findViewWithTag<View>("connection-ha-advanced-body").isShown)
                assertEquals(0, server.requestCount)
                views(root).filterIsInstance<CheckBox>().single { it.text == activity.getString(R.string.ha_allow_local_http) }.isChecked = true
                views(root).filterIsInstance<Button>().single { it.text == activity.getString(R.string.ha_select_cameras) }.performClick()
            }
            waitFor {
                var ready = false
                scenario.onActivity { activity -> ready = views(activity.findViewById(android.R.id.content)).filterIsInstance<CheckBox>().any { it.text == "Ring test" } }
                ready
            }
            scenario.onActivity { activity ->
                val all = views(activity.findViewById(android.R.id.content))
                all.filterIsInstance<CheckBox>().single { it.text == "Ring test" }.isChecked = true
                all.filterIsInstance<Button>().single { it.text == activity.getString(R.string.ha_save_selection) }.performClick()
                assertEquals(1, cards(activity).size)
            }
        }
        assertEquals("camera.ring_test", store.load()!!.cameras.single().entityId)
        assertTrue(commands.isEmpty())
    }

    @Test fun standaloneHomeShowsImageButDoesNotAutoStartLiveAndReleasesOnNavigation() {
        store.save(connection())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertNotNull(server.takeRequest(10, TimeUnit.SECONDS))
            Thread.sleep(400)
            screenshot("ha-home.png")
            scenario.onActivity { activity ->
                assertEquals(1, cards(activity).size)
                val all = views(activity.findViewById(android.R.id.content))
                assertTrue(all.filterIsInstance<TextView>().any { it.text == "Ring test" })
                assertTrue(commands.isEmpty())
                MainActivity::class.java.getDeclaredMethod("renderSettings").apply { isAccessible = true }.invoke(activity)
                assertTrue(cards(activity).isEmpty())
            }
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun unsupportedLiveStaysClearlyAnImageAndNeverCallsAService() {
        store.save(connection())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertNotNull(server.takeRequest(10, TimeUnit.SECONDS))
            scenario.onActivity { (cards(it).single() as HomeAssistantCameraView).startLive() }
            waitFor {
                var done = false
                scenario.onActivity { activity -> done = views(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
                    .any { it.text == activity.getString(R.string.ha_image_only) } }
                done
            }
            assertEquals(listOf("auth", "camera/capabilities"), commands)
        }
    }

    @Test fun webRtcNegotiationIsReceiveOnlyAndStopsOnBackground() {
        streamTypes = listOf("web_rtc")
        store.save(connection())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertNotNull(server.takeRequest(10, TimeUnit.SECONDS))
            scenario.onActivity { (cards(it).single() as HomeAssistantCameraView).startLive() }
            assertTrue("WebRTC offer was not sent", offerReceived.await(20, TimeUnit.SECONDS))
            assertTrue(offer.contains("a=recvonly")); assertFalse(offer.contains("a=sendrecv")); assertFalse(offer.contains("a=sendonly"))
            assertTrue(commands.all { it in setOf("auth", "camera/capabilities", "camera/webrtc/get_client_config", "camera/webrtc/offer", "camera/webrtc/candidate") })
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { activity ->
                val card = cards(activity).single() as HomeAssistantCameraView
                assertNull(HomeAssistantCameraView::class.java.getDeclaredField("web").apply { isAccessible = true }.get(card))
                assertNull(HomeAssistantCameraView::class.java.getDeclaredField("socket").apply { isAccessible = true }.get(card))
            }
        }
    }

    @Test fun fullscreenReturnsToTheSameHomeWithoutChangingStoredSources() {
        val connection = connection()
        store.save(connection)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertNotNull(server.takeRequest(10, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("openHomeAssistantCamera", HomeAssistantConnection::class.java, HomeAssistantCamera::class.java)
                    .apply { isAccessible = true }.invoke(activity, connection, connection.cameras.single())
            }
            Thread.sleep(400)
            screenshot("ha-fullscreen.png")
            scenario.onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("homeAssistantFullscreen").apply { isAccessible = true }
                val dialog = field.get(activity) as android.app.Dialog
                assertTrue(dialog.isShowing)
                dialog.dismiss()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("homeAssistantFullscreen").apply { isAccessible = true }
                assertNull(field.get(activity))
                assertEquals(1, cards(activity).size)
            }
        }
        assertEquals(connection.cameras, store.load()!!.cameras)
    }

    @Test fun compactCameraControlsFitSmallColumnWidths() {
        val connection = connection()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                for (widthDp in listOf(90, 140, 296)) {
                    val card = HomeAssistantCameraView(activity, connection, connection.cameras.single(), {}, {})
                    val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                    repeat(2) {
                        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        card.layout(0, 0, width, card.measuredHeight)
                    }
                    views(card).filterIsInstance<android.widget.ImageButton>().filter { it.visibility == View.VISIBLE }.forEach {
                        assertTrue("Controls overflow at $widthDp dp", it.right <= (it.parent as View).width)
                        assertTrue(it.left >= 0)
                    }
                }
            }
        }
        assertEquals(0, server.requestCount)
    }
}
