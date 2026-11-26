package com.felixos.drohne.sprache.modell

/**
 * Hülle für Stufe 1, das lokale Sprachmodell (Entwurf Abschnitt 5.2 und 5.3). Heute ist kein Modell eingebaut und keine
 * Laufzeit im Projekt: der einzige Zustand, den es gibt, ist [ModellZustand.FEHLT], und die App arbeitet mit Stufe 0
 * (den Regeln). Der spätere Einbau ersetzt [KeinModell] durch ein Lager, das die Modelldatei findet und lädt, und gibt
 * dem Cockpit dazu ein Verstehen mit; sonst ändert sich nichts.
 */
enum class ModellZustand { FEHLT, LAEDT, BEREIT, ENTLADEN_SPEICHER, FEHLER, GERAET_ZU_SCHWACH }

interface ModellLager {
    val zustand: ModellZustand
    /** Asset pack, heruntergeladene Datei oder per Kabel abgelegte Datei; null = keine da. */
    fun datei(): java.io.File?
    fun laden()
    fun entladen()
    fun speicherReicht(imFlug: Boolean): Boolean
}

/** Kein Modell auf dem Handy: alles bleibt bei den Regeln. */
object KeinModell : ModellLager {
    override val zustand: ModellZustand get() = ModellZustand.FEHLT
    override fun datei(): java.io.File? = null
    override fun laden() {}
    override fun entladen() {}
    override fun speicherReicht(imFlug: Boolean): Boolean = false
}

/** Die Zeile unter Sys: welche Stufe der Sprachbedienung gerade läuft. */
fun ModellLager.stufenZeile(): String = when (zustand) {
    ModellZustand.FEHLT -> "Voice: rules only (local model not installed)"
    ModellZustand.LAEDT -> "Voice: rules only (local model is loading)"
    ModellZustand.BEREIT -> "Voice: rules and local model"
    ModellZustand.ENTLADEN_SPEICHER -> "Voice: rules only (local model unloaded to free memory)"
    ModellZustand.FEHLER -> "Voice: rules only (local model failed to load)"
    ModellZustand.GERAET_ZU_SCHWACH -> "Voice: rules only (this phone is too weak for the local model)"
}
