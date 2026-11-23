package com.felixos.drohne.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Rechner der Missionen: jeder Plan bleibt in seiner Fläche, auf sicherer Höhe und außerhalb der Sperrzone. */
class MissionsbauTest {
    private val o = LatLon(10.0, 20.0)   // erfundener Ort
    private fun rechteck(breiteM: Double, hoeheM: Double, drehung: Double = 0.0): List<LatLon> {
        val a = Geo.offset(o, breiteM / 2, 270 + drehung); val b = Geo.offset(o, breiteM / 2, 90 + drehung)
        return listOf(Geo.offset(a, hoeheM / 2, 0 + drehung), Geo.offset(b, hoeheM / 2, 0 + drehung), Geo.offset(b, hoeheM / 2, 180 + drehung), Geo.offset(a, hoeheM / 2, 180 + drehung))
    }

    @Test fun areaOfRectangle() = assertEquals(100.0 * 60.0, Missionsbau.flaecheM2(rechteck(100.0, 60.0)), 60.0)

    @Test fun gridStaysInsideAndCoversTheArea() {
        val poly = rechteck(120.0, 80.0, drehung = 30.0)
        val m = Missionsbau.raster(poly, 60.0, 30.0, 20.0, "t")
        // Jeder Punkt liegt im Vieleck oder höchstens 1 m daneben (Rundung am Rand).
        m.waypoints.forEach { assertTrue("Punkt außerhalb: $it", Obstacles.inside(it.pos, poly) || Obstacles.distanceToPolygon(it.pos, poly) < 1.0) }
        assertTrue(m.waypoints.all { it.gimbalPitchDeg == -90.0 && it.takePhoto && it.heightM == 60.0 })
        // Kein Ort der Fläche liegt weiter als ein Bahnabstand von einem Fotopunkt entfernt.
        for (dx in -55..55 step 10) for (dy in -35..35 step 10) {
            val p = Geo.offset(Geo.offset(o, dx.toDouble(), 90.0 + 30.0), dy.toDouble(), 0.0 + 30.0)
            assertTrue(m.waypoints.minOf { Geo.distance(it.pos, p) } < 30.0)
        }
        // Bahnen längs der langen Seite: wenige Bahnen (80 m / 30 m = 3).
        val kurse = m.waypoints.zipWithNext { a, b -> Geo.distance(a.pos, b.pos) }
        assertEquals(2, kurse.count { it > 21.0 })   // zwei Wenden zwischen drei Bahnen
    }

    @Test fun gridLegsNeverLongerThanPhotoSpacingOnALine() {
        val m = Missionsbau.raster(rechteck(200.0, 40.0), 60.0, 25.0, 18.0, "t")
        assertTrue(m.waypoints.zipWithNext { a, b -> Geo.distance(a.pos, b.pos) }.all { it <= 25.5 })
    }

    @Test fun gridRefusesLowHeightTinyAreaAndCrossedOutline() {
        fun grund(f: () -> Unit): String = try { f(); fail("sollte ablehnen"); "" } catch (e: Missionsbau.Abgelehnt) { e.grund }
        assertTrue(grund { Missionsbau.raster(rechteck(100.0, 60.0), 20.0, 30.0, 20.0, "t") }.contains("safe height"))
        assertTrue(grund { Missionsbau.raster(rechteck(100.0, 60.0).take(2), 60.0, 30.0, 20.0, "t") }.contains("three corners"))
        val r = rechteck(100.0, 60.0)
        assertTrue(grund { Missionsbau.raster(listOf(r[0], r[2], r[1], r[3]), 60.0, 30.0, 20.0, "t") }.contains("crosses itself"))
        assertFalse(Missionsbau.ueberschlagen(r))
    }

    @Test fun surveyHasGridAndObliqueRing() {
        val m = Missionsbau.vermessung(rechteck(80.0, 60.0), 60.0)
        val senkrecht = m.waypoints.count { it.gimbalPitchDeg == -90.0 }
        val schraeg = m.waypoints.filter { it.gimbalPitchDeg > -90.0 }
        assertTrue(senkrecht >= 6)
        assertTrue("Ring fehlt", schraeg.size >= 8)
        // Der Ring liegt außerhalb der Fläche und schaut zur Mitte.
        schraeg.forEach { w -> assertEquals(Geo.bearing(w.pos, o), w.headingDeg!!, 2.0); assertTrue(Geo.distance(w.pos, o) > 50.0) }
        assertTrue(m.waypoints.all { it.heightM >= Missionsbau.SICHERE_HOEHE_M })
        assertTrue(m.waypoints.size <= Missions.MAX_TOTAL_WAYPOINTS)
    }

    @Test fun overlapGivesSpacing() {
        val (bahn, foto) = Missionsbau.abstaende(60.0, 0.70, 0.70)
        assertEquals(27.0, bahn, 0.5); assertEquals(20.25, foto, 0.5)
    }

    @Test fun searchCoversCircle() {
        val m = Missionsbau.suche(Missionsbau.kreis(o, 80.0), 45.0)
        assertTrue(m.waypoints.all { Geo.distance(it.pos, o) <= 81.0 && it.hoverSec == 2 })
        assertTrue(m.waypoints.size in 6..60)
    }

    @Test fun lookGoesToThePlaceAndHovers() {
        val p = Geo.offset(o, 200.0, 45.0)
        val m = Missionsbau.nahsicht(p, 40.0)
        assertEquals(0.0, Geo.distance(m.waypoints[0].pos, p), 0.01)
        assertTrue(m.waypoints[0].hoverSec >= 10 && m.waypoints[0].gimbalPitchDeg == -90.0)
        assertFalse(m.hoverAtEnd)   // kommt von selbst heim: Akku-Wächter bleibt zuständig
    }

    // ---- 07 Atmosphere: nie über die Menge

    private val menge = Geo.offset(o, 300.0, 0.0)

    @Test fun arcStaysOutsideTheZoneIncludingTheChords() {
        val m = Missionsbau.bogen(menge, 40.0, 30.0, 35.0, mitteGrad = 180.0, spannGrad = 160.0)
        m.waypoints.forEach { assertEquals(70.0, Geo.distance(it.pos, menge), 0.5) }
        m.waypoints.zipWithNext { a, b -> assertTrue(Missionsbau.abstandStrecke(menge, a.pos, b.pos) >= 40.0 + Missionsbau.SPERR_PUFFER_M * 0.5 - 0.5) }
        m.waypoints.forEach { assertEquals(Geo.bearing(it.pos, menge), it.headingDeg!!, 1.0) }
        assertEquals(1, m.waypoints.first().video); assertEquals(2, m.waypoints.last().video)
    }

    @Test fun arcFacingTheStartIsClear() {
        val seite = Missionsbau.seiteZumStart(menge, o)
        val m = Missionsbau.bogen(menge, 40.0, 30.0, 35.0, mitteGrad = seite, spannGrad = Missionsbau.groessterBogen(menge, o, 40.0, 30.0))
        assertNull(Missionsbau.sperrzoneProblem(m, o, menge, 40.0))
    }

    @Test fun arcBehindTheCrowdIsRefused() {
        val m = Missionsbau.bogen(menge, 40.0, 30.0, 35.0, mitteGrad = 0.0, spannGrad = 60.0)   // hinter der Menge, vom Start aus gesehen
        val p = Missionsbau.sperrzoneProblem(m, o, menge, 40.0)
        assertNotNull(p); assertTrue(p!!.contains("way home"))
    }

    @Test fun startInsideTheZoneIsRefused() {
        val m = Missionsbau.bogen(menge, 40.0, 30.0, 35.0, mitteGrad = 180.0)
        assertTrue(Missionsbau.sperrzoneProblem(m, Geo.offset(menge, 20.0, 90.0), menge, 40.0)!!.contains("inside the keep-out zone"))
        assertTrue(Missionsbau.sperrzoneProblem(m, null, menge, 40.0)!!.contains("no home point"))
    }

    @Test fun arcRefusesSmallZoneOrDistance() {
        try { Missionsbau.bogen(menge, 5.0, 30.0, 35.0, 180.0); fail() } catch (_: Missionsbau.Abgelehnt) {}
        try { Missionsbau.bogen(menge, 40.0, 5.0, 35.0, 180.0); fail() } catch (_: Missionsbau.Abgelehnt) {}
        try { Missionsbau.bogen(menge, 40.0, 30.0, 20.0, 180.0); fail() } catch (_: Missionsbau.Abgelehnt) {}
    }

    @Test fun widestArcShrinksNearTheStart() {
        val weit = Missionsbau.groessterBogen(menge, o, 40.0, 30.0, 160.0)
        assertTrue(weit in 40.0..160.0)
        val m = Missionsbau.bogen(menge, 40.0, 30.0, 35.0, Missionsbau.seiteZumStart(menge, o), weit)
        assertNull(Missionsbau.sperrzoneProblem(m, o, menge, 40.0))
    }
}
