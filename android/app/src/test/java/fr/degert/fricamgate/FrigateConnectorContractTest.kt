package com.nvr.camnory

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FrigateConnectorContractTest {
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
