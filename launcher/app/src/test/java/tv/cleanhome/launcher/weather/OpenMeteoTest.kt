package tv.cleanhome.launcher.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class OpenMeteoTest {

    @Test
    fun forecastUrlRequestsCurrentConditionsWithDecimalPoints() {
        val previous = Locale.getDefault()
        try {
            // A locale with a decimal comma must not leak into the URL.
            Locale.setDefault(Locale.GERMANY)
            assertEquals(
                "https://api.open-meteo.com/v1/forecast?latitude=52.5244&longitude=-13.4105" +
                    "&current=temperature_2m,weather_code,is_day",
                OpenMeteo.forecastUrl(52.52437, -13.41053),
            )
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun geocodingUrlEncodesTheNameAndAsksForOneResult() {
        assertEquals(
            "https://geocoding-api.open-meteo.com/v1/search?name=S%C3%A3o%20Paulo&count=1&language=pt",
            OpenMeteo.geocodingUrl(" São Paulo ", "pt"),
        )
        assertEquals(
            "https://geocoding-api.open-meteo.com/v1/search?name=Rock%26Roll&count=1&language=en",
            OpenMeteo.geocodingUrl("Rock&Roll", ""),
        )
    }

    @Test
    fun parsesCurrentConditions() {
        val json = """
            {"latitude":52.52,"longitude":13.419998,"current_units":{"temperature_2m":"°C"},
             "current":{"time":"2026-11-23T10:00","interval":900,"temperature_2m":7.4,"weather_code":61,"is_day":1}}
        """.trimIndent()
        assertEquals(CurrentWeather(7.4, 61, isDay = true), OpenMeteo.parseCurrent(json))
    }

    @Test
    fun parsesNightReadings() {
        val json = """{"current":{"temperature_2m":-2,"weather_code":0,"is_day":0}}"""
        assertEquals(CurrentWeather(-2.0, 0, isDay = false), OpenMeteo.parseCurrent(json))
    }

    @Test
    fun incompleteOrBrokenForecastIsNull() {
        assertNull(OpenMeteo.parseCurrent("""{"current":{"temperature_2m":5.0}}"""))
        assertNull(OpenMeteo.parseCurrent("""{"error":true,"reason":"Latitude must be in range"}"""))
        assertNull(OpenMeteo.parseCurrent("<html>"))
        assertNull(OpenMeteo.parseCurrent(""))
    }

    @Test
    fun parsesFirstGeocodingResult() {
        val json = """
            {"results":[
              {"id":2950159,"name":"Berlin","latitude":52.52437,"longitude":13.41053,"country":"Germany","admin1":"Land Berlin"},
              {"id":1,"name":"Berlin","latitude":44.46867,"longitude":-71.18508,"country":"United States"}
            ],"generationtime_ms":0.5}
        """.trimIndent()
        assertEquals(WeatherLocation("Berlin", "Germany", 52.52437, 13.41053), OpenMeteo.parseLocation(json))
    }

    @Test
    fun geocodingResultWithoutCountryIsAccepted() {
        val json = """{"results":[{"name":"Atlantis","latitude":1.5,"longitude":2.5}]}"""
        assertEquals(WeatherLocation("Atlantis", null, 1.5, 2.5), OpenMeteo.parseLocation(json))
    }

    @Test
    fun noGeocodingResultIsNull() {
        assertNull(OpenMeteo.parseLocation("""{"generationtime_ms":0.3}"""))
        assertNull(OpenMeteo.parseLocation("""{"results":[]}"""))
        assertNull(OpenMeteo.parseLocation("""{"results":[{"name":"No coordinates"}]}"""))
        assertNull(OpenMeteo.parseLocation("not json"))
    }
}
