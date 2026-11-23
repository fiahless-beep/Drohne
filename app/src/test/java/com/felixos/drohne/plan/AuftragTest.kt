package com.felixos.drohne.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Der Brief einer Mission: was fehlt, was gebaut wird, und dass kein Plan aus einem Brief die Grundregeln verletzt. */
class AuftragTest {
    private val home = LatLon(10.0, 20.0)   // erfundener Ort
    private val mittag = 1_700_000_000_000L

    private fun abgelehnt(b: Brief, h: LatLon = home): String = try { Auftragsbau.bauen(b, h); fail("Plan wurde gebaut"); "" } catch (e: Missionsbau.Abgelehnt) { e.grund }

    @Test fun everyMissionHasANumberAndOnlyBriefMissionsHaveFields() {
        assertEquals((1..7).toList(), Art.values().map { it.nr })
        assertTrue(Auftragsbau.felder(Art.PLAN).isEmpty() && Auftragsbau.felder(Art.MOTIV).isEmpty())
        Art.values().forEach { a -> Auftragsbau.felder(a).forEach { f -> assertTrue(f.gewaehlt in f.werte.indices) } }
    }

    @Test fun briefSaysWhatIsMissing() {
        val b = Brief(Art.NACHSEHEN)
        assertTrue(Auftragsbau.fehlt(b)!!.contains("place"))
        b.ort = Geo.offset(home, 120.0, 45.0)
        assertTrue(Auftragsbau.fehlt(b)!!.contains("Say"))
        b.text = "Is the gate open?"
        assertNull(Auftragsbau.fehlt(b))
        assertTrue(abgelehnt(Brief(Art.SUCHEN)).contains("middle"))
    }

    @Test fun checkAPlaceFliesThereAndComesHome() {
        val b = Brief(Art.NACHSEHEN).apply { ort = Geo.offset(home, 150.0, 90.0); text = "Is the gate open?" }
        val bau = Auftragsbau.bauen(b, home)
        val m = bau.mission
        assertTrue(!m.hoverAtEnd && !m.startFromCurrent)                       // kommt heim, der Akku-Wächter bleibt zuständig
        assertTrue(m.waypoints.all { it.heightM >= Missionsbau.SICHERE_HOEHE_M })
        assertTrue(Geo.distance(m.waypoints[0].pos, b.ort!!) < 1.0)
        assertEquals(home, m.plannedStart)
        assertTrue(bau.beschreibung.contains("Is the gate open?"))
    }

    @Test fun searchSweepsTheCircleFromSafeHeight() {
        val mitte = Geo.offset(home, 200.0, 10.0)
        val b = Brief(Art.SUCHEN).apply { ort = mitte; text = "a place to park" }
        val m = Auftragsbau.bauen(b, home).mission
        val r = b.wert("r")
        assertTrue(m.waypoints.size >= 9)
        assertTrue(m.waypoints.all { Geo.distance(it.pos, mitte) <= r + 2.0 && it.gimbalPitchDeg == -90.0 && it.heightM >= Missionsbau.SICHERE_HOEHE_M })
        assertTrue(m.waypoints.size <= Missions.MAX_PLAN_WAYPOINTS)
    }

    @Test fun everyFieldChoiceOfEveryBriefBuildsAPlanWithinTheLimits() {
        // Alle Kombinationen der festen Werte: kein Plan unter 30 m, keiner über 195 Punkte, keiner über die Entfernungsgrenze.
        for (art in listOf(Art.NACHSEHEN, Art.SUCHEN, Art.VERMESSEN, Art.STIMMUNG)) {
            val muster = Brief(art)
            val zahl = muster.felder.map { it.werte.size }
            var kombi = IntArray(zahl.size)
            while (true) {
                val b = Brief(art)
                b.felder.forEachIndexed { i, f -> f.gewaehlt = kombi[i] }
                b.text = "x"
                if (art == Art.VERMESSEN) listOf(0.0 to 0.0, 80.0 to 0.0, 80.0 to 60.0, 0.0 to 60.0).forEach { (x, y) -> b.ecken += Geo.offset(Geo.offset(home, 100.0 + y, 0.0), x, 90.0) }
                else b.ort = Geo.offset(home, 250.0, 60.0)
                val m = Auftragsbau.bauen(b, home).mission
                assertTrue("$art ${kombi.toList()}: zu tief", m.waypoints.all { it.heightM >= Missionsbau.SICHERE_HOEHE_M })
                assertTrue("$art ${kombi.toList()}: ${m.waypoints.size} Punkte", m.waypoints.size in 2..Missions.MAX_PLAN_WAYPOINTS)
                assertTrue(m.waypoints.all { Geo.distance(home, it.pos) <= Preflight.MAX_DISTANCE_M })
                if (art == Art.STIMMUNG) {
                    assertEquals(1, m.sperrzonen.size)
                    assertTrue("$art ${kombi.toList()}: Sperrzone verletzt", Sperrzone.konflikte(m, home, heimwegPruefen = true).isEmpty())
                }
                var i = 0
                while (i < kombi.size && ++kombi[i] == zahl[i]) { kombi[i] = 0; i++ }
                if (i == kombi.size) break
            }
        }
    }

    @Test fun atmosphereRefusesAStartInsideTheZone() {
        val b = Brief(Art.STIMMUNG).apply { ort = Geo.offset(home, 20.0, 0.0) }
        assertTrue(abgelehnt(b).contains("keep-out"))
    }

    @Test fun surveyNeedsThreeCornersInOrder() {
        val b = Brief(Art.VERMESSEN)
        b.ecken += Geo.offset(home, 50.0, 0.0); b.ecken += Geo.offset(home, 50.0, 90.0)
        assertTrue(Auftragsbau.fehlt(b)!!.contains("three"))
        // Schleife: vier Ecken über Kreuz angetippt
        val k = Brief(Art.VERMESSEN)
        listOf(0.0 to 0.0, 80.0 to 60.0, 80.0 to 0.0, 0.0 to 60.0).forEach { (x, y) -> k.ecken += Geo.offset(Geo.offset(home, 100.0 + y, 0.0), x, 90.0) }
        assertTrue(Auftragsbau.fehlt(k)!!.contains("crosses"))
    }

    @Test fun tapsFarFromTheStartAreRefusedAndCornersAreCapped() {
        val b = Brief(Art.NACHSEHEN)
        assertNotNull(Auftragsbau.tippen(b, Geo.offset(home, 1500.0, 0.0), home))
        assertNull(b.ort)
        assertNull(Auftragsbau.tippen(b, Geo.offset(home, 300.0, 0.0), home))
        assertNotNull(b.ort)
        val v = Brief(Art.VERMESSEN)
        repeat(Auftragsbau.MAX_ECKEN) { assertNull(Auftragsbau.tippen(v, Geo.offset(home, 100.0, it * 30.0), home)) }
        assertNotNull(Auftragsbau.tippen(v, Geo.offset(home, 100.0, 5.0), home))
        assertEquals(Auftragsbau.MAX_ECKEN, v.ecken.size)
    }

    @Test fun planTooFarFromTheStartIsRefused() {
        val b = Brief(Art.SUCHEN).apply { ort = Geo.offset(home, 960.0, 0.0); text = "x"; felder.first { it.id == "r" }.gewaehlt = 2 }
        assertTrue(abgelehnt(b).contains("from the start point"))
    }

    @Test fun placeInWords() {
        assertEquals("at the start point", Auftragsbau.ortText(home, Geo.offset(home, 2.0, 0.0)))
        assertEquals("140 m north-east of the start point", Auftragsbau.ortText(home, Geo.offset(home, 141.0, 45.0)))
        assertEquals("200 m west of the start point", Auftragsbau.ortText(home, Geo.offset(home, 200.0, 270.0)))
        assertEquals("100 m north of the start point", Auftragsbau.ortText(home, Geo.offset(home, 100.0, 350.0)))
        assertTrue(Auftragsbau.ortText(null, home).contains("chosen"))
    }

    @Test fun pointInAStillStraightDown() {
        val hier = Geo.offset(home, 100.0, 0.0)
        // Bildmitte = unter der Drohne
        assertTrue(Geo.distance(hier, Auftragsbau.bildOrt(hier, 45.0, 0.0, 0.5, 0.5)) < 0.5)
        // Oben im Bild = in Blickrichtung; Kurs Ost → der Ort liegt östlich
        val oben = Auftragsbau.bildOrt(hier, 45.0, 90.0, 0.5, 0.0)
        assertEquals(90.0, Geo.bearing(hier, oben), 2.0)
        assertEquals(45.0 * Math.tan(Math.toRadians(23.0)), Geo.distance(hier, oben), 1.0)
        // Rechts im Bild bei Kurs Nord = östlich
        assertEquals(90.0, Geo.bearing(hier, Auftragsbau.bildOrt(hier, 45.0, 0.0, 1.0, 0.5)), 2.0)
    }

    // ---------------------------------------------------------------- Folgen und Heimweg

    private fun tor(flying: Boolean = true, h: Double = 30.0, d: Double = 25.0, akku: Int? = 80, sats: Int? = 15, wind: Int? = 0, plan: Boolean = false, limit: Int? = 120, verbunden: Boolean = true, heim: Boolean = true) =
        Preflight.followGate(verbunden, flying, plan, home, heim, h, d, akku, sats, wind, limit, true, "on", sonneOben())

    /** Ein Zeitpunkt, an dem am erfundenen Ort die Sonne über dem Horizont steht. */
    private fun sonneOben(): Long { var t = mittag; while (Geo.sunElevationDeg(home, t) < 20) t += 3_600_000L; return t }

    @Test fun followOnlyFromHoveringAndWithinItsLimits() {
        assertTrue(tor().isEmpty())
        assertTrue(tor(flying = false).single().contains("Take off first"))
        assertTrue(tor(h = 20.0).any { it.contains("30 m") })
        assertTrue(tor(d = 10.0).any { it.contains("Distance") })
        assertTrue(tor(akku = 30).any { it.contains("Battery") })
        assertTrue(tor(akku = null).any { it.contains("Battery") })
        assertTrue(tor(sats = 6).any { it.contains("GPS") })
        assertTrue(tor(wind = 1).any { it.contains("Wind") })
        assertTrue(tor(plan = true).any { it.contains("plan is running") })
        assertTrue(tor(limit = 25).any { it.contains("height limit") })
        assertTrue(tor(verbunden = false).any { it.contains("not connected") })
        assertTrue(tor(heim = false).any { it.contains("Home point") })
    }

    @Test fun followRefusedAtNight() {
        var nacht = mittag; while (Geo.sunElevationDeg(home, nacht) > -10) nacht += 3_600_000L
        assertTrue(Preflight.followGate(true, true, false, home, true, 30.0, 25.0, 80, 15, 0, 120, true, "on", nacht).any { it.contains("daylight") })
    }

    @Test fun wayHomeCostsMoreWithDistanceAndIncludesTheReserve() {
        val nah = Budget.heimwegProzent(0.0, 30.0, 40.0)
        val weit = Budget.heimwegProzent(800.0, 30.0, 40.0)
        assertTrue(nah > Budget.RESERVE_PERCENT)
        assertTrue(weit > nah)
        // 800 m bei 8 m/s = 100 s, dazu Steigen, Abstieg, Landung: gut 2,5 min von 25 = rund 10 % plus Reserve
        assertEquals((Budget.RESERVE_PERCENT + 10).toDouble(), weit.toDouble(), 3.0)
    }
}
