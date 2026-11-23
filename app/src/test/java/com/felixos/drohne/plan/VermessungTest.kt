package com.felixos.drohne.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VermessungTest {
    private val o = LatLon(10.0, 20.0)
    private val punkte = listOf(o, Geo.offset(o, 80.0, 0.0), Geo.offset(o, 80.0, 90.0))

    @Test fun coveredAreaNeedsNoSurvey() =
        assertEquals(Vermessung.Lage.GEDECKT, Vermessung.urteil(punkte, 12.0, { true }, null).lage)

    @Test fun onePointOutsideIsNotCovered() =
        assertEquals(Vermessung.Lage.EMPFOHLEN, Vermessung.urteil(punkte, 12.0, { it != punkte[2] }, null).lage)

    @Test fun highPlanDoesNotNeedIt() {
        val u = Vermessung.urteil(punkte, 45.0, { false }, null)
        assertEquals(Vermessung.Lage.NICHT_NOETIG, u.lage); assertTrue(u.satz.contains("45 m"))
    }

    @Test fun flownSurveyIsRememberedButHonest() {
        val u = Vermessung.urteil(punkte, 12.0, { false }, "2026-11-22")
        assertEquals(Vermessung.Lage.GEFLOGEN, u.lage); assertTrue(u.satz.contains("outside the app"))
    }

    @Test fun surveyedAreaMustContainEveryPoint() {
        val g = listOf(Vermessung.Gebiet(o, 100.0, "2026-11-22"))
        assertEquals("2026-11-22", Vermessung.geflogen(punkte, g))
        assertNull(Vermessung.geflogen(punkte + Geo.offset(o, 300.0, 0.0), g))
        assertNull(Vermessung.geflogen(emptyList(), g))
    }
}
