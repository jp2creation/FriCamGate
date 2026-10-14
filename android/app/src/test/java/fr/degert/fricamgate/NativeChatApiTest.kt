package fr.degert.fricamgate

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativeChatApiTest {
    @Test fun streamsContentAndPreservesConversationChain() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/x-ndjson").setBody(
                "{\"type\":\"content\",\"delta\":\"Bonjour\"}\n" +
                "{\"type\":\"messages\",\"messages\":[{\"role\":\"assistant\",\"content\":\"Bonjour\"}]}\n" +
                "{\"type\":\"done\"}\n"))
            val done = CountDownLatch(1)
            var answer = ""
            var history: JSONArray? = null
            var failure: Throwable? = null
            NativeChatApi(OkHttpClient()).send(server.url("/nvr").toString(), "test-token",
                JSONArray().put(JSONObject().put("role", "user").put("content", "Salut")),
                { answer += it }, { history = it; done.countDown() }, { failure = it; done.countDown() })
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertNull(failure)
            assertEquals("Bonjour", answer)
            assertEquals("Bonjour", history!!.getJSONObject(0).getString("content"))
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/nvr/api/chat/completion", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
            assertTrue(JSONObject(request.body.readUtf8()).getBoolean("stream"))
        }
    }

    @Test fun incompleteStreamIsNotReportedAsSuccess() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"type\":\"content\",\"delta\":\"partial\"}\n"))
            val done = CountDownLatch(1)
            var succeeded = false
            NativeChatApi(OkHttpClient()).send(server.url("/").toString(), "test", JSONArray(), {},
                { succeeded = true; done.countDown() }, { done.countDown() })
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertFalse(succeeded)
        }
    }

    @Test fun unauthorizedResponseIsAnError() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val done = CountDownLatch(1)
            var failure: Throwable? = null
            NativeChatApi(OkHttpClient()).send(server.url("/").toString(), "test", JSONArray(), {},
                { done.countDown() }, { failure = it; done.countDown() })
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals("HTTP 401", failure?.message)
        }
    }

    @Test fun acceptsNonStreamingJsonFallback() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"message\":{\"role\":\"assistant\",\"content\":\"Hello\"}}"))
            val done = CountDownLatch(1)
            var history: JSONArray? = null
            NativeChatApi(OkHttpClient()).send(server.url("/").toString(), "test", JSONArray(), {},
                { history = it; done.countDown() }, { done.countDown() })
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals("Hello", history!!.getJSONObject(0).getString("content"))
        }
    }
}
