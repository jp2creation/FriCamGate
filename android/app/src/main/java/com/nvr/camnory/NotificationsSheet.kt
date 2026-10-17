package com.nvr.camnory

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat

internal class NotificationsSheet(
    context: Context,
    private val events: List<NativeEvent>,
    unreadIds: Set<String>,
    private val title: (NativeEvent) -> String,
    private val cameraName: (NativeEvent) -> String,
    private val narrative: (NativeEvent) -> String,
    private val thumbnail: (NativeEvent, ImageView) -> Unit,
    private val onReadAll: (List<NativeEvent>) -> Unit,
    private val onOpen: (NativeEvent) -> Unit,
    private val onHistory: () -> Unit,
    private val onSettings: () -> Unit
) : SelectionBottomSheet(context, context.getString(R.string.notifications)) {
    private val unread = unreadIds.toMutableSet()
    private var kind = EventKind.Any
    private lateinit var rows: LinearLayout
    private lateinit var count: TextView
    private lateinit var readAll: TextView
    private val tabs = mutableMapOf<EventKind, TextView>()

    private fun label(value: String, size: Float, color: Int = Palette.text) = TextView(context).apply {
        text = value; textSize = size * textScale; setTextColor(color)
    }
    private fun updateCount() {
        count.text = context.resources.getQuantityString(R.plurals.notifications_unread, unread.size, unread.size)
        readAll.isEnabled = unread.isNotEmpty()
        readAll.alpha = if (readAll.isEnabled) 1f else 0.45f
    }
    override fun createHeaderAccessory(): View = label("", 12f, Palette.accent).apply { count = this }

    override fun createSheetToolbar(): View {
        val toolbar = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        toolbar.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(8), 0)
            addView(label(context.getString(R.string.notifications_read_all), 14f, Palette.muted).apply {
                readAll = this
                tag = "notifications-read-all"
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(48)
                isFocusable = true
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_selection_check, 0, 0, 0)
                compoundDrawablePadding = dp(10)
                compoundDrawablesRelative.filterNotNull().forEach { it.setTint(Palette.muted) }
                setOnClickListener {
                    onReadAll(events.filter { it.id in unread })
                    unread.clear()
                    renderRows()
                    updateCount()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(FrameLayout(context).apply {
                tag = "notifications-settings"
                contentDescription = context.getString(R.string.notifications)
                isFocusable = true
                setOnClickListener { dismiss(); onSettings() }
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_settings); setColorFilter(Palette.muted)
                }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        })
        val tabRow = LinearLayout(context)
        val labels = listOf(R.string.notifications_all, R.string.notifications_people, R.string.notifications_vehicles, R.string.notifications_animals)
        EventKind.values().forEachIndexed { index, option ->
            val cell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            cell.addView(label(context.getString(labels[index]), 13f).apply {
                tabs[option] = this
                tag = "notifications-filter-${option.name}"
                gravity = Gravity.CENTER
                isFocusable = true
                setPadding(dp(16), 0, dp(16), 0)
                minHeight = dp(48)
                setOnClickListener { kind = option; renderRows() }
            }, LinearLayout.LayoutParams(-2, -2))
            tabRow.addView(cell)
        }
        toolbar.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) })
        updateCount()
        return toolbar
    }

    override fun createSheetContent(): View {
        rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "notifications-rows"
            setPadding(dp(20), 0, dp(20), 0)
        }
        renderRows()
        return rows
    }

    private fun renderRows() {
        tabs.forEach { (option, tab) ->
            tab.isSelected = option == kind
            tab.setTextColor(if (option == kind) Palette.accent else Palette.muted)
            (tab.parent as LinearLayout).apply {
                if (childCount > 1) removeViewAt(1)
                addView(View(context).apply { setBackgroundColor(if (option == kind) Palette.accent else Palette.divider) },
                    LinearLayout.LayoutParams(-1, dp(2)))
            }
        }
        if (!::rows.isInitialized) return
        rows.removeAllViews()
        var previousDay: String? = null
        val filtered = events.filter { notificationMatchesKind(it, kind) }
        if (filtered.isEmpty()) rows.addView(label(context.getString(R.string.notifications_empty), 14f, Palette.muted).apply {
            tag = "notifications-empty"
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(28))
        })
        filtered.forEach { event ->
            val millis = event.startTime?.times(1000)?.toLong()
            val day = if (millis == null) "" else if (DateUtils.isToday(millis)) context.getString(R.string.notifications_today)
                else DateFormat.getDateInstance(DateFormat.MEDIUM).format(java.util.Date(millis))
            if (day != previousDay && day.isNotEmpty()) rows.addView(label(day, 11f, Palette.muted).apply {
                setPadding(0, dp(16), 0, dp(8))
            })
            previousDay = day
            rows.addView(eventRow(event), LinearLayout.LayoutParams(-1, -2))
            rows.addView(View(context).apply { setBackgroundColor(Palette.divider) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    private fun eventRow(event: NativeEvent): View = LinearLayout(context).apply {
        tag = "notification-row:${event.id}"
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(86)
        setPadding(0, dp(12), 0, dp(12))
        isFocusable = true
        contentDescription = narrative(event)
        setOnClickListener { dismiss(); onOpen(event) }
        val image = object : androidx.appcompat.widget.AppCompatImageView(context) {
            private var requested = false
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                if (!requested) {
                    requested = true
                    thumbnail(event, this)
                }
            }
        }.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setColor(Palette.surface); cornerRadius = dp(6).toFloat() }
            clipToOutline = true
        }
        addView(image, LinearLayout.LayoutParams(dp(82), dp(52)).apply { marginEnd = dp(14) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                if (event.id in unread) addView(View(context).apply {
                    tag = "notification-unread"
                    background = GradientDrawable().apply { setColor(Palette.accent); shape = GradientDrawable.OVAL }
                }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(7) })
                addView(label(title(event), 14f).apply {
                    setTypeface(typeface, Typeface.BOLD); maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(label(cameraName(event), 13f, Palette.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(label(event.startTime?.let {
                DateUtils.getRelativeTimeSpanString((it * 1000).toLong(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
            } ?: event.time, 12f, Palette.muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_right); setColorFilter(Palette.muted)
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) })
    }

    override fun createSheetFooter(): View = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        tag = "notifications-history"
        minimumHeight = dp(56)
        setPadding(dp(20), dp(12), dp(20), dp(12))
        isFocusable = true
        setOnClickListener { dismiss(); onHistory() }
        addView(label(context.getString(R.string.notifications_history), 14f), LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(context).apply { setImageResource(R.drawable.ic_chevron_right); setColorFilter(Palette.muted) },
            LinearLayout.LayoutParams(dp(18), dp(18)))
    }
}
