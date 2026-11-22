package com.felixos.drohne.cockpit

import com.felixos.drohne.CockpitZustand
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wann das Vollbild bleiben darf und wann es von selbst endet. */
class VollbildTest {
    private val flug = CockpitZustand(verbindung = CockpitZustand.Verbindung.VERBUNDEN, fliegt = true, phase = CockpitZustand.Phase.FLIEGT)

    @Test fun ruhigerFlugBleibtImVollbild() = assertTrue(vollbildErlaubt(flug))

    @Test fun ohneVerbindungErlaubt() = assertTrue(vollbildErlaubt(CockpitZustand()))

    @Test fun warnungBeendet() = assertFalse(vollbildErlaubt(flug.copy(warnung = CockpitZustand.Warnung("BATTERY 23 %", "Battery low."))))

    @Test fun funkabrissBeendet() = assertFalse(vollbildErlaubt(flug.copy(funkabriss = true)))

    @Test fun fehlendeDatenBeenden() = assertFalse(vollbildErlaubt(flug.copy(keineDatenSeitS = 4)))

    @Test fun pauseBeendet() = assertFalse(vollbildErlaubt(flug.copy(phase = CockpitZustand.Phase.PAUSIERT)))
}
