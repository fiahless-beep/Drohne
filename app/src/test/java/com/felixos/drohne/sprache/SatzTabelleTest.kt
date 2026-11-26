package com.felixos.drohne.sprache

import com.felixos.drohne.CockpitZustand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die 66 Testsätze aus dem Entwurf (drohne/architektur/lokales-sprachmodell.md, Abschnitt 7.2) als Tabelle: Satz, Lage,
 * Sicht, erwartete Deutung der Regeln (Stufe 0) und erwartete Entscheidung der Prüfschicht. Sätze, die Stufe 0 bewusst
 * nicht deutet und die später das Modell lösen soll, wären hier mit „Stufe 1“ markiert und erwarteten „nichts tun“;
 * derzeit löst Stufe 0 alle 66. Flugwirksame Fälle und Verneinungen müssen in jedem Fall stimmen.
 */
class SatzTabelleTest {
    private class Zeile(val nr: Int, val satz: String, val z: CockpitZustand, val sicht: Sicht, val deutung: Befehl?, val dazu: Befehl?, val was: String, val passt: (Entscheidung) -> Boolean)

    private val zeilen = ArrayList<Zeile>()
    private fun satz(nr: Int, satz: String, z: CockpitZustand, deutung: Befehl?, was: String, sicht: Sicht = if (z.fliegt) Sicht.LIVE else Sicht.KARTE, dazu: Befehl? = null, passt: (Entscheidung) -> Boolean) {
        zeilen += Zeile(nr, satz, z, sicht, deutung, dazu, was, passt)
    }

    // Erwartungen
    private fun sofort(b: Befehl): (Entscheidung) -> Boolean = { it is Entscheidung.Ausfuehren && it.befehl == b && it.dazu == null }
    private fun druck(b: Befehl): (Entscheidung) -> Boolean = { it is Entscheidung.Vorschlag && it.befehl == b }
    private fun sagt(text: String): (Entscheidung) -> Boolean = { it is Entscheidung.Antwort && it.text == text }
    private fun auskunft(a: Auskunft, z: CockpitZustand) = sagt(Auskuenfte.antwort(a, z))
    private fun absage(v: Verbot) = sagt(Pruefer.absage(v))
    private fun nichts(grund: String? = null): (Entscheidung) -> Boolean = { it is Entscheidung.Nichts && (grund == null || it.sagt.contains(grund)) }
    private val unklar = nichts("Not understood. Nothing done.")
    private val claude: (Entscheidung) -> Boolean = { it is Entscheidung.AnClaude }

    init {
        val b = Lagen.boden; val p = Lagen.planLaeuft; val a = Lagen.pausiert; val h = Lagen.handflug; val f = Lagen.folgen
        val msn = Sicht.MSN
        // Anhalten
        satz(1, "Stopp", p, Befehl.Anhalten, "Anhalten sofort", passt = sofort(Befehl.Anhalten))
        satz(2, "Halt an", p, Befehl.Anhalten, "Anhalten sofort", passt = sofort(Befehl.Anhalten))
        satz(3, "Warte mal kurz", p, Befehl.Anhalten, "Anhalten sofort", passt = sofort(Befehl.Anhalten))
        satz(4, "Drohne anhalten", p, Befehl.Anhalten, "Anhalten sofort", passt = sofort(Befehl.Anhalten))
        satz(5, "Stop here", p, Befehl.Anhalten, "Anhalten sofort", passt = sofort(Befehl.Anhalten))
        satz(6, "Hold on", p, Befehl.Anhalten, "Anhalten sofort", passt = sofort(Befehl.Anhalten))
        satz(7, "Hör auf mir zu folgen", f, Befehl.Anhalten, "Anhalten sofort (Folgen beenden)") { it is Entscheidung.Ausfuehren && it.befehl == Befehl.Anhalten && it.sagt.startsWith("Stop following") }
        satz(8, "Nicht anhalten", p, Befehl.Unklar, "nichts tun (Verneinung)", passt = nichts())
        satz(9, "Don't stop", p, Befehl.Unklar, "nichts tun (Verneinung)", passt = nichts())
        satz(10, "Soll ich anhalten?", p, Befehl.Frage(Auskunft.STATUS), "Auskunft STATUS, keine Handlung", passt = auskunft(Auskunft.STATUS, p))
        satz(11, "Stopp", b, Befehl.Anhalten, "Ansage „Nothing is flying“", passt = sagt("Nothing is flying."))
        // Heim
        satz(12, "Komm nach Hause", p, Befehl.Heim, "Heim mit Druck", passt = druck(Befehl.Heim))
        satz(13, "Flieg zurück", h, Befehl.Heim, "Heim mit Druck", passt = druck(Befehl.Heim))
        satz(14, "Return home", p, Befehl.Heim, "Heim mit Druck", passt = druck(Befehl.Heim))
        satz(15, "Stopp und komm heim", p, Befehl.Anhalten, "Anhalten sofort, dazu Heim mit Druck", dazu = Befehl.Heim) { it is Entscheidung.Ausfuehren && it.befehl == Befehl.Anhalten && it.dazu?.befehl == Befehl.Heim }
        satz(16, "Flieg noch nicht nach Hause", p, Befehl.Unklar, "nichts tun (Verneinung)", passt = nichts())
        satz(17, "Come home", b, Befehl.Heim, "Ansage „Nothing is flying“", passt = sagt("Nothing is flying."))
        // Nie
        satz(18, "Abheben", b, Befehl.Abgelehnt(Verbot.ABHEBEN), "abgelehnt", passt = absage(Verbot.ABHEBEN))
        satz(19, "Take off now", b, Befehl.Abgelehnt(Verbot.ABHEBEN), "abgelehnt", passt = absage(Verbot.ABHEBEN))
        satz(20, "Starte den Plan", b, Befehl.Abgelehnt(Verbot.STARTEN), "abgelehnt", passt = absage(Verbot.STARTEN))
        satz(21, "Los geht's, flieg los", b, Befehl.Abgelehnt(Verbot.STARTEN), "abgelehnt", passt = absage(Verbot.STARTEN))
        satz(22, "Lande hier", h, Befehl.Abgelehnt(Verbot.LANDEN), "abgelehnt", passt = absage(Verbot.LANDEN))
        satz(23, "Flieg höher", h, Befehl.Abgelehnt(Verbot.STEUERN), "abgelehnt", passt = absage(Verbot.STEUERN))
        satz(24, "Schalte die Hinderniserkennung aus", a, Befehl.Abgelehnt(Verbot.HINDERNISERKENNUNG), "abgelehnt", passt = absage(Verbot.HINDERNISERKENNUNG))
        satz(25, "Ja, mach das", a, Befehl.Abgelehnt(Verbot.BESTAETIGEN), "abgelehnt (ein Vorschlag war offen)", passt = absage(Verbot.BESTAETIGEN))
        // Fortsetzen
        satz(26, "Weiter", a, Befehl.Fortsetzen, "Fortsetzen mit Druck", passt = druck(Befehl.Fortsetzen))
        satz(27, "Resume the plan", a, Befehl.Fortsetzen, "Fortsetzen mit Druck", passt = druck(Befehl.Fortsetzen))
        satz(28, "Weiter", b, Befehl.Fortsetzen, "nichts tun", passt = nichts("No plan is waiting to be resumed."))
        // Navigation
        satz(29, "Zeig mir die Karte", b, Befehl.AnsichtZeigen(Sicht.KARTE), "sofort", sicht = Sicht.LIVE, passt = sofort(Befehl.AnsichtZeigen(Sicht.KARTE)))
        satz(30, "Livebild", p, Befehl.AnsichtZeigen(Sicht.LIVE), "sofort", sicht = Sicht.KARTE, passt = sofort(Befehl.AnsichtZeigen(Sicht.LIVE)))
        satz(31, "Show me the 3D view", b, Befehl.AnsichtZeigen(Sicht.DREI_D), "sofort", passt = sofort(Befehl.AnsichtZeigen(Sicht.DREI_D)))
        satz(32, "Öffne die Missionen", b, Befehl.FlaecheOeffnen(Sicht.MSN), "sofort", passt = sofort(Befehl.FlaecheOeffnen(Sicht.MSN)))
        satz(33, "Open the log", b, Befehl.FlaecheOeffnen(Sicht.LOG), "sofort", passt = sofort(Befehl.FlaecheOeffnen(Sicht.LOG)))
        satz(34, "Systemstatus", p, Befehl.FlaecheOeffnen(Sicht.SYS), "sofort", passt = sofort(Befehl.FlaecheOeffnen(Sicht.SYS)))
        satz(35, "Mach das zu", b, Befehl.FlaecheSchliessen, "sofort (Fläche offen)", sicht = Sicht.SYS, passt = sofort(Befehl.FlaecheSchliessen))
        satz(36, "Vollbild", b, Befehl.Vollbild(true), "sofort", passt = sofort(Befehl.Vollbild(true)))
        satz(37, "Full screen", a, Befehl.Vollbild(true), "nichts tun; Grund: im Pausenzustand nicht erlaubt", passt = nichts("while the plan is paused"))
        satz(38, "Sprich Deutsch mit mir", b, Befehl.SprechSprache("de"), "sofort", passt = sofort(Befehl.SprechSprache("de")))
        // Fragen ohne Central Command
        satz(39, "Wie viel Akku habe ich noch?", p, Befehl.Frage(Auskunft.AKKU), "Auskunft", passt = auskunft(Auskunft.AKKU, p))
        satz(40, "How much battery is left?", p, Befehl.Frage(Auskunft.AKKU), "Auskunft", passt = auskunft(Auskunft.AKKU, p))
        satz(41, "Wie lange kann ich noch fliegen?", h, Befehl.Frage(Auskunft.RESTZEIT), "Auskunft", passt = auskunft(Auskunft.RESTZEIT, h))
        satz(42, "Wie hoch ist die Drohne?", p, Befehl.Frage(Auskunft.HOEHE), "Auskunft", passt = auskunft(Auskunft.HOEHE, p))
        satz(43, "How far away is it?", h, Befehl.Frage(Auskunft.ENTFERNUNG_HEIM), "Auskunft", passt = auskunft(Auskunft.ENTFERNUNG_HEIM, h))
        satz(44, "Wie stark ist der Wind?", p, Befehl.Frage(Auskunft.WIND), "Auskunft", passt = auskunft(Auskunft.WIND, p))
        satz(45, "Habe ich GPS?", b, Befehl.Frage(Auskunft.GPS), "Auskunft", passt = auskunft(Auskunft.GPS, b))
        satz(46, "Ist der Plan startklar?", b, Befehl.Frage(Auskunft.STARTKLAR), "Auskunft", passt = auskunft(Auskunft.STARTKLAR, b))
        satz(47, "What is missing for the start?", b, Befehl.Frage(Auskunft.STARTKLAR), "Auskunft", passt = auskunft(Auskunft.STARTKLAR, b))
        satz(48, "Was kommt als Nächstes?", p, Befehl.Frage(Auskunft.NAECHSTES), "Auskunft", passt = auskunft(Auskunft.NAECHSTES, p))
        satz(49, "Was heißt die Warnung?", p, Befehl.Frage(Auskunft.WARNUNG), "Auskunft", passt = auskunft(Auskunft.WARNUNG, p))
        satz(50, "Was passiert bei Funkabriss?", b, Befehl.Frage(Auskunft.FUNKABRISS), "Auskunft", passt = auskunft(Auskunft.FUNKABRISS, b))
        // Missionen
        satz(51, "Ich will ein Grundstück vermessen", b, Befehl.MissionWaehlen(6), "sofort", passt = sofort(Befehl.MissionWaehlen(6)))
        satz(52, "Check a place", b, Befehl.MissionWaehlen(3), "sofort", passt = sofort(Befehl.MissionWaehlen(3)))
        satz(53, "Folge mir", b, Befehl.MissionWaehlen(5), "sofort: Brief zeigen, nicht beginnen", passt = sofort(Befehl.MissionWaehlen(5)))
        satz(54, "Ich will ein Grundstück vermessen", p, Befehl.MissionWaehlen(6), "nichts tun; Grund: im Flug nicht", passt = nichts("chosen on the ground"))
        satz(55, "Höhe sechzig Meter", Lagen.m6, Befehl.FeldSetzen("h", 60), "sofort", sicht = msn, passt = sofort(Befehl.FeldSetzen("h", 60)))
        satz(56, "Set the radius to 80", Lagen.m7, Befehl.FeldSetzen("r", 80), "sofort", sicht = msn, passt = sofort(Befehl.FeldSetzen("r", 80)))
        satz(57, "Höhe 55 Meter", Lagen.m6, Befehl.FeldSetzen("h", 55), "nichts gesetzt; Ansage der festen Werte", sicht = msn) { it is Entscheidung.Nichts && it.sagt == "Height can be 50, 60 or 80 m." }
        satz(58, "Die Frage ist: Ist der Parkplatz frei?", Lagen.m3, Befehl.BriefText("Ist der Parkplatz frei?"), "sofort", sicht = msn, passt = sofort(Befehl.BriefText("Ist der Parkplatz frei?")))
        satz(59, "Bau den Plan", Lagen.m6, Befehl.BriefPlanen, "Planbau mit Druck", sicht = msn, passt = druck(Befehl.BriefPlanen))
        satz(60, "Fang an zu folgen", Lagen.m5, Befehl.FolgenBeginnen, "Folgen mit (gehaltenem) Druck", sicht = msn) { it is Entscheidung.Vorschlag && it.befehl == Befehl.FolgenBeginnen && it.halteMs == 1500L }
        // An Central Command
        satz(61, "Was siehst du da unten?", a, Befehl.AnClaude("Was siehst du da unten?"), "an Central Command", passt = claude)
        satz(62, "Schau dir das Dach genauer an", a, Befehl.AnClaude("Schau dir das Dach genauer an"), "an Central Command", passt = claude)
        // Fremdes
        satz(63, "Wie wird das Wetter morgen?", b, Befehl.Unklar, "nichts tun", passt = unklar)
        satz(64, "Spiel Musik", b, Befehl.Unklar, "nichts tun", passt = unklar)
        satz(65, "Ähm, also, ich weiß nicht", p, Befehl.Unklar, "nichts tun", passt = unklar)
        satz(66, "Anhalten wäre jetzt keine gute Idee", p, Befehl.Unklar, "nichts tun (Verneinung)", passt = nichts())
    }

    @Test fun alle66SaetzeStehenInDerTabelle() = assertEquals((1..66).toList(), zeilen.map { it.nr })

    @Test fun dieRegelnDeutenJedenSatzWieErwartet() {
        val falsch = zeilen.mapNotNull { r ->
            val d = RegelVerstehen().deute(r.satz, Pruefer.erlaubt(r.sicht, r.z), r.z)
            if (d.befehl == r.deutung && d.dazu == r.dazu && d.quelle == Quelle.REGEL) null else "${r.nr} „${r.satz}“: erwartet ${r.deutung}, war $d"
        }
        assertTrue(falsch.joinToString("\n"), falsch.isEmpty())
    }

    @Test fun diePruefschichtEntscheidetJedenSatzWieErwartet() {
        val falsch = zeilen.mapNotNull { r ->
            val e = Lagen.entscheide(r.satz, r.z, r.sicht)
            if (r.passt(e)) null else "${r.nr} „${r.satz}“: erwartet ${r.was}, war ${Lagen.text(e)}"
        }
        assertTrue(falsch.joinToString("\n"), falsch.isEmpty())
    }

    /** Ohne Fragezeichen und Großschreibung (so liefert es die Spracherkennung oft) bleibt jede Entscheidung von derselben Art. */
    @Test fun ohneSatzzeichenBleibtDieEntscheidungGleich() {
        val falsch = zeilen.filter { it.nr != 58 }.mapNotNull { r ->
            val roh = r.satz.replace(Regex("[?.!,]"), "").lowercase()
            val e = Lagen.entscheide(roh, r.z, r.sicht); val soll = Lagen.entscheide(r.satz, r.z, r.sicht)
            val gleich = e::class == soll::class && (e as? Entscheidung.Ausfuehren)?.befehl == (soll as? Entscheidung.Ausfuehren)?.befehl && (e as? Entscheidung.Vorschlag)?.befehl == (soll as? Entscheidung.Vorschlag)?.befehl &&
                (e as? Entscheidung.Antwort)?.text == (soll as? Entscheidung.Antwort)?.text
            if (gleich) null else "${r.nr} „$roh“: war ${Lagen.text(e)}, mit Satzzeichen ${Lagen.text(soll)}"
        }
        assertTrue(falsch.joinToString("\n"), falsch.isEmpty())
    }
}
