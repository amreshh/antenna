package com.antenna.ui

import com.antenna.data.hourIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** The Analytics tab's zoomable watch time chart: its bars, scale, labels, and zooming and scrolling. */
class WatchTimeChartTest {

    private val day = LocalDate.of(2026, 11, 24).toEpochDay() // a Tuesday

    @Test
    fun `bars are hours while they're wide enough, then days, then weeks`() {
        // 600 px wide, bars at least 5 px.
        assertEquals(Step.Hour, stepFor(spanHours = 24.0, widthPx = 600f, minSlotPx = 5f)) // 25 px an hour
        assertEquals(Step.Hour, stepFor(spanHours = 72.0, widthPx = 600f, minSlotPx = 5f)) // 3 days, 8 px an hour
        assertEquals(Step.Day, stepFor(spanHours = 73.0, widthPx = 6000f, minSlotPx = 5f)) // over 3 days: days, however wide
        assertEquals(Step.Day, stepFor(spanHours = 48.0, widthPx = 200f, minSlotPx = 5f)) // hours too narrow
        assertEquals(Step.Day, stepFor(spanHours = 30 * 24.0, widthPx = 600f, minSlotPx = 5f))
        assertEquals(Step.Week, stepFor(spanHours = 365 * 24.0, widthPx = 600f, minSlotPx = 5f))
    }

    @Test
    fun `weeks start on Monday, and bars at the start of their hour, day or week`() {
        assertEquals(DayOfWeek.MONDAY, LocalDate.ofEpochDay(mondayOf(day)).dayOfWeek)
        assertEquals(day - 1, mondayOf(day))
        assertEquals(day - 1, mondayOf(day - 1)) // a Monday is its own week's start
        val at = hourIndex(day, 15)
        assertEquals(at, barStart(at, Step.Hour))
        assertEquals(day * 24, barStart(at, Step.Day))
        assertEquals((day - 1) * 24, barStart(at, Step.Week))
    }

    @Test
    fun `the scale tops out at a round amount, with a round half`() {
        val minute = 60_000L
        val hour = 60 * minute
        assertEquals(2 * minute, scaleTop(30_000))
        assertEquals(20 * minute, scaleTop(12 * minute))
        assertEquals(hour, scaleTop(45 * minute))
        assertEquals(2 * hour, scaleTop(61 * minute))
        assertEquals(4 * hour, scaleTop(150 * minute)) // not 3 h: its half would be 1.5
        assertEquals(400 * hour, scaleTop(300 * hour)) // past the list: doubled
    }

    @Test
    fun `days, and weeks, add up their hours`() {
        val series = TimeSeries(
            mapOf(
                hourIndex(day, 9) to 10L,
                hourIndex(day, 21) to 20L,
                hourIndex(day - 1, 21) to 5L, // Monday: the same week
                hourIndex(day - 2, 21) to 7L, // Sunday: the week before
            ),
        )
        assertEquals(20L, series.value(Step.Hour, hourIndex(day, 21)))
        assertEquals(0L, series.value(Step.Hour, hourIndex(day, 22)))
        assertEquals(30L, series.value(Step.Day, day * 24))
        assertEquals(35L, series.value(Step.Week, mondayOf(day) * 24))
        assertEquals(7L, series.value(Step.Week, mondayOf(day - 2) * 24))
        assertEquals(day - 2, series.firstDay)
    }

    @Test
    fun `labels are hours of the day with the date at midnight, days, or months, in view`() {
        val hours = ticks(Step.Hour, start = hourIndex(day, 0).toDouble(), end = hourIndex(day + 1, 0).toDouble(), minGap = 5.0)
        assertEquals(listOf("Nov 24", "6:00", "12:00", "18:00"), hours.map { it.text })
        assertEquals(hourIndex(day, 6) + 0.5, hours[1].at, 1e-9) // under the middle of its bar

        val days = ticks(Step.Day, start = day * 24.0, end = (day + 3) * 24.0, minGap = 24.0)
        assertEquals(listOf("Tue 24", "Wed 25", "Thu 26"), days.map { it.text }) // every day: with its weekday

        // Too narrow for every day: Mondays.
        val mondays = ticks(Step.Day, start = day * 24.0, end = (day + 14) * 24.0, minGap = 3 * 24.0)
        assertEquals(listOf("Nov 30", "Dec 7"), mondays.map { it.text })

        // Far out: the first of the months, the year in January.
        val months = ticks(Step.Week, start = day * 24.0, end = (day + 70) * 24.0, minGap = 20 * 24.0)
        assertEquals(listOf("Dec", "2027", "Feb"), months.map { it.text })
    }

    @Test
    fun `zooming keeps the time under the fingers, within the bounds`() {
        val w = TimeWindow(start = 1000.0, span = 100.0)
        w.setBounds(first = 0.0, last = 2000.0)
        w.zoom(2f, focus = 0.5f) // around 1050
        assertEquals(50.0, w.span, 1e-9)
        assertEquals(1025.0, w.start, 1e-9)
        w.zoom(100f, focus = 0f)
        assertEquals(MIN_SPAN_HOURS, w.span, 1e-9) // no further in
        w.zoom(0.001f, focus = 0.5f)
        assertEquals(2000.0, w.span, 1e-9) // no further out than the history
        assertEquals(0.0, w.start, 1e-9)
    }

    @Test
    fun `scrolling stops at the first day and at today`() {
        val w = TimeWindow(start = 1000.0, span = 100.0)
        w.setBounds(first = 0.0, last = 2000.0)
        assertTrue(w.pan(500.0))
        assertEquals(1500.0, w.start, 1e-9)
        w.pan(10_000.0)
        assertEquals(1900.0, w.start, 1e-9) // ends at today
        assertFalse(w.pan(1.0)) // already there
        w.pan(-10_000.0)
        assertEquals(0.0, w.start, 1e-9)
    }

    @Test
    fun `it knows when it shows the last days, as it does at first`() {
        val last = (day + 1) * 24.0
        val span = 30 * 24.0
        val w = TimeWindow(last - span, span)
        w.setBounds(first = last - 400 * 24.0, last = last)
        assertTrue(w.showsLast(span))
        w.pan(-24.0)
        assertFalse(w.showsLast(span))
    }

    @Test
    fun `what's picked, a day, an hour of every day, or an hour of a day`() {
        val nine = hourIndex(day, 9)
        assertTrue(TimePick(day = day, hour = null).includes(Step.Hour, nine))
        assertFalse(TimePick(day = day, hour = null).includes(Step.Hour, hourIndex(day + 1, 9)))
        assertTrue(TimePick(day = null, hour = 9).includes(Step.Hour, hourIndex(day + 5, 9)))
        assertFalse(TimePick(day = day, hour = 9).includes(Step.Hour, hourIndex(day, 10)))
        assertTrue(TimePick(day = day, hour = 9).includes(Step.Day, day * 24))
        assertTrue(TimePick(day = day, hour = null).includes(Step.Week, mondayOf(day) * 24))
        assertFalse(TimePick(day = day, hour = null).includes(Step.Week, (mondayOf(day) + 7) * 24))
    }
}
