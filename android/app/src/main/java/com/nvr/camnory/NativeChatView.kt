package com.nvr.camnory

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import okhttp3.Call
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal class NativeChatView(context: Context, private val baseUrl: String, private val token: String, username: String, client: OkHttpClient) : LinearLayout(context) {
    private val api = NativeChatApi(client)
    private val store = context.getSharedPreferences("camgate-chat", Context.MODE_PRIVATE)
    private val historyKey = MessageDigest.getInstance("SHA-256")
        .digest("$baseUrl\n$username".toByteArray()).joinToString("") { "%02x".format(it) }
    private var messages = runCatching { JSONArray(store.getString(historyKey, "[]")) }.getOrDefault(JSONArray())
    private var request: Call? = null
    private var generation = 0
    private var busy = false
    private val list = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(12)) }
    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(list) }
    private val status = text("", 12, Palette.muted).apply { setPadding(dp(16), dp(4), dp(16), dp(8)) }
    private val input = EditText(context).apply {
        hint = context.getString(R.string.chat_message)
        setTextColor(Palette.text)
        setHintTextColor(Palette.muted)
        textSize = 15f * scale()
        background = shape(Palette.surface)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        minLines = 1
        maxLines = 4
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND
    }
    private val sendIcon = ImageView(context)
    private val send = FrameLayout(context).apply {
        background = shape(Palette.accent)
        isClickable = true
        isFocusable = true
        addView(sendIcon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        setOnClickListener { if (busy) stop() else submit() }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Palette.background)
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(12), dp(8))
            addView(text(context.getString(R.string.ai_chat), 21, Palette.text).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(FrameLayout(context).apply {
                contentDescription = context.getString(R.string.chat_clear)
                isFocusable = true
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_delete)
                    setColorFilter(Palette.muted)
                }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                setOnClickListener {
                    AlertDialog.Builder(context).setMessage(R.string.chat_clear_confirm)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.clear) { _, _ ->
                            stop()
                            messages = JSONArray()
                            store.edit().remove(historyKey).apply()
                            renderHistory()
                        }.show()
                }
            }, LayoutParams(dp(48), dp(48)))
        })
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(8), dp(12), dp(12))
            addView(input, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(send, LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshSend() }
            override fun afterTextChanged(s: Editable?) {}
        })
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND && !busy) { submit(); true } else false
        }
        renderHistory()
        refreshSend()
    }

    private fun renderHistory() {
        list.removeAllViews()
        status.text = ""
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            val role = message.optString("role")
            val content = message.opt("content")
            if (role in listOf("user", "assistant") && content is String && content.isNotBlank()) bubble(content, role == "user")
        }
        if (list.childCount == 0) status.setText(R.string.chat_welcome)
        bottom()
    }

    private fun bubble(value: String, user: Boolean): TextView {
        val label = text(value, 15, Palette.text).apply {
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = shape(if (user) Palette.surface else Palette.card)
            setTextIsSelectable(true)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        list.addView(FrameLayout(context).apply {
            setPadding(if (user) dp(32) else 0, 0, if (user) 0 else dp(32), dp(12))
            addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                if (user) Gravity.END else Gravity.START))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return label
    }

    private fun submit() {
        val value = input.text.toString().trim()
        if (busy || value.isBlank()) return
        val pending = JSONArray(messages.toString()).put(JSONObject().put("role", "user").put("content", value))
        bubble(value, true)
        val reply = bubble(context.getString(R.string.chat_thinking), false)
        val answer = StringBuilder()
        val activeGeneration = ++generation
        input.setText("")
        busy = true
        refreshSend()
        status.setText(R.string.chat_thinking)
        bottom()
        request = api.send(baseUrl, token, pending, { delta ->
            post {
                if (generation == activeGeneration) {
                    val follow = !scroll.canScrollVertically(1)
                    answer.append(delta)
                    reply.text = answer.toString()
                    if (follow) bottom()
                }
            }
        }, { chain ->
            post {
                if (generation == activeGeneration) {
                    messages = chain
                    saveHistory()
                    busy = false
                    request = null
                    refreshSend()
                    val finalMessage = (chain.length() - 1 downTo 0).mapNotNull { chain.optJSONObject(it) }
                        .firstOrNull { it.optString("role") == "assistant" && it.opt("content") is String }
                        ?.optString("content").orEmpty()
                    reply.text = finalMessage.ifBlank { answer.toString().ifBlank { context.getString(R.string.chat_failed) } }
                    status.text = ""
                }
            }
        }, { error ->
            post {
                if (generation == activeGeneration) {
                    busy = false
                    request = null
                    refreshSend()
                    reply.text = context.getString(R.string.chat_failed)
                    status.setText(if (error.message == "HTTP 404") R.string.chat_unsupported else R.string.chat_failed)
                    input.setText(value)
                }
            }
        })
    }

    private fun saveHistory() {
        val json = messages.toString()
        // Do not persist potentially large multimodal tool results indefinitely.
        if (json.length <= 1_000_000) store.edit().putString(historyKey, json).apply()
        else store.edit().remove(historyKey).apply()
    }

    private fun stop() {
        generation++
        request?.cancel()
        request = null
        busy = false
        refreshSend()
        renderHistory()
    }

    override fun onDetachedFromWindow() {
        generation++
        request?.cancel()
        request = null
        super.onDetachedFromWindow()
    }

    private fun refreshSend() {
        send.isEnabled = busy || input.text.isNotBlank()
        send.alpha = if (send.isEnabled) 1f else 0.4f
        send.contentDescription = context.getString(if (busy) R.string.chat_stop else R.string.chat_send)
        sendIcon.setImageResource(if (busy) R.drawable.ic_stop else R.drawable.ic_arrow_up)
        sendIcon.setColorFilter(android.graphics.Color.WHITE)
    }

    private fun bottom() { scroll.post { scroll.scrollTo(0, list.height) } }
    private fun scale() = Appearance.textScale(context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE).getString("textSize", null))
    private fun text(value: String, size: Int, color: Int) = TextView(context).apply {
        text = value; textSize = size * scale(); setTextColor(color); includeFontPadding = false
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(8).toFloat() }
}
