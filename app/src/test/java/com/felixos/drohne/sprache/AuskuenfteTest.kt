package com.felixos.drohne.sprache

import com.felixos.drohne.CockpitZustand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Antworten aus dem Zustand: Werte nur, solange sie frisch sind; nie ein erfundener Wert. */
class AuskuenfteTest {
    private fun a(was: Auskunft, z: CockpitZustand) = Auskuenfte.antwort(was, z)

    @Test fun werteImFlug() {
        val p = Lagen.planLaeuft
        assertEquals("Battery 71 %, about 21 min of flight.", a(Auskunft.AKKU, p))
        assertEquals("About 21 min of flight left (battery 71 %). A rough estimate.", a(Auskunft.RESTZEIT, p))
        assertEquals("Height 45 m above the start point.", a(Auskunft.HOEHE, p))
        assertEquals("Speed 5.0 m/s.", a(Auskunft.TEMPO, p))
        assertEquals("The drone is 140 m from the start point, the way home is at bearing 220°.", a(Auskunft.ENTFERNUNG_HEIM, p))
        assertEquals("Wind 4 m/s (south-west), measured by the drone. No wind warning from the drone.", a(Auskunft.WIND, p))
        assertEquals("19 satellites. GPS is good.", a(Auskunft.GPS, p))
        assertEquals("Connected to DJI Mini 4 Pro.", a(Auskunft.VERBINDUNG, p))
        assertEquals("Obstacle sensing: on.", a(Auskunft.SENSOREN, p))
        assertEquals("30 % of the plan, point 12 of 40.", a(Auskunft.FORTSCHRITT, p))
        assertEquals("Photo at waypoint 12, then on to 13", a(Auskunft.NAECHSTES, p))
        assertEquals("No warning right now.", a(Auskunft.WARNUNG, p))
        assertEquals("If the link is lost, the drone will finish the plan, then return home.", a(Auskunft.FUNKABRISS, p))
        assertEquals("Height limit of the drone: 120 m.", a(Auskunft.HOEHENLIMIT, p))
        assertEquals("It is daylight.", a(Auskunft.TAGESLICHT, p))
        assertEquals("Plan: flying to waypoint 12.", a(Auskunft.STATUS, p))
        assertEquals("Plan “Mühle Scan V5”: 124 photos · 12 min", a(Auskunft.PLAN, p))
        assertTrue(a(Auskunft.STARTKLAR, p).startsWith("The drone is already flying."))
    }

    @Test fun startklarUndBrief() {
        assertEquals("Ready for takeoff: “Mühle Scan V5”. Start stays with your thumb.", a(Auskunft.STARTKLAR, Lagen.boden))
        assertEquals("Ready for takeoff: “Mühle Scan V5”. Battery 60 % is less than the 73 % the whole plan needs. Start stays with your thumb.", a(Auskunft.STARTKLAR, Lagen.boden.copy(akkuProzent = 60)))
        assertEquals("Not ready. Missing: No plan loaded", a(Auskunft.STARTKLAR, Lagen.bodenOhnePlan))
        assertEquals("Not ready. Missing: GPS: 6 satellites · Obstacle sensing OFF", a(Auskunft.STARTKLAR, Lagen.boden.copy(startProbleme = listOf("GPS: 6 satellites", "Obstacle sensing OFF"))))
        assertEquals("Choose the place on the map.", a(Auskunft.BRIEF_FEHLT, Lagen.m3))
        assertEquals("Nothing is missing in the brief of mission 06. The plan can be built.", a(Auskunft.BRIEF_FEHLT, Lagen.m6))
        assertEquals("No mission is open. Choose one under Msn.", a(Auskunft.BRIEF_FEHLT, Lagen.boden))
        assertEquals("BATTERY 23 %: Battery low.", a(Auskunft.WARNUNG, Lagen.planLaeuft.copy(warnung = CockpitZustand.Warnung("BATTERY 23 %", "Battery low."))))
    }

    /** Ohne Verbindung oder ohne frische Daten nennt keine Antwort einen Wert der Drohne. */
    @Test fun ohneDatenKeinWert() {
        val alt = Lagen.planLaeuft.copy(keineDatenSeitS = 6)
        val messwerte = listOf(Auskunft.AKKU, Auskunft.RESTZEIT, Auskunft.HOEHE, Auskunft.TEMPO, Auskunft.ENTFERNUNG_HEIM, Auskunft.GPS, Auskunft.SENSOREN, Auskunft.HOEHENLIMIT)
        for (was in messwerte) {
            assertEquals(was.name, "No data from the drone right now.", a(was, alt))
            assertEquals(was.name, "No data: the drone is not connected.", a(was, Lagen.getrennt))
            assertEquals(was.name, "No data: the drone is not connected.", a(was, Lagen.funkabriss))
            assertFalse(was.name, a(was, Lagen.funkabriss).any { it.isDigit() })
        }
        assertEquals("Link lost. No connection to the drone.", a(Auskunft.VERBINDUNG, Lagen.funkabriss))
        assertEquals("Connected, but no data from the drone for 6 s.", a(Auskunft.VERBINDUNG, alt))
        assertEquals("Not connected.", a(Auskunft.VERBINDUNG, Lagen.getrennt))
        assertEquals("No data: the drone is not connected.", a(Auskunft.WIND, Lagen.getrennt))
        assertEquals("No plan is loaded.", a(Auskunft.PLAN, Lagen.getrennt))
        assertEquals("Unknown: there is no position yet.", a(Auskunft.TAGESLICHT, Lagen.getrennt))
        assertEquals("Not connected.", a(Auskunft.STATUS, Lagen.getrennt))
    }

    @Test fun jedeAuskunftHatInJederLageEineAntwort() {
        for (was in Auskunft.values()) for ((lage, z) in Lagen.alle) assertTrue("$was / $lage", a(was, z).isNotBlank())
    }
}
