package com.felixos.drohne.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyTest {
    private val here = LatLon(10.0, 20.0)   // erfundener Ort, kein echter Startplatz

    @Test fun plan196WaypointsIsAccepted() {
        assertNull(Missions.waypointLimitProblem(196))
        assertNull(Missions.waypointLimitProblem(2))
    }

    @Test fun plan197WaypointsIsRejected() {
        assertEquals("Plan has 197 waypoints, max 196. Not loaded.", Missions.waypointLimitProblem(197))
        assertTrue(Missions.waypointLimitProblem(200) != null)
    }

    /** Die App setzt bis zu 4 Punkte dazu; ein gerade noch erlaubter Plan muss danach noch in die Grenze des Konstruktors passen. */
    @Test fun acceptedPlanStillFitsWithClimbAndReturnPoints() {
        val wps = (0 until Missions.MAX_PLAN_WAYPOINTS + 4).map { Waypoint(Geo.offset(here, 5.0 * (it + 1), 90.0), 40.0) }
        assertEquals(200, Mission(name = "lang", waypoints = wps).waypoints.size)
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
        assertTrue(Privacy.isPrivate(LatLon(Double.NaN, Double.NaN), 400.0) { listOf(here) })
    }
}
