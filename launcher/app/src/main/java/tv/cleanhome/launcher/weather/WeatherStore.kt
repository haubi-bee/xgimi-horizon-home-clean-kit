package tv.cleanhome.launcher.weather

import android.content.Context

/** Weather location, the last reading and the time of the last request (SharedPreferences). */
class WeatherStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun location(): WeatherLocation? {
        val name = prefs.getString(KEY_NAME, null) ?: return null
        if (!prefs.contains(KEY_LATITUDE) || !prefs.contains(KEY_LONGITUDE)) return null
        return WeatherLocation(
            name = name,
            country = prefs.getString(KEY_COUNTRY, null),
            latitude = Double.fromBits(prefs.getLong(KEY_LATITUDE, 0L)),
            longitude = Double.fromBits(prefs.getLong(KEY_LONGITUDE, 0L)),
        )
    }

    /** Sets a new location (or turns weather off with null) and forgets the previous reading. */
    fun setLocation(location: WeatherLocation?) {
        val editor = prefs.edit().clear()
        if (location != null) {
            editor.putString(KEY_NAME, location.name)
                .putString(KEY_COUNTRY, location.country)
                .putLong(KEY_LATITUDE, location.latitude.toRawBits())
                .putLong(KEY_LONGITUDE, location.longitude.toRawBits())
        }
        editor.apply()
    }

    fun lastAttemptMillis(): Long = prefs.getLong(KEY_LAST_ATTEMPT, 0L)

    fun setLastAttempt(millis: Long) {
        prefs.edit().putLong(KEY_LAST_ATTEMPT, millis).apply()
    }

    /** The last successful reading and when it was taken, or null. */
    fun reading(): Pair<CurrentWeather, Long>? {
        val observedAt = prefs.getLong(KEY_OBSERVED_AT, 0L)
        if (observedAt <= 0L || !prefs.contains(KEY_TEMPERATURE)) return null
        val weather = CurrentWeather(
            temperatureCelsius = Double.fromBits(prefs.getLong(KEY_TEMPERATURE, 0L)),
            weatherCode = prefs.getInt(KEY_CODE, 0),
            isDay = prefs.getBoolean(KEY_IS_DAY, true),
        )
        return weather to observedAt
    }

    fun setReading(weather: CurrentWeather, observedAtMillis: Long) {
        prefs.edit()
            .putLong(KEY_TEMPERATURE, weather.temperatureCelsius.toRawBits())
            .putInt(KEY_CODE, weather.weatherCode)
            .putBoolean(KEY_IS_DAY, weather.isDay)
            .putLong(KEY_OBSERVED_AT, observedAtMillis)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "weather"
        const val KEY_NAME = "location_name"
        const val KEY_COUNTRY = "location_country"
        const val KEY_LATITUDE = "location_latitude"
        const val KEY_LONGITUDE = "location_longitude"
        const val KEY_LAST_ATTEMPT = "last_attempt"
        const val KEY_TEMPERATURE = "temperature_celsius"
        const val KEY_CODE = "weather_code"
        const val KEY_IS_DAY = "is_day"
        const val KEY_OBSERVED_AT = "observed_at"
    }
}
