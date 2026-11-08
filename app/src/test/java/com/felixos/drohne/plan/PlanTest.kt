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

class LoomingTest {
    private fun texture(seed: Int): FloatArray {
        val r = java.util.Random(seed.toLong())
        val base = FloatArray(com.felixos.drohne.Looming.W * com.felixos.drohne.Looming.H) { r.nextInt(256).toFloat() }
        // glätten, damit bilineares Abtasten sinnvoll ist
        val out = base.copyOf()
        val w = com.felixos.drohne.Looming.W; val h = com.felixos.drohne.Looming.H
        for (j in 1 until h - 1) for (i in 1 until w - 1) out[j * w + i] = (base[j * w + i] + base[j * w + i - 1] + base[j * w + i + 1] + base[(j - 1) * w + i] + base[(j + 1) * w + i]) / 5
        return out
    }
    private fun zoom(img: FloatArray, s: Double): FloatArray {
        val w = com.felixos.drohne.Looming.W; val h = com.felixos.drohne.Looming.H
        val out = FloatArray(w * h)
        for (j in 0 until h) for (i in 0 until w) {
            val x = ((i - w / 2) / s + w / 2).toInt().coerceIn(0, w - 1); val y = ((j - h / 2) / s + h / 2).toInt().coerceIn(0, h - 1)
            out[j * w + i] = img[y * w + x]
        }
        return out
    }
    @Test fun erkenntVergroesserung() {
        val a = texture(7)
        val s = com.felixos.drohne.Looming.expansion(a, zoom(a, 1.12))!!
        assertTrue("erwartet ~1.12, war $s", s in 1.08..1.17)
        val s0 = com.felixos.drohne.Looming.expansion(a, a)!!
        assertEquals(1.0, s0, 0.001)
    }
    @Test fun ohneStrukturKeinUrteil() {
        val flat = FloatArray(com.felixos.drohne.Looming.W * com.felixos.drohne.Looming.H) { 100f }
        assertEquals(null, com.felixos.drohne.Looming.expansion(flat, flat))
    }
}

class SicherheitsfelderTest {
    @Test fun umgebungUndStartWerdenGelesen() {
        val (m, _) = PlanJson.parse("""{"name":"t","umgebung":"frei","start":{"lat":38.74,"lon":-9.1},"waypoints":[{"lat":38.7401,"lon":-9.1,"h":20},{"lat":38.7402,"lon":-9.1,"h":20}]}""")
        assertTrue(m.openArea)
        assertEquals(38.74, m.plannedStart!!.lat, 1e-9)
        val (m2, _) = PlanJson.parse("""{"name":"t","waypoints":[{"lat":38.7401,"lon":-9.1,"h":20},{"lat":38.7402,"lon":-9.1,"h":20}]}""")
        assertTrue(!m2.openArea && m2.plannedStart == null)
        assertTrue(PlanJson.toJson(m).contains("umgebung") && PlanJson.toJson(m).contains("frei"))
    }
}

class BuildingsTest {
    @Test fun findetGebaeudeImUmkreis() {
        val haus = listOf(LatLon(38.7380, -9.1056), LatLon(38.7380, -9.1053), LatLon(38.7383, -9.1053), LatLon(38.7383, -9.1056))
        val fern = listOf(LatLon(38.7500, -9.1500), LatLon(38.7500, -9.1497), LatLon(38.7503, -9.1497), LatLon(38.7503, -9.1500))
        Buildings.loadForTest(listOf(haus to 12.5, fern to 30.0))
        val near = Buildings.near(listOf(LatLon(38.7381, -9.1060)), 300.0)
        assertEquals(1, near.size)
        assertEquals(12.5, near.first().heightM, 1e-9)
        assertEquals(4, near.first().polygon.size)
        assertTrue(Buildings.near(listOf(LatLon(38.70, -9.20)), 300.0).isEmpty())
    }

    @Test fun binaerformatRundreise() {
        val haus = listOf(LatLon(38.7380, -9.1056), LatLon(38.7380, -9.1053), LatLon(38.7383, -9.1053))
        val bytes = java.io.ByteArrayOutputStream()
        val b = java.nio.ByteBuffer.allocate(4 + 4 + 20 + 3 * 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("GEB1".toByteArray()); b.putInt(1)
        b.putInt(38738000); b.putInt(-9105600); b.putInt(38738300); b.putInt(-9105300); b.putShort(125); b.putShort(3)
        haus.forEach { b.putInt((it.lat * 1e6).toInt()); b.putInt((it.lon * 1e6).toInt()) }
        bytes.write(b.array())
        assertEquals(1, Buildings.load(java.io.ByteArrayInputStream(bytes.toByteArray())))
        val near = Buildings.near(listOf(LatLon(38.7381, -9.1055)), 50.0)
        assertEquals(1, near.size); assertEquals(12.5, near.first().heightM, 1e-9)
    }
}

class TerrainTest {
    @Test fun hangUndBaumWerdenErkannt() {
        // 10 x 10 Zellen à ~11 m; Gelände steigt nach Osten von 0 auf 27 m, Baum (Oberfläche +15 m) in Spalte 5.
        val lat0 = 38.7410; val lon0 = -9.1000; val d = 0.0001
        val ground = Array(10) { DoubleArray(10) { c -> c * 3.0 } }
        val surface = Array(10) { DoubleArray(10) { c -> c * 3.0 + if (c == 5) 15.0 else 0.0 } }
        Terrain.loadForTest(lat0, lon0, d, d, ground, surface)
        val home = LatLon(38.74055, -9.09995)            // Spalte 0, Gelände 0 m
        val ziel = LatLon(38.74055, -9.09905)            // Spalte 9, Gelände 27 m
        assertEquals(27.0, Terrain.riseM(home, ziel)!!, 1e-9)
        val tief = Mission("t", listOf(Waypoint(ziel, 20.0), Waypoint(ziel, 25.0)), speedMs = 3.0)
        val c = Terrain.check(tief, home)
        assertTrue("20 m über Start sind 7 m unter dem Gelände am Ziel", c.isNotEmpty() && c.first().surfaceRelM >= 20.0)
        val hoch = Mission("t", listOf(Waypoint(ziel, 45.0), Waypoint(ziel, 50.0)), speedMs = 3.0)
        assertTrue(Terrain.check(hoch, home).isEmpty())
        val baum = Mission("t", listOf(Waypoint(ziel, 36.0), Waypoint(ziel, 40.0)), speedMs = 3.0)
        assertTrue("36 m über Start: Ziel frei (27+8), aber Baum unterwegs (15+15+8)", Terrain.check(baum, home).any { it.surfaceRelM > 28.0 })
    }
}
