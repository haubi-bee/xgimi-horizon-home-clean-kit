package tv.cleanhome.launcher.weather

import android.util.Log
import tv.cleanhome.launcher.CleanHomeApp.Companion.TAG
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Outcome of looking up a place name. */
sealed class GeocodeResult {
    data class Found(val location: WeatherLocation) : GeocodeResult()
    object NotFound : GeocodeResult()
    object Failed : GeocodeResult()
}

/**
 * Plain HTTP access to Open-Meteo. Blocking; call on an IO thread. Never throws: failures are
 * logged and reported as null / [GeocodeResult.Failed].
 */
class OpenMeteoClient {

    fun current(location: WeatherLocation): CurrentWeather? {
        val body = get(OpenMeteo.forecastUrl(location.latitude, location.longitude)) ?: return null
        return OpenMeteo.parseCurrent(body).also {
            if (it == null) Log.w(TAG, "Weather: unexpected forecast response")
        }
    }

    fun geocode(name: String, language: String): GeocodeResult {
        val body = get(OpenMeteo.geocodingUrl(name, language)) ?: return GeocodeResult.Failed
        return OpenMeteo.parseLocation(body)?.let { GeocodeResult.Found(it) } ?: GeocodeResult.NotFound
    }

    private fun get(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept", "application/json")
            }
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Weather: HTTP $status from ${connection.url.host}")
                null
            } else {
                connection.inputStream.use { stream ->
                    val bytes = stream.readAtMost(MAX_RESPONSE_BYTES)
                    String(bytes, Charsets.UTF_8)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Weather: request failed (${e.javaClass.simpleName}: ${e.message})")
            null
        } catch (e: RuntimeException) {
            Log.w(TAG, "Weather: request failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Reads at most [limit] bytes; the responses used here are a few hundred bytes. */
    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(BUFFER_BYTES)
        while (buffer.size() < limit) {
            val read = read(chunk, 0, minOf(chunk.size, limit - buffer.size()))
            if (read < 0) break
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val MAX_RESPONSE_BYTES = 64 * 1024
        const val BUFFER_BYTES = 4 * 1024
    }
}
