package com.felixos.drohne

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartPlacesTest {
    @Test fun parsesSavedList() {
        val l = StartPlaces.parse("""[{"name":"Testplatz","lat":10.0,"lon":20.0,"offset":7.5}]""")
        assertEquals(1, l.size); assertEquals("Testplatz", l[0].name); assertEquals(7.5, l[0].offsetM, 0.0)
        assertTrue(StartPlaces.parse(null).isEmpty())
        assertTrue(StartPlaces.parse("[]").isEmpty())
    }

    /** Beschädigte Liste muss werfen, damit Standbilder und Live-Draht den Ort als privat behandeln. */
    @Test fun damagedListThrows() {
        for (bad in listOf("{kaputt", "[{\"name\":\"x\"}]", "[{\"name\":\"x\",\"lat\":\"abc\",\"lon\":1,\"offset\":1}]", "42")) {
            val threw = try { StartPlaces.parse(bad); false } catch (_: Throwable) { true }
            assertTrue(bad, threw)
        }
    }
}
