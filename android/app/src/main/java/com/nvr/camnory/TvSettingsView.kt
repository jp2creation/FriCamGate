package com.nvr.camnory

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*

internal data class TvSettingsSection(val key: String, val title: String, val icon: Int, val open: () -> Unit)

/** Keeps the existing settings pages inside a remote-friendly two-pane workspace. */
internal class TvSettingsView(context: Context, brand: View, title: String, content: View,
    sections: List<TvSettingsSection>, selected: String, backTitle: String, back: () -> Unit) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float) = TextView(context).apply {
        text = value; textSize = size; setTextColor(Palette.text)
    }
    private fun background(selected: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(6).toFloat()
        setColor(if (selected) Palette.card else android.graphics.Color.TRANSPARENT)
    }
    init {
        tag = "tv-settings-layout"
        orientation = VERTICAL
        setPadding(dp(24), dp(12), dp(24), dp(12))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(brand, LayoutParams(dp(150), dp(48)))
            addView(text(title, 24f).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(24), 0, 0, 0)
            }, LayoutParams(0, -2, 1f))
        }, LayoutParams(-1, dp(56)))
        val body = LinearLayout(context).apply { orientation = HORIZONTAL }
        addView(body, LayoutParams(-1, 0, 1f))
        val sidebar = LinearLayout(context).apply {
            tag = "tv-settings-sidebar"; orientation = VERTICAL
            setPadding(0, dp(8), dp(20), 0)
        }
        body.addView(sidebar, LayoutParams(dp(246), -1))
        val entries = LinearLayout(context).apply { orientation = VERTICAL }
        sidebar.addView(ScrollView(context).apply {
            isFillViewport = false; isVerticalScrollBarEnabled = false
            addView(entries, FrameLayout.LayoutParams(-1, -2))
        }, LayoutParams(-1, 0, 1f))
        content.tag = content.tag ?: "tv-settings-page"
        if (content is ScrollView) {
            content.getChildAt(0)?.layoutParams = FrameLayout.LayoutParams(-1, -2)
            content.isVerticalScrollBarEnabled = false
        }
        val detail = FrameLayout(context).apply {
            tag = "tv-settings-detail"
            addView(content, FrameLayout.LayoutParams(-1, -1))
        }
        body.addView(detail, LayoutParams(0, -1, 1f))
        var selectedRow: View? = null
        fun firstAction(view: View): View? {
            if (view.visibility != VISIBLE || !view.isEnabled) return null
            if (view.isFocusable && view.isClickable) return view
            if (view is ViewGroup) repeat(view.childCount) { firstAction(view.getChildAt(it))?.let { return it } }
            return null
        }
        sections.forEach { section ->
            val active = section.key == selected
            val row = LinearLayout(context).apply {
                tag = "tv-settings-${section.key}"; id = View.generateViewId()
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true; isClickable = true; isSelected = active
                contentDescription = section.title
                minimumHeight = dp(36)
                setPadding(0, dp(6), 0, dp(6))
                background = background(active)
                addView(View(context).apply { setBackgroundColor(if (active) Palette.accent else android.graphics.Color.TRANSPARENT) }, LayoutParams(dp(3), dp(26)))
                addView(ImageView(context).apply {
                    setImageResource(section.icon); setColorFilter(Palette.text)
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(dp(20), dp(20)).apply { marginStart = dp(12); marginEnd = dp(12) })
                addView(text(section.title, 15f).apply {
                    maxLines = 2
                    if (active) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }, LayoutParams(0, -2, 1f))
                setOnClickListener {
                    if (active) firstAction(content)?.requestFocus() else section.open()
                }
                setOnKeyListener { _, key, event ->
                    if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        if (event.action == KeyEvent.ACTION_DOWN) firstAction(content)?.requestFocus()
                        true
                    } else false
                }
            }
            entries.addView(row, LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
            if (active) selectedRow = row
        }
        sidebar.addView(LinearLayout(context).apply {
            tag = "tv-settings-back"; gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            setPadding(dp(12), 0, 0, 0)
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_arrow_back); setColorFilter(Palette.muted) }, LayoutParams(dp(20), dp(20)))
            addView(text(backTitle, 14f).apply { setTextColor(Palette.muted); setPadding(dp(12), 0, 0, 0) })
            setOnClickListener { back() }
        }, LayoutParams(-1, dp(42)))
        // Left always returns to the selected section, even from a deeply nested switch.
        fun linkActions(view: View) {
            if (view.isFocusable && view.isClickable && selectedRow != null) view.nextFocusLeftId = selectedRow!!.id
            if (view is ViewGroup) repeat(view.childCount) { linkActions(view.getChildAt(it)) }
        }
        linkActions(content)
        val accents = mutableListOf<View>()
        fun collectAccents(view: View) {
            if ((view.tag as? String)?.startsWith("appearance-accent-") == true) accents.add(view)
            if (view is ViewGroup) repeat(view.childCount) { collectAccents(view.getChildAt(it)) }
        }
        collectAccents(content)
        accents.forEach { it.id = View.generateViewId() }
        accents.forEachIndexed { index, swatch ->
            if (index > 0) swatch.nextFocusLeftId = accents[index - 1].id
            swatch.nextFocusRightId = accents.getOrNull(index + 1)?.id ?: swatch.id
        }
        post { selectedRow?.requestFocus() }
    }
}
