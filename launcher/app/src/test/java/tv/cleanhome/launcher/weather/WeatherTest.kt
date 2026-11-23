package tv.cleanhome.launcher.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {

    @Test
    fun clearSkyIsSunByDayAndMoonByNight() {
        assertEquals(WeatherIcon.SUN, WeatherCodes.iconFor(0, isDay = true))
        assertEquals(WeatherIcon.SUN, WeatherCodes.iconFor(1, isDay = true))
        assertEquals(WeatherIcon.MOON, WeatherCodes.iconFor(0, isDay = false))
        assertEquals(WeatherIcon.MOON, WeatherCodes.iconFor(1, isDay = false))
    }

    @Test
    fun cloudsAndFogAreCloud() {
        for (code in listOf(2, 3, 45, 48)) {
            assertEquals("code $code", WeatherIcon.CLOUD, WeatherCodes.iconFor(code, isDay = true))
        }
    }

    @Test
    fun drizzleRainShowersAndThunderstormsAreRain() {
        for (code in listOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99)) {
            assertEquals("code $code", WeatherIcon.RAIN, WeatherCodes.iconFor(code, isDay = false))
        }
    }

    @Test
    fun snowfallGrainsAndSnowShowersAreSnow() {
        for (code in listOf(71, 73, 75, 77, 85, 86)) {
            assertEquals("code $code", WeatherIcon.SNOW, WeatherCodes.iconFor(code, isDay = true))
        }
    }

    @Test
    fun unknownCodesFallBackToCloud() {
        assertEquals(WeatherIcon.CLOUD, WeatherCodes.iconFor(42, isDay = true))
        assertEquals(WeatherIcon.CLOUD, WeatherCodes.iconFor(-1, isDay = false))
    }

    @Test
    fun fahrenheitOnlyForTheUnitedStates() {
        assertEquals(TemperatureUnit.FAHRENHEIT, TemperatureUnit.forCountry("US"))
        assertEquals(TemperatureUnit.FAHRENHEIT, TemperatureUnit.forCountry("us"))
        assertEquals(TemperatureUnit.CELSIUS, TemperatureUnit.forCountry("DE"))
        assertEquals(TemperatureUnit.CELSIUS, TemperatureUnit.forCountry("GB"))
        assertEquals(TemperatureUnit.CELSIUS, TemperatureUnit.forCountry(""))
        assertEquals(TemperatureUnit.CELSIUS, TemperatureUnit.forCountry(null))
    }

    @Test
    fun temperatureIsRoundedWithDegreeSign() {
        assertEquals("12°", WeatherFormat.temperature(12.3, TemperatureUnit.CELSIUS))
        assertEquals("13°", WeatherFormat.temperature(12.5, TemperatureUnit.CELSIUS))
        assertEquals("0°", WeatherFormat.temperature(-0.4, TemperatureUnit.CELSIUS))
        assertEquals("-7°", WeatherFormat.temperature(-6.6, TemperatureUnit.CELSIUS))
    }

    @Test
    fun fahrenheitIsConvertedFromCelsius() {
        assertEquals("32°", WeatherFormat.temperature(0.0, TemperatureUnit.FAHRENHEIT))
        assertEquals("54°", WeatherFormat.temperature(12.3, TemperatureUnit.FAHRENHEIT))
        assertEquals("-40°", WeatherFormat.temperature(-40.0, TemperatureUnit.FAHRENHEIT))
    }

    @Test
    fun firstFetchIsDueImmediately() {
        assertEquals(0L, WeatherSchedule.delayUntilNextFetch(lastAttemptMillis = 0L, nowMillis = 1_000_000L))
    }

    @Test
    fun fetchesAtMostEveryThirtyMinutes() {
        val interval = WeatherSchedule.REFRESH_INTERVAL_MS
        val last = 10_000_000L
        assertEquals(interval, WeatherSchedule.delayUntilNextFetch(last, last))
        assertEquals(interval - 60_000L, WeatherSchedule.delayUntilNextFetch(last, last + 60_000L))
        assertEquals(0L, WeatherSchedule.delayUntilNextFetch(last, last + interval))
        assertEquals(0L, WeatherSchedule.delayUntilNextFetch(last, last + 5 * interval))
    }

    @Test
    fun clockSetBackwardsNeverWaitsLongerThanOneInterval() {
        val last = 10_000_000L
        assertEquals(WeatherSchedule.REFRESH_INTERVAL_MS, WeatherSchedule.delayUntilNextFetch(last, last - 86_400_000L))
    }

    @Test
    fun readingsExpireAfterMaxAge() {
        val observed = 50_000_000L
        assertTrue(WeatherSchedule.isFresh(observed, observed))
        assertTrue(WeatherSchedule.isFresh(observed, observed + WeatherSchedule.MAX_AGE_MS))
        assertFalse(WeatherSchedule.isFresh(observed, observed + WeatherSchedule.MAX_AGE_MS + 1))
        assertFalse(WeatherSchedule.isFresh(0L, observed))
    }

    @Test
    fun displayNameIncludesCountryWhenKnown() {
        assertEquals("Berlin, Germany", WeatherLocation("Berlin", "Germany", 52.52, 13.41).displayName)
        assertEquals("Berlin", WeatherLocation("Berlin", null, 52.52, 13.41).displayName)
        assertEquals("Berlin", WeatherLocation("Berlin", " ", 52.52, 13.41).displayName)
    }
}
