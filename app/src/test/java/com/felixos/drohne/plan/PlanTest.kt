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

class ZielPlanTest {
    private val ziel = LatLon(38.5170, -9.0130)

    @Test fun kreisBlicktAufsZiel() {
        val (m, _) = PlanJson.parse("""{"name":"haus","ziel":{"lat":38.517,"lon":-9.013,"h":6},"kreis":{"radius":30,"h":40,"punkte":8,"start":180}}""")
        assertEquals(9, m.waypoints.size)
        assertEquals(m.waypoints.first().pos.lat, m.waypoints.last().pos.lat, 1e-9)
        m.waypoints.forEach { w ->
            assertEquals(30.0, Geo.distance(w.pos, ziel), 0.5)
            assertEquals(Geo.bearing(w.pos, ziel), w.headingDeg!!, 1.0)
            assertEquals(-48.6, w.gimbalPitchDeg, 1.0)
        }
        assertEquals(180.0, Geo.bearing(ziel, m.waypoints.first().pos), 1.0)
        val kmz = Wpml.writeKmz(m, File.createTempFile("kreis", ".kmz"))
        assertTrue(kmz.length() > 0)
        assertTrue(Wpml.waylinesWpml(m).contains("smoothTransition"))
    }

    @Test fun wegpunkteMitZielBekommenRichtung() {
        val (m, _) = PlanJson.parse("""{"ziel":{"lat":38.517,"lon":-9.013},"waypoints":[
            {"lat":38.5175,"lon":-9.013,"h":30},{"lat":38.517,"lon":-9.0125,"h":30,"yaw":45,"pitch":-20}]}""")
        assertEquals(180.0, m.waypoints[0].headingDeg!!, 1.0)
        assertEquals(45.0, m.waypoints[1].headingDeg!!, 0.01)
        assertEquals(-20.0, m.waypoints[1].gimbalPitchDeg, 0.01)
        assertTrue(m.waypoints[0].gimbalPitchDeg < -20 && m.waypoints[0].gimbalPitchDeg > -40)
    }
}
