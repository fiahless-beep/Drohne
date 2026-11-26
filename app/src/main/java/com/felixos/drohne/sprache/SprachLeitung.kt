package com.felixos.drohne.sprache

import com.felixos.drohne.CockpitZustand

/**
 * Die Leitung vom gehörten Satz zur [Entscheidung] (Entwurf Abschnitt 5.1): Stufe 0 (Regeln) rechnet immer zuerst und
 * wartet nie; trifft sie, ist entschieden, bevor ein Modell überhaupt gefragt wird (Anhalten zuerst). Nur was die Regeln
 * nicht deuten, geht an Stufe 1 (das Modell), wenn eins da und bereit ist, und nur bis zur Zeitgrenze. Jede Deutung, von
 * wem auch immer, geht durch den [Pruefer]. Ohne Modell läuft alles mit Stufe 0.
 *
 * [uhr] ruft die Handlung nach so vielen Millisekunden auf dem UI-Thread und gibt etwas zum Absagen zurück; so bleibt die
 * Leitung reines Kotlin und im Test ohne Wartezeit prüfbar.
 */
class SprachLeitung(
    private val regeln: Verstehen,
    private val modell: Verstehen? = null,
    private val grenzeMs: Long = 2500,
    private val uhr: (Long, () -> Unit) -> (() -> Unit) = { _, _ -> {} },
) {
    private var lauf = 0
    private var absagen: (() -> Unit)? = null
    /** Das Modell rechnet gerade an einem Satz. */
    private var beimModell = false

    /** Welche Stufe gerade arbeitet (für die Anzeige unter Sys). */
    val modellBereit: Boolean get() = modell?.bereit == true

    /** [fertig] wird genau einmal gerufen, außer ein neuer Satz oder [abbrechen] kommt dazwischen. Alles auf dem UI-Thread. */
    fun gehoert(text: String, sprache: String, sicht: Sicht, z: CockpitZustand, fertig: (Entscheidung) -> Unit) {
        abbrechen()
        val nr = lauf
        val erlaubt = Pruefer.erlaubt(sicht, z)
        var regel: Deutung? = null
        regeln.verstehe(text, sprache, erlaubt, z) { regel = it }
        val m = modell
        if (!offen(regel) || m == null || !m.bereit) return fertig(Pruefer.pruefe(regel, text, sicht, z))
        // Stufe 1: nur für das, was die Regeln offen lassen, und nur bis zur Zeitgrenze.
        var erledigt = false
        fun ende(d: Deutung?) {
            if (erledigt || nr != lauf) return
            erledigt = true
            absagen?.invoke(); absagen = null
            fertig(Pruefer.pruefe(zusammen(regel, d), text, sicht, z))
        }
        absagen = uhr(grenzeMs) { if (!erledigt && nr == lauf) { beimModell = false; try { m.abbrechen() } catch (_: Throwable) {}; ende(null) } }
        beimModell = true
        try { m.verstehe(text, sprache, erlaubt, z) { beimModell = false; ende(it) } } catch (_: Throwable) { beimModell = false; ende(null) }
    }

    /** Ein laufendes Verstehen verwerfen (der Streifen wurde geschlossen, ein neuer Satz kommt). */
    fun abbrechen() {
        lauf++
        absagen?.invoke(); absagen = null
        if (beimModell) { beimModell = false; try { modell?.abbrechen() } catch (_: Throwable) {} }
    }

    companion object {
        /** Die Regeln haben nichts gedeutet: nur dann darf ein Modell gefragt werden. */
        fun offen(regel: Deutung?): Boolean = regel == null || regel.befehl is Befehl.Unklar

        /**
         * Regel und Modell zusammenführen (Sicherheitsregel 5b): Haben die Regeln etwas gedeutet, gilt das; ein Modell kann
         * dem nicht widersprechen. Nur wo die Regeln nichts sagen, zählt das Modell, und nur mit genau einem Befehl
         * ([Deutung.dazu] gibt es aus dem Modell nicht).
         */
        fun zusammen(regel: Deutung?, modell: Deutung?): Deutung? = when {
            !offen(regel) -> regel
            modell == null || modell.quelle != Quelle.MODELL || modell.dazu != null -> regel
            else -> modell
        }
    }
}
