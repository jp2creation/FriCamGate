package com.nvr.camnory

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OnvifInstrumentationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun credentialsPersistEncryptedAndTamperingFailsClosed() {
        val file = File(context.noBackupFilesDir, "onvif-store-test.enc")
        try {
            val store = OnvifCameraStore(context, file.name)
            store.save(listOf(OnvifSavedCamera("test", "Test", "http://camera/onvif/device_service", OnvifCredentials("test-user", "secret-value"))))
            val bytes = file.readBytes()
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("secret-value"))
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("test-user"))
            val camera = OnvifCameraStore(context, file.name).load().single()
            assertEquals("secret-value", camera.credentials.password)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            assertTrue(runCatching { store.load() }.isFailure)
        } finally { file.delete() }
    }

    @Test fun openingCameraManagerDoesNotChangeFrigateSession() {
        val prefs = context.getSharedPreferences("fricamgate-native", Context.MODE_PRIVATE)
        val before = prefs.all
        ActivityScenario.launch(OnvifActivity::class.java).use { scenario ->
            scenario.onActivity { assertNotNull(it.findViewById<android.view.View>(android.R.id.content)) }
        }
        assertTrue("Frigate session changed", before == prefs.all)
    }

    @Test fun hardwareDevantReadOnlyProfilesAndRtspVideo() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hardwareOnvif") == "true")
        val secretFile = File(context.filesDir, "onvif-hardware-test.json")
        try {
            val data = JSONObject(secretFile.readText())
            val credentials = OnvifCredentials(data.getString("username"), data.getString("password"))
            val client = OnvifReadOnlyClient()
            val connection = client.connect(data.getString("endpoint"), credentials)
            assertTrue(connection.profiles.isNotEmpty())
            val profile = connection.profiles.lastOrNull { it.encoding == "H264" } ?: connection.profiles.first()
            val stream = client.stream(connection, profile, credentials)
            val frame = CountDownLatch(1)
            val failure = java.util.concurrent.atomic.AtomicReference("timeout")
            ActivityScenario.launch(OnvifActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val camera = OnvifSavedCamera("hardware-test", "Devant", data.getString("endpoint"), credentials)
                    OnvifActivity::class.java.getDeclaredMethod("play", OnvifSavedCamera::class.java, String::class.java)
                        .apply { isAccessible = true }.invoke(activity, camera, stream.toString())
                    val player = OnvifActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.get(activity) as ExoPlayer
                    player.addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() { frame.countDown() }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            failure.set(error.errorCodeName + ":" + error.cause?.javaClass?.simpleName)
                        }
                    })
                }
                val rendered = frame.await(30, TimeUnit.SECONDS)
                scenario.onActivity { activity ->
                    val player = OnvifActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.get(activity) as? ExoPlayer
                    if (!rendered) failure.set(failure.get() + ":state=" + player?.playbackState + ":ready=" + player?.playWhenReady)
                }
                assertTrue("No direct camera frame rendered: ${failure.get()}", rendered)
                var playing = false
                var state = 0
                for (attempt in 0 until 60) {
                    scenario.onActivity { activity ->
                        val player = OnvifActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.get(activity) as ExoPlayer
                        playing = player.isPlaying
                        state = player.playbackState
                    }
                    if (playing) break
                    Thread.sleep(200)
                }
                assertTrue("Stream did not leave buffering: state=$state", playing)
                var position = 0L
                scenario.onActivity { activity ->
                    val player = OnvifActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.get(activity) as ExoPlayer
                    position = player.currentPosition
                }
                Thread.sleep(2000)
                scenario.onActivity { activity ->
                    val player = OnvifActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.get(activity) as ExoPlayer
                    assertTrue("Direct stream did not keep playing", player.currentPosition > position + 500)
                    assertTrue(player.isPlaying)
                }
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                    context.openFileOutput("onvif-devant-video.png", Context.MODE_PRIVATE).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        } finally { secretFile.delete() }
    }
}
