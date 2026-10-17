package com.nvr.camnory

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** TV-only tokens and remote focus treatment; mobile appearance is not changed. */
internal class TvUi(val context: Context) {
    val prefs = context.getSharedPreferences("camnory-tv-appearance", Context.MODE_PRIVATE)
    val accent get() = Appearance.accent(prefs.getString("accent", null))
    val background get() = if (prefs.getBoolean("black", true)) Color.BLACK else 0xff141416.toInt()
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun text(value: String, size: Int = 16, muted: Boolean = false) = TextView(context).apply {
        text = value; textSize = size * if (prefs.getBoolean("largeText", false)) 1.12f else 1f
        setTextColor(if (muted) 0xffb8b8c0.toInt() else Color.WHITE)
    }
    fun focus(view: View) {
        view.isFocusable = true; view.isFocusableInTouchMode = true
        fun draw(selected: Boolean) = GradientDrawable().apply {
            setColor(0xff19191d.toInt()); cornerRadius = dp(6).toFloat()
            setStroke(dp(if (selected) 2 else 1), if (selected) accent else 0xff34343a.toInt())
        }
        view.background = draw(false)
        view.setOnFocusChangeListener { target, selected -> target.background = draw(selected) }
    }
    fun icon(icon: Int, title: String, click: () -> Unit) = ImageButton(context).apply {
        id = View.generateViewId(); setImageResource(icon); setColorFilter(Color.WHITE); contentDescription = title
        setPadding(dp(12), dp(12), dp(12), dp(12)); focus(this)
        androidx.appcompat.widget.TooltipCompat.setTooltipText(this, title); setOnClickListener { click() }
    }
    fun action(title: String, click: () -> Unit) = Button(context).apply {
        id = View.generateViewId(); text = title; textSize = 15f; isAllCaps = false; setTextColor(Color.WHITE)
        minimumHeight = dp(48); setPadding(dp(14), dp(6), dp(14), dp(6)); focus(this); setOnClickListener { click() }
    }
    fun row(title: String, value: String = "", click: (() -> Unit)? = null): LinearLayout = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56); setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(text(title).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(value, 14, true).apply {
            gravity = Gravity.END; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (click != null) { focus(this); setOnClickListener { click() } }
    }
    fun toggle(title: String, checked: Boolean, enabled: Boolean = true, changed: (Boolean) -> Unit): View {
        val toggle = androidx.appcompat.widget.SwitchCompat(context).apply {
            isChecked = checked; isEnabled = enabled; contentDescription = title
            val colors = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, 0xff777780.toInt()))
            buttonTintList = colors; thumbTintList = colors
            trackTintList = android.content.res.ColorStateList.valueOf(0xff45454e.toInt())
            setOnCheckedChangeListener { _, value -> changed(value) }
            if (enabled) focus(this)
        }
        return LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(58); alpha = if (enabled) 1f else 0.45f
            addView(text(title), LinearLayout.LayoutParams(0, -2, 1f)); addView(toggle)
        }
    }
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun scroll(body: View) = ScrollView(context).apply { isFillViewport = false; addView(body); isVerticalScrollBarEnabled = false }
}
