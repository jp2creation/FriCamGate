package com.nvr.camnory

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in recorded playback on Devant only. No camera mutations or provider writes. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvEventPlaybackInstrumentationTest {
    @Test fun recordedEventAdvancesOnTv() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("eventPlayback") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val stored = java.io.File(context.noBackupFilesDir, "tv-config.enc")
        val before = stored.readBytes()
        val server = requireNotNull(TvConfigurationStore(context).load()).frigate.first()
        val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)
            .addInterceptor(LocalRouteInterceptor.create(context, server.baseUrl, server.localRoute, server.token, "/api/version")).build()
        fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("recorded", message) })
        try {
            val transport = FrigateNativeApi(server.baseUrl, client, server.localRoute)
            val latch = CountDownLatch(1)
            var data: DashboardData? = null
            transport.loadDashboard(server.token) { data = it.getOrNull(); latch.countDown() }
            assertTrue(latch.await(30, TimeUnit.SECONDS))
            val camera = requireNotNull(data).cameras.first { it.friendlyName.equals("Devant", true) || it.name.equals("devant", true) }
            val event = requireNotNull(data).events.first { it.camera == camera.name && it.endTime != null &&
                it.endTime - (it.startTime ?: it.endTime) >= 5 }
            for ((label, url) in listOf("VOD" to transport.eventVodUrl(event.id), "MP4" to transport.eventClipUrl(event.id))) {
                client.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${server.token}")
                    .header("Range", "bytes=0-255").build()).execute().use {
                    val prefix = it.body?.source()?.peek()?.readByteString(16)?.utf8().orEmpty()
                    report("$label: HTTP ${it.code}; type=${it.header("Content-Type")}; playlist=${prefix.startsWith("#EXTM3U")}; html=${prefix.trimStart().startsWith("<!") || prefix.trimStart().startsWith("<html")}")
                }
            }
            val qualifiedCamera = camera.copy(name = server.qualify(camera.name), streamName = server.qualify(camera.streamName), tvStreamName = server.qualify(camera.tvStreamName))
            val qualifiedEvent = event.copy(id = server.qualify(event.id), camera = qualifiedCamera.name)
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
                action = "com.nvr.camnory.TV_SETTINGS"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }).use { scenario ->
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredField("cameras").apply { isAccessible = true }.set(activity, listOf(qualifiedCamera))
                    MainActivity::class.java.getDeclaredMethod("renderEventDetail", NativeEvent::class.java, String::class.java)
                        .apply { isAccessible = true }.invoke(activity, qualifiedEvent, qualifiedCamera.name)
                }
                val deadline = SystemClock.elapsedRealtime() + 30000
                var advanced = false
                var failure: String? = null
                while (!advanced && failure == null && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { activity ->
                        val player = MainActivity::class.java.getDeclaredField("currentPlayer").apply { isAccessible = true }.get(activity) as ExoPlayer
                        player.playerError?.let { error ->
                            report("Format: ${player.videoFormat?.sampleMimeType}; ${player.videoFormat?.codecs}; ${player.videoFormat?.width}x${player.videoFormat?.height}; fps=${player.videoFormat?.frameRate}")
                            failure = error.errorCodeName + ":" + generateSequence(error.cause) { it.cause }.joinToString(",") {
                                if (it is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) "HTTP-${it.responseCode}" else it.javaClass.simpleName + "(" + it.message.orEmpty()
                                    .replace(server.token, "[secret]").replace(Regex("https?://[^\\s]+"), "[address]").take(180) + ")"
                            }
                        }
                        advanced = player.currentPosition >= 1500 && player.videoSize.width > 0
                        if (advanced) report("Playback advances; duration=${player.duration}; video=${player.videoSize.width}x${player.videoSize.height}")
                    }
                    if (!advanced && failure == null) Thread.sleep(100)
                }
                assertNull("Recorded video error", failure)
                assertTrue("Recorded playback must advance", advanced)
                val hideDeadline = SystemClock.elapsedRealtime() + 6500
                var hidden = false
                while (!hidden && SystemClock.elapsedRealtime() < hideDeadline) {
                    scenario.onActivity { activity ->
                        val controls = MainActivity::class.java.getDeclaredField("eventPlaybackControls").apply { isAccessible = true }
                            .get(activity) as androidx.media3.ui.PlayerControlView
                        hidden = controls.visibility == android.view.View.INVISIBLE
                    }
                    if (!hidden) Thread.sleep(100)
                }
                assertTrue("TV playback chrome hides without moving the video", hidden)
                scenario.onActivity { activity ->
                    val frame = activity.window.decorView.findViewWithTag<android.view.View>("event-player-frame")
                    val height = frame.height
                    frame.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
                    val controls = MainActivity::class.java.getDeclaredField("eventPlaybackControls").apply { isAccessible = true }
                        .get(activity) as androidx.media3.ui.PlayerControlView
                    assertEquals(android.view.View.VISIBLE, controls.visibility)
                    assertEquals(height, frame.height)
                }
                var pausedAt = 0L
                scenario.onActivity { activity ->
                    val player = MainActivity::class.java.getDeclaredField("currentPlayer").apply { isAccessible = true }.get(activity) as ExoPlayer
                    player.pause()
                    pausedAt = player.currentPosition
                    val controls = MainActivity::class.java.getDeclaredField("eventPlaybackControls").apply { isAccessible = true }
                        .get(activity) as androidx.media3.ui.PlayerControlView
                    assertTrue(controls.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause).performClick())
                }
                var resumed = false
                val resumeDeadline = SystemClock.elapsedRealtime() + 5000
                while (!resumed && SystemClock.elapsedRealtime() < resumeDeadline) {
                    scenario.onActivity { activity ->
                        val player = MainActivity::class.java.getDeclaredField("currentPlayer").apply { isAccessible = true }.get(activity) as ExoPlayer
                        assertNull(player.playerError)
                        resumed = player.currentPosition > pausedAt + 500
                    }
                    if (!resumed) Thread.sleep(100)
                }
                assertTrue("Play must resume the recorded video", resumed)
                report("Pause/play resume verified")
                scenario.onActivity { activity ->
                    fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
                        if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
                    val video = descendants(activity.window.decorView).filterIsInstance<androidx.media3.ui.PlayerView>().single()
                    val image = requireNotNull((video.videoSurfaceView as android.view.TextureView).getBitmap(640, 360))
                    val colors = mutableSetOf<Int>()
                    for (y in 0 until image.height step 18) for (x in 0 until image.width step 32) colors.add(image.getPixel(x, y))
                    assertTrue("Recorded video must contain actual decoded pixels", colors.size > 60)
                    java.io.FileOutputStream(java.io.File(context.cacheDir, "tv-recorded-quality.png")).use {
                        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    image.recycle()
                }
            }
            assertArrayEquals(before, stored.readBytes())
        } finally { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
    }
}
