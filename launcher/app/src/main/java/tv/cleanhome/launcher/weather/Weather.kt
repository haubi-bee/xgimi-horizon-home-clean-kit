package tv.cleanhome.launcher.weather

import kotlin.math.roundToInt

/** A place the weather is shown for, as resolved by the Open-Meteo geocoding API. */
data class WeatherLocation(
    val name: String,
    val country: String?,
    val latitude: Double,
    val longitude: Double,
) {
    val displayName: String get() = listOfNotNull(name, country?.takeIf { it.isNotBlank() }).joinToString(", ")
}

/** Current conditions as returned by the Open-Meteo forecast API (temperature always in °C). */
data class CurrentWeather(
    val temperatureCelsius: Double,
    val weatherCode: Int,
    val isDay: Boolean,
)

/** The few icons the home screen can show. */
enum class WeatherIcon { SUN, MOON, CLOUD, RAIN, SNOW }

/** Maps WMO weather codes (as used by Open-Meteo) to [WeatherIcon]. Pure Kotlin. */
object WeatherCodes {
    fun iconFor(code: Int, isDay: Boolean): WeatherIcon = when (code) {
        // Clear sky, mainly clear.
        0, 1 -> if (isDay) WeatherIcon.SUN else WeatherIcon.MOON
        // Partly cloudy, overcast, fog, depositing rime fog.
        2, 3, 45, 48 -> WeatherIcon.CLOUD
        // Drizzle, freezing drizzle, rain, freezing rain, rain showers, thunderstorms.
        in 51..57, in 61..67, in 80..82, in 95..99 -> WeatherIcon.RAIN
        // Snow fall, snow grains, snow showers.
        in 71..77, 85, 86 -> WeatherIcon.SNOW
        else -> WeatherIcon.CLOUD
    }
}

enum class TemperatureUnit {
    CELSIUS,
    FAHRENHEIT,
    ;

    companion object {
        /** Fahrenheit for the United States, Celsius everywhere else. */
        fun forCountry(country: String?): TemperatureUnit =
            if (country.equals("US", ignoreCase = true)) FAHRENHEIT else CELSIUS
    }
}

object WeatherFormat {
    /** Rounded temperature with a degree sign, e.g. "12°" or "54°" (Fahrenheit). */
    fun temperature(celsius: Double, unit: TemperatureUnit): String {
        val value = when (unit) {
            TemperatureUnit.CELSIUS -> celsius
            TemperatureUnit.FAHRENHEIT -> celsius * 9.0 / 5.0 + 32.0
        }
        return "${value.roundToInt()}°"
    }
}

/** When to fetch and how long a reading may be shown. Pure Kotlin. */
object WeatherSchedule {
    const val REFRESH_INTERVAL_MS = 30L * 60 * 1000

    /** Older readings are hidden rather than shown as if they were current. */
    const val MAX_AGE_MS = 2L * 60 * 60 * 1000

    /** Milliseconds to wait before the next request; 0 when a request is due now. */
    fun delayUntilNextFetch(lastAttemptMillis: Long, nowMillis: Long): Long {
        if (lastAttemptMillis <= 0L) return 0L
        return (lastAttemptMillis + REFRESH_INTERVAL_MS - nowMillis).coerceIn(0L, REFRESH_INTERVAL_MS)
    }

    fun isFresh(observedAtMillis: Long, nowMillis: Long): Boolean =
        observedAtMillis > 0L && nowMillis - observedAtMillis <= MAX_AGE_MS
}
