package com.nvr.camnory

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Speech is opt-in, local-only, and owned by the visible activity panel. */
internal class ActivityBriefingSheet(
    context: Context,
    private val summary: String,
    private val details: View,
    private val sentences: List<String>,
    private val acknowledge: () -> Unit,
    private val review: () -> Unit
) : SelectionBottomSheet(context, context.getString(R.string.recent_activity)) {
    override val maxHeightFraction = 0.70f
    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var initializing = false
    private var speaking = false
    private var closed = false
    private var sentence = 0
    private var request = 0
    private lateinit var play: ImageButton
    private lateinit var status: TextView

    override fun createSheetContent(): View {
        val ui = ConnectionFormUi(context)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(12))
            addView(ui.text(context.getString(R.string.activity_period), 12))
            addView(ui.text(summary, 15, Palette.text).apply { setPadding(0, dp(12), 0, dp(14)) })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = ui.outline()
                play = control(R.drawable.ic_play_outline, R.string.activity_listen) { toggleSpeech() }
                addView(play)
                status = ui.text(context.getString(R.string.activity_listen), 14, Palette.text)
                addView(status, LinearLayout.LayoutParams(0, -2, 1f))
                addView(control(R.drawable.ic_stop, R.string.activity_stop) {
                    request++; engine?.stop(); speaking = false; sentence = 0; updateSpeech()
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
            addView(details)
        }
    }

    override fun createSheetFooter(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), 0, dp(20), dp(12))
        val ui = ConnectionFormUi(context)
        addView(ui.action(context.getString(R.string.activity_review_all)) { dismiss(); review() })
        addView(ui.action(context.getString(R.string.activity_mark_read), false) { dismiss(); acknowledge() })
    }

    private fun control(icon: Int, title: Int, action: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon); setColorFilter(Palette.text); setBackgroundColor(Color.TRANSPARENT)
        contentDescription = context.getString(title)
        setPadding(dp(13), dp(13), dp(13), dp(13))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(52))
        setOnClickListener { action() }
    }

    private fun toggleSpeech() {
        if (closed || initializing) return
        if (speaking) {
            request++; engine?.stop(); speaking = false; updateSpeech(); return
        }
        if (ready) { speak(); return }
        initializing = true; play.isEnabled = false
        status.setText(R.string.activity_voice_loading)
        engine = TextToSpeech(context.applicationContext) { result ->
            handler.post {
                if (closed) return@post
                initializing = false; play.isEnabled = true
                val tts = engine
                val locale = context.resources.configuration.locales[0]
                val voice = if (result == TextToSpeech.SUCCESS) runCatching {
                    tts?.voices?.filter { !it.isNetworkConnectionRequired && it.locale.language == locale.language
                        && "notInstalled" !in it.features.orEmpty() }
                        ?.sortedByDescending { it.locale == locale }?.firstOrNull()
                }.getOrNull() else null
                if (voice == null || tts == null || tts.setVoice(voice) == TextToSpeech.ERROR) {
                    unavailable(); return@post
                }
                tts.setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                ready = true
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        handler.post {
                            if (closed || !speaking || utteranceId != request.toString()) return@post
                            sentence++
                            if (sentence < sentences.size) speak() else {
                                speaking = false; sentence = 0; updateSpeech()
                            }
                        }
                    }
                    @Deprecated("Required by Android")
                    override fun onError(utteranceId: String?) {
                        handler.post { if (!closed && utteranceId == request.toString()) unavailable() }
                    }
                })
                speak()
            }
        }
    }

    private fun speak() {
        if (closed || sentences.isEmpty()) return
        speaking = true; request++; updateSpeech()
        if (engine?.speak(sentences[sentence].take(TextToSpeech.getMaxSpeechInputLength()),
                TextToSpeech.QUEUE_FLUSH, null, request.toString()) == TextToSpeech.ERROR) unavailable()
    }

    private fun updateSpeech() {
        play.setImageResource(if (speaking) R.drawable.ic_pause_outline else R.drawable.ic_play_outline)
        play.contentDescription = context.getString(if (speaking) R.string.activity_pause else R.string.activity_listen)
        status.setText(if (speaking) R.string.activity_listening else R.string.activity_listen)
    }

    private fun unavailable() {
        ready = false; speaking = false; initializing = false
        engine?.stop(); engine?.shutdown(); engine = null
        play.isEnabled = true; updateSpeech()
        status.setText(R.string.activity_voice_unavailable)
        Toast.makeText(context, R.string.activity_voice_unavailable, Toast.LENGTH_LONG).show()
    }

    override fun dismiss() {
        closed = true; request++; handler.removeCallbacksAndMessages(null)
        engine?.stop(); engine?.shutdown(); engine = null
        super.dismiss()
    }
}
