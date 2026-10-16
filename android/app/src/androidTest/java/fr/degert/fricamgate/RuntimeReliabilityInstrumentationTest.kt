package com.nvr.camnory

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import androidx.lifecycle.Lifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RuntimeReliabilityInstrumentationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var native: SharedPreferences
    private lateinit var settings: SharedPreferences
    private lateinit var originalNative: Map<String, *>
    private lateinit var originalSettings: Map<String, *>

    @Before fun setup() {
        native = context.getSharedPreferences("fricamgate-native", Context.MODE_PRIVATE)
        settings = context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE)
        originalNative = native.all
        originalSettings = settings.all
        server = MockWebServer()
        server.dispatcher = dispatcher(false)
        server.start()
        native.edit().clear().putString("baseUrl", server.url("/").toString().trimEnd('/'))
            .putString("username", "test").putString("token", "test-token").commit()
        settings.edit().clear().commit()
    }

    @After fun cleanup() {
        restore(native, originalNative)
        restore(settings, originalSettings)
        CameraNotifications.schedule(context)
        server.shutdown()
    }

    @Test fun playingVideoResumesButManualPauseIsPreserved() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText(R.string.no_cameras)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val player = ExoPlayer.Builder(activity).build().apply { playWhenReady = true }
                field("currentPlayer").set(activity, player)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                val player = field("currentPlayer").get(activity) as ExoPlayer
                assertTrue(player.playWhenReady)
                player.playWhenReady = false
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity -> assertFalse((field("currentPlayer").get(activity) as ExoPlayer).playWhenReady) }
        }
    }

    @Test fun realRecordingRendersAdvancesAndHonorsPause() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("recordingServer")
        org.junit.Assume.assumeTrue("Opt-in real-server check", url != null)
        val cameraName = args.getString("recordingCamera")!!
        val focus = args.getString("recordingFocus")!!.toDouble()
        val firstFrame = CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText(R.string.no_cameras)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val frame = android.widget.FrameLayout(activity)
                val fallback = android.widget.ImageView(activity)
                val view = androidx.media3.ui.PlayerView(activity)
                frame.addView(fallback)
                frame.addView(view)
                activity.setContentView(frame)
                val camera = NativeCamera(cameraName, cameraName, true, cameraName, null, null, true, null, null, null, null)
                val clock: (Double) -> Unit = {}
                MainActivity::class.java.getDeclaredMethod("startTimelineRecordingPlayer", NvrPlaybackMedia::class.java,
                    String::class.java, NativeCamera::class.java, androidx.media3.ui.PlayerView::class.java,
                    android.widget.ImageView::class.java, java.lang.Double.TYPE, kotlin.jvm.functions.Function1::class.java)
                    .apply { isAccessible = true }.invoke(activity,
                        FrigateNativeApi(url!!, okhttp3.OkHttpClient()), "smoke-test", camera, view, fallback, focus, clock)
                (field("currentPlayer").get(activity) as ExoPlayer).addListener(object : androidx.media3.common.Player.Listener {
                    override fun onRenderedFirstFrame() { firstFrame.countDown() }
                })
            }
            assertTrue("No real video frame rendered", firstFrame.await(25, TimeUnit.SECONDS))
            var start = 0L
            scenario.onActivity { start = (field("currentPlayer").get(it) as ExoPlayer).currentPosition }
            Thread.sleep(1500)
            var paused = 0L
            scenario.onActivity {
                val player = field("currentPlayer").get(it) as ExoPlayer
                assertTrue("Recording clock did not advance", player.currentPosition > start + 500)
                player.pause()
                paused = player.currentPosition
            }
            Thread.sleep(700)
            scenario.onActivity {
                val player = field("currentPlayer").get(it) as ExoPlayer
                assertFalse(player.playWhenReady)
                assertTrue("Pause did not hold position", kotlin.math.abs(player.currentPosition - paused) < 100)
            }
        }
    }

    @Test fun notificationTimerSurvivesSettingsNavigation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText(R.string.no_cameras)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("renderSettings").apply { isAccessible = true }.invoke(activity)
                val handler = field("notificationHandler").get(activity) as Handler
                assertTrue(handler.hasCallbacks(field("notificationPoll").get(activity) as Runnable))
            }
        }
    }

    @Test fun returnLiveAnimationDoesNotStartIntermediateRecordings() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText(R.string.no_cameras)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val camera = NativeCamera("front", "Front", true, "front", null, null, true, null, null, null, null)
                MainActivity::class.java.getDeclaredMethod("renderCameraDetail", NativeCamera::class.java,
                    java.lang.Long.TYPE, List::class.java, java.lang.Double::class.java, java.lang.Boolean.TYPE)
                    .apply { isAccessible = true }.invoke(activity, camera, System.currentTimeMillis(), emptyList<NativeEvent>(),
                        System.currentTimeMillis() / 1000.0 - 600, false)
            }
            Thread.sleep(700)
            var generation = 0
            scenario.onActivity { activity ->
                fun find(view: android.view.View): android.view.View? {
                    if (view.contentDescription == activity.getString(R.string.live) && view.visibility == android.view.View.VISIBLE) return view
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                        find(view.getChildAt(index))?.let { return it }
                    }
                    return null
                }
                generation = field("timelinePlaybackGeneration").getInt(activity)
                assertTrue(find(activity.findViewById(android.R.id.content))!!.performClick())
            }
            Thread.sleep(800)
            scenario.onActivity { activity ->
                assertEquals("Animation started intermediate recordings", generation + 1,
                    field("timelinePlaybackGeneration").getInt(activity))
                assertNull(field("currentPlayer").get(activity))
            }
        }
    }

    @Test fun notificationVideoExposesPlaybackAndSeekControls() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText(R.string.no_cameras)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val event = NativeEvent("test", "front", "person", "person", null, "12:00", 1000.0, 1020.0, null)
                MainActivity::class.java.getDeclaredMethod("renderEventDetail", NativeEvent::class.java, String::class.java)
                    .apply { isAccessible = true }.invoke(activity, event, "front")
                fun find(view: android.view.View): androidx.media3.ui.PlayerView? {
                    if (view is androidx.media3.ui.PlayerView) return view
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                        find(view.getChildAt(index))?.let { return it }
                    }
                    return null
                }
                val player = find(activity.findViewById(android.R.id.content))!!
                assertFalse(player.useController)
                val controls = activity.findViewById<androidx.media3.ui.PlayerControlView>(R.id.event_playback_controls)
                assertNotNull(controls)
                assertSame(player.player, controls.player)
                assertNotNull(controls.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_progress))
                val playPause = controls.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause)
                assertTrue(controls.player!!.playWhenReady)
                playPause.performClick()
                assertFalse(controls.player!!.playWhenReady)
                playPause.performClick()
                assertTrue(controls.player!!.playWhenReady)
            }
            Thread.sleep(300)
            scenario.onActivity { activity ->
                val controls = activity.findViewById<androidx.media3.ui.PlayerControlView>(R.id.event_playback_controls)
                assertTrue(controls.height <= (56 * activity.resources.displayMetrics.density).toInt())
                assertTrue(controls.width > 0)
            }
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                context.openFileOutput("compact-event-player.png", Context.MODE_PRIVATE).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
    }

    @Test fun sameMomentGridUsesTwoColumnsAndPreservesMetadata() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText(R.string.no_cameras)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                field("cameras").set(activity, listOf("Allée", "Devant", "Entrée", "Entrée suivi").map {
                    NativeCamera(it, it, true, it, null, null, null, null, null, null, null)
                })
                val event = NativeEvent("grid-test", "Allée", "person", "person", null, "09:42", 1000.0, 1020.0, null)
                val grid = MainActivity::class.java.getDeclaredMethod("syncCameraGrid", NativeEvent::class.java, String::class.java)
                    .apply { isAccessible = true }.invoke(activity, event, "Allée") as android.widget.LinearLayout
                val width = (320 * activity.resources.displayMetrics.density).toInt()
                grid.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
                grid.layout(0, 0, width, grid.measuredHeight)
                assertEquals(2, grid.childCount)
                val firstRow = grid.getChildAt(0) as android.widget.LinearLayout
                assertEquals(2, firstRow.childCount)
                assertTrue(firstRow.getChildAt(0).width <= width / 2)
                assertTrue(firstRow.getChildAt(1).left > firstRow.getChildAt(0).left)
                assertEquals("Devant, 09:42", firstRow.getChildAt(0).contentDescription)
                val preview = (firstRow.getChildAt(0) as android.view.ViewGroup).getChildAt(0)
                assertEquals(preview.width * 9 / 16, preview.height)
                val bitmap = Bitmap.createBitmap(width, grid.height, Bitmap.Config.ARGB_8888)
                grid.draw(android.graphics.Canvas(bitmap))
                context.openFileOutput("same-moment-grid.png", Context.MODE_PRIVATE).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
    }

    @Test fun sameMomentFullscreenKeepsClockPauseAndRestoresPlayer() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText(R.string.no_cameras)).check(matches(isDisplayed()))
            var fullscreenPlayer: ExoPlayer? = null
            scenario.onActivity { activity ->
                val camera = NativeCamera("front", "Devant", true, "front", null, null, null, null, null, null, null)
                field("cameras").set(activity, listOf(camera))
                val event = NativeEvent("fullscreen-test", "front", "person", "person", null, "09:42", 1000.0, 1020.0, null)
                MainActivity::class.java.getDeclaredMethod("renderEventDetail", NativeEvent::class.java, String::class.java)
                    .apply { isAccessible = true }.invoke(activity, event, camera.name)
                (field("currentPlayer").get(activity) as ExoPlayer).apply { seekTo(5000); pause() }
                MainActivity::class.java.getDeclaredMethod("openEventCameraFullscreen", NativeEvent::class.java, NativeCamera::class.java)
                    .apply { isAccessible = true }.invoke(activity, event, camera)
                val dialog = field("eventFullscreenDialog").get(activity) as android.app.Dialog
                assertTrue(dialog.isShowing)
                val player = field("currentPlayer").get(activity) as ExoPlayer
                fullscreenPlayer = player
                assertFalse(player.playWhenReady)
                assertEquals(5000L, player.currentPosition)
                assertNotNull(dialog.findViewById<android.view.View>(R.id.event_playback_controls))
                dialog.dismiss()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertNull(field("eventFullscreenDialog").get(activity))
                assertSame(fullscreenPlayer, field("currentPlayer").get(activity))
                assertNotNull(activity.findViewById<android.view.View>(R.id.event_playback_controls))
            }
        }
    }

    @Test fun minimumZoomRulerHasContinuousMinorMarks() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText(R.string.no_cameras)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val width = (110 * activity.resources.displayMetrics.density).toInt()
                val height = (400 * activity.resources.displayMetrics.density).toInt()
                val ruler = TimelineRulerView(activity, 10007.0, 0,
                    (4 * activity.resources.displayMetrics.density).toInt(), 4) { "12:30" }
                ruler.layout(0, 0, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                ruler.draw(android.graphics.Canvas(bitmap))
                val x = (6 * activity.resources.displayMetrics.density).toInt()
                val markedRows = (0 until height).count { android.graphics.Color.alpha(bitmap.getPixel(x, it)) > 0 }
                assertTrue("Not enough ruler marks", markedRows > 25)
                context.openFileOutput("minimum-zoom-ruler.png", Context.MODE_PRIVATE).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
    }

    @Test fun cameraDetailLiveSoundStartsAndStopsTheAudioTransport() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText(R.string.no_cameras)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val camera = NativeCamera("front", "Front", true, "front", null, null, null, null, true, null, null)
                MainActivity::class.java.getDeclaredMethod("renderCameraDetail", NativeCamera::class.java,
                    java.lang.Long.TYPE, List::class.java, java.lang.Double::class.java, java.lang.Boolean.TYPE)
                    .apply { isAccessible = true }.invoke(activity, camera, System.currentTimeMillis(), null, null, false)
                fun find(view: android.view.View): android.view.View? {
                    if (view.contentDescription == activity.getString(R.string.playback_sound)) return view
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                        find(view.getChildAt(index))?.let { return it }
                    }
                    return null
                }
                val button = find(activity.findViewById(android.R.id.content))!!
                button.performClick()
                assertEquals("home:front", field("currentPlaybackKey").get(activity))
                assertNotNull(field("homeAudioWebView").get(activity))
                button.performClick()
                assertNull(field("homeAudioWebView").get(activity))
                assertEquals(false, field("soundEnabled").get(activity))
            }
        }
    }

    @Test fun recognizedNameFilterQueriesServerAndDisplaysMatchingEvents() {
        val requested = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/api/events") == true && request.requestUrl?.queryParameter("sub_labels") == "Bob") {
                    requested.countDown()
                    return MockResponse().setBody("""[{"id":"bob-event","camera":"front","label":"person","sub_label":"Bob","start_time":1792041090}]""")
                }
                return dispatcher(true).dispatch(request)
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText(R.string.cameras)).check(matches(isDisplayed()))
            scenario.onActivity { activity -> MainActivity::class.java.getDeclaredMethod("renderGlobalEvents").apply { isAccessible = true }.invoke(activity) }
            onView(withText(R.string.filter_name)).perform(androidx.test.espresso.action.ViewActions.click())
            onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.EditText::class.java))
                .perform(androidx.test.espresso.action.ViewActions.replaceText("Bob"))
            onView(withText(android.R.string.ok)).perform(androidx.test.espresso.action.ViewActions.click())
            assertTrue(requested.await(5, TimeUnit.SECONDS))
            var displayed = false
            for (attempt in 0..60) {
                scenario.onActivity { activity ->
                    fun count(view: android.view.View): Int =
                        (if (view is android.widget.TextView && view.text.toString() == "Bob") 1 else 0) +
                            if (view is android.view.ViewGroup) (0 until view.childCount).sumOf { count(view.getChildAt(it)) } else 0
                    displayed = count(activity.findViewById(android.R.id.content)) >= 2
                }
                if (displayed) break
                Thread.sleep(50)
            }
            assertTrue("The server-matched event must appear below the active name filter", displayed)
        }
    }

    @Test fun cleanServerSocketClosureTriggersNewConnection() {
        val connected = CountDownLatch(2)
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, this) }.toByteArray()
        bitmap.recycle()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Upgrade")?.equals("websocket", true) == true) {
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            connected.countDown()
                            webSocket.send(bytes.toByteString())
                            webSocket.close(1000, "test reconnect")
                        }
                    })
                }
                return dispatcher(true).dispatch(request)
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("The closed live stream must reconnect", connected.await(12, TimeUnit.SECONDS))
        }
    }

    private fun dispatcher(camera: Boolean) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) = when {
            request.path?.startsWith("/api/config") == true -> MockResponse().setBody(if (camera) """{"cameras":{"front":{"enabled":true}}}""" else """{"cameras":{}}""")
            request.path?.startsWith("/api/stats") == true -> MockResponse().setBody("{}")
            request.path?.startsWith("/api/events") == true -> MockResponse().setBody("[]")
            request.path?.startsWith("/api/version") == true -> MockResponse().setBody("test")
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun restore(prefs: SharedPreferences, values: Map<String, *>) {
        prefs.edit().clear().apply {
            values.forEach { (key, value) -> when (value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Long -> putLong(key, value)
                is Int -> putInt(key, value)
                is Float -> putFloat(key, value)
                is Set<*> -> putStringSet(key, value as Set<String>)
            } }
        }.commit()
    }
}
