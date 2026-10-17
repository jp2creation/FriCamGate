package com.nvr.camnory

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class SettingsNavigationInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val server = MockWebServer()
    private lateinit var prefs: Map<String, Map<String, *>>
    private lateinit var files: Map<String, ByteArray?>

    @Before fun setup() {
        prefs = listOf("fricamgate-native", "camgate-settings").associateWith { context.getSharedPreferences(it, 0).all }
        files = listOf("home-assistant.enc", "onvif-cameras.enc").associateWith {
            File(context.noBackupFilesDir, it).takeIf { file -> file.exists() }?.readBytes()
        }
        context.getSharedPreferences("camgate-settings", 0).edit().clear().putString("theme", "dark").commit()
        HomeAssistantStore(context).save(null); OnvifCameraStore(context).save(emptyList())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path?.substringBefore('?')) {
                "/api/config" -> MockResponse().setBody("""{"cameras":{"front":{"enabled":true}}}""")
                "/api/events" -> MockResponse().setBody("[]")
                "/api/version" -> MockResponse().setBody("test-version")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        context.getSharedPreferences("fricamgate-native", 0).edit().clear().putString("baseUrl", server.url("/").toString().trimEnd('/'))
            .putString("username", "fixture").putString("token", "fixture-token").commit()
    }

    @After fun cleanup() {
        server.shutdown()
        files.forEach { (name, bytes) ->
            val file = File(context.noBackupFilesDir, name)
            if (bytes == null) file.delete() else file.writeBytes(bytes)
        }
        prefs.forEach { (name, values) ->
            val edit = context.getSharedPreferences(name, 0).edit().clear()
            values.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            edit.commit()
        }
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun root(activity: MainActivity) = activity.findViewById<ViewGroup>(android.R.id.content)
    private fun render(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun page(activity: MainActivity) = MainActivity::class.java.getDeclaredField("settingsPage").apply { isAccessible = true }.get(activity)
    private fun awaitDashboard(scenario: ActivityScenario<MainActivity>) {
        repeat(100) {
            var loaded = false
            scenario.onActivity { activity -> loaded = (MainActivity::class.java.getDeclaredField("cameras").apply { isAccessible = true }.get(activity) as List<*>).isNotEmpty() }
            if (loaded) return
            Thread.sleep(50)
        }
        fail("Fixture dashboard did not load")
    }

    @Test fun activityBriefingStylesHaveOneEntryAndRespectHiddenPreference() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitDashboard(scenario)
            scenario.onActivity { activity ->
                val preferences = context.getSharedPreferences("camgate-settings", 0)
                ActivityBriefingStyle.values().forEach { style ->
                    preferences.edit().putString("activityBriefingStyle", style.preference).putBoolean("showActivityBriefing", true).commit()
                    MainActivity::class.java.getDeclaredMethod("renderHome", String::class.java)
                        .apply { isAccessible = true }.invoke(activity, null)
                    val entries = views(root(activity)).filter { it.tag == "activity-briefing-entry" }
                    assertEquals(if (style == ActivityBriefingStyle.Bell) 0 else 1, entries.size)
                    entries.firstOrNull()?.let { entry ->
                        if (style == ActivityBriefingStyle.Floating) assertTrue(entry.layoutParams is android.widget.FrameLayout.LayoutParams)
                        else assertTrue(entry.layoutParams is android.widget.LinearLayout.LayoutParams)
                        entry.performClick()
                        val sheet = MainActivity::class.java.getDeclaredField("activityBriefingSheet")
                            .apply { isAccessible = true }.get(activity) as ActivityBriefingSheet
                        assertTrue(sheet.isShowing)
                        sheet.dismiss()
                    }
                    preferences.edit().putBoolean("showActivityBriefing", false).commit()
                    MainActivity::class.java.getDeclaredMethod("renderHome", String::class.java)
                        .apply { isAccessible = true }.invoke(activity, null)
                    assertNull(root(activity).findViewWithTag<View>("activity-briefing-entry"))
                }
                render(activity, "renderAppearanceSettings")
                assertNotNull(root(activity).findViewWithTag<View>("appearance-activityBriefingStyle"))
            }
        }
    }

    @Test fun activityBriefingFloatsAboveNavigationAndRemainsAvailableWhenRead() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitDashboard(scenario)
            scenario.onActivity { activity ->
                val entry = root(activity).findViewWithTag<View>("activity-briefing-entry")
                assertNotNull(entry)
                assertTrue(entry.parent is android.widget.FrameLayout)
                val params = entry.layoutParams as android.widget.FrameLayout.LayoutParams
                assertEquals(android.view.Gravity.BOTTOM or android.view.Gravity.END, params.gravity)
                assertEquals((64 * activity.resources.displayMetrics.density).toInt(), params.width)
                assertNull(root(activity).findViewWithTag<View>("activity-briefing-badge"))
                entry.performClick()
                val field = MainActivity::class.java.getDeclaredField("activityBriefingSheet").apply { isAccessible = true }
                val sheet = field.get(activity) as ActivityBriefingSheet
                assertTrue(views(sheet.window!!.decorView).filterIsInstance<TextView>()
                    .any { it.text == activity.getString(R.string.activity_up_to_date) })
                views(sheet.window!!.decorView).filterIsInstance<TextView>()
                    .first { it.text == activity.getString(R.string.activity_mark_read) }.performClick()
                assertFalse(sheet.isShowing)
                assertNotNull(root(activity).findViewWithTag<View>("activity-briefing-entry"))
                render(activity, "renderAppearanceSettings")
                val toggle = root(activity).findViewWithTag<androidx.appcompat.widget.SwitchCompat>("show-activity-briefing")
                assertTrue(toggle.isChecked)
                toggle.isChecked = false
                assertFalse(context.getSharedPreferences("camgate-settings", 0).getBoolean("showActivityBriefing", true))
                MainActivity::class.java.getDeclaredMethod("renderHome", String::class.java).apply { isAccessible = true }.invoke(activity, null)
                assertNull(root(activity).findViewWithTag<View>("activity-briefing-entry"))
                render(activity, "renderAppearanceSettings")
                root(activity).findViewWithTag<androidx.appcompat.widget.SwitchCompat>("show-activity-briefing").isChecked = true
                MainActivity::class.java.getDeclaredMethod("renderHome", String::class.java).apply { isAccessible = true }.invoke(activity, null)
                assertNotNull(root(activity).findViewWithTag<View>("activity-briefing-entry"))
            }
        }
    }

    @Test fun activityBriefingIsSilentUntilRequestedAndActionsDismissTheSheet() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitDashboard(scenario)
            scenario.onActivity { activity ->
                var read = false
                var reviewed = false
                fun sheet() = ActivityBriefingSheet(activity, "3 detections", TextView(activity).apply {
                    text = "Front camera: person at 07:08"
                }, listOf("3 detections"), { read = true }, { reviewed = true })
                val panel = sheet()
                panel.show()
                assertTrue(panel.isShowing)
                assertNull(ActivityBriefingSheet::class.java.getDeclaredField("engine").apply { isAccessible = true }.get(panel))
                val contents = views(panel.window!!.decorView)
                assertTrue(contents.filterIsInstance<TextView>().any { it.text == "Front camera: person at 07:08" })
                contents.filterIsInstance<TextView>().first { it.text == activity.getString(R.string.activity_mark_read) }.performClick()
                assertTrue(read); assertFalse(reviewed); assertFalse(panel.isShowing)
                val second = sheet()
                second.show()
                views(second.window!!.decorView).filterIsInstance<TextView>()
                    .first { it.text == activity.getString(R.string.activity_review_all) }.performClick()
                assertTrue(reviewed); assertFalse(second.isShowing)
                val canceled = sheet()
                canceled.show(); canceled.cancel()
                assertFalse(canceled.isShowing)
                assertTrue(ActivityBriefingSheet::class.java.getDeclaredField("closed").apply { isAccessible = true }.getBoolean(canceled))
            }
        }
    }

    @Test fun eventFilterPanelAppliesOnlyOnConfirmationAndResetIsLocal() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitDashboard(scenario)
            scenario.onActivity { activity ->
                val initial = EventSearch(camera = "front", kind = EventKind.Person, hours = 24)
                var applied: EventSearch? = null
                val sheet = EventFilterSheet(activity, initial, listOf("front" to "Devant")) { applied = it }
                sheet.show()
                sheet.window!!.decorView.findViewWithTag<View>("event-filter-type").performClick()
                val choices = EventFilterSheet::class.java.getDeclaredField("choice").apply { isAccessible = true }.get(sheet) as AppearanceChoiceSheet
                choices.window!!.decorView.findViewWithTag<View>("appearance-option-event-type-Vehicle").performClick()
                assertNull(applied)
                sheet.window!!.decorView.findViewWithTag<android.widget.EditText>("event-filter-name").setText(" Céline ")
                sheet.window!!.decorView.findViewWithTag<View>("event-filter-apply").performClick()
                assertFalse(sheet.isShowing)
                assertEquals(initial.copy(kind = EventKind.Vehicle, name = "Céline"), applied)
                applied = null
                val canceled = EventFilterSheet(activity, initial, listOf("front" to "Devant")) { applied = it }
                canceled.show()
                canceled.window!!.decorView.findViewWithTag<View>("event-filter-reset").performClick()
                assertNull(applied)
                canceled.cancel()
                assertNull(applied)
                val reset = EventFilterSheet(activity, initial, listOf("front" to "Devant")) { applied = it }
                reset.show()
                reset.window!!.decorView.findViewWithTag<View>("event-filter-reset").performClick()
                reset.window!!.decorView.findViewWithTag<View>("event-filter-apply").performClick()
                assertEquals(EventSearch(), applied)
            }
        }
    }

    @Test fun switchesFollowEveryAccentInLightAndDarkWithoutChangingTheirValues() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitDashboard(scenario)
            scenario.onActivity { activity ->
                val originalAccent = Palette.accent
                val originalDark = Palette.dark
                try {
                    for (dark in listOf(false, true)) for (accent in listOf("red", "blue", "green", "violet", "ochre")) {
                        Palette.dark = dark; Palette.accent = Appearance.accent(accent)
                        render(activity, "renderAppearanceSettings")
                        val switches = views(root(activity)).filterIsInstance<androidx.appcompat.widget.SwitchCompat>()
                        assertEquals(4, switches.size)
                        switches.forEach { toggle ->
                            val value = toggle.isChecked
                            val enabled = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked)
                            val unchecked = intArrayOf(android.R.attr.state_enabled, -android.R.attr.state_checked)
                            val disabled = intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked)
                            assertEquals(Palette.accent, toggle.thumbTintList!!.getColorForState(enabled, 0))
                            assertEquals(Palette.muted, toggle.thumbTintList!!.getColorForState(unchecked, 0))
                            assertEquals(androidx.core.graphics.ColorUtils.setAlphaComponent(Palette.accent, 85),
                                toggle.trackTintList!!.getColorForState(enabled, 0))
                            assertEquals(androidx.core.graphics.ColorUtils.setAlphaComponent(Palette.accent, 97),
                                toggle.thumbTintList!!.getColorForState(disabled, 0))
                            toggle.applyAccentTint()
                            assertEquals(value, toggle.isChecked)
                        }
                    }
                } finally { Palette.accent = originalAccent; Palette.dark = originalDark }
            }
        }
    }

    @Test fun legacyOledMigratesOnceAndNeverOverridesLightMode() {
        val testPrefs = context.getSharedPreferences("appearance-migration-fixture", 0)
        val style = Palette.backgroundStyle; val dark = Palette.dark
        val oled = Palette.oledBlack; val accent = Palette.accent
        try {
            testPrefs.edit().clear().putString("theme", "light").putString("backgroundStyle", "oled").putString("accent", "blue").commit()
            Appearance.loadPalette(testPrefs, true)
            assertEquals("automatic", testPrefs.getString("backgroundStyle", null))
            assertTrue(testPrefs.getBoolean("oledBlack", false))
            assertFalse(Palette.dark)
            assertEquals(BackgroundStyle.Automatic.lightBackground, Palette.background)
            assertEquals(Appearance.accent("blue"), Palette.accent)
            testPrefs.edit().putString("theme", "dark").commit()
            Appearance.loadPalette(testPrefs, false)
            assertEquals(0xff000000.toInt(), Palette.background)
            testPrefs.edit().putBoolean("oledBlack", false).commit()
            Appearance.loadPalette(testPrefs, false)
            assertFalse(Palette.oledBlack)
            assertEquals(BackgroundStyle.Automatic.background, Palette.background)
        } finally {
            testPrefs.edit().clear().commit()
            Palette.backgroundStyle = style; Palette.dark = dark; Palette.oledBlack = oled; Palette.accent = accent
        }
    }

    @Test fun chatPopupPreservesUnderlyingScreenAndRestoresStoredHistory() {
        val chatPrefs = context.getSharedPreferences("camgate-chat", 0)
        val base = server.url("/").toString().trimEnd('/')
        val key = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$base\nfixture".toByteArray()).joinToString("") { "%02x".format(it) }
        val previous = chatPrefs.getString(key, null)
        val history = """[{"role":"user","content":"Where is Bob?"},{"role":"assistant","content":"Fixture answer"}]"""
        chatPrefs.edit().putString(key, history).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitDashboard(scenario)
                scenario.onActivity { activity ->
                    val content = root(activity).getChildAt(0)
                    fun popup() = NativeChatDialog(activity, FrigateServer("test", "Test", base, "fixture", "fixture-token", true), okhttp3.OkHttpClient())
                    val dialog = popup()
                    dialog.show()
                    assertSame(content, root(activity).getChildAt(0))
                    assertEquals(android.view.Gravity.BOTTOM, dialog.window!!.attributes.gravity)
                    assertNotNull(dialog.window!!.decorView.findViewWithTag<View>("chat-popup"))
                    assertTrue(views(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text == "Fixture answer" })
                    dialog.window!!.decorView.findViewWithTag<View>("chat-popup-close").performClick()
                    assertFalse(dialog.isShowing)
                    assertSame(content, root(activity).getChildAt(0))
                    val reopened = popup()
                    reopened.show()
                    assertTrue(views(reopened.window!!.decorView).filterIsInstance<TextView>().any { it.text == "Fixture answer" })
                    reopened.cancel()
                    assertFalse(reopened.isShowing)
                    assertEquals(history, chatPrefs.getString(key, null))
                }
            }
        } finally {
            chatPrefs.edit().apply { if (previous == null) remove(key) else putString(key, previous) }.commit()
        }
    }

    @Test fun builtinDiscoveryAndCanceledSirenConfirmationNeverSendCommands() {
        val commands = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "POST") commands.incrementAndGet()
                return when (request.path?.substringBefore('?')) {
                    "/api/websocket" -> MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                        override fun onOpen(ws: okhttp3.WebSocket, response: okhttp3.Response) { ws.send("""{"type":"auth_required"}""") }
                        override fun onMessage(ws: okhttp3.WebSocket, text: String) {
                            val message = org.json.JSONObject(text)
                            if (message.optString("type") == "auth") ws.send("""{"type":"auth_ok"}""")
                            else ws.send(org.json.JSONObject().put("id", message.getInt("id")).put("type", "result").put("success", true)
                                .put("result", org.json.JSONArray("""[{"entity_id":"camera.fixture","device_id":"fixture","platform":"ring"},
                                    {"entity_id":"siren.fixture","device_id":"fixture","platform":"ring"},
                                    {"entity_id":"light.fixture","device_id":"fixture","platform":"ring"}]""")).toString())
                        }
                    })
                    "/api/states" -> MockResponse().setBody("""[{"entity_id":"light.fixture","state":"off","attributes":{}},
                        {"entity_id":"siren.fixture","state":"off","attributes":{"supported_features":3}}]""")
                    "/api/config" -> MockResponse().setBody("""{"cameras":{"front":{"enabled":true}}}""")
                    "/api/events" -> MockResponse().setBody("[]")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val base = server.url("/").toString().trimEnd('/')
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitDashboard(scenario)
                lateinit var sheet: CameraControlsSheet
                scenario.onActivity { activity ->
                    sheet = CameraControlsSheet(activity, CameraControlTarget("Fixture camera",
                        homeAssistant = HomeAssistantConnection(base, "fixture-token", emptyList()), haEntity = "camera.fixture"), { true })
                    sheet.show()
                }
                var loaded = false
                repeat(100) {
                    if (!loaded) {
                        scenario.onActivity { loaded = sheet.window!!.decorView.findViewWithTag<View>("builtin-siren")?.isEnabled == true }
                        if (!loaded) Thread.sleep(50)
                    }
                }
                assertTrue(loaded)
                scenario.onActivity {
                    assertEquals(0, commands.get())
                    assertNull(sheet.window!!.decorView.findViewWithTag<View>("accessory-native-link"))
                    (sheet.window!!.decorView.findViewWithTag<View>("builtin-siren") as androidx.appcompat.widget.SwitchCompat).isChecked = true
                    val controlView = sheet.window!!.decorView.findViewWithTag<View>("camera-builtins") as CameraControlsView
                    val dialog = CameraControlsView::class.java.getDeclaredField("confirmation").apply { isAccessible = true }.get(controlView) as androidx.appcompat.app.AlertDialog
                    assertTrue(dialog.isShowing)
                    assertEquals(0, commands.get())
                    dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
                    sheet.dismiss()
                }
                assertEquals(0, commands.get())
            }
    }

    @Test fun submenuNavigationAndRecreationKeepSettingsContext() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitDashboard(scenario)
            for (destination in listOf("connections", "cameras", "notifications", "appearance", "language", "security", "updates", "about")) {
                scenario.onActivity { activity ->
                    render(activity, "renderSettings")
                    root(activity).findViewWithTag<View>("settings-entry-$destination").performClick()
                    assertEquals(destination, page(activity))
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    activity.onBackPressedDispatcher.onBackPressed()
                    assertEquals("main", page(activity))
                }
            }
            scenario.onActivity { activity ->
                root(activity).findViewWithTag<View>("settings-entry-connections").performClick()
                root(activity).findViewWithTag<View>("settings-entry-frigate").performClick()
                assertEquals("server", page(activity))
            }
            instrumentation.waitForIdleSync()
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(R.string.frigate_servers))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
            androidx.test.espresso.Espresso.pressBack()
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals("connections", page(it)) }
            scenario.recreate()
            awaitDashboard(scenario)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals("connections", page(activity))
                activity.onBackPressedDispatcher.onBackPressed()
                root(activity).findViewWithTag<View>("settings-entry-about").performClick()
                root(activity).findViewWithTag<View>("settings-entry-licenses").performClick()
                assertEquals("licenses", page(activity))
                activity.onBackPressedDispatcher.onBackPressed()
                assertEquals("about", page(activity))
            }
        }
    }

    @Test fun appearancePreviewUpdatesWithoutScrollJumpAndResetKeepsConnections() {
        context.getSharedPreferences("camgate-settings", 0).edit().putString("timelineColor", "blue").commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitDashboard(scenario)
            scenario.onActivity { render(it, "renderAppearanceSettings") }
            instrumentation.waitForIdleSync()
            var previousScroll = 0
            scenario.onActivity { activity ->
                val preview = root(activity).findViewWithTag<View>("appearance-preview")
                assertNotNull(preview)
                val bitmap = android.graphics.Bitmap.createBitmap(preview.width, preview.height, android.graphics.Bitmap.Config.ARGB_8888)
                preview.draw(android.graphics.Canvas(bitmap))
                assertEquals("The timeline playhead must be visible below the camera",
                    Palette.accent, bitmap.getPixel((preview.width * 0.42f).toInt(), preview.height - (20 * activity.resources.displayMetrics.density).toInt()))
                bitmap.recycle()
                val scroll = root(activity).findViewWithTag<ScrollView>("appearance-scroll")
                scroll.scrollTo(0, 300)
                previousScroll = scroll.scrollY
                root(activity).findViewWithTag<View>("appearance-accent-green").performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val scroll = root(activity).findViewWithTag<ScrollView>("appearance-scroll")
                assertEquals(previousScroll, scroll.scrollY)
                assertEquals("green", context.getSharedPreferences("camgate-settings", 0).getString("accent", null))
                assertEquals(Appearance.accent("green"), Palette.accent)
                root(activity).findViewWithTag<View>("appearance-textSize").performClick()
            }
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(org.hamcrest.Matchers.`is`("appearance-option-textSize-small" as Any)))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(androidx.test.espresso.action.ViewActions.click())
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(previousScroll, root(activity).findViewWithTag<ScrollView>("appearance-scroll").scrollY)
                root(activity).findViewWithTag<View>("appearance-reset").performClick()
                val dialog = activity.window.decorView
                assertNotNull(dialog)
            }
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(android.R.string.ok))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(androidx.test.espresso.action.ViewActions.click())
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertFalse(context.getSharedPreferences("camgate-settings", 0).contains("accent"))
                assertEquals("fixture-token", context.getSharedPreferences("fricamgate-native", 0).getString("token", null))
                assertEquals("appearance", page(it))
            }
        }
    }

    @Test fun captureAppearanceInBothThemes() {
        for (theme in listOf("dark", "light")) {
            context.getSharedPreferences("camgate-settings", 0).edit().putString("theme", theme).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitDashboard(scenario)
                scenario.onActivity { render(it, "renderAppearanceSettings") }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity -> root(activity).findViewWithTag<View>("appearance-backgroundStyle").performClick() }
                instrumentation.waitForIdleSync()
                Thread.sleep(250)
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                File(context.getExternalFilesDir(null), "appearance-$theme.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
        }
    }

    @Test fun appearanceSheetIsBottomAnchoredAndCanBeCanceledWithoutChanges() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitDashboard(scenario)
            scenario.onActivity { activity ->
                render(activity, "renderAppearanceSettings")
                assertNotNull(root(activity).findViewWithTag<View>("appearance-accent-ochre"))
                assertNull(root(activity).findViewWithTag<View>("appearance-timelineColor"))
                root(activity).findViewWithTag<View>("appearance-backgroundStyle").performClick()
                val sheet = MainActivity::class.java.getDeclaredField("appearanceChoiceSheet").apply { isAccessible = true }.get(activity) as AppearanceChoiceSheet
                assertTrue(sheet.isShowing)
                assertEquals(android.view.Gravity.BOTTOM, sheet.window!!.attributes.gravity)
                assertNotNull(sheet.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("appearance-option-backgroundStyle-anthracite"))
            }
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(org.hamcrest.Matchers.`is`("selection-sheet" as Any)))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(androidx.test.espresso.action.ViewActions.pressKey(android.view.KeyEvent.KEYCODE_BACK))
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertFalse(context.getSharedPreferences("camgate-settings", 0).contains("backgroundStyle"))
                assertEquals("appearance", page(activity))
                root(activity).findViewWithTag<View>("appearance-accent-ochre").performClick()
                assertEquals(Appearance.accent("ochre"), Palette.accent)
            }
        }
    }

    @Test fun captureSettingsInBothThemes() {
        for (theme in listOf("dark", "light")) {
            context.getSharedPreferences("camgate-settings", 0).edit().putString("theme", theme).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitDashboard(scenario)
                scenario.onActivity { render(it, "renderSettings") }
                instrumentation.waitForIdleSync()
                Thread.sleep(300)
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                File(context.getExternalFilesDir(null), "settings-$theme.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
        }
    }

    private fun sheetSwipe(start: Float, end: Float) {
        fun coordinates(fraction: Float) = androidx.test.espresso.action.CoordinatesProvider { view ->
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            floatArrayOf(location[0] + view.width * 0.6f, location[1] + view.height * fraction)
        }
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(org.hamcrest.Matchers.`is`("selection-sheet" as Any)))
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.GeneralSwipeAction(androidx.test.espresso.action.Swipe.SLOW,
                coordinates(start), coordinates(end), androidx.test.espresso.action.Press.FINGER))
        instrumentation.waitForIdleSync()
        Thread.sleep(200)
    }

    @Test fun dragClosesFromContentAndHeaderButRespectsListScrolling() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitDashboard(scenario)
            lateinit var sheet: SelectionBottomSheet
            var clicks = 0
            fun open() {
                scenario.onActivity { activity ->
                    val rows = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
                    repeat(60) { index -> rows.addView(TextView(activity).apply {
                        text = "Camera $index"; minimumHeight = (60 * resources.displayMetrics.density).toInt()
                        setOnClickListener { clicks++ }
                    }) }
                    sheet = SelectionBottomSheet(activity, "Cameras", rows)
                    sheet.show()
                }
                instrumentation.waitForIdleSync()
                Thread.sleep(500)
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(org.hamcrest.Matchers.`is`("selection-sheet" as Any)))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
            }
            open()
            sheetSwipe(0.5f, 0.53f)
            scenario.onActivity { assertTrue("Small drags must snap back", sheet.isShowing) }
            sheetSwipe(0.5f, 0.9f)
            scenario.onActivity { assertFalse("Content drag must dismiss the panel", sheet.isShowing); assertEquals(0, clicks) }
            open()
            scenario.onActivity { activity ->
                val scroll = views(sheet.findViewById(android.R.id.content)).filterIsInstance<ScrollView>().single()
                scroll.scrollTo(0, (500 * activity.resources.displayMetrics.density).toInt())
                assertTrue("The list must start scrolled for this probe", scroll.scrollY > 0)
            }
            sheetSwipe(0.45f, 0.60f)
            scenario.onActivity { activity ->
                assertTrue("Scrolling a list must not dismiss its panel", sheet.isShowing)
                val scroll = views(sheet.findViewById(android.R.id.content)).filterIsInstance<ScrollView>().single()
                assertTrue("List offset after downward scrolling: ${scroll.scrollY}", scroll.scrollY > 0 && scroll.scrollY < (500 * activity.resources.displayMetrics.density).toInt())
                scroll.scrollTo(0, 0)
            }
            sheetSwipe(0.5f, 0.9f)
            scenario.onActivity { assertFalse(sheet.isShowing); assertEquals(0, clicks) }
            open()
            sheetSwipe(0.06f, 0.5f)
            scenario.onActivity { assertFalse("Header drag must dismiss the panel", sheet.isShowing) }
        }
    }

    @Test fun notificationsFilterAndReadStatePersistWithoutDeletingEvents() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitDashboard(scenario)
            lateinit var sheet: NotificationsSheet
            scenario.onActivity { activity ->
                val now = System.currentTimeMillis() / 1000.0
                val events = listOf("person", "car", "cat").mapIndexed { index, type ->
                    NativeEvent("notification-$index", "front", type, type, null, "12:00", now - 120 * (index + 1), now - 100, null)
                }
                MainActivity::class.java.getDeclaredField("events").apply { isAccessible = true }.set(activity, events)
                MainActivity::class.java.getDeclaredMethod("showAlertsPopup", View::class.java).apply { isAccessible = true }.invoke(activity, root(activity))
                sheet = MainActivity::class.java.getDeclaredField("alertsPopup").apply { isAccessible = true }.get(activity) as NotificationsSheet
                fun content() = sheet.findViewById<ViewGroup>(android.R.id.content)
                fun eventRows() = views(content()).filter { it.tag?.toString()?.startsWith("notification-row:") == true }
                assertEquals(3, eventRows().size)
                content().findViewWithTag<View>("notifications-filter-Vehicle").performClick()
                assertEquals(listOf("notification-row:notification-1"), eventRows().map { it.tag })
                content().findViewWithTag<View>("notifications-read-all").performClick()
                assertTrue(sheet.isShowing)
                assertFalse(views(content()).any { it.tag == "notification-unread" })
                content().findViewWithTag<View>("notifications-filter-Any").performClick()
                assertEquals(3, eventRows().size)
                val seen = MainActivity::class.java.getDeclaredMethod("seenAlertIds").apply { isAccessible = true }.invoke(activity) as Set<*>
                assertTrue(seen.containsAll(events.map { it.id }))
                assertEquals("fixture-token", context.getSharedPreferences("fricamgate-native", 0).getString("token", null))
                content().findViewWithTag<View>("notifications-history").performClick()
                assertFalse(sheet.isShowing)
                assertEquals("Grid", MainActivity::class.java.getDeclaredField("activeTab").apply { isAccessible = true }.get(activity).toString())
            }
        }
    }

    @Test fun groupedMenuHasEightEntriesInApprovedOrderAndWideBlocks() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitDashboard(scenario)
            scenario.onActivity { render(it, "renderSettings") }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val all = views(root(activity))
                assertEquals(listOf("settings-section-installation", "settings-section-preferences", "settings-section-application"),
                    all.mapNotNull { it.tag as? String }.filter { it.startsWith("settings-section-") })
                assertEquals(listOf("connections", "cameras", "notifications", "appearance", "language", "security", "updates", "about").map { "settings-entry-$it" },
                    all.mapNotNull { it.tag as? String }.filter { it.startsWith("settings-entry-") })
                val entry = root(activity).findViewWithTag<View>("settings-entry-cameras")
                assertNotNull(entry)
                val scroll = all.filterIsInstance<ScrollView>().first()
                if (activity.resources.configuration.screenWidthDp < 700)
                    assertTrue("Menu has excessive side gutters", entry.width >= scroll.width - (26 * activity.resources.displayMetrics.density).toInt())
            }
        }
    }
}
