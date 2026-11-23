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
    /** Prüfliste zur Startprüfung jetzt; [voll] = jede Regel mit Ergebnis, sonst nur die Probleme. */
    fun pruefliste(voll: Boolean): List<Pruefzeile>
    /** Hinweis, wenn der Akku jetzt nicht für den ganzen Plan reicht (der Start bleibt erlaubt); sonst null. */
    fun akkuHinweis(): String?
    /** Start vom Boden. Rechnet die Startprüfung selbst und verweigert, solange ein Problem besteht. */
    fun starten(): StartErgebnis
    /** Abheben ohne Plan: Motoren an, gut einen Meter steigen, schweben. Prüft plan.Takeoff selbst. */
    fun abheben(): StartErgebnis
    fun pausieren()
    /** Pausierten Plan fortsetzen; wartet ein Hauptplan hinter einem Umweg, dessen Rest ab hier. */
    fun fortsetzen()
    /** Folgen beenden, Plan abbrechen, Heimflug auslösen. */
    fun stoppUndHeim()
    /** Was der Hauptknopf bei einem Druck tut (Reihenfolge wie bisher in der Activity); null = gerade nichts. */
    fun hauptaktionWaehlen(): CockpitZustand.Hauptaktion?
    fun zustand(): CockpitZustand

    // ---- Missionen (Brief → Plan-Check → Flug → Ergebnis). Der Kern hält Brief und Orte; die Oberfläche sieht nur Worte.
    /** Mission 1…7 wählen (ihr Brief erscheint), 0 = keine. Antwort: Grund, falls gerade nicht möglich. */
    fun auftragWaehlen(art: Int): String?
    /** Im Brief einen der festen Werte wählen. */
    fun auftragFeld(id: String, index: Int)
    /** Die gesprochene Frage (03) oder das Gesuchte (04) setzen. */
    fun auftragText(text: String)
    /** Ort auf der Karte wählen beginnen; Antwort: Grund, falls gerade nicht möglich. */
    fun ortWaehlen(): String?
    /** Letzte Ecke zurücknehmen bzw. den Punkt löschen. */
    fun ortZurueck()
    /** Ortswahl beenden, zurück zum Brief. */
    fun ortFertig()
    /** Aus dem Brief einen Plan bauen und durch die Starttür schicken (prüfen, hochladen). Antwort: Grund der Ablehnung oder null. */
    fun auftragPlanen(): String?
    /** Einen Kandidaten der Suche (04) als „Check a place“ (03) vorbereiten. */
    fun kandidatWaehlen(nr: Int)
    /** Den empfohlenen Vermessungsflug über dem Gebiet des geladenen Plans als Mission 06 vorbereiten. */
    fun vermessungVorbereiten(): String?
    /** Folgen (05) mit den Werten des Briefs beginnen; Antwort: Gründe, falls es nicht geht (leer = läuft). */
    fun folgenBeginnen(): List<String>
    fun folgenAufhoeren()
}

/** Eine Zeile der Prüfliste: [ok] true = erfüllt, false = Problem, null = ohne Plan noch nicht prüfbar. */
class Pruefzeile(val ok: Boolean?, val text: String, val detail: String?)

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
