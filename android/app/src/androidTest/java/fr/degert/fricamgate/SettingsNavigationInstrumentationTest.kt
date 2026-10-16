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
                activity.onBackPressedDispatcher.onBackPressed()
                assertEquals("connections", page(activity))
            }
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
            androidx.test.espresso.Espresso.pressBack()
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
