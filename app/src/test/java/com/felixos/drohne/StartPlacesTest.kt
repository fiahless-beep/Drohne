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

    @Test fun mirrorRoundTripKeepsPlacesAndDeletions() {
        val places = listOf(StartPlaces.Place("Wiese", com.felixos.drohne.plan.LatLon(10.5, 20.25), 0.0), StartPlaces.Place("Dach", com.felixos.drohne.plan.LatLon(-1.0, 2.0), 12.5))
        val (p, d) = StartPlaces.fromMirror(StartPlaces.toMirror(places, setOf("Alt")))
        org.junit.Assert.assertEquals(places, p); org.junit.Assert.assertEquals(setOf("Alt"), d)
    }

    @Test fun brokenMirrorThrowsInsteadOfReturningAnEmptyList() {
        for (bad in listOf("", "kaputt", "{}", "{\"plaetze\":[{\"name\":\"x\"}]}"))
            try { StartPlaces.fromMirror(bad); org.junit.Assert.fail("kein Fehler bei: $bad") } catch (_: org.json.JSONException) {}
    }

    @Test fun mergeBringsBackUnknownPlacesAndKeepsOwnOnes() {
        fun pl(n: String, lat: Double) = StartPlaces.Place(n, com.felixos.drohne.plan.LatLon(lat, 5.0), 1.0)
        val own = listOf(pl("neu", 10.0)); val mirror = listOf(pl("alt-gleicher-ort", 10.0001), pl("alt-woanders", 11.0))
        org.junit.Assert.assertEquals(listOf("neu", "alt-woanders"), StartPlaces.merge(own, mirror).map { it.name })
        org.junit.Assert.assertEquals(mirror, StartPlaces.merge(emptyList(), mirror))
    }
}
