package com.nvr.camnory

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout

internal class TvAdminView(context: Context, selected: Int, select: (Int) -> Unit, back: () -> Unit) : LinearLayout(context) {
    val content: LinearLayout
    init {
        val ui = TvUi(context)
        orientation = VERTICAL; tag = "tv-admin-v2"; setBackgroundColor(ui.background)
        setPadding(ui.dp(28), ui.dp(16), ui.dp(28), ui.dp(16))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(BrandWordmarkView(context, 150, darkBackground = true), LayoutParams(ui.dp(150), ui.dp(46)))
            addView(ui.text(context.getString(R.string.tv_admin), 22), LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(24) })
            addView(ui.icon(R.drawable.ic_arrow_back, context.getString(R.string.onvif_back), back), LayoutParams(ui.dp(48), ui.dp(44)))
        }, LayoutParams(-1, ui.dp(54)))
        val split = LinearLayout(context)
        addView(split, LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(12) })
        val navigation = ui.column()
        split.addView(ui.scroll(navigation), LayoutParams(ui.dp(190), -1).apply { marginEnd = ui.dp(24) })
        val titles = listOf(R.string.tv_servers, R.string.cameras, R.string.tv_picture_sound, R.string.tv_appearance, R.string.tv_diagnostics)
        titles.forEachIndexed { index, title ->
            navigation.addView(ui.action(context.getString(title)) { select(index) }.apply {
                tag = "tv-admin-section-$index"
                if (index == selected) setTextColor(ui.accent)
            }, LayoutParams(-1, ui.dp(54)).apply { bottomMargin = ui.dp(10) })
        }
        content = ui.column()
        content.tag = "tv-admin-content"
        split.addView(ui.scroll(content), LayoutParams(0, -1, 1f))
        post { navigation.getChildAt(selected.coerceIn(0, titles.lastIndex)).requestFocus() }
    }
}
