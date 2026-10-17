package com.nvr.camnory

import android.content.Intent
import android.graphics.BitmapFactory
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class CameraConnectionOverlayInstrumentationTest {
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun neutralPreviewAndFallbackRenderWithoutActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
            action = "com.nvr.camnory.TV_SETTINGS"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }).use { scenario ->
            scenario.onActivity { activity ->
                val photo = instrumentation.context.assets.open("ha-previews/terrace.jpg").use { BitmapFactory.decodeStream(it) }
                val body = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(24, 20, 24, 0)
                }
                for (index in 0..1) {
                    val overlay = CameraConnectionOverlay(activity).apply { showConnection(if (index == 0) photo else null) }
                    val placeholder = overlay.findViewWithTag<View>("connection-camera-placeholder")
                    assertEquals(if (index == 0) View.GONE else View.VISIBLE, placeholder.visibility)
                    assertFalse(descendants(overlay).any { it.isClickable })
                    val texts = descendants(overlay).filterIsInstance<TextView>().map { it.text.toString() }
                    assertTrue(texts.contains(activity.getString(R.string.connecting)))
                    assertFalse(texts.any { it.contains("Frigate", true) || it.contains("Assistant", true) || it.contains("ONVIF", true) })
                    body.addView(FrameLayout(activity).apply {
                        addView(overlay, FrameLayout.LayoutParams(-1, -1))
                        addView(CameraColumnOverlay(activity, if (index == 0) "Caméra salon" else "Caméra Porte d’entrée", CameraSource.HomeAssistant, emptyList()), FrameLayout.LayoutParams(-1, -1))
                    }, LinearLayout.LayoutParams(0, (220 * context.resources.displayMetrics.density).toInt(), 1f).apply { marginEnd = 16 })
                }
                val shell = MainActivity::class.java.declaredMethods.first { it.name == "setShell" && it.parameterCount == 4 }.apply { isAccessible = true }
                val home = shell.parameterTypes[1].enumConstants!!.first { it.toString() == "Home" }
                shell.invoke(activity, body, home, true, false)
            }
            val drawn = java.util.concurrent.CountDownLatch(1)
            scenario.onActivity { activity -> activity.window.decorView.postOnAnimation {
                activity.window.decorView.postOnAnimation { drawn.countDown() }
            } }
            assertTrue(drawn.await(5, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.FileOutputStream(java.io.File(context.cacheDir, "camera-connection-states.png")).use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
    }

    @Test fun homeAssistantUsesSharedConnectionStateWithoutOpeningNetwork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val camera = HomeAssistantCamera("camera.fixture", "Fixture", true)
            val view = HomeAssistantCameraView(context, HomeAssistantConnection("https://fixture.invalid", "fixture", listOf(camera)),
                camera, fullscreen = {}, onAudible = {}, fullscreenMode = true, playerOnly = true)
            val state = HomeAssistantCameraView::class.java.getDeclaredMethod("setState", Int::class.javaPrimitiveType).apply { isAccessible = true }
            state.invoke(view, R.string.connecting)
            assertEquals(View.VISIBLE, view.findViewWithTag<View>("camera-connection-overlay").visibility)
            assertEquals(View.GONE, view.findViewWithTag<View>("ha-camera-state").visibility)
            state.invoke(view, R.string.ha_live)
            assertEquals(View.GONE, view.findViewWithTag<View>("camera-connection-overlay").visibility)
        }
    }
}
