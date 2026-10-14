package fr.degert.fricamgate

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class NativeChatApi(client: OkHttpClient) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .readTimeout(120, TimeUnit.SECONDS).callTimeout(3, TimeUnit.MINUTES).build()

    fun send(baseUrl: String, token: String, messages: JSONArray, onDelta: (String) -> Unit,
             onDone: (JSONArray) -> Unit, onError: (Throwable) -> Unit): Call {
        val payload = JSONObject().put("messages", messages).put("stream", true)
        val request = Request.Builder().url("${normalizeBaseUrl(baseUrl)}/api/chat/completion")
            .header("Authorization", "Bearer $token").header("Accept", "application/x-ndjson, application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        return http.newCall(request).also { call ->
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (!call.isCanceled()) onError(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                            val body = it.body ?: throw IOException("Empty response")
                            if (body.contentType()?.subtype == "json") {
                                val result = JSONObject(body.string())
                                if (result.has("error")) throw IOException("Chat error")
                                val text = result.optJSONObject("message")?.optString("content").orEmpty()
                                if (text.isBlank()) throw IOException("Empty response")
                                onDelta(text)
                                onDone(result.optJSONArray("messages") ?: JSONArray(messages.toString()).put(
                                    JSONObject().put("role", "assistant").put("content", text)))
                                return
                            }
                            val answer = StringBuilder()
                            var chain: JSONArray? = null
                            var size = 0L
                            while (!call.isCanceled()) {
                                val line = body.source().readUtf8Line() ?: break
                                size += line.length
                                if (size > 4_000_000) throw IOException("Response too large")
                                if (line.isBlank()) continue
                                val event = JSONObject(line)
                                when (event.optString("type")) {
                                    "content" -> {
                                        val delta = event.optString("delta")
                                        answer.append(delta)
                                        onDelta(delta)
                                    }
                                    "messages" -> chain = event.getJSONArray("messages")
                                    "error" -> throw IOException("Chat error")
                                    "done" -> {
                                        onDone(chain ?: JSONArray(messages.toString()).put(
                                            JSONObject().put("role", "assistant").put("content", answer.toString())))
                                        return
                                    }
                                }
                            }
                            if (!call.isCanceled()) throw IOException("Interrupted response")
                        }
                    } catch (error: Exception) { if (!call.isCanceled()) onError(error) }
                }
            })
        }
    }
}
