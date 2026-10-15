package com.nvr.camnory

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FrigateConnectorContractTest {
    @Test fun recordedMediaContractPreservesTheDefaultRecordingRoute() {
        val media: NvrRecordedMedia = FrigateNativeApi("https://example.test/nvr", OkHttpClient())
        assertEquals("https://example.test/nvr/vod/front/start/100/end/145/master.m3u8",
            media.recordingVodUrl("front", 100, 145))
        assertEquals("https://example.test/nvr/vod/front/sub/start/100/end/145/master.m3u8",
            media.recordingVodUrl("front", 100, 145, "sub"))
    }

    @Test fun eventHistoryContractPreservesFiltersAndAuthentication() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":"event-2","camera":"front","label":"car","start_time":1000}]"""))
            val history: NvrEventHistory = FrigateNativeApi(server.url("/nvr").toString(), OkHttpClient())
            val events = await<List<NativeEvent>> {
                history.searchEvents("test-token", EventSearch(camera = "front", kind = EventKind.Vehicle), 2000.0, 1500.0, it)
            }.getOrThrow()
            assertEquals("car", events.single().objectLabel)
            val request = server.takeRequest()
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
            assertEquals("front", request.requestUrl!!.queryParameter("cameras"))
            assertEquals("1500.0", request.requestUrl!!.queryParameter("before"))
            assertEquals("car,vehicle,truck,bus,motorcycle,bicycle", request.requestUrl!!.queryParameter("labels"))
        }
    }

    @Test fun loginFallbackPreservesExistingHttpContract() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""{"token":"test-token","username":"admin"}"""))
            val connector: NvrConnector = FrigateNativeApi(server.url("/nvr").toString(), OkHttpClient())
            val result = await<NativeLogin> { connector.login("admin", "test-password", it) }.getOrThrow()
            assertEquals("test-token", result.token)
            assertEquals("/nvr/api/login", server.takeRequest().path)
            assertEquals("/nvr/login", server.takeRequest().path)
        }
    }

    @Test fun discoversCamerasAndMapsEventsThroughCommonContract() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"cameras":{"front":{"friendly_name":"Front","detect":{"enabled":true}}}}"""))
            server.enqueue(MockResponse().setBody("""[{"id":"event-1","camera":"front","label":"person","sub_label":"Bob","start_time":1000}]"""))
            val connector: NvrConnector = FrigateNativeApi(server.url("/").toString().trimEnd('/'), OkHttpClient())
            val data = await<DashboardData> { connector.loadDashboard("test-token", it) }.getOrThrow()
            assertEquals("Front", data.cameras.single().friendlyName)
            assertEquals(true, data.cameras.single().detectEnabled)
            assertNull(data.cameras.single().audioEnabled)
            assertEquals("person", data.events.single().objectLabel)
            assertEquals("Bob", data.events.single().subLabel)
            assertEquals("Bearer test-token", server.takeRequest().getHeader("Authorization"))
            assertTrue(server.takeRequest().path!!.startsWith("/api/events?"))
        }
    }

    @Test fun unauthorizedResponseIsNotAnEmptyDashboard() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val connector: NvrConnector = FrigateNativeApi(server.url("/").toString().trimEnd('/'), OkHttpClient())
            assertTrue(await<DashboardData> { connector.loadDashboard("expired", it) }.isFailure)
        }
    }

    private fun <T> await(start: ((Result<T>) -> Unit) -> Unit): Result<T> {
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        start { result = it; latch.countDown() }
        assertTrue("Connector callback timed out", latch.await(5, TimeUnit.SECONDS))
        return result!!
    }
}
