package com.nvr.camnory

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

internal class TimelineCalendar(initial: Long, private val locale: Locale, private val zone: TimeZone) {
    private fun calendar(value: Long) = Calendar.getInstance(zone, locale).apply { timeInMillis = value }
    fun dayStart(value: Long): Long = calendar(value).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    var selected: Long = dayStart(initial)
        private set
    var month: Long = calendar(selected).apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        private set
    fun select(value: Long) {
        selected = dayStart(value)
        month = calendar(selected).apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
    }
    fun moveMonth(delta: Int) { month = calendar(month).apply { add(Calendar.MONTH, delta) }.timeInMillis }
    fun relativeDay(now: Long, delta: Int): Long = calendar(dayStart(now)).apply { add(Calendar.DAY_OF_MONTH, delta) }.timeInMillis
    fun weekDays(): List<Int> {
        val first = calendar(month).firstDayOfWeek
        return (0..6).map { (first - 1 + it) % 7 + 1 }
    }
    fun cells(): List<Long?> {
        val date = calendar(month)
        val offset = (date.get(Calendar.DAY_OF_WEEK) - date.firstDayOfWeek + 7) % 7
        val count = date.getActualMaximum(Calendar.DAY_OF_MONTH)
        return (0 until 42).map { index ->
            val day = index - offset + 1
            if (day in 1..count) calendar(month).apply { set(Calendar.DAY_OF_MONTH, day) }.timeInMillis else null
        }
    }
}
