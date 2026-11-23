package tv.cleanhome.launcher.ui

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Formats the clock and the date for a locale, e.g. "09:05" / "Monday, November 23" in English
 * or "09:05" / "Montag, 23. November" in German.
 *
 * The patterns come from [forContext], which follows the device locale and its 12/24-hour setting.
 */
class ClockFormat(locale: Locale, timePattern: String, datePattern: String) {
    private val timeFormat = SimpleDateFormat(timePattern, locale)
    private val dateFormat = SimpleDateFormat(datePattern, locale)

    fun time(date: Date = Date()): String = timeFormat.format(date)

    fun date(date: Date = Date()): String = dateFormat.format(date)

    companion object {
        /** Weekday, month name and day of month, ordered and punctuated as the locale expects. */
        const val DATE_SKELETON = "EEEEMMMMd"
        const val TIME_SKELETON_24H = "Hm"
        const val TIME_SKELETON_12H = "hm"

        fun forContext(context: Context): ClockFormat {
            val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
            val timeSkeleton = if (DateFormat.is24HourFormat(context)) TIME_SKELETON_24H else TIME_SKELETON_12H
            return ClockFormat(
                locale = locale,
                timePattern = DateFormat.getBestDateTimePattern(locale, timeSkeleton),
                datePattern = DateFormat.getBestDateTimePattern(locale, DATE_SKELETON),
            )
        }
    }
}
