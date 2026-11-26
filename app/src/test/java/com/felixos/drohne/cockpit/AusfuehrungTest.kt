package com.felixos.drohne.cockpit

import com.felixos.drohne.Aktionen
import com.felixos.drohne.CockpitZustand
import com.felixos.drohne.Pruefzeile
import com.felixos.drohne.StartErgebnis
import com.felixos.drohne.sprache.Befehl
import com.felixos.drohne.sprache.Entscheidung
import com.felixos.drohne.sprache.Klasse
import com.felixos.drohne.sprache.Lagen
import com.felixos.drohne.sprache.Pruefer
import com.felixos.drohne.sprache.PrueferTest
import com.felixos.drohne.sprache.Sicht
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die eine Stelle, an der ein Sprachbefehl zu einer Handlung wird, gegen einen Flugkern aus Pappe, der nur mitschreibt.
 * Feste Grenzen: Abheben und Start werden nie gerufen; Flugwirksames nur nach dem Druck; Anhalten sofort.
 */
class AusfuehrungTest {
    private class Kern(var z: CockpitZustand) : Aktionen {
        val rufe = ArrayList<String>()
        var planGrund: String? = null
        override fun planLaden() { rufe += "planLaden" }
        override fun hochladen() { rufe += "hochladen" }
        override fun startProbleme(): List<String> = z.startProbleme
        override fun pruefliste(voll: Boolean): List<Pruefzeile> = emptyList()
        override fun akkuHinweis(): String? = null
        override fun starten(): StartErgebnis { rufe += "starten"; return StartErgebnis.Gestartet }
        override fun abheben(): StartErgebnis { rufe += "abheben"; return StartErgebnis.Gestartet }
        override fun pausieren() { rufe += "pausieren" }
        override fun fortsetzen() { rufe += "fortsetzen" }
        override fun stoppUndHeim() { rufe += "stoppUndHeim" }
        override fun hauptaktionWaehlen(): CockpitZustand.Hauptaktion? = z.hauptaktion
        override fun zustand(): CockpitZustand = z
        override fun auftragWaehlen(art: Int): String? { rufe += "auftragWaehlen($art)"; return null }
        override fun auftragFeld(id: String, index: Int) { rufe += "auftragFeld($id,$index)" }
        override fun auftragText(text: String) { rufe += "auftragText($text)" }
        override fun ortWaehlen(): String? { rufe += "ortWaehlen"; return null }
        override fun ortZurueck() { rufe += "ortZurueck" }
        override fun ortFertig() { rufe += "ortFertig" }
        override fun auftragPlanen(): String? { rufe += "auftragPlanen"; return planGrund }
        override fun auftragAendern() { rufe += "auftragAendern" }
        override fun auftragNochmal() { rufe += "auftragNochmal" }
        override fun kandidatWaehlen(nr: Int) { rufe += "kandidatWaehlen($nr)" }
        override fun vermessungVorbereiten(): String? { rufe += "vermessungVorbereiten"; return null }
        override fun folgenBeginnen(): List<String> { rufe += "folgenBeginnen"; return emptyList() }
        override fun folgenAufhoeren() { rufe += "folgenAufhoeren" }
    }

    private class Buehne(override var sicht: Sicht) : Flaechen {
        val rufe = ArrayList<String>()
        override fun ansichtZeigen(s: Sicht) { rufe += "ansicht($s)" }
        override fun flaecheZeigen(s: Sicht?) { rufe += "flaeche($s)" }
        override fun vollbild(an: Boolean) { rufe += "vollbild($an)" }
        override fun einpassen() { rufe += "einpassen" }
        override fun sprechSprache(kuerzel: String) { rufe += "sprache($kuerzel)" }
        override fun missionOeffnen(nr: Int): String? { rufe += "mission($nr)"; return null }
        override fun missionMitgehen() { rufe += "mitgehen" }
    }

    private val flugwirksam = setOf("starten", "abheben", "pausieren", "fortsetzen", "stoppUndHeim", "planLaden", "hochladen", "auftragPlanen", "folgenBeginnen", "folgenAufhoeren", "auftragNochmal")

    private fun lauf(b: Befehl, z: CockpitZustand, sicht: Sicht, gedrueckt: Boolean): Triple<String?, Kern, Buehne> {
        val k = Kern(z); val f = Buehne(sicht)
        return Triple(Ausfuehrung(k, f).ausfuehren(b, gedrueckt), k, f)
    }

    @Test fun abhebenUndStartenWerdenNieGerufen() {
        for (b in Lagen.befehle) for ((lage, z) in Lagen.alle) for (sicht in Sicht.values()) for (gedrueckt in listOf(false, true)) {
            val (_, k, _) = lauf(b, z, sicht, gedrueckt)
            assertFalse("$b / $lage / $sicht", "abheben" in k.rufe || "starten" in k.rufe)
        }
    }

    @Test fun vorschlagOhneDruckTutNichts() {
        for (b in Lagen.befehle.filter { it.klasse == Klasse.VORSCHLAG }) for ((lage, z) in Lagen.alle) for (sicht in Sicht.values()) {
            val (grund, k, f) = lauf(b, z, sicht, gedrueckt = false)
            assertNotNull("$b / $lage", grund); assertTrue("$b / $lage: ${k.rufe}", k.rufe.isEmpty() && f.rufe.isEmpty())
        }
    }

    @Test fun ohneDruckGeschiehtNichtsFlugwirksamesAusserAnhalten() {
        for (b in Lagen.befehle) for ((lage, z) in Lagen.alle) for (sicht in Sicht.values()) {
            val (_, k, _) = lauf(b, z, sicht, gedrueckt = false)
            val wirksam = k.rufe.filter { it in flugwirksam }
            if (b == Befehl.Anhalten) assertTrue("$lage: $wirksam", wirksam.all { it == "pausieren" || it == "folgenAufhoeren" })
            else assertTrue("$b / $lage / $sicht: $wirksam", wirksam.isEmpty())
        }
    }

    @Test fun nieClaudeFrageUndUnklarTunNichts() {
        for (b in Lagen.befehle.filter { it.klasse == Klasse.NIE || it.klasse == Klasse.CLAUDE || it is Befehl.Unklar || it is Befehl.Frage }) for ((_, z) in Lagen.alle) for (gedrueckt in listOf(false, true)) {
            val (grund, k, f) = lauf(b, z, Sicht.MSN, gedrueckt)
            assertNotNull(b.toString(), grund); assertTrue(k.rufe.isEmpty() && f.rufe.isEmpty())
        }
    }

    @Test fun anhaltenPausiertOderBeendetDasFolgen() {
        assertEquals(listOf("pausieren"), lauf(Befehl.Anhalten, Lagen.planLaeuft, Sicht.SYS, false).second.rufe)
        assertEquals(listOf("folgenAufhoeren"), lauf(Befehl.Anhalten, Lagen.folgen, Sicht.LIVE, false).second.rufe)
        for (lage in listOf("boden", "pausiert", "handflug", "getrennt")) {
            val (grund, k, _) = lauf(Befehl.Anhalten, Lagen.alle.getValue(lage), Sicht.LIVE, false)
            assertNotNull(lage, grund); assertTrue(lage, k.rufe.isEmpty())
        }
    }

    @Test fun mitDruckUndNurWennDerZustandNochPasst() {
        assertEquals(listOf("stoppUndHeim"), lauf(Befehl.Heim, Lagen.planLaeuft, Sicht.LIVE, true).second.rufe)
        assertEquals(listOf("fortsetzen"), lauf(Befehl.Fortsetzen, Lagen.pausiert, Sicht.LIVE, true).second.rufe)
        assertEquals(listOf("planLaden"), lauf(Befehl.PlanHolen, Lagen.bodenOhnePlan, Sicht.KARTE, true).second.rufe)
        assertEquals(listOf("folgenBeginnen"), lauf(Befehl.FolgenBeginnen, Lagen.m5, Sicht.MSN, true).second.rufe)
        val (g, k, f) = lauf(Befehl.BriefPlanen, Lagen.m6, Sicht.MSN, true)
        assertNull(g); assertEquals(listOf("auftragPlanen"), k.rufe); assertEquals(listOf("flaeche(null)"), f.rufe)
        // Zwischen Vorschlag und Druck hat sich die Lage geändert: nichts geschieht, der Grund kommt zurück.
        for ((b, z) in listOf(Befehl.Heim to Lagen.boden, Befehl.Fortsetzen to Lagen.planLaeuft, Befehl.Fortsetzen to Lagen.boden, Befehl.PlanHolen to Lagen.handflug, Befehl.BriefPlanen to Lagen.m3,
            Befehl.BriefPlanen to Lagen.m5, Befehl.FolgenBeginnen to Lagen.folgen, Befehl.FolgenBeginnen to Lagen.boden, Befehl.Hochladen to Lagen.planLaeuft, Befehl.NochmalFragen to Lagen.boden)) {
            val (grund, kern, _) = lauf(b, z, Sicht.MSN, true)
            assertNotNull("$b", grund); assertTrue("$b: ${kern.rufe}", kern.rufe.isEmpty())
        }
        // Lehnt der Kern den Planbau ab, bleibt die Missions-Fläche offen und sein Grund kommt zurück.
        val kern = Kern(Lagen.m6).apply { planGrund = "No start point yet." }; val buehne = Buehne(Sicht.MSN)
        assertEquals("No start point yet.", Ausfuehrung(kern, buehne).ausfuehren(Befehl.BriefPlanen, true)); assertTrue(buehne.rufe.isEmpty())
    }

    @Test fun briefUndAnsehen() {
        assertEquals(listOf("auftragFeld(h,2)"), lauf(Befehl.FeldSetzen("h", 80), Lagen.m6, Sicht.MSN, false).second.rufe)
        assertEquals(listOf("auftragFeld(d,0)"), lauf(Befehl.FeldSetzen("d", 30), Lagen.m7, Sicht.MSN, false).second.rufe)
        assertTrue(lauf(Befehl.FeldSetzen("h", 55), Lagen.m6, Sicht.MSN, false).second.rufe.isEmpty())
        assertEquals(listOf("auftragText(Ist der Parkplatz frei?)"), lauf(Befehl.BriefText("Ist der Parkplatz frei?"), Lagen.m3, Sicht.MSN, false).second.rufe)
        assertEquals(listOf("mission(6)"), lauf(Befehl.MissionWaehlen(6), Lagen.boden, Sicht.KARTE, false).third.rufe)
        assertTrue(lauf(Befehl.MissionWaehlen(6), Lagen.boden, Sicht.KARTE, false).second.rufe.isEmpty())   // den Brief öffnet die Missions-Fläche selbst, wie beim Druck auf die Kachel
        assertEquals(listOf("auftragWaehlen(0)"), lauf(Befehl.MissionWaehlen(0), Lagen.m6, Sicht.MSN, false).second.rufe)
        assertTrue(lauf(Befehl.MissionWaehlen(6), Lagen.planLaeuft, Sicht.MSN, false).third.rufe.isEmpty())
        assertEquals(listOf("ortWaehlen"), lauf(Befehl.OrtWaehlen, Lagen.m3, Sicht.MSN, false).second.rufe)
        assertEquals(listOf("ortFertig"), lauf(Befehl.OrtFertig, Lagen.ortswahl, Sicht.ORTSWAHL, false).second.rufe)
        assertEquals(listOf("kandidatWaehlen(2)"), lauf(Befehl.KandidatWaehlen(2), Lagen.ergebnis04, Sicht.MSN, false).second.rufe)
        assertEquals(listOf("ansicht(KARTE)"), lauf(Befehl.AnsichtZeigen(Sicht.KARTE), Lagen.planLaeuft, Sicht.LIVE, false).third.rufe)
        assertEquals(listOf("flaeche(SYS)"), lauf(Befehl.FlaecheOeffnen(Sicht.SYS), Lagen.planLaeuft, Sicht.LIVE, false).third.rufe)
        assertEquals(listOf("flaeche(null)"), lauf(Befehl.FlaecheSchliessen, Lagen.boden, Sicht.SYS, false).third.rufe)
        assertEquals(listOf("vollbild(true)"), lauf(Befehl.Vollbild(true), Lagen.boden, Sicht.KARTE, false).third.rufe)
        assertTrue(lauf(Befehl.Vollbild(true), Lagen.pausiert, Sicht.LIVE, false).third.rufe.isEmpty())
        assertEquals(listOf("sprache(de)"), lauf(Befehl.SprechSprache("de"), Lagen.boden, Sicht.KARTE, false).third.rufe)
    }

    /** Der ganze Weg vom Satz bis zum Flugkern, über jede Lage und Sicht: ohne Druck nie mehr als Pausieren oder Folgen beenden. */
    @Test fun vomSatzBisZumKern() {
        for (satz in PrueferTest.SAETZE) for ((lage, z) in Lagen.alle) for (sicht in Sicht.values()) {
            val e = Lagen.entscheide(satz, z, sicht)
            val k = Kern(z); val f = Buehne(sicht)
            if (e is Entscheidung.Ausfuehren) {
                // Was die Prüfschicht freigibt, nimmt die Ausführung im selben Zustand auch an.
                assertNull("$satz / $lage / $sicht", Ausfuehrung(k, f).ausfuehren(e.befehl, false))
            }
            val wirksam = k.rufe.filter { it in flugwirksam }
            assertTrue("$satz / $lage / $sicht: $wirksam", wirksam.all { it == "pausieren" || it == "folgenAufhoeren" })
            if (wirksam.isNotEmpty()) assertTrue("$satz / $lage", z.plan.laeuft || z.folgenAktiv)
            // Ein Vorschlag, sofort gedrückt, wird im selben Zustand angenommen und ruft nie Abheben oder Start.
            if (e is Entscheidung.Vorschlag) { assertNull("$satz / $lage / $sicht", Ausfuehrung(k, f).ausfuehren(e.befehl, true)); assertFalse("abheben" in k.rufe || "starten" in k.rufe) }
        }
        assertNull(Pruefer.grund(Befehl.Anhalten, Sicht.LIVE, Lagen.boden))
    }
}
