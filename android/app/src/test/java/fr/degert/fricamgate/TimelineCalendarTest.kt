package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class TimelineCalendarTest {
    private val zone = TimeZone.getTimeZone("Europe/Paris")
    private fun date(year: Int, month: Int, day: Int, hour: Int = 0) = Calendar.getInstance(zone).apply {
        clear(); set(year, month - 1, day, hour, 0)
    }.timeInMillis

    @Test fun normalizesSelectionAndKeepsItWhileBrowsingMonths() {
        val model = TimelineCalendar(date(2026, 10, 17, 12), Locale.FRANCE, zone)
        assertEquals(date(2026, 10, 17), model.selected)
        model.moveMonth(1)
        assertEquals(date(2026, 11, 1), model.month)
        assertEquals(date(2026, 10, 17), model.selected)
        model.moveMonth(-2)
        assertEquals(date(2026, 9, 1), model.month)
    }
    @Test fun leapFebruaryHas29DaysAndStableSixRows() {
        val model = TimelineCalendar(date(2024, 2, 29), Locale.FRANCE, zone)
        assertEquals(42, model.cells().size)
        assertEquals(29, model.cells().filterNotNull().size)
        assertTrue(model.cells().contains(date(2024, 2, 29)))
        assertEquals(Calendar.MONDAY, model.weekDays().first())
    }
    @Test fun respectsLocaleFirstWeekday() {
        val french = TimelineCalendar(date(2026, 11, 1), Locale.FRANCE, zone)
        val american = TimelineCalendar(date(2026, 11, 1), Locale.US, zone)
        assertEquals(6, french.cells().indexOf(date(2026, 11, 1)))
        assertEquals(0, american.cells().indexOf(date(2026, 11, 1)))
    }
    @Test fun yesterdayUsesCalendarDaysAcrossDstAndYearBoundaries() {
        val model = TimelineCalendar(date(2026, 3, 30), Locale.FRANCE, zone)
        assertEquals(date(2026, 3, 29), model.relativeDay(date(2026, 3, 30, 14), -1))
        assertEquals(date(2025, 12, 31), model.relativeDay(date(2026, 1, 1, 9), -1))
        model.select(date(2025, 12, 31, 18))
        assertEquals(date(2025, 12, 1), model.month)
    }
}
