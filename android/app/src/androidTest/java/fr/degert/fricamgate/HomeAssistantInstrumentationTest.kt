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

    @Test fun mixedCameraArrangementPersistsVisibilityOrderAndIconPreference() {
        store.save(connection())
        OnvifCameraStore(context).save(listOf(OnvifSavedCamera("fixture", "ONVIF test", "http://127.0.0.1:1/onvif/device_service", OnvifCredentials("test", "test"))))
        val address = server.url("/").toString().trimEnd('/')
        context.getSharedPreferences("fricamgate-native", 0).edit().putString("baseUrl", address)
            .putString("username", "fixture").putString("token", "fixture-token").commit()
        val arrangement = CameraArrangementStore(context)
        val frigateKey = CameraArrangement.key("frigate", address, "front")
        val onvifKey = CameraArrangement.key("onvif", "", "fixture")
        val haKey = CameraArrangement.key("ha", address, "camera.ring_test")
        val wanted = listOf(haKey, frigateKey, onvifKey)
        arrangement.saveOrder(wanted)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor {
                var ready = false
                scenario.onActivity { activity -> ready = views(activity.findViewById(android.R.id.content)).count {
                    it.tag?.toString()?.startsWith("dashboard-camera:") == true
                } == 3 }
                ready
            }
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                assertEquals(wanted.map { "dashboard-camera:$it" }, views(root).mapNotNull { it.tag as? String }.filter { it.startsWith("dashboard-camera:") })
                val card = root.findViewWithTag<ViewGroup>("dashboard-camera:$haKey")
                val icon = card.findViewWithTag<View>("camera-source-HomeAssistant")
                val footer = icon.parent as ViewGroup
                assertSame(icon, footer.getChildAt(0))
                assertEquals("Ring test", (footer.getChildAt(1) as TextView).text.toString())
                MainActivity::class.java.getDeclaredMethod("renderCameraSettings").apply { isAccessible = true }.invoke(activity)
                val list = root.findViewWithTag<ViewGroup>("camera-arrangement")
                assertEquals(3, list.childCount)
                root.findViewWithTag<androidx.appcompat.widget.SwitchCompat>("camera-visibility:$onvifKey").isChecked = false
                assertTrue(arrangement.hidden(onvifKey))
                assertEquals(1, OnvifCameraStore(context).load().size)
                assertEquals(1, store.load()!!.cameras.size)
            }
            screenshot("camera-arrangement-settings.png")
            // Exercise the real drag handle, not a direct call to the reorder callback.
            var start = floatArrayOf(); var end = floatArrayOf()
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                fun point(view: View, fraction: Float): FloatArray {
                    val location = IntArray(2); view.getLocationOnScreen(location)
                    return floatArrayOf(location[0] + view.width / 2f, location[1] + view.height * fraction)
                }
                start = point(root.findViewWithTag("camera-reorder:$onvifKey"), 0.5f)
                end = point(root.findViewWithTag("camera-arrangement-row:$haKey"), 0.1f)
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val down = android.os.SystemClock.uptimeMillis()
            fun pointer(action: Int, x: Float, y: Float) {
                val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle()
            }
            try {
                pointer(android.view.MotionEvent.ACTION_DOWN, start[0], start[1]); Thread.sleep(750)
                repeat(15) { index ->
                    val progress = (index + 1) / 15f
                    pointer(android.view.MotionEvent.ACTION_MOVE, start[0] + (end[0] - start[0]) * progress,
                        start[1] + (end[1] - start[1]) * progress)
                    Thread.sleep(40)
                }
            } finally {
                pointer(android.view.MotionEvent.ACTION_UP, end[0], end[1])
            }
            waitFor { arrangement.savedOrder().firstOrNull() == onvifKey }
            scenario.recreate()
            waitFor {
                var ready = false
                scenario.onActivity { activity -> ready = views(activity.findViewById(android.R.id.content)).any { it.tag == "camera-arrangement" } }
                ready
            }
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                val list = root.findViewWithTag<ViewGroup>("camera-arrangement")
                assertEquals("camera-arrangement-row:$onvifKey", list.getChildAt(0).tag)
                assertFalse(root.findViewWithTag<androidx.appcompat.widget.SwitchCompat>("camera-visibility:$onvifKey").isChecked)
                MainActivity::class.java.getDeclaredMethod("renderAppearanceSettings").apply { isAccessible = true }.invoke(activity)
                root.findViewWithTag<androidx.appcompat.widget.SwitchCompat>("show-camera-source-icons").isChecked = false
                assertFalse(CameraArrangementStore(context).showSourceIcons)
                MainActivity::class.java.getDeclaredMethod("renderHome", String::class.java).apply { isAccessible = true }.invoke(activity, null)
                val rendered = views(root)
                assertEquals(listOf("dashboard-camera:$haKey", "dashboard-camera:$frigateKey"),
                    rendered.mapNotNull { it.tag as? String }.filter { it.startsWith("dashboard-camera:") })
                assertTrue(rendered.filter { it.tag?.toString()?.startsWith("camera-source-") == true }.all { it.visibility == View.GONE })
            }
        }
    }

    @Test fun standaloneCameraSettingsRemainAvailableWithoutFrigate() {
        store.save(connection())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("renderSettings").apply { isAccessible = true }.invoke(activity)
                val title = views(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>().single { it.text == activity.getString(R.string.cameras) }
                assertTrue(title.isShown)
                (title.parent.parent as View).performClick()
                assertNotNull(activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("camera-arrangement"))
            }
        }
    }

    @Test fun columnCameraPresentationMatchesFrigateWithoutFooters() {
        store.save(connection())
        OnvifCameraStore(context).save(listOf(OnvifSavedCamera("fixture", "ONVIF test", "http://127.0.0.1:1/onvif/device_service", OnvifCredentials("test", "test"))))
        val address = server.url("/").toString().trimEnd('/')
        context.getSharedPreferences("fricamgate-native", 0).edit().putString("baseUrl", address)
            .putString("username", "fixture").putString("token", "fixture-token").commit()
        context.getSharedPreferences("camgate-settings", 0).edit().putString("homeCameraLayout", "column").commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor {
                var ready = false
                scenario.onActivity { activity -> ready = views(activity.findViewById(android.R.id.content)).count {
                    it.tag?.toString()?.startsWith("dashboard-camera:") == true
                } == 3 }
                ready
            }
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                val direct = MainActivity::class.java.getDeclaredField("onvifCards").apply { isAccessible = true }.get(activity) as List<*>
                val ha = cards(activity).single() as ViewGroup
                val onvif = direct.single() as ViewGroup
                assertEquals("HA column card must not have a separate footer", 1, ha.childCount)
                assertEquals("ONVIF column card must not have a separate footer", 1, onvif.childCount)
                for (card in listOf(ha, onvif)) {
                    assertNotNull(card.findViewWithTag<View>("camera-column-overlay"))
                    assertEquals((218 * activity.resources.displayMetrics.density).toInt(), card.height)
                    val controls = card.findViewWithTag<ViewGroup>("camera-column-controls")
                    assertEquals(4, controls.childCount)
                    val name = card.findViewWithTag<View>("camera-column-name")
                    val nameRect = android.graphics.Rect(); val controlsRect = android.graphics.Rect()
                    name.getHitRect(nameRect); controls.getHitRect(controlsRect)
                    assertTrue("Camera name overlaps controls", nameRect.right <= controlsRect.left)
                }
                val live = onvif.findViewWithTag<TextView>("onvif-camera-live")
                assertEquals(activity.getString(R.string.live), live.text.toString())
                context.getSharedPreferences("camgate-settings", 0).edit().putString("homeCameraLayout", "two_columns").commit()
                MainActivity::class.java.getDeclaredMethod("renderHome", String::class.java).apply { isAccessible = true }.invoke(activity, null)
                assertNull(root.findViewWithTag<View>("camera-column-overlay"))
                val gridHa = cards(activity).single() as ViewGroup
                assertEquals("Grid must keep its compact footer", 2, gridHa.childCount)
            }
        }
    }

    @Test fun cameraPickerUnifiesSourcesAndRespectsOrderAndVisibility() {
        store.save(connection())
        OnvifCameraStore(context).save(listOf(OnvifSavedCamera("fixture", "ONVIF test", "http://127.0.0.1:1/onvif/device_service", OnvifCredentials("test", "test"))))
        val address = server.url("/").toString().trimEnd('/')
        context.getSharedPreferences("fricamgate-native", 0).edit().putString("baseUrl", address)
            .putString("username", "fixture").putString("token", "fixture-token").commit()
        val keys = listOf(CameraArrangement.key("ha", address, "camera.ring_test"),
            CameraArrangement.key("onvif", "", "fixture"), CameraArrangement.key("frigate", address, "front"))
        val arrangement = CameraArrangementStore(context)
        arrangement.saveOrder(keys)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor {
                var ready = false
                scenario.onActivity { activity -> ready = views(activity.findViewById(android.R.id.content)).count {
                    it.tag?.toString()?.startsWith("dashboard-camera:") == true
                } == 3 }
                ready
            }
            fun popup(activity: MainActivity) = MainActivity::class.java.getDeclaredField("cameraPickerSheet").apply { isAccessible = true }.get(activity) as SelectionBottomSheet
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("showCameraPickerPopup", View::class.java).apply { isAccessible = true }
                    .invoke(activity, activity.findViewById<View>(android.R.id.content))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(android.view.Gravity.BOTTOM, popup(activity).window!!.attributes.gravity)
                val rows = views(popup(activity).findViewById(android.R.id.content)).filter { it.tag?.toString()?.startsWith("camera-picker-row:") == true }
                assertEquals(keys.map { "camera-picker-row:$it" }, rows.map { it.tag })
                rows.forEach { row ->
                    val children = row as ViewGroup
                    val thumbnail = children.getChildAt(0)
                    assertEquals("camera-picker-thumbnail", thumbnail.tag)
                    assertEquals((72 * activity.resources.displayMetrics.density).toInt(), thumbnail.width)
                    val title = children.findViewWithTag<TextView>("camera-picker-name")
                    val nameRow = title.parent as ViewGroup
                    assertTrue(nameRow.getChildAt(0).tag.toString().startsWith("camera-source-"))
                    assertSame(title, nameRow.getChildAt(1))
                    val bounds = android.graphics.Rect()
                    assertTrue("Picker row is clipped", row.getGlobalVisibleRect(bounds))
                    assertEquals(row.height, bounds.height())
                }
                arrangement.setHidden(keys[1], true)
                MainActivity::class.java.getDeclaredMethod("showCameraPickerPopup", View::class.java).apply { isAccessible = true }
                    .invoke(activity, activity.findViewById<View>(android.R.id.content))
                val remaining = views(popup(activity).findViewById(android.R.id.content)).mapNotNull { it.tag as? String }.filter { it.startsWith("camera-picker-row:") }
                assertEquals(listOf("camera-picker-row:${keys[0]}", "camera-picker-row:${keys[2]}"), remaining)
            }
        }
    }

    @Test fun savedCamerasAreAccessibleWithoutReenteringTokenOrMakingRequests() {
        val saved = connection()
        store.save(saved)
        val before = File(context.noBackupFilesDir, "home-assistant.enc").readBytes()
        ActivityScenario.launch(HomeAssistantActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                val button = root.findViewWithTag<View>("connection-ha-saved-cameras")
                assertNotNull("Saved cameras need a direct, visible entry point", button)
                assertEquals(View.VISIBLE, button.visibility)
                assertTrue(views(root).filterIsInstance<EditText>().single { it.tag == "connection-ha-token" }.text.isEmpty())
                button.performClick()
                val selection = views(root).filterIsInstance<CheckBox>().single { it.text == "Ring test" }
                assertTrue(selection.isChecked)
                activity.onBackPressedDispatcher.onBackPressed()
                assertNotNull(root.findViewWithTag<View>("connection-ha-saved-cameras"))
            }
        }
        assertEquals(0, server.requestCount)
        assertArrayEquals(before, File(context.noBackupFilesDir, "home-assistant.enc").readBytes())
        val retained = requireNotNull(store.load())
        assertEquals(saved.baseUrl, retained.baseUrl)
        assertEquals(saved.token, retained.token)
        assertEquals(saved.cameras.map { it.entityId }, retained.cameras.map { it.entityId })
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

    @Test fun selectionCannotSaveEmptyAndBackRetainsConnection() {
        val address = server.url("/").toString()
        ActivityScenario.launch(HomeAssistantActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val all = views(activity.findViewById(android.R.id.content))
                all.filterIsInstance<EditText>()[0].setText(address)
                all.filterIsInstance<EditText>()[1].setText("fixture-secret-token")
                all.filterIsInstance<CheckBox>().single().isChecked = true
                all.filterIsInstance<Button>().single { it.text == activity.getString(R.string.ha_select_cameras) }.performClick()
            }
            waitFor {
                var ready = false
                scenario.onActivity { activity -> ready = views(activity.findViewById(android.R.id.content)).filterIsInstance<CheckBox>().any { it.text == "Ring test" } }
                ready
            }
            scenario.onActivity { activity ->
                val all = views(activity.findViewById(android.R.id.content))
                assertFalse(all.filterIsInstance<Button>().single { it.text == activity.getString(R.string.ha_save_selection) }.isEnabled)
                activity.onBackPressedDispatcher.onBackPressed()
                val fields = views(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>()
                assertEquals(address, fields[0].text.toString())
                assertEquals("fixture-secret-token", fields[1].text.toString())
            }
            assertEquals("fixture-secret-token", store.load()!!.token)
            assertTrue(store.load()!!.cameras.isEmpty())
        }
    }

    @Test fun captureRefinedHomeAssistantScreens() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("captureHaVisuals") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val theme = InstrumentationRegistry.getArguments().getString("previewTheme", "dark")
        val layout = InstrumentationRegistry.getArguments().getString("previewLayout", "two_columns")
        val localeManager = if (android.os.Build.VERSION.SDK_INT >= 33) context.getSystemService(android.app.LocaleManager::class.java) else null
        val oldLocales = if (android.os.Build.VERSION.SDK_INT >= 33) localeManager?.applicationLocales else null
        context.getSharedPreferences("camgate-settings", 0).edit().putString("theme", theme).putString("homeCameraLayout", layout).commit()
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.runOnMainSync {
            localeManager?.applicationLocales = android.os.LocaleList.forLanguageTags("fr")
        }
        val images = listOf("workshop", "driveway", "entrance", "terrace").associateWith { name ->
            instrumentation.context.assets.open("ha-previews/$name.jpg").use { it.readBytes() }
        }
        val names = listOf("Caméra salon", "Caméra Porte d’entrée", "Devant", "Allée", "Terrasse", "Jardin", "Garage", "Atelier", "Portail", "Cour", "Entrée suivi")
        val states = JSONArray().apply {
            names.forEachIndexed { index, name -> put(JSONObject().put("entity_id", "camera.preview_$index")
                .put("state", if (index == 7) "unavailable" else "idle").put("attributes", JSONObject().put("friendly_name", name))) }
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                val photo = when {
                    path.contains("preview_0") || path.contains("devant") -> "workshop"
                    path.contains("allee") -> "driveway"
                    path.contains("preview_1") || path.contains("entree") -> "entrance"
                    else -> "terrace"
                }
                return when (path) {
                    "/api/config" -> MockResponse().setBody("""{"cameras":{"devant":{"enabled":true,"friendly_name":"Devant"},"allee":{"enabled":true,"friendly_name":"Allée"}}}""")
                    "/api/events" -> MockResponse().setBody("[]")
                    "/api/version" -> MockResponse().setBody("0.16.0")
                    "/api/states" -> MockResponse().setBody(states.toString())
                    else -> MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(images.getValue(photo)))
                }
            }
        }
        val address = server.url("/").toString().trimEnd('/')
        store.save(HomeAssistantConnection(address, "fixture-token", names.take(2).mapIndexed { index, name -> HomeAssistantCamera("camera.preview_$index", name, true) }))
        OnvifCameraStore(context).save(listOf(OnvifSavedCamera("visual-only", "IPC-BO", "http://127.0.0.1:1/onvif/device_service", OnvifCredentials("", ""))))
        if (layout == "column") CameraArrangementStore(context).saveOrder(listOf(
            CameraArrangement.key("frigate", address, "devant"),
            CameraArrangement.key("ha", address, "camera.preview_0"),
            CameraArrangement.key("ha", address, "camera.preview_1"),
            CameraArrangement.key("frigate", address, "allee"),
            CameraArrangement.key("onvif", "", "visual-only")))
        context.getSharedPreferences("fricamgate-native", 0).edit().putString("baseUrl", address).putString("username", "fixture").putString("token", "fixture-token").commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor {
                    var ready = false
                    scenario.onActivity { activity -> ready = cards(activity).size == 2 && (layout == "column" || cards(activity).all {
                        (HomeAssistantCameraView::class.java.getDeclaredField("image").apply { isAccessible = true }.get(it) as android.widget.ImageView).drawable != null
                    }) }
                    ready
                }
                if (layout == "column") for (index in 0..1) {
                    scenario.onActivity { activity ->
                        val view = cards(activity)[index] as View
                        view.requestRectangleOnScreen(android.graphics.Rect(0, 0, view.width, view.height), true)
                    }
                    waitFor {
                        var loaded = false
                        scenario.onActivity { activity -> loaded = (cards(activity)[index] as HomeAssistantCameraView).cachedPreview() != null }
                        loaded
                    }
                }
                scenario.onActivity { activity ->
                    // Photo fixture only: validates ONVIF layout without connecting a real camera.
                    val direct = MainActivity::class.java.getDeclaredField("onvifCards").apply { isAccessible = true }.get(activity) as List<*>
                    val card = direct.single() as ViewGroup
                    (card as OnvifLiveCard).setActive(false)
                    val frame = card.getChildAt(0) as android.widget.FrameLayout
                    val status = OnvifLiveCard::class.java.getDeclaredField("status").apply { isAccessible = true }.get(card) as View
                    status.visibility = View.GONE
                    frame.addView(android.widget.ImageView(activity).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(images.getValue("workshop"), 0, images.getValue("workshop").size))
                    }, 1, android.widget.FrameLayout.LayoutParams(-1, -1))
                }
                screenshot("ha-refined-$theme-home.png")
                if (layout == "column") {
                    scenario.onActivity { activity -> views(activity.findViewById(android.R.id.content)).filterIsInstance<android.widget.ScrollView>().first().fullScroll(View.FOCUS_DOWN) }
                    screenshot("camera-column-$theme.png")
                }
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("showCameraPickerPopup", View::class.java).apply { isAccessible = true }
                        .invoke(activity, activity.findViewById<View>(android.R.id.content))
                }
                screenshot("camera-picker-$theme.png")
                scenario.onActivity { activity -> MainActivity::class.java.getDeclaredMethod("dismissCameraPicker").apply { isAccessible = true }.invoke(activity) }
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("renderCameraSettings").apply { isAccessible = true }.invoke(activity)
                }
                screenshot("camera-sources-$theme-settings.png")
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("renderHome", String::class.java).apply { isAccessible = true }.invoke(activity, null)
                }
                scenario.onActivity { activity -> (cards(activity).first() as HomeAssistantCameraView).findViewWithTag<View>("ha-camera-overflow").performClick() }
                screenshot("ha-refined-$theme-menu.png")
                scenario.onActivity { activity ->
                    val card = cards(activity).first() as HomeAssistantCameraView
                    val popup = HomeAssistantCameraView::class.java.getDeclaredField("menu").apply { isAccessible = true }.get(card) as android.widget.PopupWindow
                    val last = popup.contentView.findViewWithTag<View>("ha-camera-action-4")
                    val bounds = android.graphics.Rect()
                    assertTrue("Camera menu must not clip its final action", last.getGlobalVisibleRect(bounds))
                    assertEquals(last.height, bounds.height())
                }
            }
            ActivityScenario.launch(HomeAssistantActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> views(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
                    .single { it.text == activity.getString(R.string.ha_select_cameras) }.performClick() }
                waitFor {
                    var ready = false
                    scenario.onActivity { activity -> ready = activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<EditText>("ha-camera-search") != null }
                    ready
                }
                screenshot("ha-refined-$theme-selection.png")
                scenario.onActivity { activity ->
                    val save = views(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
                        .single { it.text == activity.getString(R.string.ha_save_selection) }
                    val bounds = android.graphics.Rect()
                    assertTrue("Save must remain visible with a long camera list", save.getGlobalVisibleRect(bounds))
                    assertEquals(save.height, bounds.height())
                }
            }
        } finally {
            if (android.os.Build.VERSION.SDK_INT >= 33 && oldLocales != null) instrumentation.runOnMainSync { localeManager?.applicationLocales = oldLocales }
        }
    }

    @Test fun cameraSearchPreservesSelectionsOutsideTheFilter() {
        val address = server.url("/").toString()
        ActivityScenario.launch(HomeAssistantActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                root.findViewWithTag<EditText>("connection-ha-server").setText(address)
                root.findViewWithTag<EditText>("connection-ha-token").setText("fixture-secret-token")
                views(root).filterIsInstance<CheckBox>().single().isChecked = true
                views(root).filterIsInstance<Button>().single { it.text == activity.getString(R.string.ha_select_cameras) }.performClick()
            }
            waitFor {
                var ready = false
                scenario.onActivity { activity -> ready = views(activity.findViewById(android.R.id.content)).filterIsInstance<CheckBox>().any { it.text == "Ring test" } }
                ready
            }
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                val checks = views(root).filterIsInstance<CheckBox>()
                val ring = checks.single { it.text == "Ring test" }
                ring.isChecked = true
                val search = root.findViewWithTag<EditText>("ha-camera-search")
                assertNotNull("Camera selection needs a search field", search)
                search.setText("Blink")
                assertFalse(ring.isShown)
                assertTrue(checks.single { it.text == "Blink test" }.isShown)
                search.setText("")
                assertTrue(ring.isChecked)
                views(root).filterIsInstance<Button>().single { it.text == activity.getString(R.string.ha_save_selection) }.performClick()
            }
        }
        assertEquals("camera.ring_test", store.load()!!.cameras.single().entityId)
        assertTrue(commands.isEmpty())
    }

    @Test fun emptyDiscoveryKeepsSavedCamerasUntilExplicitSelection() {
        store.save(connection())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("[]")
        }
        ActivityScenario.launch(HomeAssistantActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                views(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
                    .single { it.text == activity.getString(R.string.ha_select_cameras) }.performClick()
            }
            waitFor {
                var empty = false
                scenario.onActivity { activity -> empty = views(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.ha_no_cameras) } }
                empty
            }
            assertEquals("camera.ring_test", store.load()!!.cameras.single().entityId)
            scenario.onActivity { activity ->
                assertFalse(views(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
                    .single { it.text == activity.getString(R.string.ha_save_selection) }.isEnabled)
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test fun authenticationFailureStaysOnFormAndDoesNotSaveToken() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401)
        }
        val address = server.url("/").toString()
        ActivityScenario.launch(HomeAssistantActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val all = views(activity.findViewById(android.R.id.content))
                all.filterIsInstance<EditText>()[0].setText(address)
                all.filterIsInstance<EditText>()[1].setText("fixture-secret-token")
                all.filterIsInstance<CheckBox>().single().isChecked = true
                all.filterIsInstance<Button>().single { it.text == activity.getString(R.string.ha_select_cameras) }.performClick()
            }
            waitFor {
                var failed = false
                scenario.onActivity { activity -> failed = views(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.ha_auth_failed) } }
                failed
            }
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                assertEquals("fixture-secret-token", root.findViewWithTag<EditText>("connection-ha-token").text.toString())
                assertEquals(address, root.findViewWithTag<EditText>("connection-ha-server").text.toString())
                assertNull(store.load())
            }
        }
    }

    @Test fun cancelingHomeAssistantReturnsToSettingsWithoutLosingFrigate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context.getSharedPreferences("fricamgate-native", 0).edit().putString("baseUrl", server.url("/").toString().trimEnd('/'))
            .putString("username", "test").putString("token", "frigate-fixture-token").commit()
        val monitor = instrumentation.addMonitor(HomeAssistantActivity::class.java.name, null, false)
        var setupActivity: HomeAssistantActivity? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor {
                    var loaded = false
                    scenario.onActivity { activity -> loaded = (MainActivity::class.java.getDeclaredField("cameras").apply { isAccessible = true }.get(activity) as List<*>).isNotEmpty() }
                    loaded
                }
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("renderSettings").apply { isAccessible = true }.invoke(activity)
                    activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("settings-entry-connections").performClick()
                    activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("settings-entry-ha").performClick()
                }
                setupActivity = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? HomeAssistantActivity
                val setup = requireNotNull(setupActivity)
                instrumentation.runOnMainSync { setup.onBackPressedDispatcher.onBackPressed() }
                waitFor { scenario.state == Lifecycle.State.RESUMED }
                scenario.onActivity { activity ->
                    assertTrue("Canceling HA setup must stay in settings", views(activity.findViewById(android.R.id.content))
                        .filterIsInstance<TextView>().any { it.text == activity.getString(R.string.ha_cameras) })
                }
            }
        } finally {
            instrumentation.runOnMainSync { setupActivity?.takeUnless { it.isFinishing }?.finish() }
            instrumentation.removeMonitor(monitor)
        }
        assertNull(store.load())
        assertEquals("frigate-fixture-token", context.getSharedPreferences("fricamgate-native", 0).getString("token", null))
    }

    @Test fun settingsWithFrigateKeepsHomeAssistantSelectionOpen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val address = server.url("/").toString()
        context.getSharedPreferences("fricamgate-native", 0).edit().putString("baseUrl", address.trimEnd('/'))
            .putString("username", "test").putString("token", "frigate-fixture-token").commit()
        val monitor = instrumentation.addMonitor(HomeAssistantActivity::class.java.name, null, false)
        var setupActivity: HomeAssistantActivity? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor {
                    var loaded = false
                    scenario.onActivity { activity -> loaded = (MainActivity::class.java.getDeclaredField("cameras").apply { isAccessible = true }.get(activity) as List<*>).isNotEmpty() }
                    loaded
                }
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("renderSettings").apply { isAccessible = true }.invoke(activity)
                    activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("settings-entry-connections").performClick()
                    activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("settings-entry-ha").performClick()
                }
                setupActivity = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? HomeAssistantActivity
                val activity = requireNotNull(setupActivity)
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val root = activity.findViewById<ViewGroup>(android.R.id.content)
                    root.findViewWithTag<EditText>("connection-ha-server").setText(address)
                    root.findViewWithTag<EditText>("connection-ha-token").setText("fixture-secret-token")
                    views(root).filterIsInstance<CheckBox>().single().isChecked = true
                    views(root).filterIsInstance<Button>().single { it.text == activity.getString(R.string.ha_select_cameras) }.performClick()
                }
                waitFor {
                    var ready = false
                    instrumentation.runOnMainSync { ready = views(activity.findViewById(android.R.id.content)).filterIsInstance<CheckBox>().any { it.text == "Ring test" } }
                    ready
                }
                instrumentation.runOnMainSync {
                    assertFalse(activity.isFinishing)
                    views(activity.findViewById(android.R.id.content)).filterIsInstance<CheckBox>().single { it.text == "Ring test" }.isChecked = true
                    views(activity.findViewById(android.R.id.content)).filterIsInstance<Button>().single { it.text == activity.getString(R.string.ha_save_selection) }.performClick()
                }
                waitFor {
                    var shown = false
                    if (scenario.state == Lifecycle.State.RESUMED) scenario.onActivity {
                        shown = it.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("settings-entry-ha") != null
                    }
                    shown
                }
            }
        } finally {
            instrumentation.runOnMainSync { setupActivity?.takeUnless { it.isFinishing }?.finish() }
            instrumentation.removeMonitor(monitor)
        }
        assertEquals("camera.ring_test", store.load()!!.cameras.single().entityId)
        assertTrue(commands.isEmpty())
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
                    .any { it.contentDescription == activity.getString(R.string.ha_image_only) } }
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
                val card = cards(activity).single() as HomeAssistantCameraView
                card.findViewWithTag<View>("ha-camera-overflow").performClick()
                val popup = HomeAssistantCameraView::class.java.getDeclaredField("menu").apply { isAccessible = true }.get(card) as android.widget.PopupWindow
                popup.contentView.findViewWithTag<View>("ha-camera-action-3").performClick()
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
                    assertNotNull("HA cards need the same overflow control as Frigate", card.findViewWithTag<View>("ha-camera-overflow"))
                    assertEquals("HA grid footer must match Frigate", width * 9 / 16 + (48 * activity.resources.displayMetrics.density).toInt(), card.measuredHeight)
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
