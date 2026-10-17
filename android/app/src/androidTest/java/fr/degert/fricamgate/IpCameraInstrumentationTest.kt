package com.nvr.camnory

import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Credentials
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class IpCameraInstrumentationTest {
    @Test fun directCameraEditorUsesTheSharedThemeAndOptionalAuth() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ActivityScenario.launch<IpCameraActivity>(Intent(context, IpCameraActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                IpCameraActivity::class.java.getDeclaredMethod("edit", IpCamera::class.java).apply { isAccessible = true }.invoke(activity, null)
                assertNotNull(activity.window.decorView.findViewWithTag<View>("ip-server"))
                assertFalse(activity.window.decorView.findViewWithTag<SwitchCompat>("ip-auth").isChecked)
                assertFalse(activity.window.decorView.findViewWithTag<View>("ip-password").isShown)
            }
            val drawn = java.util.concurrent.CountDownLatch(1)
            scenario.onActivity { it.window.decorView.postOnAnimation { drawn.countDown() } }
            assertTrue(drawn.await(3, TimeUnit.SECONDS))
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.cacheDir, "ip-camera-editor.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }

    @Test fun rtspConnectsDirectlyWithAndWithoutCredentials() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (authenticated in listOf(false, true)) {
            val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            val described = java.util.concurrent.CountDownLatch(1)
            val authSeen = java.util.concurrent.atomic.AtomicBoolean()
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            var socket: java.net.Socket? = null
            executor.submit {
                try {
                    socket = server.accept()
                    socket!!.soTimeout = 5000
                    val reader = socket!!.getInputStream().bufferedReader()
                    val output = socket!!.getOutputStream()
                    while (!Thread.currentThread().isInterrupted) {
                        val line = reader.readLine() ?: break
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                            headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                        }
                        val method = line.substringBefore(' ')
                        val authorized = !authenticated || headers["authorization"] == Credentials.basic("viewer", "fixture-secret")
                        val sequence = headers["cseq"] ?: "1"
                        val response = when {
                            authenticated && !authorized -> "RTSP/1.0 401 Unauthorized\r\nCSeq: $sequence\r\nWWW-Authenticate: Basic realm=\"webcam\"\r\n\r\n"
                            method == "DESCRIBE" -> {
                                authSeen.set(authorized)
                                described.countDown()
                                "RTSP/1.0 404 Not Found\r\nCSeq: $sequence\r\n\r\n"
                            }
                            else -> "RTSP/1.0 200 OK\r\nCSeq: $sequence\r\nPublic: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n\r\n"
                        }
                        output.write(response.toByteArray()); output.flush()
                    }
                } catch (_: Exception) { }
            }
            try {
                ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply { action = "com.nvr.camnory.TV_SETTINGS" }).use { scenario ->
                    lateinit var card: IpCameraCard
                    scenario.onActivity { activity ->
                        card = IpCameraCard(activity, IpCamera("fixture", "RTSP fixture", "rtsp://127.0.0.1:${server.localPort}/live",
                            if (authenticated) "viewer" else "", if (authenticated) "fixture-secret" else ""), {}, {}, playerOnly = true)
                        activity.setContentView(card); card.setActive(true)
                    }
                    assertTrue("RTSP DESCRIBE reaches the stream directly", described.await(10, TimeUnit.SECONDS))
                    assertTrue("RTSP authentication uses the optional supplied credentials", authSeen.get())
                    scenario.onActivity { card.setActive(false) }
                }
            } finally { socket?.close(); server.close(); executor.shutdownNow() }
        }
    }

    @Test fun managerAddsEditsAndRemovesAuthenticationInEncryptedIsolatedStorage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = java.io.File(context.noBackupFilesDir, "ip-qa.enc")
        assertFalse("QA file must not preexist", file.exists())
        try {
            ActivityScenario.launch<IpCameraActivity>(Intent(context, IpCameraActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    val store = IpCameraStore(context, "ip-qa.enc")
                    IpCameraActivity::class.java.getDeclaredField("store").apply { isAccessible = true }.set(activity, store)
                    val edit = IpCameraActivity::class.java.getDeclaredMethod("edit", IpCamera::class.java).apply { isAccessible = true }
                    edit.invoke(activity, null)
                    fun field(tag: String) = activity.window.decorView.findViewWithTag<EditText>(tag)
                    field("ip-name").setText("Fixture webcam")
                    field("ip-server").setText("http://127.0.0.1:12345/video")
                    activity.window.decorView.findViewWithTag<View>("ip-save").performClick()
                    assertEquals("", store.load().single().username)
                    edit.invoke(activity, store.load().single())
                    activity.window.decorView.findViewWithTag<SwitchCompat>("ip-auth").isChecked = true
                    field("ip-user").setText("viewer")
                    field("ip-password").setText("fixture-secret")
                    activity.window.decorView.findViewWithTag<View>("ip-save").performClick()
                    assertEquals("fixture-secret", store.load().single().password)
                    assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("fixture-secret"))
                    edit.invoke(activity, store.load().single())
                    assertEquals("", field("ip-password").text.toString())
                    activity.window.decorView.findViewWithTag<View>("ip-save").performClick()
                    assertEquals("fixture-secret", store.load().single().password)
                    edit.invoke(activity, store.load().single())
                    activity.window.decorView.findViewWithTag<SwitchCompat>("ip-auth").isChecked = false
                    activity.window.decorView.findViewWithTag<View>("ip-save").performClick()
                    assertEquals("", store.load().single().password)
                    assertEquals("", store.load().single().username)
                }
            }
        } finally { file.delete() }
    }

    @Test fun jpegMultipartAndMp4WorkWithOptionalAuthenticationAndStopOnExit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val jpeg = instrumentation.context.assets.open("ha-previews/driveway.jpg").use { it.readBytes() }
        val mp4 = instrumentation.context.assets.open("ip-camera-sample.mp4").use { it.readBytes() }
        val server = MockWebServer()
        val wrongAuth = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/protected" && request.getHeader("Authorization") != Credentials.basic("viewer", "fixture-secret")) {
                    wrongAuth.incrementAndGet()
                    return MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=webcam")
                }
                if (request.path == "/video.mp4") return MockResponse().addHeader("Content-Type", "video/mp4").setBody(Buffer().write(mp4))
                if (request.path == "/multipart") {
                    val body = Buffer()
                    repeat(20) { body.writeUtf8("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n"); body.write(jpeg); body.writeUtf8("\r\n") }
                    return MockResponse().addHeader("Content-Type", "multipart/x-mixed-replace; boundary=frame").setBody(body).throttleBody(jpeg.size.toLong(), 1, TimeUnit.SECONDS)
                }
                return MockResponse().addHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg))
            }
        }
        server.start()
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply { action = "com.nvr.camnory.TV_SETTINGS" }).use { scenario ->
                for (path in listOf("/open", "/protected", "/multipart", "/video.mp4")) {
                    lateinit var card: IpCameraCard
                    scenario.onActivity { activity ->
                        val camera = IpCamera("fixture", "Webcam fixture", server.url(path).toString(),
                            if (path == "/protected") "viewer" else "", if (path == "/protected") "fixture-secret" else "")
                        card = IpCameraCard(activity, camera, {}, {}, playerOnly = true)
                        activity.setContentView(card); card.setActive(true)
                    }
                    val deadline = android.os.SystemClock.elapsedRealtime() + 15000
                    var ready = false
                    while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity {
                            ready = if (path == "/video.mp4") {
                                val player = IpCameraCard::class.java.getDeclaredField("player").apply { isAccessible = true }.get(card) as? androidx.media3.exoplayer.ExoPlayer
                                player != null && player.currentPosition >= 500 && player.videoSize.width > 0
                            } else (card.getChildAt(0) as ImageView).drawable != null && card.getChildAt(2).visibility == View.GONE
                        }
                        if (!ready) Thread.sleep(50)
                    }
                    assertTrue("Direct webcam renders $path", ready)
                    scenario.onActivity { card.setActive(false) }
                    instrumentation.waitForIdleSync()
                    Thread.sleep(150)
                    val stoppedCount = server.requestCount
                    Thread.sleep(1100)
                    assertEquals("Inactive camera must not poll", stoppedCount, server.requestCount)
                }
                scenario.onActivity { activity ->
                    val bad = IpCameraCard(activity, IpCamera("bad", "Bad password", server.url("/protected").toString(), "viewer", "wrong"), {}, {}, playerOnly = true)
                    activity.setContentView(bad); bad.setActive(true)
                }
                val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                while (wrongAuth.get() == 0 && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
                assertEquals("Failed auth must not retry in a loop", 1, wrongAuth.get())
            }
        } finally { server.shutdown() }
    }
}
