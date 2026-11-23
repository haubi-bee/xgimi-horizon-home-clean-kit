package tv.cleanhome.launcher.weather

import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * Request URLs and response parsing for the Open-Meteo APIs (no API key needed).
 * See https://open-meteo.com/en/docs and https://open-meteo.com/en/docs/geocoding-api.
 */
object OpenMeteo {
    private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
    private const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"

    fun forecastUrl(latitude: Double, longitude: Double): String =
        // Locale.ROOT: always a decimal point, whatever the device language is.
        String.format(
            Locale.ROOT,
            "%s?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day",
            FORECAST_URL,
            latitude,
            longitude,
        )

    fun geocodingUrl(name: String, language: String): String =
        "$GEOCODING_URL?name=${encode(name.trim())}&count=1&language=${encode(language.ifBlank { "en" })}"

    /** Parses a forecast response; null if it does not contain the current conditions. */
    fun parseCurrent(json: String): CurrentWeather? = try {
        val current = JSONObject(json).optJSONObject("current")
        if (current == null || !current.has("temperature_2m") || !current.has("weather_code")) {
            null
        } else {
            CurrentWeather(
                temperatureCelsius = current.getDouble("temperature_2m"),
                weatherCode = current.getInt("weather_code"),
                isDay = current.optInt("is_day", 1) != 0,
            )
        }
    } catch (e: JSONException) {
        null
    }

    /** Parses a geocoding response; null if no place was found or the response is malformed. */
    fun parseLocation(json: String): WeatherLocation? = try {
        val first = JSONObject(json).optJSONArray("results")?.optJSONObject(0)
        val name = first?.optString("name").orEmpty().trim()
        if (first == null || name.isEmpty() || !first.has("latitude") || !first.has("longitude")) {
            null
        } else {
            WeatherLocation(
                name = name,
                country = first.optString("country").trim().ifEmpty { null },
                latitude = first.getDouble("latitude"),
                longitude = first.getDouble("longitude"),
            )
        }
    } catch (e: JSONException) {
        null
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
