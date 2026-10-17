package com.nvr.camnory

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import okhttp3.OkHttpClient

/** Chat overlays the current screen; its composer remains outside the scrolling history. */
internal class NativeChatDialog(context: Context, private val server: FrigateServer, private val client: OkHttpClient) : Dialog(context) {
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private var chatView: NativeChatView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body = object : SheetDragLayout(context, { dismiss() }) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val screen = resources.displayMetrics.heightPixels
                val desired = (chatView?.preferredHeight(View.MeasureSpec.getSize(widthMeasureSpec)) ?: dp(220)) + dp(24) + paddingBottom
                val limit = minOf((screen * 0.85f).toInt(), View.MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: screen)
                val height = chatPanelHeight(screen, limit, desired)
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            }
        }.apply {
            tag = "chat-popup"
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            background = GradientDrawable().apply {
                setColor(Palette.background)
                cornerRadii = floatArrayOf(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), 0f, 0f, 0f, 0f)
                setStroke(dp(1), Palette.border)
            }
            clipToOutline = true
        }
        body.addView(FrameLayout(context).apply {
            addView(View(context).apply {
                background = GradientDrawable().apply { setColor(Palette.muted); cornerRadius = dp(2).toFloat() }
            }, FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER))
        }, LinearLayout.LayoutParams(-1, dp(24)))
        val chat = NativeChatView(context, server.baseUrl, server.token, server.username, client) { dismiss() }
        chatView = chat
        body.scrollView = chat.messageScroll
        body.addView(chat, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(body) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        setContentView(body)
        body.requestFocus()
        setCanceledOnTouchOutside(true)
        window?.apply {
            WindowCompat.setDecorFitsSystemWindows(this, false)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.45f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if (!context.getSharedPreferences("camgate-settings", 0).getBoolean("reducedMotion", false))
                attributes = attributes.apply { windowAnimations = R.style.AppearanceSheetAnimation }
        }
        ViewCompat.requestApplyInsets(body)
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(-1, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

internal fun chatPanelHeight(screen: Int, available: Int, desired: Int): Int =
    desired.coerceAtLeast((screen * 0.33f).toInt()).coerceAtMost(minOf(available, (screen * 0.85f).toInt()))
