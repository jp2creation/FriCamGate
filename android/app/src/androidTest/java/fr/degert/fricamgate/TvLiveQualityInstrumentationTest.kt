package com.nvr.camnory

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in, actual shared camera screen. Devant only; no hardware command or saved setting change. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvLiveQualityInstrumentationTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun sharedCameraScreenRendersAdvancingMainStream() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveQuality") == "true")
        val context = instrumentation.targetContext
        val stored = File(context.noBackupFilesDir, "tv-config.enc")
        val before = stored.readBytes()
        val configuration = requireNotNull(TvConfigurationStore(context).load())
        val server = configuration.frigate.first()
        val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)
            .addInterceptor(LocalRouteInterceptor.create(context, server.baseUrl, server.localRoute, server.token, "/api/version"))
            .build()
        fun report(value: String) { instrumentation.sendStatus(0, Bundle().apply { putString("quality", value) }) }
        try {
            val api = FrigateNativeApi(server.baseUrl, client, server.localRoute)
            val metadataReady = CountDownLatch(1)
            var dashboard: DashboardData? = null
            api.loadDashboard(server.token) { dashboard = it.getOrNull(); metadataReady.countDown() }
            assertTrue("Metadata timeout", metadataReady.await(30, TimeUnit.SECONDS))
            val native = requireNotNull(dashboard?.cameras?.singleOrNull {
                it.friendlyName.equals("Devant", true) || it.name.equals("devant", true)
            }) { "Devant unavailable; no other camera will be tested" }
            val camera = native.copy(name = server.qualify(native.name), streamName = server.qualify(native.streamName),
                tvStreamName = server.qualify(native.tvStreamName))
            val ready = CountDownLatch(1)
            var playback: ExoPlayer? = null
            var failure: Int? = null
            var width = 0
            var height = 0
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
                action = "com.nvr.camnory.TV_SETTINGS"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }).use { scenario ->
                scenario.onActivity { activity ->
                    val sessionField = MainActivity::class.java.getDeclaredField("session").apply { isAccessible = true }
                    val savedSession = sessionField.get(activity)
                    val tile: View
                    try {
                        // Build only the selected tile, without opening any home previews.
                        sessionField.set(activity, null)
                        tile = MainActivity::class.java.getDeclaredMethod("cameraCard", NativeCamera::class.java)
                            .apply { isAccessible = true }.invoke(activity, camera) as View
                    } finally { sessionField.set(activity, savedSession) }
                    assertEquals("tv-camera-tile", tile.tag)
                    assertEquals(listOf(tile), descendants(tile).filter { it.isClickable })
                    activity.setContentView(android.widget.FrameLayout(activity).apply { addView(tile) })
                    assertTrue(tile.requestFocus())
                }
                instrumentation.waitForIdleSync()
                val windowDeadline = SystemClock.elapsedRealtime() + 10000
                var windowReady = false
                while (!windowReady && SystemClock.elapsedRealtime() < windowDeadline) {
                    scenario.onActivity { windowReady = it.hasWindowFocus() && it.window.decorView.height > 0 }
                    if (!windowReady) Thread.sleep(50)
                }
                assertTrue("TV window must be visible", windowReady)
                scenario.onActivity { activity ->
                    fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                    field("cameras").set(activity, listOf(camera))
                    field("homeVisible").setBoolean(activity, true)
                    val item = (MainActivity::class.java.getDeclaredMethod("dashboardCameras").apply { isAccessible = true }
                        .invoke(activity) as List<*>).single { candidate ->
                            candidate!!.javaClass.getDeclaredField("frigate").apply { isAccessible = true }.get(candidate) == camera
                        }
                    val select = MainActivity::class.java.declaredMethods.first { it.name == "selectTelevisionCamera" }.apply { isAccessible = true }
                    select.invoke(activity, item)
                    assertNotNull("Selecting Devant must create a player", field("currentPlayer").get(activity))
                }
                var homeAudioDecoded = false
                val audioDeadline = SystemClock.elapsedRealtime() + 25000
                while (!homeAudioDecoded && SystemClock.elapsedRealtime() < audioDeadline) {
                    scenario.onActivity { activity ->
                        val player = MainActivity::class.java.getDeclaredField("currentPlayer").apply { isAccessible = true }.get(activity) as? ExoPlayer
                        homeAudioDecoded = (player?.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
                        if (homeAudioDecoded) assertEquals(1f, player!!.volume)
                    }
                    if (!homeAudioDecoded) Thread.sleep(100)
                }
                assertTrue("Selected home camera must decode audio", homeAudioDecoded)
                report("Selected home tile: audio decoded")
                scenario.onActivity { activity ->
                    MainActivity::class.java.declaredMethods.first { it.name == "selectTelevisionCamera" }
                        .apply { isAccessible = true }.invoke(activity, null)
                    assertNull("Leaving tile must release home audio", MainActivity::class.java.getDeclaredField("currentPlayer")
                        .apply { isAccessible = true }.get(activity))
                    MainActivity::class.java.getDeclaredField("homeVisible").apply { isAccessible = true }.setBoolean(activity, false)
                }
                scenario.onActivity { activity ->
                    val tile = descendants(activity.window.decorView).first { it.tag == "tv-camera-tile" }
                    assertTrue(tile.performClick())
                }
                instrumentation.waitForIdleSync()
                val playerField = MainActivity::class.java.getDeclaredField("currentPlayer").apply { isAccessible = true }
                val playerDeadline = SystemClock.elapsedRealtime() + 5000
                var playerCreated = false
                while (!playerCreated && SystemClock.elapsedRealtime() < playerDeadline) {
                    scenario.onActivity { playerCreated = playerField.get(it) != null }
                    if (!playerCreated) Thread.sleep(30)
                }
                assertTrue("Fullscreen video player was not created", playerCreated)
                scenario.onActivity { activity ->
                    playback = MainActivity::class.java.getDeclaredField("currentPlayer").apply { isAccessible = true }
                        .get(activity) as ExoPlayer
                    assertEquals(1f, playback!!.volume)
                    assertTrue(MainActivity::class.java.getDeclaredField("expandedLiveImmersive").apply { isAccessible = true }.getBoolean(activity))
                    assertNotNull(activity.window.decorView.findViewWithTag<View>("tv-live-camera-options"))
                    playback!!.addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            width = playback!!.videoSize.width; height = playback!!.videoSize.height
                            report("Shared camera rendered: "+width+"x"+height)
                            ready.countDown()
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            failure = error.errorCode
                            report("Video error code: $failure"); ready.countDown()
                        }
                    })
                }
                assertTrue("No decoded video within 25 seconds", ready.await(25, TimeUnit.SECONDS))
                assertNull("Main-stream playback failed", failure)
                assertTrue("Expected main-stream image, got "+width+"x"+height, width >= 1280)
                val advancing = CountDownLatch(1)
                var advanced = false
                scenario.onActivity {
                    val handler = Handler(Looper.getMainLooper())
                    val initial = playback!!.currentPosition
                    val deadline = SystemClock.elapsedRealtime() + 10000
                    fun check() {
                        advanced = playback!!.currentPosition >= initial + 1500
                        if (advanced || SystemClock.elapsedRealtime() >= deadline) advancing.countDown()
                        else handler.postDelayed({ check() }, 100)
                    }
                    check()
                }
                assertTrue(advancing.await(12, TimeUnit.SECONDS))
                assertTrue("Video did not advance", advanced)
                scenario.onActivity { activity ->
                    val audio = playback!!.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
                    report("Audio tracks: ${audio.size}; selected: ${audio.any { it.isSelected }}; decoded: ${playback!!.audioFormat?.sampleMimeType}")
                    report("Audio output buffers: ${playback!!.audioDecoderCounters?.renderedOutputBufferCount}")
                    assertEquals(1f, playback!!.volume)
                    assertTrue("Devant audio track must be selected", audio.any { it.isSelected })
                    assertTrue("Devant audio must be decoded", (playback!!.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0)
                    val manager = activity.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                    report("TV media volume: ${manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}; muted: ${manager.isStreamMute(android.media.AudioManager.STREAM_MUSIC)}")
                }
                var pixels: android.graphics.Bitmap? = null
                scenario.onActivity { activity ->
                    val view = descendants(activity.window.decorView).filterIsInstance<PlayerView>().single()
                    pixels = (view.videoSurfaceView as TextureView).getBitmap(1280, 720)
                }
                val image = requireNotNull(pixels)
                val colors = mutableSetOf<Int>()
                for (y in 0 until image.height step 36) for (x in 0 until image.width step 64) colors.add(image.getPixel(x, y))
                assertTrue("Decoded image must not be a flat green/black surface", colors.size > 60)
                FileOutputStream(File(context.cacheDir, "tv-live-quality.png")).use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            assertArrayEquals("TV connections changed", before, stored.readBytes())
        } finally {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll()
        }
    }
}
