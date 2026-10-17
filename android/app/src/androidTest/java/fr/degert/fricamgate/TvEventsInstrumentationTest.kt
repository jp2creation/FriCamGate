package com.nvr.camnory

import android.content.Intent
import android.graphics.BitmapFactory
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The TV event browser is exercised with photographs, not real event playback. */
class TvEventsInstrumentationTest {
    @Test fun remoteBrowsesSixEventsPerPage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(TelevisionMode.enabled(context))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
            action = "com.nvr.camnory.TV_SETTINGS"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }).use { scenario ->
            scenario.onActivity { activity ->
                fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                val camera = NativeCamera("fixture", "Allée", true, "fixture", null, null, null, null, null, null, null)
                field("cameras").set(activity, listOf(camera))
                val fixtures = (0 until 9).map { index ->
                    val asset = listOf("driveway", "entrance", "terrace", "workshop")[index % 4]
                    val image = instrumentation.context.assets.open("ha-previews/$asset.jpg").use { BitmapFactory.decodeStream(it) }
                    NativeEvent("fixture-$index", "fixture", "Personne", "person", null, "14:${20 - index}",
                        1000.0 - index, 1010.0 - index, image)
                }
                field("events").set(activity, fixtures)
                field("globalResults").set(activity, fixtures)
                val session = field("session")
                session.set(activity, session.type.getDeclaredConstructor(String::class.java, String::class.java, String::class.java)
                    .apply { isAccessible = true }.newInstance("https://fixture.invalid", "fixture", "fixture"))
                field("api").set(activity, object : FrigateNativeApi("https://fixture.invalid", okhttp3.OkHttpClient()) {
                    override fun loadBitmap(token: String, url: String, callback: (android.graphics.Bitmap?) -> Unit) {
                        callback(fixtures.first().thumbnail)
                    }
                    override fun eventVodUrl(eventId: String) = "file:///nonexistent-fixture.mp4"
                })
                MainActivity::class.java.getDeclaredMethod("renderTvEvents").apply { isAccessible = true }.invoke(activity)
            }
            instrumentation.waitForIdleSync()
            var ready = false
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { ready = it.hasWindowFocus() }
                if (!ready) Thread.sleep(50)
            }
            assertTrue(ready)
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                assertNotNull(root.findViewWithTag<View>("tv-events-browser"))
                assertTrue(root.findViewWithTag<View>("tv-event:fixture-0").requestFocus())
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                assertTrue(root.findViewWithTag<View>("tv-event:fixture-6").hasFocus())
                assertFalse(root.findViewWithTag<View>("tv-event:fixture-0").isShown)
                val grid = root.findViewWithTag<TvCameraGrid>("tv-camera-grid")
                assertEquals(1, grid.currentPage)
            }
            val drawn = java.util.concurrent.CountDownLatch(1)
            scenario.onActivity { activity -> activity.window.decorView.postOnAnimation {
                activity.window.decorView.postOnAnimation { drawn.countDown() }
            } }
            assertTrue(drawn.await(3, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                java.io.FileOutputStream(java.io.File(context.cacheDir, "tv-events.png")).use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val frame = activity.window.decorView.findViewWithTag<View>("event-player-frame")
                assertNotNull(frame)
                val playback = frame.parent as android.widget.LinearLayout
                val body = playback.parent as android.widget.LinearLayout
                assertEquals(android.widget.LinearLayout.HORIZONTAL, body.orientation)
                assertTrue("Video must dominate the TV event layout", playback.width > body.getChildAt(1).width)
                assertTrue(frame.height > 100)
            }
            val detailDrawn = java.util.concurrent.CountDownLatch(1)
            scenario.onActivity { it.window.decorView.postOnAnimation { detailDrawn.countDown() } }
            assertTrue(detailDrawn.await(3, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                java.io.FileOutputStream(java.io.File(context.cacheDir, "tv-event-detail.png")).use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertNotNull(it.window.decorView.findViewWithTag<View>("tv-events-browser")) }
        }
    }
}
