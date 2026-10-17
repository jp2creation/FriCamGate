package com.nvr.camnory

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

internal data class TvCameraTile(val key: String, val name: String)

/** Source rendering is injected so focus/layout can be tested without real cameras. */
internal class TvDashboardView(context: Context, cameras: List<TvCameraTile>, primary: String?, page: Int,
    render: (FrameLayout, TvCameraTile, Boolean) -> Unit,
    select: (String) -> Unit, fullscreen: () -> Unit, settings: () -> Unit, admin: () -> Unit, activity: () -> Unit,
    reload: () -> Unit, changePage: (Int) -> Unit) : LinearLayout(context) {
    private val ui = TvUi(context)
    init {
        orientation = VERTICAL; setBackgroundColor(ui.background); setPadding(ui.dp(28), ui.dp(16), ui.dp(28), ui.dp(16))
        tag = "tv-dashboard-v2"
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(BrandWordmarkView(context, 150, darkBackground = true), LayoutParams(ui.dp(150), ui.dp(46)))
            addView(Space(context), LayoutParams(0, 1, 1f))
            addView(ui.action(context.getString(R.string.events_tab), activity).apply { tag = "tv-open-events" }, LayoutParams(-2, ui.dp(44)).apply { marginEnd = ui.dp(12) })
            addView(ui.icon(R.drawable.ic_refresh, context.getString(R.string.reload), reload), LayoutParams(ui.dp(46), ui.dp(44)))
            addView(ui.action(context.getString(R.string.tv_admin), admin).apply { tag = "tv-open-admin" }, LayoutParams(-2, ui.dp(44)).apply { marginStart = ui.dp(12) })
        }, LayoutParams(-1, ui.dp(52)))
        val selected = cameras.firstOrNull { it.key == TvWorkspace.primary(cameras.map { c -> c.key }, primary) }
        if (selected == null) {
            addView(ui.text(context.getString(R.string.no_cameras)), LayoutParams(-1, -2))
        } else {
        val content = LinearLayout(context)
        addView(content, LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(12) })
        val main = ui.column()
        content.addView(main, LayoutParams(0, -1, 7f))
        val video = FrameLayout(context).apply {
            tag = "tv-primary"; id = View.generateViewId(); contentDescription = selected.name
            setPadding(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2)); ui.focus(this); setOnClickListener { fullscreen() }
        }
        main.addView(video, LayoutParams(-1, 0, 1f)); render(video, selected, true)
        main.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.text(selected.name, 18).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LayoutParams(0, -2, 1f))
            addView(ui.icon(R.drawable.ic_fullscreen, context.getString(R.string.camera_fullscreen), fullscreen).apply { tag = "tv-primary-fullscreen" }, LayoutParams(ui.dp(48), ui.dp(44)))
            addView(ui.icon(R.drawable.ic_settings, context.getString(R.string.camera_all_settings), settings).apply { tag = "tv-primary-settings" }, LayoutParams(ui.dp(48), ui.dp(44)).apply { marginStart = ui.dp(6) })
        }, LayoutParams(-1, ui.dp(50)))
        val alternatives = cameras.filter { it.key != selected.key }
        if (alternatives.isNotEmpty()) {
            val side = ui.column()
            content.addView(side, LayoutParams(0, -1, 3f).apply { marginStart = ui.dp(16) })
            val currentPage = page.coerceIn(0, (alternatives.size - 1) / 3)
            alternatives.drop(currentPage * 3).take(3).forEach { camera ->
                val tile = FrameLayout(context).apply {
                    tag = "tv-camera:${camera.key}"; id = View.generateViewId()
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    contentDescription = camera.name; setPadding(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2))
                    ui.focus(this); setOnClickListener { select(camera.key) }
                    nextFocusLeftId = video.id
                }
                side.addView(tile, LayoutParams(-1, 0, 1f).apply { bottomMargin = ui.dp(8) })
                render(tile, camera, false)
                tile.addView(ui.text(camera.name, 14).apply {
                    setBackgroundColor(0xb8000000.toInt()); setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            }
            if (alternatives.size > 3) side.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(ui.icon(R.drawable.ic_arrow_back, context.getString(R.string.tv_previous_page)) { changePage(currentPage - 1) }.apply { isEnabled = currentPage > 0 }, LayoutParams(ui.dp(44), ui.dp(40)))
                addView(ui.text("${currentPage + 1}/${(alternatives.size + 2) / 3}", 13), LayoutParams(0, -2, 1f))
                addView(ui.icon(R.drawable.ic_chevron_right, context.getString(R.string.tv_next_page)) { changePage(currentPage + 1) }.apply { isEnabled = (currentPage + 1) * 3 < alternatives.size }, LayoutParams(ui.dp(44), ui.dp(40)))
            }, LayoutParams(-1, ui.dp(40)))
        }
        post { video.requestFocus() }
        }
    }
}
