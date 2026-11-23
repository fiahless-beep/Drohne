package com.felixos.drohne.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AkkusTest {
    private val home = LatLon(10.0, 20.0)
    private fun linie(punkte: Int, abstandM: Double = 20.0, ab: Double = 100.0) = Mission(name = "t",
        waypoints = (0 until punkte).map { Waypoint(Geo.offset(Geo.offset(home, ab, 0.0), it * abstandM, 90.0), 60.0) })

    @Test fun smallPlanIsOneBattery() {
        val m = linie(10)
        val t = Akkus.teilen(m, home)
        assertEquals(1, t.akkus); assertTrue(t.abschnitte[0] === m)
        assertTrue(Akkus.satz(t).startsWith("1 battery"))
    }

    @Test fun longPlanSplitsAndKeepsEveryPointOnce() {
        val m = linie(190, abstandM = 12.0)   // 190 Fotos à 3 s + Strecke: mehr als ein Akku
        val t = Akkus.teilen(m, home)
        assertTrue("erwartet mehrere Akkus: ${t.prozentJeAbschnitt}", t.akkus >= 2)
        assertEquals(m.waypoints, t.abschnitte.flatMap { it.waypoints })
        assertTrue(t.prozentJeAbschnitt.all { it <= Akkus.VOLL_PROZENT })
        assertTrue(t.abschnitte.all { it.waypoints.size >= 2 })
        assertTrue(t.abschnitte[0].name.endsWith("leg1of${t.akkus}"))
        assertTrue(Akkus.satz(t).contains("${t.akkus} batteries"))
    }

    @Test fun pointLimitSplitsToo() {
        val m = linie(60, abstandM = 5.0)
        val t = Akkus.teilen(m, home, maxPunkte = 25)
        assertEquals(3, t.akkus)
        assertTrue(t.abschnitte.all { it.waypoints.size in 2..25 })
        assertEquals(60, t.abschnitte.sumOf { it.waypoints.size })
    }

    @Test fun noSinglePointLeftOver() {
        val t = Akkus.teilen(linie(51, abstandM = 5.0), home, maxPunkte = 25)
        assertEquals(51, t.abschnitte.sumOf { it.waypoints.size })
        assertTrue(t.abschnitte.all { it.waypoints.size >= 2 })
    }

    @Test fun videoRunningAcrossASplitIsReopened() {
        val w = linie(60, abstandM = 5.0).waypoints.toMutableList()
        w[0] = w[0].copy(video = 1); w[59] = w[59].copy(video = 2)
        val t = Akkus.teilen(linie(60).copy(waypoints = w), home, maxPunkte = 25)
        assertEquals(1, t.abschnitte[1].waypoints.first().video)
        assertEquals(1, t.abschnitte[2].waypoints.first().video)
    }

    @Test fun tooFarIsSaid() {
        val weit = Mission(name = "t", waypoints = listOf(Waypoint(Geo.offset(home, 9000.0, 0.0), 60.0), Waypoint(Geo.offset(home, 9020.0, 0.0), 60.0)))
        val t = Akkus.teilen(weit, home)
        assertTrue(Akkus.zuWeit(t)); assertTrue(Akkus.satz(t).startsWith("Too far"))
    }

    @Test fun restPlanFromNextPoint() {
        val m = linie(10)
        val r = Akkus.rest(m, 6)!!
        assertEquals(m.waypoints.drop(6), r.waypoints); assertEquals("t-rest", r.name)
        assertNull(Akkus.rest(m, 9))
        assertFalse(Akkus.rest(m.copy(name = "t-rest"), 2)!!.name.endsWith("-rest-rest"))
    }

    @Test fun restPlanReopensARunningVideo() {
        val o = LatLon(10.0, 20.0)
        val m = Missionsbau.bogen(Geo.offset(o, 150.0, 0.0), 50.0, 30.0, 35.0, 180.0)
        assertEquals(1, m.waypoints.first().video)
        val rest = Akkus.rest(m, 3)!!
        assertEquals(1, rest.waypoints.first().video)
        assertEquals(m.waypoints.size - 3, rest.waypoints.size)
        assertEquals(m.sperrzonen, rest.sperrzonen)
        // Am ersten Punkt beendet: der Rest ist der ganze Plan
        assertEquals(m.waypoints.size, Akkus.rest(m, 0)!!.waypoints.size)
    }
}
