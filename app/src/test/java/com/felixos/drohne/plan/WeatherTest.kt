package com.felixos.drohne.plan

import org.junit.Assert.*
import org.junit.Test

class WeatherTest {
    private val ok = """{"current":{"time":"2026-11-22T13:30","wind_speed_10m":4.2,"wind_direction_10m":315,"wind_gusts_10m":7.1},
        "hourly":{"time":["a","b","c"],"wind_gusts_10m":[7.1,9.4,null]}}"""

    @Test fun readsWindAndGusts() {
        val r = Weather.parse(ok)!!
        assertEquals(4.2, r.windMs, 1e-9); assertEquals(315.0, r.fromDeg, 1e-9); assertEquals(9.4, r.gustNext2hMs!!, 1e-9)
        assertTrue(r.line(), r.line().contains("from NW") && r.line().contains("gusts up to 9 m/s") && r.line().contains("Gusty"))
    }

    @Test fun serviceErrorAndNonsenseGiveNull() {
        assertNull(Weather.parse("""{"reason":"limit","error":true}"""))
        assertNull(Weather.parse("not json"))
        assertNull(Weather.parse("""{"current":{"wind_speed_10m":-1,"wind_direction_10m":10,"wind_gusts_10m":2}}"""))
        assertNull(Weather.parse("""{"current":{"wind_speed_10m":3,"wind_direction_10m":10,"wind_gusts_10m":900}}"""))
    }

    @Test fun compassAndAdvice() {
        assertEquals("N", Weather.compass(350.0)); assertEquals("N", Weather.compass(10.0)); assertEquals("SW", Weather.compass(225.0)); assertEquals("E", Weather.compass(-270.0))
        assertEquals("", Weather.advice(7.9)); assertTrue(Weather.advice(8.0).contains("Gusty")); assertTrue(Weather.advice(12.0).contains("TOO WINDY"))
    }

    @Test fun urlCarriesOnlyTheRoundedPosition() {
        val u = Weather.url(LatLon(38.727834, -9.157271))
        assertTrue(u, u.contains("latitude=38.73&longitude=-9.16&"))
        assertFalse(u, u.contains("38.727") || u.contains("9.157"))
    }
}
