package com.felixos.drohne.sprache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Einheitliche Form, Verneinung, Zweifel und Zahlen im gesprochenen Text. */
class WorteTest {
    @Test fun normForm() {
        assertEquals("hoer auf mir zu folgen", Worte.norm("Hör auf, mir zu folgen!"))
        assertEquals("los geht's flieg los", Worte.norm("Los geht’s, flieg los"))
        assertEquals("hoehe 60 m", Worte.norm("  Höhe:   60 m. "))
        assertEquals("take off", Worte.norm("Take-off"))
        assertEquals("", Worte.norm(" ?! "))
    }

    @Test fun verneinung() {
        for (s in listOf("Nicht anhalten", "Don't stop", "do not stop", "Never", "no", "kein Stopp", "keine gute Idee", "nie", "I wouldn't", "Flieg noch nicht nach Hause", "can't", "isn't it"))
            assertTrue(s, Worte.verneint(Worte.norm(s)))
        // Wörter, die nur so aussehen: „Norden“, „Notiz“, „Knoten“, „nichtig“ gibt es als ganze Wörter nicht in der Liste.
        for (s in listOf("Stopp", "Komm nach Hause", "Flieg nach Norden", "now", "know", "Mission eins", "Knoten", "nonstop"))
            assertFalse(s, Worte.verneint(Worte.norm(s)))
    }

    @Test fun frage() {
        for (s in listOf("Soll ich anhalten?", "Stop?", "should i stop", "kann ich weiter", "was passiert", "Wie hoch", "How far", "is it safe", "anhalten wäre gut"))
            assertTrue(s, Worte.fragt(s, Worte.norm(s)))
        for (s in listOf("Stopp", "Halt an", "Komm nach Hause", "Kannst du bitte anhalten", "Warte mal kurz", "Hold on", "Weiter"))
            assertFalse(s, Worte.fragt(s, Worte.norm(s)))
    }

    @Test fun zahlenInZiffernUndWorten() {
        fun z(s: String) = Worte.zahlen(Worte.norm(s))
        assertEquals(listOf(60), z("Höhe 60 Meter"))
        assertEquals(listOf(60), z("Höhe 60m"))
        assertEquals(listOf(60), z("Höhe sechzig Meter"))
        assertEquals(listOf(25), z("Abstand fünfundzwanzig"))
        assertEquals(listOf(120), z("Radius hundertzwanzig"))
        assertEquals(listOf(120), z("radius einhundertundzwanzig"))
        assertEquals(listOf(120), z("radius one hundred and twenty"))
        assertEquals(listOf(120), z("radius a hundred twenty"))
        assertEquals(listOf(45), z("height forty-five"))
        assertEquals(listOf(80), z("set the radius to eighty"))
        assertEquals(listOf(15), z("fifteen meters"))
        assertEquals(listOf(3), z("Mission drei"))
        assertEquals(listOf(3), z("mission 03"))
        assertEquals(listOf(7), z("mission seven"))
        assertEquals(listOf(50, 60), z("fünfzig oder sechzig"))
        assertEquals(listOf(20, 30), z("twenty thirty"))
        // Kein Zahlwort: „ein“, „eine“, „a“, „and“ allein zählen nicht.
        assertEquals(emptyList<Int>(), z("Ich will ein Grundstück vermessen"))
        assertEquals(emptyList<Int>(), z("Such mir eine Stelle"))
        assertEquals(emptyList<Int>(), z("find a spot and a place"))
        assertEquals(emptyList<Int>(), z("Höhe hoch"))
        assertEquals(listOf(100), z("hundert"))
        assertEquals(listOf(1), z("Mission eins"))
    }

    @Test fun katalogUebersetztNurBekannteAufrufe() {
        fun k(name: String, vararg w: Pair<String, String>) = Katalog.ausAufrufen(listOf(Aufruf(name, mapOf(*w))))
        assertEquals(Befehl.AnsichtZeigen(Sicht.KARTE), k("ansicht_zeigen", "sicht" to "karte"))
        assertEquals(Befehl.Unklar, k("ansicht_zeigen", "sicht" to "SYS"))          // eine Fläche ist keine Ansicht
        assertEquals(Befehl.FlaecheOeffnen(Sicht.LOG), k("flaeche_oeffnen", "sicht" to "LOG"))
        assertEquals(Befehl.Unklar, k("flaeche_oeffnen", "sicht" to "ORTSWAHL"))
        assertEquals(Befehl.Vollbild(false), k("vollbild", "an" to "false"))
        assertEquals(Befehl.Unklar, k("vollbild"))
        assertEquals(Befehl.SprechSprache("de"), k("sprech_sprache", "kuerzel" to "DE"))
        assertEquals(Befehl.Unklar, k("sprech_sprache", "kuerzel" to "fr"))
        assertEquals(Befehl.Frage(Auskunft.AKKU), k("frage", "was" to "akku"))
        assertEquals(Befehl.Unklar, k("frage", "was" to "wetter"))
        assertEquals(Befehl.MissionWaehlen(6), k("mission_waehlen", "art" to "6"))
        assertEquals(Befehl.Unklar, k("mission_waehlen", "art" to "8"))
        assertEquals(Befehl.FeldSetzen("r", 80), k("feld_setzen", "id" to "r", "wert" to "80"))
        assertEquals(Befehl.KandidatWaehlen(2), k("kandidat_waehlen", "nr" to "2"))
        assertEquals(Befehl.Unklar, k("kandidat_waehlen", "nr" to "0"))
        assertEquals(Befehl.Anhalten, k(" Anhalten "))
        assertEquals(Befehl.Abgelehnt(Verbot.LANDEN), k("abgelehnt", "was" to "landen"))
        assertEquals(Befehl.Unklar, k("abgelehnt", "was" to "nix"))
        assertEquals(Befehl.AnClaude("What do you see?"), k("an_claude", "frage" to "What do you see?"))
        assertEquals(Befehl.Unklar, k("unklar"))
        // Jeder Befehl des Katalogs hat einen Namen, sonst könnte ein Modell ihn nie liefern.
        val namen = listOf("ansicht_zeigen", "flaeche_oeffnen", "flaeche_schliessen", "vollbild", "einpassen", "sprech_sprache", "frage", "mission_waehlen", "feld_setzen", "brief_text", "ort_waehlen", "ort_zurueck",
            "ort_fertig", "kandidat_waehlen", "brief_aendern", "vermessung_vorbereiten", "anhalten", "heim", "fortsetzen", "plan_holen", "hochladen", "brief_planen", "folgen_beginnen", "nochmal_fragen", "abgelehnt", "an_claude")
        val werte = mapOf("sicht" to "KARTE", "an" to "true", "kuerzel" to "de", "was" to "AKKU", "art" to "3", "id" to "h", "wert" to "60", "text" to "x", "nr" to "1", "frage" to "x")
        val klassen: Set<Class<*>> = namen.map { n -> Katalog.ausAufrufen(listOf(Aufruf(n, if (n == "flaeche_oeffnen") werte + ("sicht" to "SYS") else if (n == "abgelehnt") werte + ("was" to "ABHEBEN") else werte)))::class.java }.toSet()
        assertFalse(Befehl.Unklar::class.java in klassen)
        val katalog: Set<Class<*>> = Befehl::class.java.classes.filter { Befehl::class.java.isAssignableFrom(it) }.toSet()
        assertEquals(27, katalog.size)
        assertEquals(katalog - Befehl.Unklar::class.java, klassen)
        // Und die Liste der Testbefehle deckt den ganzen Katalog ab.
        assertEquals(katalog, Lagen.befehle.map { it::class.java }.toSet())
    }
}
