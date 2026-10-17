package com.nvr.camnory

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LocalRouteInterceptorTest {
    private val local = LocalServerRoute("http://192.168.0.144:5000/nvr", "192.168.0.0/24")
    private fun reply(request: Request, code: Int = 200) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(code).message("fixture").body("ok".toResponseBody()).build()

    @Test fun localProbeThenLocalReadAndNetworkChangeBackToExternal() {
        var network: Pair<String, List<String>>? = "home" to listOf("192.168.0.20")
        var probes = 0
        val probe = OkHttpClient.Builder().addInterceptor {
            probes++; assertEquals("/nvr/api/version", it.request().url.encodedPath)
            assertEquals("Bearer fixture", it.request().header("Authorization")); reply(it.request())
        }.build()
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor(LocalRouteInterceptor("https://external.example/frigate", local,
            "fixture", "/api/version", { network }, probe)).addInterceptor { requests.add(it.request()); reply(it.request()) }.build()
        fun read() { client.newCall(Request.Builder().url("https://external.example/frigate/api/config").build()).execute().close() }
        read(); read()
        assertEquals(1, probes); assertEquals("192.168.0.144", requests.last().url.host)
        assertEquals("http://192.168.0.144:5000/nvr", ServerRouteStatus.shared.get("https://external.example/frigate", "/api/version")!!.address)
        network = "other" to listOf("192.168.1.20"); read()
        assertEquals("external.example", requests.last().url.host); assertEquals(1, probes)
        assertFalse(ServerRouteStatus.shared.get("https://external.example/frigate", "/api/version")!!.local)
        network = null; read(); assertEquals("external.example", requests.last().url.host)
    }
    @Test fun failedProbeUsesExternalAndNeverFollowsRedirect() {
        for (code in listOf(302, 401, 500)) {
            val probe = OkHttpClient.Builder().addInterceptor { reply(it.request(), code) }.build()
            var host = ""
            val client = OkHttpClient.Builder().addInterceptor(LocalRouteInterceptor("https://external.example", local, "fixture", "/api/version",
                { "home" to listOf("192.168.0.20") }, probe)).addInterceptor { host = it.request().url.host; reply(it.request()) }.build()
            client.newCall(Request.Builder().url("https://external.example/api/config").build()).execute().close()
            assertEquals("external.example", host)
            assertTrue(ServerRouteStatus.shared.get("https://external.example", "/api/version")!!.success)
            assertFalse(ServerRouteStatus.shared.get("https://external.example", "/api/version")!!.local)
        }
    }
    @Test fun interruptedReadsFallBackButCommandsAreNeverReplayed() {
        for (method in listOf("GET", "POST")) {
            val probe = OkHttpClient.Builder().addInterceptor { reply(it.request()) }.build()
            val hosts = mutableListOf<String>()
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(LocalRouteInterceptor("https://external.example", local,
                "fixture", "/api/version", { "home" to listOf("192.168.0.20") }, probe)).addInterceptor {
                hosts.add(it.request().url.host)
                if (it.request().url.host == "192.168.0.144") throw IOException("fixture disconnect")
                reply(it.request())
            }.build()
            val result = runCatching { client.newCall(Request.Builder().url("https://external.example/api/action")
                .method(method, if (method == "POST") RequestBody.create(null, ByteArray(0)) else null).build()).execute().close() }
            assertEquals(if (method == "GET") listOf("192.168.0.144", "external.example") else listOf("192.168.0.144"), hosts)
            assertEquals(method == "GET", result.isSuccess)
        }
    }
    @Test fun foreignRequestsDoNotTriggerProbeOrReceiveCredentials() {
        val probe = OkHttpClient.Builder().addInterceptor { error("Foreign request must not probe") }.build()
        val client = OkHttpClient.Builder().addInterceptor(LocalRouteInterceptor("https://external.example", local, "fixture", "/api/version",
            { "home" to listOf("192.168.0.20") }, probe)).addInterceptor {
            assertNull(it.request().header("Authorization")); reply(it.request())
        }.build()
        client.newCall(Request.Builder().url("https://unrelated.example/update").build()).execute().close()
    }
}
