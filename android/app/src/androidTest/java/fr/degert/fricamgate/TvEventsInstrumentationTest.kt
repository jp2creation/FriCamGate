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
    @Test fun cinemaFilmstripAndInboxHaveContextualPlayback() {
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
                field("cameras").set(activity, listOf(camera, camera.copy(name = "fixture-other", friendlyName = "Entrée")))
                val fixtures = (0 until 12).map { index ->
                    val asset = listOf("driveway", "entrance", "terrace", "workshop")[index % 4]
                    val image = instrumentation.context.assets.open("ha-previews/$asset.jpg").use { BitmapFactory.decodeStream(it) }
                    NativeEvent("fixture-$index", "fixture", "Personne", "person", null, "14:${20 - index}",
                        System.currentTimeMillis() / 1000.0 - 1200 - index * 60, System.currentTimeMillis() / 1000.0 - 1190 - index * 60, image)
                }
                field("events").set(activity, fixtures)
                field("globalResults").set(activity, fixtures)
                val session = field("session")
                session.set(activity, session.type.getDeclaredConstructor(String::class.java, String::class.java, String::class.java)
                    .apply { isAccessible = true }.newInstance("https://fixture.invalid", "fixture", "fixture"))
                field("api").set(activity, object : FrigateNativeApi("https://fixture.invalid", okhttp3.OkHttpClient()) {
                    override fun loadBitmap(token: String, url: String, callback: (android.graphics.Bitmap?) -> Unit) {
                        callback((fixtures.firstOrNull { it.id in android.net.Uri.parse(url).pathSegments } ?: fixtures.first()).thumbnail)
                    }
                    override fun eventVodUrl(eventId: String) = "file:///nonexistent-fixture.mp4"
                    override fun eventClipUrl(eventId: String) = "file:///nonexistent-fixture.mp4"
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
            repeat(5) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                assertTrue(root.findViewWithTag<View>("tv-event:fixture-5").hasFocus())
                assertNull(root.findViewWithTag<View>("tv-event:fixture-0"))
                val grid = root.findViewWithTag<TvHistoryView>("tv-history-pages")
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
                val frame = activity.window.decorView.findViewWithTag<View>("tv-history-preview-frame")
                assertNotNull(frame)
                val playback = frame.parent as android.widget.LinearLayout
                val body = playback.parent as android.widget.LinearLayout
                assertEquals(android.widget.LinearLayout.HORIZONTAL, body.orientation)
                assertTrue("Video must dominate the TV event layout", playback.width > body.getChildAt(1).width)
                assertTrue(frame.height > 100)
                assertNotNull("Playback remains within EN1 browser", activity.window.decorView.findViewWithTag<View>("tv-events-browser"))
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
            scenario.onActivity {
                assertNotNull(it.window.decorView.findViewWithTag<View>("tv-events-browser"))
                assertTrue("Back restores the exact event and page", it.window.decorView.findViewWithTag<View>("tv-event:fixture-5").hasFocus())
                assertEquals(1, it.window.decorView.findViewWithTag<TvHistoryView>("tv-history-pages").currentPage)
            }
            scenario.onActivity { activity ->
                val actions = activity.window.decorView.findViewWithTag<ViewGroup>("tv-history-preview-actions")
                assertTrue(actions.getChildAt(1).performClick())
            }
            instrumentation.waitForIdleSync()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertNotNull("Fullscreen Back restores EN1", activity.window.decorView.findViewWithTag<View>("tv-events-browser"))
                assertTrue(activity.window.decorView.findViewWithTag<View>("tv-event:fixture-5").hasFocus())
            }
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("renderGlobalEvents").apply { isAccessible = true }.invoke(activity)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                assertNotNull(root.findViewWithTag<View>("tv-notifications-browser"))
                assertNotNull(root.findViewWithTag<View>("tv-notification:fixture-4"))
                assertNull("Only five rows per TV inbox page", root.findViewWithTag<View>("tv-notification:fixture-5"))
                assertTrue(root.findViewWithTag<View>("tv-notification:fixture-0").requestFocus())
                val split = root.findViewWithTag<android.widget.LinearLayout>("tv-inbox-split")
                assertEquals(2, split.childCount)
                assertTrue("Inbox and its preview fit side by side", split.getChildAt(1).left > split.getChildAt(0).left)
            }
            repeat(5) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                assertTrue(root.findViewWithTag<View>("tv-notification:fixture-5").hasFocus())
            }
            val inboxDrawn = java.util.concurrent.CountDownLatch(1)
            scenario.onActivity { it.window.decorView.postOnAnimation { inboxDrawn.countDown() } }
            assertTrue(inboxDrawn.await(3, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                java.io.FileOutputStream(java.io.File(context.cacheDir, "tv-notifications.png")).use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.waitForIdleSync()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                assertNotNull("Inbox playback returns to inbox", root.findViewWithTag<View>("tv-notifications-browser"))
                assertTrue(root.findViewWithTag<View>("tv-notification:fixture-5").hasFocus())
                assertEquals(android.graphics.Color.TRANSPARENT,
                    (root.findViewWithTag<View>("tv-unread:fixture-5").background as android.graphics.drawable.ColorDrawable).color)
                root.findViewWithTag<View>("tv-read-all").performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { it.window.decorView.findViewWithTag<View>("tv-unread-filter").performClick() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertNull("Read-all then unread filter has no event cells", activity.window.decorView.findViewWithTag<View>("tv-notification:fixture-0"))
            }
        }
        context.getSharedPreferences("fricamgate-native", 0).edit().remove("seenAlerts:https://fixture.invalid").commit()
    }
}
