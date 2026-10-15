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
                assertTrue(player.useController)
                assertNotNull(player.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_progress))
                assertNotNull(player.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause))
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
