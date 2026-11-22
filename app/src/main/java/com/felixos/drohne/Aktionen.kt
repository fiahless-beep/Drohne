package com.felixos.drohne

/**
 * Vertrag Oberfläche → Flugkern, schreibende Seite: jede flugwirksame Handlung des Cockpits.
 * Jede Aktion prüft im Kern selbst, ob sie zulässig ist; die Oberfläche bekommt Ergebnis und Gründe zurück.
 * Stand 22.11.2026 (Zielbild Schritt 3a und der Anfang von 3b): Plan und Flug. Sicherungen, Gerät und Daten
 * ruft der Gastgeber (MainActivity) noch direkt am Kern auf; sie folgen als weitere Aktionen.
 */
interface Aktionen {
    /** Plan aus der Cloud holen, prüfen und auf die Drohne laden. Rückfragen kommen über [Anzeige.frage]. */
    fun planLaden()
    /** Den geladenen Plan noch einmal auf die Drohne laden. */
    fun hochladen()
    /** Startprüfung jetzt; leer = startklar. */
    fun startProbleme(): List<String>
    /** Start vom Boden. Rechnet die Startprüfung selbst und verweigert, solange ein Problem besteht. */
    fun starten(): StartErgebnis
    fun pausieren()
    /** Pausierten Plan fortsetzen; wartet ein Hauptplan hinter einem Umweg, dessen Rest ab hier. */
    fun fortsetzen()
    /** Folgen beenden, Plan abbrechen, Heimflug auslösen. */
    fun stoppUndHeim()
    /** Was der Hauptknopf bei einem Druck tut (Reihenfolge wie bisher in der Activity); null = gerade nichts. */
    fun hauptaktionWaehlen(): CockpitZustand.Hauptaktion?
    fun zustand(): CockpitZustand
}

sealed class StartErgebnis {
    object Gestartet : StartErgebnis()
    class Verweigert(val probleme: List<String>) : StartErgebnis()
}

/** Was der Flugkern von der Oberfläche braucht: Meldungen zeigen, Rückfragen stellen, neu zeichnen. Alles auf dem UI-Thread. */
interface Anzeige {
    fun meldung(titel: String, text: String)
    fun frage(titel: String, text: String, ja: String, nein: String, abbrechbar: Boolean, antwort: (Boolean) -> Unit)
    /** Kurzer Hinweis, der von selbst verschwindet. */
    fun hinweis(text: String)
    /** Zustand hat sich geändert: neu zeichnen. */
    fun aktualisieren()
    /** Ein Plan wurde übernommen: Karte und 3D-Ansicht neu füllen; [vorschau] = 3D-Vorschau zeigen. */
    fun planUebernommen(vorschau: Boolean)
}
