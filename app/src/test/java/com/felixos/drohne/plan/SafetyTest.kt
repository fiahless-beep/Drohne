package com.felixos.drohne.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyTest {
    private val here = LatLon(10.0, 20.0)   // erfundener Ort, kein echter Startplatz

    @Test fun plan195WaypointsIsAccepted() {
        assertEquals(195, Missions.MAX_PLAN_WAYPOINTS)
        assertNull(Missions.waypointLimitProblem(195))
        assertNull(Missions.waypointLimitProblem(2))
    }

    @Test fun plan196WaypointsIsRejected() {
        assertEquals("Plan has 196 waypoints, max 195. Not loaded.", Missions.waypointLimitProblem(196))
        assertTrue(Missions.waypointLimitProblem(200) != null)
    }

    @Test fun totalLimitIsTheConstructorLimit() {
        assertNull(Missions.totalLimitProblem(200))
        assertEquals("Plan has 201 waypoints with climb and return, max 200. Not loaded.", Missions.totalLimitProblem(201))
    }

    /** Alle Zweige des Rahmens: nie mehr als MAX_EXTRA Punkte, und ein gerade noch erlaubter Plan passt danach in den Konstruktor. */
    @Test fun frameNeverAddsMoreThanMaxExtraInAnyBranch() {
        val there = Geo.offset(here, 300.0, 45.0)
        val near = Geo.offset(here, 12.0, 200.0)
        val transit = 30.0
        var worst = 0; var cases = 0
        for (home in listOf(null, here)) for (startAt in listOf(null, near)) for (anchor in listOf(null, near))
            for (anchorH in listOf(null, 6.0, transit)) for (hover in listOf(false, true))
                for (firstH in listOf(10.0, transit, 60.0)) for (lastH in listOf(10.0, transit, 60.0)) {
                    val plan = (0 until Missions.MAX_PLAN_WAYPOINTS).map { Waypoint(Geo.offset(there, 3.0 * it, 90.0), if (it == 0) firstH else lastH) }
                    val f = Transit.frame(plan.first(), plan.last(), transit, home, startAt, anchor, anchorH, hover)
                    val extra = f.pre.size + f.post.size
                    assertTrue("$extra Zusatzpunkte", extra <= Transit.MAX_EXTRA)
                    assertNull(Missions.totalLimitProblem(plan.size + extra))
                    Mission(name = "lang", waypoints = f.pre + plan + f.post)   // wirft, wenn die Grenze nicht stimmt
                    if (hover) assertTrue(f.post.isEmpty())
                    assertEquals(f.landingTail, !hover && anchor != null && anchorH != null && anchorH < transit - 1)
                    worst = maxOf(worst, extra); cases++
                }
        assertEquals(2 * 2 * 2 * 3 * 2 * 3 * 3, cases)
        assertEquals("Grenze ist nicht zu großzügig gewählt", Transit.MAX_EXTRA, worst)
    }

    /** Der Fall aus der Gegenprüfung: Balkonstart, im Flug, mit Rückweg = 2 davor + 3 dahinter. */
    @Test fun balconyInFlightWithReturnAddsFive() {
        val a = Waypoint(Geo.offset(here, 200.0, 0.0), 12.0); val b = Waypoint(Geo.offset(here, 260.0, 10.0), 12.0)
        val anchor = Geo.offset(here, 10.0, 90.0)
        val f = Transit.frame(a, b, 30.0, home = here, startAt = anchor, anchor = anchor, anchorH = 8.0, hoverAtEnd = false)
        assertEquals(2, f.pre.size); assertEquals(3, f.post.size); assertTrue(f.landingTail)
        assertEquals(30.0, f.pre[0].heightM, 0.0); assertEquals(30.0, f.pre[1].heightM, 0.0)
        assertEquals(8.0, f.post.last().heightM, 0.0)
    }

    /** Ebenerdiger Start: senkrecht über dem Start auf Transithöhe, oben hin, oben zurück über den Start. */
    @Test fun groundStartClimbsAboveHomeFirst() {
        val a = Waypoint(Geo.offset(here, 200.0, 0.0), 12.0); val b = Waypoint(Geo.offset(here, 260.0, 10.0), 12.0)
        val f = Transit.frame(a, b, 30.0, home = here, startAt = null, anchor = null, anchorH = null, hoverAtEnd = false)
        assertEquals(here, f.pre.first().pos); assertEquals(here, f.post.last().pos)
        assertTrue((f.pre + f.post).all { it.heightM == 30.0 && !it.takePhoto })
    }

    @Test fun stillsAllowedFarFromStartPlaces() {
        assertFalse(Privacy.isPrivate(here, 400.0) { listOf(Geo.offset(here, 450.0, 0.0)) })
        assertFalse(Privacy.isPrivate(here, 400.0) { emptyList() })
    }

    @Test fun noStillsNearStartPlace() {
        assertTrue(Privacy.isPrivate(here, 400.0) { listOf(Geo.offset(here, 5000.0, 0.0), Geo.offset(here, 350.0, 180.0)) })
    }

    @Test fun failedPlaceCheckCountsAsPrivate() {
        assertTrue(Privacy.isPrivate(here, 400.0) { throw IllegalStateException("Speicher nicht lesbar") })
        assertTrue(Privacy.isPrivate(here, 400.0) { listOf(LatLon(Double.NaN, Double.NaN)) })
    }

    /** Position, die keine ist: kein Standbild, auch wenn kein Platz in der Nähe liegt. */
    @Test fun implausiblePositionCountsAsPrivate() {
        val far = { listOf(Geo.offset(here, 5000.0, 0.0)) }
        for (p in listOf(LatLon(0.0, 0.0), LatLon(Double.NaN, 20.0), LatLon(10.0, Double.POSITIVE_INFINITY), LatLon(91.0, 20.0), LatLon(10.0, -181.0)))
            assertTrue("$p", Privacy.isPrivate(p, 400.0, far))
        assertTrue(Privacy.isPrivate(LatLon(0.0, 0.0), 400.0) { emptyList() })
        assertFalse(Privacy.isPrivate(LatLon(0.0, 20.0), 400.0, far))   // Äquator allein ist kein Fehler
    }
}
