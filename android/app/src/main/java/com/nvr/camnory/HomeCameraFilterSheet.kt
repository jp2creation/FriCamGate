package com.nvr.camnory

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.*

/** Both filters are drafts until Apply; closing the sheet leaves Home unchanged. */
internal class HomeCameraFilterSheet(context: Context, private val servers: List<AppearanceSheetOption>,
    private val categories: List<AppearanceSheetOption>, initialServer: String?, initialCategory: String?,
    private val apply: (String?, String?) -> Unit, private val categoryFirst: Boolean = false
) : SelectionBottomSheet(context, context.getString(R.string.home_camera_filters)) {
    override val maxHeightFraction = 0.7f
    private var server = initialServer
    private var category = initialCategory
    private var categoryTab = categoryFirst
    private val rows = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
    private val tabs = mutableListOf<Pair<TextView, View>>()
    override fun createSheetToolbar(): View = LinearLayout(context).apply {
        listOf(context.getString(R.string.server), context.getString(R.string.camera_categories)).forEachIndexed { index, title ->
            val label = ConnectionFormUi(context).text(title, 15, Palette.text).apply { gravity = Gravity.CENTER; minimumHeight = dp(48) }
            val line = View(context)
            tabs.add(label to line)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; isFocusable = true; contentDescription = title
                tag = if (index == 0) "home-filter-tab-server" else "home-filter-tab-category"
                setOnClickListener { categoryTab = index == 1; render() }
                addView(label, LinearLayout.LayoutParams(-1, -2))
                addView(line, LinearLayout.LayoutParams(-1, dp(2)))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }
    override fun createSheetContent(): View { render(); return rows }
    private fun render() {
        tabs.forEachIndexed { index, (text, line) ->
            val selected = (index == 1) == categoryTab
            text.setTextColor(if (selected) Palette.accent else Palette.muted)
            line.setBackgroundColor(if (selected) Palette.accent else Palette.divider)
        }
        rows.removeAllViews()
        val options = if (categoryTab) categories else servers
        val selected = if (categoryTab) category else server
        options.forEach { option ->
            rows.addView(RadioButton(context).apply {
                id = View.generateViewId()
                tag = "home-filter-${if (categoryTab) "category" else "server"}-${option.value}"
                text = option.title; textSize = 15f * textScale; setTextColor(Palette.text)
                buttonTintList = ColorStateList.valueOf(Palette.accent); minHeight = dp(52)
                isChecked = option.value == (selected ?: "all")
                val categoryOption = categoryTab
                setOnCheckedChangeListener { _, checked -> if (checked) {
                    val value = option.value.takeUnless { it == "all" }
                    if (categoryOption) category = value else server = value
                } }
            }, RadioGroup.LayoutParams(-1, -2))
        }
        TvRemoteSupport.install(rows)
    }
    override fun createSheetFooter(): View = LinearLayout(context).apply {
        setPadding(dp(20), dp(8), dp(20), dp(16)); gravity = Gravity.CENTER_VERTICAL
        val ui = ConnectionFormUi(context)
        addView(ui.action(context.getString(R.string.filter_reset), false) { server = null; category = null; render() }
            .apply { tag = "home-filter-reset" }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ui.action(context.getString(R.string.device_apply)) { apply(server, category); dismiss() }
            .apply { tag = "home-filter-apply" }, LinearLayout.LayoutParams(0, -2, 1f))
    }
}
