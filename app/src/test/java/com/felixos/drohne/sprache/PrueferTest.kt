package com.felixos.drohne.sprache

import com.felixos.drohne.CockpitZustand
import com.felixos.drohne.cockpit.vollbildErlaubt
import com.felixos.drohne.plan.Art
import com.felixos.drohne.plan.Auftragsbau
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Die festen Grenzen der Prüfschicht, die kein Weg umgehen darf, und ihr Gleichlauf mit dem Bestand. */
class PrueferTest {
    private val regeln = RegelVerstehen()
    private fun e(satz: String, z: CockpitZustand, sicht: Sicht) = Lagen.entscheide(satz, z, sicht)

    @Test fun imFlugKeineMissionKeinBriefKeinPlanbau() {
        val fliegend = Lagen.alle.filterValues { Pruefer.imFlug(it) }
        assertTrue(fliegend.keys.containsAll(listOf("planLaeuft", "pausiert", "handflug", "folgen", "funkabriss", "m5")))
        val nurAmBoden = Lagen.befehle.filter { it is Befehl.MissionWaehlen || it is Befehl.FeldSetzen || it is Befehl.BriefText || it == Befehl.OrtWaehlen || it == Befehl.OrtZurueck || it == Befehl.OrtFertig ||
            it is Befehl.KandidatWaehlen || it == Befehl.BriefAendern || it == Befehl.VermessungVorbereiten || it == Befehl.BriefPlanen || it == Befehl.PlanHolen || it == Befehl.Hochladen }
        for ((lage, z) in fliegend) for (sicht in Sicht.values()) for (b in nurAmBoden) {
            assertNotNull("$b im Flug ($lage, $sicht) hat keinen Grund", Pruefer.grund(b, sicht, z))
            assertFalse("$b im Flug ($lage, $sicht) ist erlaubt", b::class.java in Pruefer.erlaubt(sicht, z))
        }
        for (satz in listOf("Mission 3", "Check a place", "Höhe 60 Meter", "Bau den Plan", "Hol den Plan", "Upload again", "Brief ändern", "Ort wählen", "Kandidat 1", "Vermessung vorbereiten", "Ich suche einen Parkplatz"))
            for ((lage, z) in fliegend) for (sicht in Sicht.values()) {
                val r = e(satz, z, sicht)
                assertTrue("$satz ($lage, $sicht) → ${Lagen.text(r)}", r is Entscheidung.Nichts || r is Entscheidung.Antwort)
            }
    }

    @Test fun flugwirksamesNurAlsVorschlag() {
        val faelle = listOf(Triple("Komm nach Hause", Lagen.planLaeuft, Sicht.LIVE) to Befehl.Heim, Triple("Weiter", Lagen.pausiert, Sicht.LIVE) to Befehl.Fortsetzen,
            Triple("Bau den Plan", Lagen.m6, Sicht.MSN) to Befehl.BriefPlanen, Triple("Hol den Plan", Lagen.bodenOhnePlan, Sicht.KARTE) to Befehl.PlanHolen,
            Triple("Upload again", Lagen.boden.copy(plan = Lagen.boden.plan.copy(aufDrohne = false), hauptaktion = CockpitZustand.Hauptaktion.HOCHLADEN), Sicht.KARTE) to Befehl.Hochladen,
            Triple("Fang an zu folgen", Lagen.m5, Sicht.MSN) to Befehl.FolgenBeginnen, Triple("Folge mir", Lagen.m5, Sicht.LIVE) to Befehl.FolgenBeginnen,
            Triple("Ask again", Lagen.boden.copy(auftrag = Lagen.m3.auftrag.copy(nochmalMoeglich = true)), Sicht.MSN) to Befehl.NochmalFragen)
        for ((f, b) in faelle) {
            val r = e(f.first, f.second, f.third)
            assertTrue("${f.first} → ${Lagen.text(r)}", r is Entscheidung.Vorschlag && r.befehl == b)
            assertEquals(Klasse.VORSCHLAG, b.klasse)
        }
        // Folgen beginnt nur mit gehaltenem Knopf (wie in der Missions-Fläche), alles andere mit einem Druck.
        assertEquals(1500L, (e("Fang an zu folgen", Lagen.m5, Sicht.MSN) as Entscheidung.Vorschlag).halteMs)
        assertEquals(0L, (e("Weiter", Lagen.pausiert, Sicht.LIVE) as Entscheidung.Vorschlag).halteMs)
        // Kein Satz der Regeln führt in irgendeiner Lage zu einem sofort ausgeführten Befehl der Klasse VORSCHLAG.
        for ((_, z) in Lagen.alle) for (sicht in Sicht.values()) for (satz in SAETZE) {
            val r = e(satz, z, sicht)
            if (r is Entscheidung.Ausfuehren) assertTrue("$satz → ${Lagen.text(r)}", r.befehl.klasse != Klasse.VORSCHLAG && r.befehl.klasse != Klasse.NIE && r.befehl.klasse != Klasse.CLAUDE)
        }
    }

    @Test fun anhaltenWirktSofortUndNurWennEtwasLaeuft() {
        val p = e("Stopp", Lagen.planLaeuft, Sicht.SYS) as Entscheidung.Ausfuehren
        assertEquals(Befehl.Anhalten, p.befehl)
        val f = e("Stopp", Lagen.folgen, Sicht.MSN) as Entscheidung.Ausfuehren
        assertEquals(Befehl.Anhalten, f.befehl); assertTrue(f.sagt.startsWith("Stop following"))
        assertTrue(e("Stop following", Lagen.folgen, Sicht.LIVE) is Entscheidung.Ausfuehren)
        // Anhalten geht in jeder Sicht.
        for (sicht in Sicht.values()) assertTrue(sicht.name, e("Stopp", Lagen.planLaeuft, sicht) is Entscheidung.Ausfuehren)
        for (lage in listOf("boden", "pausiert", "handflug", "getrennt", "m6")) assertTrue(lage, e("Stopp", Lagen.alle.getValue(lage), Sicht.LIVE) is Entscheidung.Antwort)
        // Nach einem Funkabriss ist der letzte Stand „Plan läuft“: das Anhalten wird trotzdem versucht, es schadet nie.
        assertTrue(e("Stopp", Lagen.funkabriss, Sicht.LIVE) is Entscheidung.Ausfuehren)
    }

    @Test fun orteNiePerSprache() {
        for (satz in listOf("200 Meter nach Norden", "Setz den Ort 100 m nördlich", "Put the place 50 meters east", "Die Ecke 30 Meter nach Westen"))
            for (z in listOf(Lagen.m3, Lagen.ortswahl, Lagen.boden)) for (sicht in listOf(Sicht.MSN, Sicht.ORTSWAHL, Sicht.KARTE)) {
                val r = e(satz, z, sicht)
                assertTrue("$satz → ${Lagen.text(r)}", r is Entscheidung.Antwort && r.text == Pruefer.absage(Verbot.ORT))
            }
        // „Ort wählen“ öffnet nur die Karte.
        assertEquals(Befehl.OrtWaehlen, (e("Ort wählen", Lagen.m3, Sicht.MSN) as Entscheidung.Ausfuehren).befehl)
        assertEquals(Befehl.OrtFertig, (e("Fertig", Lagen.ortswahl, Sicht.ORTSWAHL) as Entscheidung.Ausfuehren).befehl)
        assertEquals(Befehl.OrtZurueck, (e("Undo", Lagen.ortswahl, Sicht.ORTSWAHL) as Entscheidung.Ausfuehren).befehl)
        assertTrue(e("Fertig", Lagen.m6, Sicht.MSN) is Entscheidung.Nichts)
    }

    @Test fun briefFelderNurMitFestenWerten() {
        assertEquals(Befehl.FeldSetzen("h", 80), (e("Height eighty meters", Lagen.m6, Sicht.MSN) as Entscheidung.Ausfuehren).befehl)
        assertEquals(Befehl.FeldSetzen("d", 50), (e("Abstand fünfzig", Lagen.m7, Sicht.MSN) as Entscheidung.Ausfuehren).befehl)
        assertEquals("Search radius can be 50, 80 or 120 m.", (e("Radius 100", Lagen.m4, Sicht.MSN) as Entscheidung.Nichts).sagt)
        assertEquals("Distance outside the zone can be 30 or 50 m.", (e("Distance 40", Lagen.m7, Sicht.MSN) as Entscheidung.Nichts).sagt)
        assertTrue((e("Radius 80", Lagen.m6, Sicht.MSN) as Entscheidung.Nichts).sagt.contains("has no such field"))
        assertEquals("Choose a mission first.", (e("Höhe 60", Lagen.boden, Sicht.MSN) as Entscheidung.Nichts).sagt)
        // Nicht in der Missions-Fläche: der Brief ist nicht zu sehen, also wird er nicht geändert.
        assertTrue(e("Höhe 60", Lagen.m6, Sicht.KARTE) is Entscheidung.Nichts)
        // Zwei Zahlen oder zwei Felder: unklar.
        assertTrue(e("Höhe 60 oder 80", Lagen.m6, Sicht.MSN) is Entscheidung.Nichts)
        assertTrue(e("Höhe und Radius 50", Lagen.m7, Sicht.MSN) is Entscheidung.Nichts)
    }

    @Test fun missionUeberNummerOderTitelwort() {
        val erwartet = mapOf("Mission 1" to 1, "Mission zwei" to 2, "Mission three" to 3, "Open mission 4" to 4, "Mission fünf" to 5, "Mission 06" to 6, "Mission seven" to 7,
            "Fly a plan" to 1, "Subject on the way" to 2, "Check a place" to 3, "Find a spot" to 4, "Follow" to 5, "Survey a property" to 6, "Atmosphere" to 7,
            "Ort prüfen" to 3, "Such mir einen Platz" to 4, "Folge mir" to 5, "Grundstück vermessen" to 6, "Stimmung" to 7)
        for ((satz, art) in erwartet) {
            val r = e(satz, Lagen.boden, Sicht.MSN)
            assertTrue("$satz → ${Lagen.text(r)}", r is Entscheidung.Ausfuehren && r.befehl == Befehl.MissionWaehlen(art))
        }
        assertTrue(e("Mission 8", Lagen.boden, Sicht.MSN) is Entscheidung.Nichts)
        assertTrue(e("Mission 3 oder 4", Lagen.boden, Sicht.MSN) is Entscheidung.Nichts)
        // Eine Mission mit Plan oder Antwort wird per Sprache nicht fallen gelassen; ein halber Brief schon.
        assertEquals(Befehl.MissionWaehlen(0), (e("Brief verwerfen", Lagen.m6, Sicht.MSN) as Entscheidung.Ausfuehren).befehl)
        assertTrue(e("Brief verwerfen", Lagen.ergebnis04, Sicht.MSN) is Entscheidung.Nichts)
        // Die Antwort einer Mission geht per Sprache nicht verloren (Felix 23.11.2026: Rückfrage vor dem Verwerfen).
        assertTrue((e("Brief ändern", Lagen.ergebnis04, Sicht.MSN) as Entscheidung.Nichts).sagt.contains("removes the answer"))
        assertEquals(Befehl.KandidatWaehlen(2), (e("Kandidat 2", Lagen.ergebnis04, Sicht.MSN) as Entscheidung.Ausfuehren).befehl)
        assertTrue((e("Kandidat 5", Lagen.ergebnis04, Sicht.MSN) as Entscheidung.Nichts).sagt.contains("There is no candidate 5"))
    }

    @Test fun briefTextNurWoertlichUndNurFuer03Und04() {
        assertEquals(Befehl.BriefText("einen Parkplatz für das Auto"), (e("Ich suche einen Parkplatz für das Auto", Lagen.m4, Sicht.MSN) as Entscheidung.Ausfuehren).befehl)
        assertEquals(Befehl.BriefText("is the gate open"), (e("The question is is the gate open", Lagen.m3, Sicht.MSN) as Entscheidung.Ausfuehren).befehl)
        // Der Inhalt ist frei: ein „nicht“ darin ist keine Verneinung des Befehls.
        assertEquals(Befehl.BriefText("Steht da kein Auto?"), (e("Die Frage ist: Steht da kein Auto?", Lagen.m3, Sicht.MSN) as Entscheidung.Ausfuehren).befehl)
        assertTrue(e("Ich suche einen Parkplatz", Lagen.m6, Sicht.MSN) is Entscheidung.Nichts)
        assertFalse(Pruefer.verankert(Befehl.BriefText("Ist die Straße frei?"), "Die Frage ist: Ist der Parkplatz frei?", Worte.norm("Die Frage ist: Ist der Parkplatz frei?")))
        assertNotNull(Pruefer.grund(Befehl.BriefText("x"), Sicht.MSN, Lagen.m6))
    }

    @Test fun absagenInJederLage() {
        val erwartet = mapOf("Abheben" to Verbot.ABHEBEN, "Heb jetzt ab" to Verbot.ABHEBEN, "Take off please" to Verbot.ABHEBEN, "Starte die Mission" to Verbot.STARTEN, "Start the plan" to Verbot.STARTEN,
            "Flieg los" to Verbot.STARTEN, "Let's go" to Verbot.STARTEN, "Lande hier" to Verbot.LANDEN, "Land now" to Verbot.LANDEN, "Flieg höher" to Verbot.STEUERN, "Go left" to Verbot.STEUERN,
            "Schneller" to Verbot.STEUERN, "Mach ein Foto" to Verbot.STEUERN, "Schalte die Hinderniserkennung aus" to Verbot.HINDERNISERKENNUNG, "Turn off obstacle sensing" to Verbot.HINDERNISERKENNUNG,
            "Wächter aus" to Verbot.WAECHTER, "Ja" to Verbot.BESTAETIGEN, "Ja, mach das" to Verbot.BESTAETIGEN, "Okay do it" to Verbot.BESTAETIGEN, "Selbsttest starten" to Verbot.EINSTELLUNG,
            "Höhenlimit ändern" to Verbot.EINSTELLUNG, "Flieg nach Norden" to Verbot.STEUERN)
        for ((satz, v) in erwartet) for ((lage, z) in Lagen.alle) for (sicht in listOf(Sicht.LIVE, Sicht.MSN)) {
            val r = e(satz, z, sicht)
            assertTrue("$satz ($lage) → ${Lagen.text(r)}", r is Entscheidung.Antwort && r.text == Pruefer.absage(v))
        }
    }

    @Test fun vollbildRegelWieImCockpit() {
        val lagen = Lagen.alle.values + Lagen.planLaeuft.copy(warnung = CockpitZustand.Warnung("WIND", "x")) + Lagen.planLaeuft.copy(keineDatenSeitS = 4)
        for (z in lagen) assertEquals(vollbildErlaubt(z), Pruefer.vollbildFrei(z))
        for (z in lagen) for (sicht in Sicht.values()) assertEquals(sicht.istAnsicht && vollbildErlaubt(z), Pruefer.grund(Befehl.Vollbild(true), sicht, z) == null)
    }

    @Test fun titelUndFelderWieImPlanPaket() {
        for (a in Art.values()) assertEquals(a.titel, Pruefer.TITEL[a.nr])
        assertEquals(Art.values().size, Pruefer.TITEL.size)
        assertEquals(Art.values().map { it.nr }.toSet(), Katalog.TITELWORT.keys)
        // Jeder englische Titel wählt seine Mission, und nur die.
        for (a in Art.values()) assertEquals(listOf(a.nr), Katalog.TITELWORT.filterValues { it.containsMatchIn(Worte.norm(a.titel)) }.keys.toList())
        assertEquals(Katalog.FELDER, Art.values().flatMap { Auftragsbau.felder(it) }.map { it.id }.toSet())
        // Jeder feste Wert jedes Feldes lässt sich als Zahl sagen, in Ziffern.
        for (a in Art.values()) for (f in Auftragsbau.felder(a)) for (w in f.werte) assertEquals(listOf(w), Worte.zahlen("$w"))
    }

    /** Was nicht erlaubt ist, hat einen Grund; was einen Grund hat, wird nie ausgeführt oder vorgeschlagen. */
    @Test fun erlaubtUndGrundPassenZusammen() {
        for (b in Lagen.befehle) for ((lage, z) in Lagen.alle) for (sicht in Sicht.values()) {
            val wo = "$b / $lage / $sicht"
            if (b::class.java !in Pruefer.erlaubt(sicht, z)) assertNotNull(wo, Pruefer.grund(b, sicht, z))
        }
        assertNull(Pruefer.grund(Befehl.Anhalten, Sicht.SET, Lagen.getrennt))
    }

    @Test fun unklaresNenntZweiBeispieleAusDerSicht() {
        for ((lage, z) in Lagen.alle) for (sicht in Sicht.values()) {
            val r = e("Spiel Musik", z, sicht) as Entscheidung.Nichts
            val (a, b) = Pruefer.beispiele(sicht, z)
            assertEquals("Not understood. Nothing done. Here for example: “$a” · “$b”", r.sagt)
            // Die Beispiele sind selbst Sätze, die in dieser Lage und Sicht etwas ergeben (kein „nicht verstanden“).
            for (bsp in listOf(a, b)) { val e2 = e(bsp, z, sicht); assertFalse("$bsp ($lage, $sicht) → ${Lagen.text(e2)}", e2 is Entscheidung.Nichts && e2.sagt.startsWith("Not understood")) }
        }
    }

    @Test fun echteFragenGehenAnCentralCommandAllesFremdeNicht() {
        for (satz in listOf("What do you see?", "Look closer at the roof", "Was siehst du da unten?", "What went wrong in the last flight?", "Was ist im letzten Flug schiefgegangen?",
            "Where do I switch the map?", "Which mission do I need to check a parking lot?", "Erklär mir den Plan-Check", "Was bedeutet Transit height?")) {
            val r = e(satz, Lagen.pausiert, Sicht.LIVE)
            assertTrue("$satz → ${Lagen.text(r)}", r is Entscheidung.AnClaude && r.frage == satz)
        }
        for (satz in listOf("Wie wird das Wetter morgen?", "Spiel Musik", "Erzähl einen Witz", "Ruf Mama an", "Hmm", "Banane", "Wer hat gestern gewonnen"))
            for ((lage, z) in Lagen.alle) { val r = e(satz, z, Sicht.LIVE); assertTrue("$satz ($lage) → ${Lagen.text(r)}", r is Entscheidung.Nichts) }
    }

    /** Das Paket sprache bleibt reines Kotlin: kein Android, kein SDK, vom App-Paket nur CockpitZustand. */
    @Test fun sprachGrenze() {
        val dir = listOf("src/main/java/com/felixos/drohne/sprache", "app/src/main/java/com/felixos/drohne/sprache").map(::File).first { it.isDirectory }
        val dateien = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(dateien.size >= 7)
        val re = Regex("""com\.felixos\.drohne\.([A-Za-z_]+)""")
        val treffer = dateien.flatMap { f -> f.readLines().mapIndexedNotNull { i, z ->
            val fremd = re.findAll(z).map { it.groupValues[1] }.filter { it != "sprache" && it != "CockpitZustand" }.toList()
            if (z.contains("import android") || z.contains("import androidx") || z.contains("dji.") || z.contains("litert", ignoreCase = true) && z.trimStart().startsWith("import") || fremd.isNotEmpty()) "${f.name}:${i + 1}" else null } }
        assertTrue("sprache greift über seine Grenze: $treffer", treffer.isEmpty())
    }

    companion object {
        /** Sätze quer durch den Katalog, für Tests, die über alle Lagen und Sichten laufen. */
        val SAETZE = listOf("Stopp", "Halt an", "Komm nach Hause", "Stopp und komm heim", "Weiter", "Resume the plan", "Bau den Plan", "Hol den Plan", "Upload again", "Fang an zu folgen", "Folge mir",
            "Ask again", "Abheben", "Starte den Plan", "Los", "Go", "Mission 3", "Check a place", "Höhe 60 Meter", "Radius 80", "Kandidat 1", "Brief ändern", "Brief verwerfen", "Ort wählen", "Fertig", "Undo",
            "Vermessung vorbereiten", "Zeig mir die Karte", "Livebild", "3D", "Systemstatus", "Open the log", "Settings", "Mach das zu", "Vollbild", "Full screen off", "Fit", "Sprich Englisch",
            "Wie viel Akku", "Ist der Plan startklar?", "Was fehlt im Brief?", "What do you see?", "Ja, mach das", "Lande hier", "Spiel Musik", "Die Frage ist: Ist der Parkplatz frei?", "Ich suche einen Parkplatz")
    }
}
