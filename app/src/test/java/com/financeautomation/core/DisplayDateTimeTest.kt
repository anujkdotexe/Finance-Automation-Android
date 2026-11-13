package com.financeautomation.core

import com.financeautomation.core.utils.DisplayDateTime
import com.financeautomation.core.utils.stripVoidPrefix
import com.financeautomation.core.utils.matchesSearch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/** R2-24 and R2-21: user facing dates and times use Asia/Kolkata whatever the phone is set to; void search. */
class DisplayDateTimeTest {

    private val ist = ZoneId.of("Asia/Kolkata")
    private val savedZone = TimeZone.getDefault()

    @Before fun setUp() { TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles")) }
    @After fun tearDown() { TimeZone.setDefault(savedZone) }

    private fun ts(y: Int, m: Int, d: Int, h: Int, min: Int) =
        LocalDateTime.of(y, m, d, h, min).atZone(ist).toInstant().toEpochMilli()

    @Test
    fun lateEveningStaysOnItsKolkataDay() {
        val t = ts(2026, 11, 30, 23, 50)
        assertEquals("11:50 PM", DisplayDateTime.time(t, Locale.US).uppercase().removePrefix("0"))
        assertEquals("30 Nov", DisplayDateTime.dayMonth(t, Locale.US))
        assertEquals("30 Nov, 11:50 PM", DisplayDateTime.dayMonthTime(t, Locale.US).uppercase().replace("NOV", "Nov"))
    }

    @Test
    fun justAfterMidnightIsTheNextKolkataDay() {
        val t = ts(2026, 12, 1, 0, 10)
        assertEquals("1 Dec", DisplayDateTime.dayMonth(t, Locale.US))
        assertEquals("00:10:00", DisplayDateTime.clock24(t))
    }

    @Test
    fun sameDayIsComparedInKolkata() {
        assertTrue(DisplayDateTime.isSameDay(ts(2026, 11, 30, 23, 50), ts(2026, 11, 30, 0, 5)))
        assertFalse(DisplayDateTime.isSameDay(ts(2026, 11, 30, 23, 50), ts(2026, 12, 1, 0, 10)))
    }

    @Test
    fun fullDateIncludesTheWeekday() {
        assertTrue(DisplayDateTime.full(ts(2026, 11, 30, 23, 50), Locale.US).startsWith("Monday, 30 November 2026"))
    }

    @Test
    fun anEpochDayIsFormattedAsThatCalendarDate() {
        assertEquals("30 Nov", DisplayDateTime.epochDay(java.time.LocalDate.of(2026, 11, 30).toEpochDay(), Locale.US))
    }

    @Test
    fun searchLooksAtTheNameWithoutTheVoidPrefix() {
        assertEquals("Tea stall", stripVoidPrefix("[Void] Tea stall"))
        assertTrue(matchesSearch("[Void] Tea stall", "tea"))
        assertFalse("the prefix must not match a search", matchesSearch("[Void] Tea stall", "void"))
        assertFalse(matchesSearch("[Void] Tea stall", "oid"))
        assertTrue(matchesSearch("Avoid traffic fine", "void"))
    }
}
