package com.nvr.camnory

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real native layouts, fictional in-memory content; no provider-store writes. */
class TabletWorkspaceInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun render(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun root(activity: MainActivity) = activity.findViewById<ViewGroup>(android.R.id.content)
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        if (name.startsWith("home-")) Thread.sleep(3500)
        Thread.sleep(1500)
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(context.getExternalFilesDir(null), "tablet-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun nativeWorkspaceRendersAndNavigatesInCurrentOrientation() {
        val photos = listOf("driveway", "entrance", "terrace", "workshop").map { name ->
            instrumentation.context.assets.open("ha-previews/$name.jpg").use { BitmapFactory.decodeStream(it) }
        }
        val cameras = listOf("Allée", "Entrée", "Terrasse", "Atelier", "Jardin", "Garage").mapIndexed { index, label ->
            NativeCamera("demo-$index", label, true, "demo-$index", true, true, true, true, false, true, false)
        }
        val now = System.currentTimeMillis() / 1000.0
        val events = cameras.mapIndexed { index, camera -> NativeEvent("demo-event-$index", camera.name, "person", "person", null,
            "12:${20 + index}", now - 300 - index * 120, now - 270 - index * 120, photos[index % photos.size]) }
        val client = OkHttpClient()
        val server = MockWebServer()
        val clip = instrumentation.context.assets.open("ip-camera-sample.mp4").use { it.readBytes() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(clip))
        }
        server.start()
        val clipUrl = server.url("/clip.mp4").toString()
        val preferences = context.getSharedPreferences("camgate-settings", 0)
        val originalTheme = preferences.getString("theme", null)
        preferences.edit().putString("theme", "dark").commit()
        val fake = object : FrigateNativeApi("http://127.0.0.1:9", client) {
            override fun eventClipUrl(eventId: String) = clipUrl
            override fun eventVodUrl(eventId: String) = clipUrl
            override fun loadBitmap(token: String, url: String, callback: (Bitmap?) -> Unit) {
                val index = Regex("demo-(\\d)").find(url)?.groupValues?.get(1)?.toInt() ?: 0
                callback(photos[index % photos.size])
            }
            override fun loadDashboard(token: String, callback: (Result<DashboardData>) -> Unit) = callback(Result.success(DashboardData(cameras, events)))
            override fun searchEvents(token: String, search: EventSearch, now: Double, before: Double?, callback: (Result<List<NativeEvent>>) -> Unit) = callback(Result.success(events))
            override fun openMjpegStream(token: String, streamName: String, onFrame: (Bitmap) -> Unit, onError: () -> Unit): WebSocket {
                val socket = object : WebSocket {
                    var canceled = false
                    override fun request() = Request.Builder().url("http://127.0.0.1:9").build()
                    override fun queueSize() = 0L
                    override fun send(text: String) = true
                    override fun send(bytes: ByteString) = true
                    override fun close(code: Int, reason: String?): Boolean { canceled = true; return true }
                    override fun cancel() { canceled = true }
                }
                Handler(Looper.getMainLooper()).postDelayed({ if (!socket.canceled) onFrame(photos[(streamName.last().digitToIntOrNull() ?: 0) % photos.size]) }, 100)
                return socket
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var landscape = false
                scenario.onActivity { activity ->
                    assertTrue(activity.resources.configuration.tabletLayoutPolicy().tablet)
                    landscape = activity.resources.configuration.tabletLayoutPolicy().splitWorkspace
                    Palette.dark = true
                    Palette.accent = Appearance.accent(null)
                    @Suppress("DEPRECATION")
                    activity.resources.updateConfiguration(android.content.res.Configuration(activity.resources.configuration).apply { setLocale(java.util.Locale.FRENCH) }, activity.resources.displayMetrics)
                    val sessionClass = Class.forName("com.nvr.camnory.NativeSession")
                    field("session").set(activity, sessionClass.declaredConstructors.single().apply { isAccessible = true }.newInstance("http://127.0.0.1:9", "demo", "demo"))
                    field("api").set(activity, fake)
                    field("cameras").set(activity, cameras)
                    field("events").set(activity, events)
                    field("dashboardErrorMessage").set(activity, null)
                    field("globalResults").set(activity, events)
                    field("globalLoading").set(activity, false)
                    field("globalHasMore").set(activity, false)
                    MainActivity::class.java.getDeclaredMethod("renderHome", String::class.java).apply { isAccessible = true }.invoke(activity, null)
                }
                val orientation = if (landscape) "landscape" else "portrait"
                capture("home-$orientation")
                scenario.onActivity { activity ->
                    assertNotNull(root(activity).findViewWithTag<View>(if (landscape) "tablet-navigation-rail" else "bottom-navigation"))
                    val header = root(activity).findViewWithTag<View>("tablet-header")
                    val location = IntArray(2); header.getLocationOnScreen(location)
                    val inset = androidx.core.view.ViewCompat.getRootWindowInsets(header)!!.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top
                    assertTrue("Header must clear the status bar", location[1] >= inset)
                    val tiles = all(root(activity)).filter { it.tag?.toString()?.startsWith("dashboard-camera:") == true }
                    assertEquals(6, tiles.size)
                    assertEquals(tiles[0].width, tiles[1].width)
                    assertTrue(tiles[0].width > 100)
                    render(activity, "renderGlobalEvents")
                }
                capture("notifications-$orientation")
                scenario.onActivity { activity ->
                    val row = root(activity).findViewWithTag<LinearLayout>("history-row")
                    assertEquals(if (landscape) 2 else 1, row.childCount)
                    MainActivity::class.java.getDeclaredMethod("renderEventDetailAt", NativeEvent::class.java, String::class.java, Double::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(activity, events.first(), cameras.first().name, events.first().startTime!!, false)
                }
                capture("event-$orientation")
                scenario.onActivity { activity ->
                    assertEquals(landscape, root(activity).findViewWithTag<View>("tablet-event-workspace") != null)
                    assertTrue("The local test recording must have loaded", (field("currentPlayer").get(activity) as androidx.media3.exoplayer.ExoPlayer).duration > 0)
                    MainActivity::class.java.getDeclaredMethod("renderCameraDetail", NativeCamera::class.java, Long::class.javaPrimitiveType, List::class.java, Double::class.javaObjectType, Boolean::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(activity, cameras.first(), System.currentTimeMillis(), events, null, false)
                }
                capture("timeline-$orientation")
                scenario.onActivity { activity ->
                    assertEquals(landscape, root(activity).findViewWithTag<View>("tablet-camera-workspace") != null)
                    render(activity, "renderSettings")
                }
                capture("settings-$orientation")
                scenario.onActivity { activity ->
                    if (landscape) {
                        assertNotNull(root(activity).findViewWithTag<View>("tablet-settings-workspace"))
                        root(activity).findViewWithTag<View>("tablet-settings-appearance").performClick()
                        assertEquals("appearance", field("settingsPage").get(activity))
                    } else render(activity, "renderAppearanceSettings")
                }
                capture("appearance-$orientation")
                scenario.onActivity { activity ->
                    render(activity, "renderCameraSettings")
                }
                capture("cameras-$orientation")
                fun resize(size: String) {
                    instrumentation.uiAutomation.executeShellCommand("wm size $size").close()
                    Thread.sleep(1500)
                    instrumentation.waitForIdleSync()
                }
                try {
                    resize(if (landscape) "1000x1600" else "1600x1000")
                    scenario.onActivity { activity ->
                        assertEquals("cameras", field("settingsPage").get(activity))
                        assertEquals(!landscape, root(activity).findViewWithTag<View>("tablet-settings-workspace") != null)
                    }
                } finally {
                    resize(if (landscape) "1600x1000" else "1000x1600")
                }
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            server.shutdown()
            preferences.edit().apply { if (originalTheme == null) remove("theme") else putString("theme", originalTheme) }.commit()
            Appearance.loadPalette(context.getSharedPreferences("camgate-settings", 0), false)
        }
    }
}
