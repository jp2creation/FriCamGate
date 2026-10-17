package com.nvr.camnory

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import java.text.DateFormat
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

internal class TimelineDateSheet(context: Context, initial: Long, private val onSelect: (Long) -> Unit)
    : SelectionBottomSheet(context, context.getString(R.string.timeline_date_title)) {
    override val maxHeightFraction = 0.70f
    private val locale = context.resources.configuration.locales[0]
    private val zone = TimeZone.getDefault()
    private val model = TimelineCalendar(initial, locale, zone)
    private val ui = ConnectionFormUi(context)
    private lateinit var content: LinearLayout
    private lateinit var confirm: TextView

    override fun createSheetContent(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), 0, dp(16), dp(4))
        content = this
        render()
    }
    private fun text(value: String, size: Int, color: Int = Palette.text) = ui.text(value, size, color).apply {
        gravity = Gravity.CENTER
    }
    private fun select(value: Long) { model.select(value); render(); updateConfirm() }
    private fun render() {
        content.removeAllViews()
        val monthFormat = SimpleDateFormat("LLLL yyyy", locale).apply { timeZone = zone }
        content.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            fun arrow(forward: Boolean) = ImageButton(context).apply {
                setImageResource(R.drawable.ic_chevron_right); rotation = if (forward) 0f else 180f
                setColorFilter(Palette.text); setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                tag = if (forward) "calendar-next" else "calendar-previous"
                contentDescription = context.getString(if (forward) R.string.timeline_next_month else R.string.timeline_previous_month)
                TooltipCompat.setTooltipText(this, contentDescription)
                setOnClickListener { model.moveMonth(if (forward) 1 else -1); render() }
            }
            addView(arrow(false), LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(text(monthFormat.format(Date(model.month)), 17).apply {
                tag = "calendar-month"; setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(arrow(true), LinearLayout.LayoutParams(dp(48), dp(48)))
        })
        val names = DateFormatSymbols(locale).shortWeekdays
        content.addView(LinearLayout(context).apply {
            model.weekDays().forEach { day -> addView(text(names[day], 11, Palette.muted), LinearLayout.LayoutParams(0, dp(28), 1f)) }
        })
        val formatter = DateFormat.getDateInstance(DateFormat.FULL, locale).apply { timeZone = zone }
        val today = model.dayStart(System.currentTimeMillis())
        model.cells().chunked(7).forEach { week ->
            content.addView(LinearLayout(context).apply {
                week.forEach { date ->
                    val day = date?.let { Calendar.getInstance(zone, locale).apply { timeInMillis = it }.get(Calendar.DAY_OF_MONTH).toString() }.orEmpty()
                    val selected = date == model.selected
                    addView(text(day, 14, if (selected) Color.WHITE else Palette.text).apply {
                        minimumHeight = dp(44)
                        if (date != null) {
                            tag = "calendar-day:$date"; isSelected = selected; isFocusable = true
                            contentDescription = formatter.format(Date(date))
                            background = GradientDrawable().apply {
                                cornerRadius = dp(6).toFloat()
                                setColor(if (selected) Palette.accent else Color.TRANSPARENT)
                                if (date == today && !selected) setStroke(dp(1), Palette.accent)
                            }
                            setOnClickListener { select(date) }
                        } else importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
                }
            })
        }
        content.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            listOf(R.string.timeline_today to 0, R.string.timeline_yesterday to -1).forEach { (label, delta) ->
                addView(ui.action(context.getString(label), false) { select(model.relativeDay(System.currentTimeMillis(), delta)) }.apply {
                    tag = if (delta == 0) "calendar-today" else "calendar-yesterday"
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(8), dp(4), 0) })
            }
        })
    }
    override fun createSheetFooter(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(16))
        confirm = text("", 12, Palette.muted).apply { tag = "calendar-selection" }
        addView(confirm, LinearLayout.LayoutParams(-1, -2))
        updateConfirm()
        addView(ui.action(context.getString(R.string.timeline_show_day), true) {
            val chosen = model.selected
            dismiss(); onSelect(chosen)
        }.apply { tag = "calendar-confirm" }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    private fun updateConfirm() {
        if (::confirm.isInitialized) confirm.text = DateFormat.getDateInstance(DateFormat.FULL, locale).apply { timeZone = zone }.format(Date(model.selected))
    }
}
