package com.felixos.drohne.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlanTest {
    private val home = LatLon(38.5170, -9.0130)

    @Test fun jsonRoundTrip() {
        val m = Missions.testSquare(home)
        val (back, text) = PlanJson.parse(PlanJson.toJson(m, "Test"))
        assertEquals("Test", text)
        assertEquals(m.waypoints.size, back.waypoints.size)
        assertEquals(m.waypoints[2].pos.lat, back.waypoints[2].pos.lat, 1e-9)
        assertEquals(m.rcLost, back.rcLost)
    }

    @Test fun repoPlanIsValid() {
        val f = listOf(File("../plaene/aktuell.json"), File("plaene/aktuell.json")).first { it.exists() }
        val (m, _) = PlanJson.parse(f.readText())
        assertTrue(m.waypoints.size >= 2)
        Wpml.writeKmz(m, File.createTempFile("plan", ".kmz"))
    }

    @Test fun budgetSquareNeedsReasonableBattery() {
        val e = Budget.estimate(Missions.testSquare(home), home)
        assertTrue("Testquadrat sollte < 5 min sein: ${e.totalMin}", e.totalMin < 5)
        assertTrue(e.neededPercent in 21..45)
    }

    @Test fun hoverTestIsTiny() {
        val m = Missions.hoverTest(home)
        assertEquals(5.0, m.lengthM, 0.1)
        val e = Budget.estimate(m, home)
        assertTrue(e.maxDistanceFromHomeM < 6)
    }
}
