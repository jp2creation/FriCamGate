package com.nvr.camnory

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

internal fun tvCameraTileHeight(viewport: Int, gap: Int, columns: Int, count: Int): Int {
    val rows = ((count + columns - 1) / columns).coerceAtLeast(1)
    val visibleRows = minOf(rows, if (columns == 1) 1 else 2)
    return ((viewport - gap * (visibleRows - 1)) / visibleRows).coerceAtLeast(1)
}

internal class TvCameraScrollView(context: Context) : ScrollView(context) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect) = 0
}

/** Fixed pages: no following row can peek into the visible page. */
internal class TvCameraGrid(context: Context, private val columns: Int, private val tiles: List<View>,
    private val onSelection: (Int?) -> Unit = {}) : LinearLayout(context) {
    private val gap = dp(8)
    private val rowsPerPage = if (columns == 1) 1 else 2
    private val pageSize = columns * rowsPerPage
    private val pageCount = ((tiles.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    private val body = LinearLayout(context).apply { orientation = VERTICAL }
    private val dots = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER }
    private var cellHeight = 0
    var currentPage = 0
        private set

    init {
        tag = "tv-camera-grid"
        orientation = HORIZONTAL
        addView(body, LayoutParams(0, -2, 1f))
        addView(dots, LayoutParams(dp(22), -1))
        dots.tag = "tv-page-dots"
        dots.visibility = if (pageCount > 1) VISIBLE else GONE
        repeat(pageCount) { page ->
            dots.addView(View(context).apply {
                tag = "tv-page-dot-$page"
                contentDescription = "${page + 1} / $pageCount"
            }, LayoutParams(dp(6), dp(6)).apply { topMargin = dp(6); bottomMargin = dp(6); gravity = Gravity.CENTER_HORIZONTAL })
        }
        tiles.chunked(columns).forEachIndexed { rowIndex, rowTiles ->
            body.addView(LinearLayout(context).apply {
                repeat(columns) { column ->
                    addView(rowTiles.getOrNull(column) ?: View(context), LayoutParams(0, -1, 1f).apply {
                        if (column > 0) marginStart = gap
                    })
                }
            }, LayoutParams(-1, 1).apply { if (rowIndex % rowsPerPage > 0) topMargin = gap })
        }
        showPage(0)
        tiles.forEachIndexed { index, tile ->
            tile.isFocusable = true
            tile.setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val next = when (key) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (index / columns < (tiles.size - 1) / columns) minOf(index + columns, tiles.lastIndex) else index
                    KeyEvent.KEYCODE_DPAD_UP -> (index - columns).coerceAtLeast(index % columns)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (index % columns < columns - 1 && index < tiles.lastIndex) index + 1 else index
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (index % columns > 0) index - 1 else return@setOnKeyListener false
                    else -> return@setOnKeyListener false
                }
                if (next != index) { showPage(next / pageSize); tiles[next].requestFocus() }
                true
            }
            val previous = tile.onFocusChangeListener
            tile.onFocusChangeListener = OnFocusChangeListener { view, focused ->
                previous?.onFocusChange(view, focused)
                if (focused) { showPage(index / pageSize); onSelection(index) }
                else post { if (tiles.none { it.hasFocus() }) onSelection(null) }
            }
        }
    }

    private fun showPage(page: Int) {
        currentPage = page.coerceIn(0, pageCount - 1)
        repeat(body.childCount) { row ->
            body.getChildAt(row).visibility = if (row / rowsPerPage == currentPage) VISIBLE else GONE
        }
        repeat(dots.childCount) { index ->
            dots.getChildAt(index).apply {
                isSelected = index == currentPage
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (index == currentPage) Palette.accent else Palette.muted)
                }
                alpha = if (isSelected) 1f else 0.35f
            }
        }
    }

    fun attachToViewport(scroll: ScrollView, column: LinearLayout) {
        fun fit() { fitViewport(scroll.height - scroll.paddingTop - scroll.paddingBottom - top - column.paddingBottom) }
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
    }
    fun fitViewport(height: Int) {
        val available = height.coerceAtLeast(1)
        if (minimumHeight != available) minimumHeight = available
        val next = tvCameraTileHeight(available, gap, columns, tiles.size)
        if (cellHeight == next) return
        cellHeight = next
        repeat(body.childCount) { row -> body.getChildAt(row).layoutParams = body.getChildAt(row).layoutParams.apply { this.height = next } }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
