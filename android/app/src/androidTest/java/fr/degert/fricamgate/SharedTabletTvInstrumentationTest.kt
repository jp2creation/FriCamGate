package com.nvr.camnory

import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class SharedTabletTvInstrumentationTest {
    @Test fun headerButtonsAreOptionalAndHiddenByDefaultOnTv() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(TelevisionMode.enabled(context))
        val prefs = context.getSharedPreferences("camgate-settings", 0)
        val keys = listOf("showHeaderChat", "showHeaderAlerts")
        val previous = keys.associateWith { if (prefs.contains(it)) prefs.getBoolean(it, false) else null }
        try {
            prefs.edit().remove(keys[0]).remove(keys[1]).commit()
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
                action = "com.nvr.camnory.TV_SETTINGS"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }).use { scenario ->
                scenario.onActivity { activity ->
                    assertNull(activity.window.decorView.findViewWithTag<View>("header-chat"))
                    assertNull(activity.window.decorView.findViewWithTag<View>("header-alerts"))
                    val render = MainActivity::class.java.getDeclaredMethod("renderAppearanceSettings").apply { isAccessible = true }
                    render.invoke(activity)
                    for ((key, header) in listOf(keys[0] to "header-chat", keys[1] to "header-alerts")) {
                        val toggle = activity.window.decorView.findViewWithTag<androidx.appcompat.widget.SwitchCompat>("appearance-$key")
                        assertFalse(toggle.isChecked)
                        toggle.performClick()
                        assertTrue(prefs.getBoolean(key, false))
                        assertNotNull(activity.window.decorView.findViewWithTag<View>(header))
                    }
                    render.invoke(activity)
                    assertTrue(activity.window.decorView.findViewWithTag<androidx.appcompat.widget.SwitchCompat>("appearance-${keys[0]}").isChecked)
                }
            }
        } finally {
            prefs.edit().apply { previous.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value) } }.commit()
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun recentEventsStripCanBeHiddenAndChoicePersists() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(TelevisionMode.enabled(context))
        val preferences = context.getSharedPreferences("camgate-settings", 0)
        val existed = preferences.contains("showHomeEvents")
        val before = preferences.getBoolean("showHomeEvents", false)
        val toolbarExisted = preferences.contains("showHomeCameraToolbar")
        val toolbarBefore = preferences.getBoolean("showHomeCameraToolbar", false)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
                action = "com.nvr.camnory.TV_SETTINGS"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }).use { scenario ->
                scenario.onActivity { activity ->
                    val render = MainActivity::class.java.getDeclaredMethod("renderAppearanceSettings").apply { isAccessible = true }
                    render.invoke(activity)
                    val toggle = activity.window.decorView.findViewWithTag<androidx.appcompat.widget.SwitchCompat>("appearance-home-events")
                    val toolbar = activity.window.decorView.findViewWithTag<androidx.appcompat.widget.SwitchCompat>("appearance-home-camera-toolbar")
                    assertEquals(preferences.getBoolean("showHomeCameraToolbar", false), toolbar.isChecked)
                    toolbar.performClick()
                    assertEquals(!toolbarBefore, preferences.getBoolean("showHomeCameraToolbar", false))
                    assertNotNull(toggle)
                    assertEquals(before, toggle.isChecked)
                    toggle.performClick()
                    assertEquals(!before, preferences.getBoolean("showHomeEvents", true))
                    render.invoke(activity)
                    assertEquals(!before, activity.window.decorView.findViewWithTag<androidx.appcompat.widget.SwitchCompat>("appearance-home-events").isChecked)
                }
            }
        } finally {
            preferences.edit().apply {
                if (existed) putBoolean("showHomeEvents", before) else remove("showHomeEvents")
                if (toolbarExisted) putBoolean("showHomeCameraToolbar", toolbarBefore) else remove("showHomeCameraToolbar")
            }.commit()
        }
    }

    @Test fun settingsUsesSharedLandscapeShellAndRemoteFocusWithoutChangingServers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(TelevisionMode.enabled(context))
        val config = File(context.noBackupFilesDir, "tv-config.enc")
        val before = if (config.exists()) config.readBytes() else null
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
            action = "com.nvr.camnory.TV_SETTINGS"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val views = descendants(activity.window.decorView)
                assertTrue(views.any { it.tag == "tv-horizontal-header" })
                assertTrue(views.any { it.tag == "tv-top-navigation" })
                assertFalse(views.any { it.tag == "tablet-navigation-rail" || it.tag == "bottom-navigation" })
                assertFalse(views.any { it.tag == "navigation-Cameras" })
                val navigation = listOf("Home", "Events", "Grid", "Settings").map { tab -> views.first { it.tag == "navigation-$tab" } }
                assertTrue(navigation.zipWithNext().all { (left, right) -> left.left < right.left && left.top == right.top })
                val settings = views.first { it.tag == "navigation-Settings" }
                assertTrue(settings.isFocusable)
                assertTrue(settings.requestFocus())
                assertTrue(settings.hasFocus())
                assertTrue(views.count { it.isClickable && it.isFocusable } >= 8)
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertNotNull(activity.currentFocus)
                assertNotEquals("navigation-Settings", activity.currentFocus?.tag)
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                FileOutputStream(File(context.cacheDir, "shared-tablet-tv.png")).use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
        }
        assertArrayEquals("TV servers must remain untouched", before, if (config.exists()) config.readBytes() else null)
    }

    @Test fun providerUpdatesPreserveOtherFieldsInIsolatedTvStorage() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(TelevisionMode.enabled(base))
        val directory = File(base.cacheDir, "tv-storage-test-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir() = directory
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("isolated-tv-test-$name", mode)
        }
        try {
            val server = FrigateServer("test", "Test", "https://example.invalid", "admin", "fixture-token")
            val initial = TvConfiguration(listOf(server), emptyList(), null, listOf("keep-order"), setOf("keep-hidden"),
                listOf(IpCamera("ip-fixture", "Fixture", "http://127.0.0.1/video", "viewer", "fixture-secret")))
            TvConfigurationStore(context).save(initial)
            assertEquals(listOf(server), FrigateServerStore(context).load())
            FrigateServerStore(context).save(listOf(server.copy(name = "Changed")))
            val updated = requireNotNull(TvConfigurationStore(context).load())
            assertEquals("Changed", updated.frigate.single().name)
            assertEquals(initial.order, updated.order)
            assertEquals(initial.hidden, updated.hidden)
            assertEquals("fixture-secret", updated.ip.single().password)
            OnvifCameraStore(context).save(emptyList())
            HomeAssistantStore(context).save(null)
            CameraArrangementStore(context).saveOrder(listOf("different-order"))
            CameraArrangementStore(context).setHidden("another", true)
            assertEquals("fixture-secret", TvConfigurationStore(context).load()!!.ip.single().password)
            assertFalse(File(directory, "frigate-servers.enc").exists())
            assertFalse(context.getSharedPreferences("fricamgate-native", 0).contains("token"))
        } finally { directory.deleteRecursively() }
    }
}
