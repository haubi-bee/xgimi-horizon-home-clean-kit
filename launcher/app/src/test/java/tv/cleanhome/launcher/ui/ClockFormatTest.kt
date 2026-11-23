package tv.cleanhome.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The patterns used here are what Android's DateFormat.getBestDateTimePattern returns for the
 * skeletons in [ClockFormat] in these locales; the test checks that names follow the locale.
 */
class ClockFormatTest {

    private fun at(hour: Int, minute: Int): Date = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.NOVEMBER, 23, hour, minute)
    }.time

    @Test
    fun formatsGermanTimeAndDate() {
        val format = ClockFormat(Locale.GERMANY, "HH:mm", "EEEE, d. MMMM")
        assertEquals("09:05", format.time(at(9, 5)))
        assertEquals("Montag, 23. November", format.date(at(9, 5)))
    }

    @Test
    fun formatsEnglishTimeAndDate() {
        val format = ClockFormat(Locale.US, "HH:mm", "EEEE, MMMM d")
        assertEquals("21:30", format.time(at(21, 30)))
        assertEquals("Monday, November 23", format.date(at(21, 30)))
    }

    @Test
    fun twelveHourPatternUsesDayPeriod() {
        val format = ClockFormat(Locale.US, "h:mm a", "EEEE, MMMM d")
        assertEquals("9:30 PM", format.time(at(21, 30)))
    }

    @Test
    fun dayAndMonthNamesFollowTheLocale() {
        val pattern = "EEEE d MMMM"
        assertEquals("lundi 23 novembre", ClockFormat(Locale.FRANCE, "HH:mm", pattern).date(at(9, 5)))
        assertEquals("Monday 23 November", ClockFormat(Locale.UK, "HH:mm", pattern).date(at(9, 5)))
    }
}
