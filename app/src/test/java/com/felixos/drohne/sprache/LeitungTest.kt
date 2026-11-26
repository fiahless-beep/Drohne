package com.felixos.drohne.sprache

import com.felixos.drohne.CockpitZustand
import com.felixos.drohne.sprache.modell.KeinModell
import com.felixos.drohne.sprache.modell.ModellZustand
import com.felixos.drohne.sprache.modell.stufenZeile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die Leitung: Regeln zuerst, Modell nur für Offenes und nur bis zur Zeitgrenze, jede Entscheidung genau einmal. */
class LeitungTest {
    /** Eine Uhr, die nur läuft, wenn der Test sie stellt. */
    private class Uhr {
        private class Termin(val ms: Long, val tun: () -> Unit, var abgesagt: Boolean = false)
        private val termine = ArrayList<Termin>()
        private var jetzt = 0L
        val stellen: (Long, () -> Unit) -> (() -> Unit) = { ms, tun -> val t = Termin(jetzt + ms, tun); termine += t; { t.abgesagt = true } }
        fun vor(ms: Long) { jetzt += ms; termine.filter { !it.abgesagt && it.ms <= jetzt }.forEach { it.abgesagt = true; it.tun() } }
        val offen: Int get() = termine.count { !it.abgesagt }
    }

    /** Ein Modell, das erst antwortet, wenn der Test es sagt. */
    private class LangsamesModell(override var bereit: Boolean = true) : Verstehen {
        var gefragt = 0; var abgebrochen = 0
        var erlaubt: Set<Class<out Befehl>> = emptySet()
        private var fertig: ((Deutung?) -> Unit)? = null
        override fun verstehe(text: String, sprache: String, erlaubt: Set<Class<out Befehl>>, z: CockpitZustand, fertig: (Deutung?) -> Unit) { gefragt++; this.erlaubt = erlaubt; this.fertig = fertig }
        override fun abbrechen() { abgebrochen++ }
        fun antworte(b: Befehl?) = fertig?.invoke(b?.let { Deutung(it, Quelle.MODELL) })
    }

    @Test fun ohneModellEntscheidenDieRegelnSofort() {
        val uhr = Uhr(); val aus = ArrayList<Entscheidung>()
        SprachLeitung(RegelVerstehen(), null, uhr = uhr.stellen).gehoert("Stopp", "de", Sicht.LIVE, Lagen.planLaeuft) { aus += it }
        assertEquals(1, aus.size); assertTrue(aus[0] is Entscheidung.Ausfuehren); assertEquals(0, uhr.offen)
    }

    @Test fun anhaltenWartetNieAufDasModell() {
        val uhr = Uhr(); val m = LangsamesModell(); val aus = ArrayList<Entscheidung>()
        SprachLeitung(RegelVerstehen(), m, uhr = uhr.stellen).gehoert("Stopp", "de", Sicht.LIVE, Lagen.planLaeuft) { aus += it }
        assertEquals(1, aus.size); assertEquals(Befehl.Anhalten, (aus[0] as Entscheidung.Ausfuehren).befehl)
        assertEquals(0, m.gefragt); assertEquals(0, uhr.offen)
    }

    @Test fun modellAntwortetRechtzeitig() {
        val uhr = Uhr(); val m = LangsamesModell(); val aus = ArrayList<Entscheidung>()
        SprachLeitung(RegelVerstehen(), m, uhr = uhr.stellen).gehoert("kannst du mal auf die landkarte gehen bitte danke", "de", Sicht.LIVE, Lagen.boden) { aus += it }
        assertEquals(0, aus.size); assertEquals(1, m.gefragt); assertEquals(1, uhr.offen)
        // Das Modell bekommt nur, was in dieser Sicht und Lage in Frage kommt.
        assertEquals(Pruefer.erlaubt(Sicht.LIVE, Lagen.boden), m.erlaubt)
        uhr.vor(2000); assertEquals(0, aus.size)
        m.antworte(Befehl.AnsichtZeigen(Sicht.KARTE))
        assertEquals(1, aus.size); assertEquals(Befehl.AnsichtZeigen(Sicht.KARTE), (aus[0] as Entscheidung.Ausfuehren).befehl); assertEquals(0, uhr.offen)
        // Eine zweite Antwort oder die abgelaufene Zeit ändert nichts mehr.
        m.antworte(Befehl.Heim); uhr.vor(5000)
        assertEquals(1, aus.size)
    }

    @Test fun nachDerZeitgrenzeGiltUnklar() {
        val uhr = Uhr(); val m = LangsamesModell(); val aus = ArrayList<Entscheidung>()
        SprachLeitung(RegelVerstehen(), m, grenzeMs = 2500, uhr = uhr.stellen).gehoert("bring sie rein", "de", Sicht.LIVE, Lagen.handflug) { aus += it }
        uhr.vor(2499); assertEquals(0, aus.size)
        uhr.vor(1)
        assertEquals(1, aus.size); assertTrue(aus[0] is Entscheidung.Nichts); assertEquals(1, m.abgebrochen)
        // Die verspätete Antwort wird verworfen: kein zweiter Aufruf, kein Vorschlag.
        m.antworte(Befehl.Heim)
        assertEquals(1, aus.size)
    }

    @Test fun neuerSatzVerwirftDenAlten() {
        val uhr = Uhr(); val m = LangsamesModell(); val aus = ArrayList<Entscheidung>()
        val l = SprachLeitung(RegelVerstehen(), m, uhr = uhr.stellen)
        l.gehoert("bring sie rein", "de", Sicht.LIVE, Lagen.handflug) { aus += it }
        l.gehoert("Stopp", "de", Sicht.LIVE, Lagen.planLaeuft) { aus += it }
        assertEquals(1, aus.size); assertEquals(Befehl.Anhalten, (aus[0] as Entscheidung.Ausfuehren).befehl)
        uhr.vor(10_000)
        assertEquals(1, aus.size)
        // Abbrechen (Streifen geschlossen): nichts kommt mehr an.
        l.gehoert("bring sie rein", "de", Sicht.LIVE, Lagen.handflug) { aus += it }
        l.abbrechen(); uhr.vor(10_000)
        assertEquals(1, aus.size)
    }

    @Test fun modellDasAbstuerztGiltAlsUnklar() {
        val kaputt = object : Verstehen {
            override val bereit = true
            override fun verstehe(text: String, sprache: String, erlaubt: Set<Class<out Befehl>>, z: CockpitZustand, fertig: (Deutung?) -> Unit) = throw IllegalStateException("kaputt")
            override fun abbrechen() = throw IllegalStateException("kaputt")
        }
        val aus = ArrayList<Entscheidung>()
        SprachLeitung(RegelVerstehen(), kaputt, uhr = { _, _ -> {} }).gehoert("bring sie rein", "de", Sicht.LIVE, Lagen.handflug) { aus += it }
        assertEquals(1, aus.size); assertTrue(aus[0] is Entscheidung.Nichts)
        // Und die Regeln gehen weiter, als wäre nichts.
        SprachLeitung(RegelVerstehen(), kaputt, uhr = { _, _ -> {} }).gehoert("Stopp", "de", Sicht.LIVE, Lagen.planLaeuft) { aus += it }
        assertTrue(aus[1] is Entscheidung.Ausfuehren)
    }

    @Test fun heuteGibtEsKeinModell() {
        assertEquals(ModellZustand.FEHLT, KeinModell.zustand)
        assertNull(KeinModell.datei())
        assertFalse(KeinModell.speicherReicht(imFlug = false)); assertFalse(KeinModell.speicherReicht(imFlug = true))
        KeinModell.laden(); KeinModell.entladen()
        assertEquals(ModellZustand.FEHLT, KeinModell.zustand)
        assertEquals("Voice: rules only (local model not installed)", KeinModell.stufenZeile())
        assertFalse(SprachLeitung(RegelVerstehen(), uhr = { _, _ -> {} }).modellBereit)
    }
}
