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

class RelativPlanTest {
    @Test fun fassadenfoto() {
        val (m, _) = PlanJson.parse("""{"name":"fassade","ziel":{"lat":38.74,"lon":-9.1,"h":10},"relativ":[{"richtung":200,"abstand":30,"h":12},{"richtung":200,"abstand":45,"h":20}]}""")
        assertEquals(2, m.waypoints.size)
        assertEquals(30.0, Geo.distance(m.waypoints[0].pos, LatLon(38.74, -9.1)), 0.5)
        assertEquals(20.0, m.waypoints[0].headingDeg!!, 1.0)
        assertTrue(m.waypoints[0].gimbalPitchDeg > -10 && m.waypoints[0].gimbalPitchDeg < 0)
    }
}

class ObstaclesTest {
    // LMSI-Block (OSM way 240952310) an der Rua Capitão Leitão, ca. 15 m hoch
    private val lmsi = Obstacle("LMSI", 15.0, listOf(LatLon(38.7380898,-9.1053589), LatLon(38.7383091,-9.1052909), LatLon(38.7382861,-9.1051676),
        LatLon(38.7382547,-9.105012), LatLon(38.7382341,-9.104891), LatLon(38.7380179,-9.1049699), LatLon(38.7380723,-9.1052678)))
    private val start = LatLon(38.73885, -9.10400)  // weiter nordöstlich in der Straße
    private val front = LatLon(38.738118, -9.105378)

    @Test fun absturzflugWirdErkannt() {
        val m = Mission("test", listOf(Waypoint(front, 12.0), Waypoint(LatLon(38.738193,-9.105380), 10.0)), obstacles = listOf(lmsi))
        val c = Obstacles.check(m, start)
        assertTrue("Start→Punkt 1 auf 12 m muss das Gebäude treffen", c.any { it.from == -1 && it.obstacle.name == "LMSI" })
    }

    @Test fun transitHoeheIstFrei() {
        val m = Mission("test", listOf(Waypoint(front, 30.0), Waypoint(LatLon(38.738193,-9.105380), 30.0)), obstacles = listOf(lmsi))
        assertTrue(Obstacles.check(m, start).isEmpty())
    }

    @Test fun punktImGebaeude() {
        assertTrue(Obstacles.inside(LatLon(38.73815, -9.10515), lmsi.polygon))
        assertTrue(!Obstacles.inside(front, lmsi.polygon))
        // Punkt 1 des Absturzplans lag nur ~2 m vor der LMSI-Wand
        assertTrue(Obstacles.distanceToPolygon(front, lmsi.polygon) in 0.5..10.0)
    }
}
