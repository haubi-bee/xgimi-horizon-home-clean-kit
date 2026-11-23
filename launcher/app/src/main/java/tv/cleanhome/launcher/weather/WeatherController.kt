package tv.cleanhome.launcher.weather

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.cleanhome.launcher.R
import java.util.Locale

/**
 * The weather line under the date. Without a location nothing is shown and no request is made.
 * While started (home screen visible) it requests Open-Meteo at most every
 * [WeatherSchedule.REFRESH_INTERVAL_MS]; failures keep the last reading until it is too old.
 */
class WeatherController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val row: View,
    private val icon: ImageView,
    private val temperature: TextView,
) {
    private val store = WeatherStore(context)
    private val client = OpenMeteoClient()
    private var job: Job? = null
    private var started = false

    fun location(): WeatherLocation? = store.location()

    fun start() {
        started = true
        job?.cancel()
        job = null
        render()
        val location = store.location() ?: return
        job = scope.launch {
            while (isActive) {
                delay(WeatherSchedule.delayUntilNextFetch(store.lastAttemptMillis(), now()))
                store.setLastAttempt(now())
                val weather = withContext(Dispatchers.IO) { client.current(location) }
                if (weather != null) store.setReading(weather, now())
                render()
            }
        }
    }

    fun stop() {
        started = false
        job?.cancel()
        job = null
    }

    /** Resolves a place name with the Open-Meteo geocoding API in the device language. */
    suspend fun lookUp(name: String): GeocodeResult {
        val language = locale().language
        return withContext(Dispatchers.IO) { client.geocode(name, language) }
    }

    /** Stores a new location (null turns the weather off) and refreshes right away. */
    fun setLocation(location: WeatherLocation?) {
        store.setLocation(location)
        if (started) start() else render()
    }

    private fun render() {
        val reading = store.reading()
        if (store.location() == null || reading == null || !WeatherSchedule.isFresh(reading.second, now())) {
            row.visibility = View.GONE
            return
        }
        val (weather, _) = reading
        val text = WeatherFormat.temperature(weather.temperatureCelsius, TemperatureUnit.forCountry(locale().country))
        icon.setImageResource(iconRes(WeatherCodes.iconFor(weather.weatherCode, weather.isDay)))
        temperature.text = text
        row.contentDescription = context.getString(R.string.weather_description, text)
        row.visibility = View.VISIBLE
    }

    private fun iconRes(icon: WeatherIcon): Int = when (icon) {
        WeatherIcon.SUN -> R.drawable.ic_weather_sun
        WeatherIcon.MOON -> R.drawable.ic_weather_moon
        WeatherIcon.CLOUD -> R.drawable.ic_weather_cloud
        WeatherIcon.RAIN -> R.drawable.ic_weather_rain
        WeatherIcon.SNOW -> R.drawable.ic_weather_snow
    }

    private fun locale(): Locale = context.resources.configuration.locales[0] ?: Locale.getDefault()

    private fun now(): Long = System.currentTimeMillis()
}
