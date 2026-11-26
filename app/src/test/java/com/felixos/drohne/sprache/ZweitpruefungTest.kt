package com.felixos.drohne.sprache

import com.felixos.drohne.CockpitZustand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die Sätze aus der Zweitprüfung der Sprachschicht (26.11.2026): jeder Befund als Gegenbeispiel. */
class ZweitpruefungTest {
    private class Fest(private val b: Befehl) : Verstehen {
        override val bereit = true
        override fun verstehe(text: String, sprache: String, erlaubt: Set<Class<out Befehl>>, z: CockpitZustand, fertig: (Deutung?) -> Unit) = fertig(Deutung(b, Quelle.MODELL, "x"))
        override fun abbrechen() {}
    }
    private fun e(s: String, z: CockpitZustand = Lagen.planLaeuft, sicht: Sicht = Sicht.LIVE, m: Verstehen? = null) = Lagen.entscheide(s, z, sicht, m)
    private fun haelt(s: String) = e(s).let { assertTrue("$s → ${Lagen.text(it)}", it is Entscheidung.Ausfuehren && it.befehl == Befehl.Anhalten) }
    private fun tutNichts(s: String, z: CockpitZustand = Lagen.planLaeuft, sicht: Sicht = Sicht.LIVE, m: Verstehen? = null) =
        e(s, z, sicht, m).let { assertTrue("$s → ${Lagen.text(it)}", it is Entscheidung.Nichts || it is Entscheidung.Antwort) }

    @Test fun stoppMitAnderemZielPausiertNicht() = listOf("Stop recording.", "Stop the upload.", "Stopp die Aufnahme.").forEach { tutNichts(it) }

    @Test fun zweifelUndBedingungTunNichts() {
        listOf("Vielleicht stopp.", "Maybe stop.", "Stop if necessary.", "Keinesfalls stoppen.", "Auf keinen Fall stoppen.", "By no means stop.", "Keineswegs anhalten").forEach { tutNichts(it) }
        tutNichts("Vielleicht komm heim.", Lagen.handflug)
    }

    @Test fun klareStoppauftraegeWirkenAuchLangUndHoeflich() =
        listOf("Bitte halte die Drohne jetzt sofort an.", "Stop the drone immediately and wait here.", "Can you stop the drone please?", "Can you stop", "Kannst du bitte anhalten?", "Stopp", "Stop following").forEach { haelt(it) }

    @Test fun modellBefehlOhneWortImSatzGiltNicht() {
        tutNichts("Banane.", m = Fest(Befehl.Anhalten))
        tutNichts("Banane.", Lagen.handflug, m = Fest(Befehl.Heim))
        val r = e("bring sie rein", Lagen.handflug, m = Fest(Befehl.Heim))
        assertTrue(Lagen.text(r), r is Entscheidung.Vorschlag)
    }

    @Test fun feldBrauchtSeinWortSeinVorzeichenUndMeter() {
        tutNichts("Use sixty meters for the search circle.", Lagen.m6, Sicht.MSN, Fest(Befehl.FeldSetzen("h", 60)))
        tutNichts("die sechzig bitte", Lagen.m6, Sicht.MSN, Fest(Befehl.FeldSetzen("h", 60)))
        listOf("Höhe -60 Meter.", "Height 60 feet.", "Height 60cm.", "Höhe minus 60").forEach { tutNichts(it, Lagen.m6, Sicht.MSN) }
        val r = e("Höhe 60 Meter", Lagen.m6, Sicht.MSN)
        assertEquals(Befehl.FeldSetzen("h", 60), (r as Entscheidung.Ausfuehren).befehl)
        assertEquals(Befehl.FeldSetzen("h", 60), (e("Height 60m", Lagen.m6, Sicht.MSN) as Entscheidung.Ausfuehren).befehl)
    }

    @Test fun ortswahlImFlugGesperrt() {
        val imFlug = Lagen.ortswahl.copy(fliegt = true, motorenAn = true)
        assertTrue(Pruefer.grund(Befehl.OrtZurueck, Sicht.ORTSWAHL, imFlug) != null)
        assertTrue(Pruefer.grund(Befehl.OrtFertig, Sicht.ORTSWAHL, imFlug) != null)
    }

    @Test fun verspaeteterRueckrufLaesstDenNeuenAuftragAbbrechbar() {
        val rufe = ArrayList<(Deutung?) -> Unit>(); var abbrueche = 0
        val m = object : Verstehen {
            override val bereit = true
            override fun verstehe(text: String, sprache: String, erlaubt: Set<Class<out Befehl>>, z: CockpitZustand, fertig: (Deutung?) -> Unit) { rufe += fertig }
            override fun abbrechen() { abbrueche++ }
        }
        val l = SprachLeitung(RegelVerstehen(), m, uhr = { _, _ -> {} })
        l.gehoert("bring sie rein", "de", Sicht.LIVE, Lagen.handflug) {}
        l.gehoert("hol sie rein", "de", Sicht.LIVE, Lagen.handflug) {}
        assertEquals(1, abbrueche)
        rufe[0](null) // der alte Rückruf kommt spät
        l.abbrechen()
        assertEquals(2, abbrueche)
    }
}
