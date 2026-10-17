package com.nvr.camnory

import android.content.Intent
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class TvSettingsLayoutInstrumentationTest {
    @Test fun administrationSectionsRemainReachableWithoutChangingPreferences() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(TelevisionMode.enabled(context))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
            action = "com.nvr.camnory.TV_SETTINGS"
        }).use { scenario ->
            instrumentation.waitForIdleSync()
            for (key in listOf("connections", "cameras", "sound", "transfer", "notifications", "language", "security", "about", "appearance")) {
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("tv-settings-$key").performClick() }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val root = activity.window.decorView
                    assertNotNull(root.findViewWithTag<View>("tv-settings-layout"))
                    assertEquals("Section $key", "tv-settings-$key", root.findFocus()?.tag)
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                instrumentation.waitForIdleSync()
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
                instrumentation.waitForIdleSync()
                scenario.onActivity { assertEquals("Return from $key", "tv-settings-$key", it.window.decorView.findFocus()?.tag) }
            }
        }
    }

    @Test fun settingsUseTwoPanesAndRemoteCanEnterAndLeaveDetails() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(TelevisionMode.enabled(context))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
            action = "com.nvr.camnory.TV_SETTINGS"
        }).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                assertNotNull(root.findViewWithTag<View>("tv-settings-layout"))
                assertNull(root.findViewWithTag<View>("appearance-preview"))
                assertEquals("tv-settings-appearance", root.findFocus()?.tag)
                val sidebar = root.findViewWithTag<View>("tv-settings-sidebar")
                val detail = root.findViewWithTag<View>("tv-settings-detail")
                assertTrue(sidebar.right <= detail.left)
                assertTrue(detail.right <= root.width)
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals("appearance-theme", it.window.decorView.findFocus()?.tag) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals("tv-settings-appearance", activity.window.decorView.findFocus()?.tag)
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertNotNull(activity.window.decorView.findViewWithTag<View>("tv-settings-layout"))
                assertNotNull(activity.window.decorView.findViewWithTag<View>("camera-categories"))
                assertEquals("tv-settings-cameras", activity.window.decorView.findFocus()?.tag)
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals("camera-categories", it.window.decorView.findFocus()?.tag) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val sheet = MainActivity::class.java.getDeclaredField("cameraCatalogSheet").apply { isAccessible = true }.get(activity) as CameraCatalogSheet
                assertTrue(sheet.isShowing)
                assertNotNull(sheet.window!!.decorView.findViewWithTag<View>("category-add"))
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            // Return without modifying any real preference or category.
            scenario.onActivity { it.window.decorView.findViewWithTag<View>("tv-settings-appearance").performClick() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { it.window.decorView.findViewWithTag<View>("appearance-accent-red").requestFocus() }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals("appearance-accent-blue", it.window.decorView.findFocus()?.tag) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals("appearance-accent-red", activity.window.decorView.findFocus()?.tag)
                activity.window.decorView.findViewWithTag<View>("tv-settings-appearance").requestFocus()
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                context.openFileOutput("tv-settings-preview.png", 0).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
