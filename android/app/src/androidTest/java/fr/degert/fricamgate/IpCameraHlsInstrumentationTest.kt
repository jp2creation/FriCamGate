package com.nvr.camnory

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class IpCameraHlsInstrumentationTest {
    @Test fun hlsMimeAliasesSelectTheHlsLoaderInsteadOfTheFileExtractor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply { action = "com.nvr.camnory.TV_SETTINGS" }).use { scenario ->
            for (mime in listOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "application/x-mpegURL")) {
                val variant = CountDownLatch(1)
                val server = MockWebServer()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path == "/variant.m3u8") {
                            variant.countDown()
                            return MockResponse().setResponseCode(404)
                        }
                        return MockResponse().addHeader("Content-Type", "$mime; charset=utf-8")
                            .setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000,RESOLUTION=640x360\nvariant.m3u8\n")
                    }
                }
                server.start()
                lateinit var card: IpCameraCard
                try {
                    scenario.onActivity { activity ->
                        // No extension: the response MIME must determine the media source.
                        card = IpCameraCard(activity, IpCamera("hls-fixture", "HLS fixture", server.url("/live").toString()), {}, {}, playerOnly = true)
                        activity.setContentView(card); card.setActive(true)
                    }
                    assertTrue("The HLS variant must be requested for $mime", variant.await(8, TimeUnit.SECONDS))
                } finally {
                    scenario.onActivity { card.setActive(false) }
                    server.shutdown()
                }
            }
        }
    }
}
