package com.nvr.camnory

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.doOnAttach

/** Dedicated empty-TV screen: two remote-friendly actions, no camera or reload controls. */
internal class TvWelcomeView(context: Context, message: String?, onPhone: () -> Unit, onManual: () -> Unit) : FrameLayout(context) {
    init {
        tag = "tv-welcome"
        setBackgroundColor(Color.BLACK)
        val width = minOf(dp(380), resources.displayMetrics.widthPixels - dp(64))
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(BrandWordmarkView(context, 240, darkBackground = true), LinearLayout.LayoutParams(dp(240), -2).apply { bottomMargin = dp(24) })
            addView(TextView(context).apply {
                text = context.getString(R.string.tv_setup_heading); textSize = 28f
                setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(-1, -2))
            addView(TextView(context).apply {
                text = message ?: context.getString(R.string.tv_setup_subtitle); textSize = 16f
                setTextColor(0xffa8a8b0.toInt()); gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(28) })
        }
        val phone = button(context.getString(R.string.tv_setup_phone), R.drawable.ic_phone, onPhone).apply { tag = "tv-pair" }
        val manual = button(context.getString(R.string.tv_setup_manual), R.drawable.ic_settings_link, onManual).apply { tag = "tv-manual" }
        phone.nextFocusDownId = manual.id; phone.nextFocusUpId = manual.id
        manual.nextFocusUpId = phone.id; manual.nextFocusDownId = phone.id
        content.addView(phone, LinearLayout.LayoutParams(width, dp(54)))
        content.addView(manual, LinearLayout.LayoutParams(width, dp(50)).apply { topMargin = dp(12) })
        addView(ScrollView(context).apply {
            isFillViewport = true; isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            addView(content, LayoutParams(-1, -2))
        }, LayoutParams(-1, -1))
        doOnAttach { post { phone.requestFocus() } }
    }
    private fun button(label: String, icon: Int, action: () -> Unit) = Button(context).apply {
        id = View.generateViewId(); text = label; textSize = 17f; isAllCaps = false
        isFocusable = true; isFocusableInTouchMode = true; stateListAnimator = null
        setPadding(dp(20), 0, dp(20), 0)
        val colors = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(Color.WHITE, 0xffbdbdc5.toInt()))
        setTextColor(colors)
        val drawable = context.getDrawable(icon)?.mutate()?.apply {
            setTintList(colors); setBounds(0, 0, dp(22), dp(22))
        }
        setCompoundDrawablesRelative(drawable, null, null, null)
        compoundDrawablePadding = dp(12)
        fun border(selected: Boolean) = GradientDrawable().apply {
            setColor(if (selected) 0xff29292f.toInt() else 0xff101014.toInt())
            cornerRadius = dp(6).toFloat()
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), border(true))
            addState(intArrayOf(), border(false))
        }
        setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
