package com.nvr.camnory

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/** TV presentation only: pagination and focus, no network or preference ownership. */
internal class TvHistoryView(
    context: Context,
    events: List<NativeEvent>,
    private val inbox: Boolean,
    private val unread: Set<String>,
    selectedId: String?,
    private val title: (NativeEvent) -> String,
    private val camera: (NativeEvent) -> String,
    private val thumbnail: (NativeEvent, ImageView) -> Unit,
    private val onSelect: (String) -> Unit,
    private val onOpen: (NativeEvent) -> Unit
) : LinearLayout(context) {
    private val columns = if (inbox) 1 else 3
    private val textScale = Appearance.textScale(context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE).getString("textSize", null))
    private val rowsPerPage = if (inbox) (if (textScale > 1.1f) 4 else 5) else 2
    private val pages = tvHistoryPages(events, columns * rowsPerPage, ::dayKey)
    private val caption = label("", 12, Palette.muted)
    private val content = LinearLayout(context).apply { orientation = VERTICAL }
    private var cells = emptyList<View>()
    var currentPage = pages.indexOfFirst { page -> page.any { it.id == selectedId } }.coerceAtLeast(0)
        private set

    init {
        tag = "tv-history-pages"
        orientation = VERTICAL
        addView(caption, LayoutParams(-1, dp(30)))
        addView(content, LayoutParams(-1, 0, 1f))
        showPage(currentPage)
    }

    fun focusSelection(id: String?) {
        val page = pages.indexOfFirst { items -> items.any { it.id == id } }
        if (page >= 0 && page != currentPage) showPage(page)
        (cells.firstOrNull { it.tag == "${if (inbox) "tv-notification" else "tv-event"}:$id" } ?: cells.firstOrNull())?.requestFocus()
    }

    private fun showPage(index: Int) {
        currentPage = index.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        content.removeAllViews()
        val page = pages.getOrNull(currentPage).orEmpty()
        caption.text = page.firstOrNull()?.let { "${dayLabel(it)}   ·   ${currentPage + 1} / ${pages.size}" }.orEmpty()
        if (page.isEmpty()) {
            content.addView(label(context.getString(R.string.notifications_empty), 16, Palette.muted).apply { gravity = Gravity.CENTER }, LayoutParams(-1, -1))
        }
        cells = page.map(::eventCell)
        repeat(rowsPerPage) { rowIndex ->
            val row = LinearLayout(context)
            repeat(columns) { column ->
                val cell = cells.getOrNull(rowIndex * columns + column) ?: View(context)
                row.addView(cell, LayoutParams(0, -1, 1f).apply { if (column > 0) marginStart = dp(12) })
            }
            content.addView(row, LayoutParams(-1, 0, 1f).apply { if (rowIndex > 0) topMargin = dp(if (inbox) 4 else 12) })
        }
        cells.forEachIndexed { position, cell ->
            cell.setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (key) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (position + columns < cells.size) cells[position + columns].requestFocus()
                        else if (currentPage < pages.lastIndex) {
                            showPage(currentPage + 1)
                            cells.getOrNull(position % columns)?.requestFocus()
                        } else return@setOnKeyListener false
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (position >= columns) cells[position - columns].requestFocus()
                        else if (currentPage > 0) {
                            showPage(currentPage - 1)
                            cells.getOrNull((cells.lastIndex / columns * columns + position % columns).coerceAtMost(cells.lastIndex))?.requestFocus()
                        } else return@setOnKeyListener false
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (position % columns < columns - 1 && position < cells.lastIndex) cells[position + 1].requestFocus()
                        else return@setOnKeyListener false
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (position % columns > 0) cells[position - 1].requestFocus()
                        else return@setOnKeyListener false
                    }
                    else -> return@setOnKeyListener false
                }
                true
            }
        }
        TvRemoteSupport.install(this)
    }

    private fun eventCell(event: NativeEvent): View {
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Palette.black)
            event.thumbnail?.let { setImageBitmap(it) }
        }
        val metadata = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            addView(label(title(event), if (inbox) 16 else 14, Palette.text).apply { setTypeface(typeface, Typeface.BOLD) })
            addView(label("${camera(event)} · ${event.time}", 12, Palette.muted))
        }
        return LinearLayout(context).apply {
            tag = "${if (inbox) "tv-notification" else "tv-event"}:${event.id}"
            orientation = if (inbox) HORIZONTAL else VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Palette.card); cornerRadius = dp(6).toFloat() }
            clipToOutline = true
            isFocusable = true
            setTag(R.id.tv_camera_tile, true)
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            contentDescription = "${title(event)}, ${camera(event)}, ${dayLabel(event)}, ${event.time}"
            if (inbox) {
                addView(View(context).apply {
                    tag = "tv-unread:${event.id}"
                    setBackgroundColor(if (event.id in unread) Palette.accent else android.graphics.Color.TRANSPARENT)
                }, LayoutParams(dp(3), -1))
                addView(image, LayoutParams(dp(104), -1))
                addView(metadata, LayoutParams(0, -1, 1f))
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_chevron_right); setColorFilter(Palette.muted) }, LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) })
            } else {
                addView(image, LayoutParams(-1, 0, 1f))
                addView(metadata, LayoutParams(-1, (dp(48) * textScale).toInt()))
            }
            onFocusChangeListener = OnFocusChangeListener { _, focused -> if (focused) onSelect(event.id) }
            setOnClickListener { onSelect(event.id); onOpen(event) }
            if (event.thumbnail == null) post { if (image.isAttachedToWindow) thumbnail(event, image) }
        }
    }

    private fun dayKey(event: NativeEvent): String = event.startTime?.let {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(Date((it * 1000).toLong()))
    }.orEmpty()
    private fun dayLabel(event: NativeEvent): String = event.startTime?.let {
        val millis = (it * 1000).toLong()
        if (DateUtils.isToday(millis)) context.getString(R.string.notifications_today)
        else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
    }.orEmpty()
    private fun label(value: String, size: Int, color: Int) = TextView(context).apply {
        text = value; textSize = size * textScale; setTextColor(color)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
