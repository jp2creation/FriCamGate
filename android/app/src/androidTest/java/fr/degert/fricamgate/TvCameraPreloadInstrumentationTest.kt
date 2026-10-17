package com.nvr.camnory

import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Simulated HA/Ring startup: no real providers or camera commands are used. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvCameraPreloadInstrumentationTest {
    @Test fun hiddenHomeAssistantStreamWarmsBeforeItsPageIsShown() {
        val streamRequested = CountDownLatch(1)
        val manifestRequested = CountDownLatch(1)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/api/websocket") return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("{\"type\":\"auth_required\"}") }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val message = JSONObject(text)
                            if (message.optString("type") == "auth") { webSocket.send("{\"type\":\"auth_ok\"}"); return }
                            val result = when (message.optString("type")) {
                                "camera/capabilities" -> JSONObject().put("frontend_stream_types", JSONArray(listOf("hls")))
                                "camera/stream" -> JSONObject().put("url", "/api/hls/fixture/master_playlist.m3u8")
                                else -> error("Unexpected fixture camera operation")
                            }
                            webSocket.send(JSONObject().put("id", message.getInt("id")).put("type", "result")
                                .put("success", true).put("result", result).toString())
                            if (message.optString("type") == "camera/stream") streamRequested.countDown()
                        }
                    })
                    manifestRequested.countDown()
                    return MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .setBody("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n")
                }
            }
            server.start()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            lateinit var card: HomeAssistantCameraView
            fun field(name: String) = HomeAssistantCameraView::class.java.getDeclaredField(name).apply { isAccessible = true }
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
                action = "com.nvr.camnory.TV_SETTINGS"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }).use { scenario ->
                scenario.onActivity { activity ->
                    val camera = HomeAssistantCamera("camera.fixture", "Ring fixture", true)
                    card = HomeAssistantCameraView(activity, HomeAssistantConnection(server.url("/").toString(), "fixture", listOf(camera)),
                        camera, fullscreen = {}, onAudible = {}, fullscreenMode = true, playerOnly = true, preloadLive = true)
                    card.visibility = View.GONE
                    activity.setContentView(FrameLayout(activity).apply { addView(card, FrameLayout.LayoutParams(-1, -1)) })
                    card.setActive(true)
                }
                assertTrue("Hidden camera must request its live stream", streamRequested.await(10, TimeUnit.SECONDS))
                var warmed = false
                val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                while (!warmed && android.os.SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { warmed = field("pendingHls").get(card) != null }
                    if (!warmed) Thread.sleep(50)
                }
                assertTrue(warmed)
                assertTrue("The hidden stream must be primed at the provider, not just obtain a URL", manifestRequested.await(5, TimeUnit.SECONDS))
                scenario.onActivity {
                    assertNull("Hidden camera must not allocate a TV decoder", field("player").get(card))
                    assertTrue(field("muted").getBoolean(card))
                    card.visibility = View.VISIBLE
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    HomeAssistantCameraView::class.java.getDeclaredMethod("syncVisibility").apply { isAccessible = true }.invoke(card)
                    assertNull(field("pendingHls").get(card))
                    assertNotNull("Showing the page must reuse the prepared stream", field("player").get(card))
                    assertEquals(0f, (field("player").get(card) as ExoPlayer).volume)
                    card.setActive(false)
                    assertNull(field("player").get(card))
                    assertNull(field("socket").get(card))
                }
            }
        }
    }
}
