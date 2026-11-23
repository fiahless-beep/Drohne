package com.felixos.drohne.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VermessungTest {
    private val o = LatLon(10.0, 20.0)
    private val punkte = listOf(o, Geo.offset(o, 80.0, 0.0), Geo.offset(o, 80.0, 90.0))

    @Test fun coveredAreaNeedsNoSurvey() =
        assertEquals(Vermessung.Lage.GEDECKT, Vermessung.urteil(punkte, 12.0, { true }).lage)

    @Test fun onePointOutsideIsNotCovered() =
        assertEquals(Vermessung.Lage.EMPFOHLEN, Vermessung.urteil(punkte, 12.0, { it != punkte[2] }).lage)

    @Test fun highPlanDoesNotNeedIt() {
        val u = Vermessung.urteil(punkte, 45.0, { false })
        assertEquals(Vermessung.Lage.NICHT_NOETIG, u.lage); assertTrue(u.satz.contains("45 m")); assertTrue(u.satz.contains("not checked"))
    }
}
