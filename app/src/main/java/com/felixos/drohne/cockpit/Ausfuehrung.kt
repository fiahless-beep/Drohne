package com.felixos.drohne.cockpit

import com.felixos.drohne.Aktionen
import com.felixos.drohne.CockpitZustand
import com.felixos.drohne.sprache.Befehl
import com.felixos.drohne.sprache.Klasse
import com.felixos.drohne.sprache.Pruefer
import com.felixos.drohne.sprache.Sicht

/** Was das Ausführen eines Sprachbefehls von den Flächen des Cockpits braucht. Nichts davon ist flugwirksam. */
internal interface Flaechen {
    /** Was der Pilot gerade sieht. */
    val sicht: Sicht
    fun ansichtZeigen(s: Sicht)
    /** Eine Fläche der Leiste öffnen; null = die offene schließen. */
    fun flaecheZeigen(s: Sicht?)
    fun vollbild(an: Boolean)
    fun einpassen()
    fun sprechSprache(kuerzel: String)
    /** Die Missions-Fläche auf dieser Mission öffnen (wie ein Druck auf ihre Kachel); Antwort: Grund vom Kern, falls ihr Brief nicht aufgeht. */
    fun missionOeffnen(nr: Int): String?
    /** Die Missions-Fläche auf die Mission stellen, die der Kern jetzt hält (nach Kandidat oder Vermessungsflug). */
    fun missionMitgehen()
}

/**
 * Die eine Stelle, an der ein Sprachbefehl zu einer Handlung wird ([Cockpit.ausfuehren] reicht nur hierher durch). Ohne
 * Android, damit die festen Grenzen auf dem Rechner prüfbar sind:
 *  - [Aktionen.abheben] und [Aktionen.starten] werden hier nirgends gerufen; es gibt keinen Befehl dafür.
 *  - Ein Befehl der Klasse VORSCHLAG geschieht nur mit [gedrueckt] = true, also aus dem Druck auf den Vorschlags-Knopf.
 *  - Vor jeder Handlung fragt sie noch einmal den [Pruefer] mit dem Zustand von jetzt (ein Vorschlag kann 20 s alt sein).
 * Der Flugkern prüft jede Aktion danach selbst noch einmal.
 */
internal class Ausfuehrung(private val aktionen: Aktionen, private val flaechen: Flaechen) {
    /** null = getan; sonst der Grund, warum nichts geschah (für den Sprechstreifen). */
    fun ausfuehren(b: Befehl, gedrueckt: Boolean): String? {
        if (b.klasse == Klasse.NIE || b.klasse == Klasse.CLAUDE || b is Befehl.Unklar || b is Befehl.Frage) return "Nothing to do."
        if (b.klasse == Klasse.VORSCHLAG && !gedrueckt) return "That needs your press on the button."
        val z = aktionen.zustand()
        Pruefer.grund(b, flaechen.sicht, z)?.let { return it }
        when (b) {
            is Befehl.AnsichtZeigen -> flaechen.ansichtZeigen(b.sicht)
            is Befehl.FlaecheOeffnen -> flaechen.flaecheZeigen(b.sicht)
            Befehl.FlaecheSchliessen -> flaechen.flaecheZeigen(null)
            is Befehl.Vollbild -> flaechen.vollbild(b.an)
            Befehl.Einpassen -> flaechen.einpassen()
            is Befehl.SprechSprache -> flaechen.sprechSprache(b.kuerzel)
            is Befehl.MissionWaehlen -> if (b.art == 0) aktionen.auftragWaehlen(0)?.let { return it } else flaechen.missionOeffnen(b.art)?.let { return it }
            is Befehl.FeldSetzen -> {
                val i = z.auftrag.felder.firstOrNull { it.id == b.id }?.let { Pruefer.feldIndex(it, b.wert) } ?: return "That value is not offered."
                aktionen.auftragFeld(b.id, i)
            }
            is Befehl.BriefText -> aktionen.auftragText(b.text)
            Befehl.OrtWaehlen -> aktionen.ortWaehlen()?.let { return it }
            Befehl.OrtZurueck -> aktionen.ortZurueck()
            Befehl.OrtFertig -> aktionen.ortFertig()
            is Befehl.KandidatWaehlen -> { aktionen.kandidatWaehlen(b.nr); flaechen.missionMitgehen() }
            Befehl.BriefAendern -> aktionen.auftragAendern()
            Befehl.VermessungVorbereiten -> { aktionen.vermessungVorbereiten()?.let { return it }; flaechen.missionMitgehen() }
            Befehl.Anhalten -> {
                // Anhalten wirkt sofort: Folgen beenden, laufenden Plan pausieren. Läuft nichts, geschieht nichts.
                var etwas = false
                if (z.folgenAktiv) { aktionen.folgenAufhoeren(); etwas = true }
                if (z.plan.laeuft || aktionen.hauptaktionWaehlen() == CockpitZustand.Hauptaktion.PAUSE) { aktionen.pausieren(); etwas = true }
                if (!etwas) return "Nothing is running that I could stop."
            }
            Befehl.Heim -> aktionen.stoppUndHeim()
            Befehl.Fortsetzen -> if (aktionen.hauptaktionWaehlen() == CockpitZustand.Hauptaktion.FORTSETZEN) aktionen.fortsetzen() else return "No plan is waiting to be resumed."
            Befehl.PlanHolen -> { flaechen.flaecheZeigen(null); aktionen.planLaden() }
            Befehl.Hochladen -> aktionen.hochladen()
            Befehl.BriefPlanen -> { aktionen.auftragPlanen()?.let { return it }; flaechen.flaecheZeigen(null) }
            Befehl.FolgenBeginnen -> aktionen.folgenBeginnen().takeIf { it.isNotEmpty() }?.let { return it.joinToString(" · ") }
            Befehl.NochmalFragen -> aktionen.auftragNochmal()
            is Befehl.Frage, is Befehl.Abgelehnt, is Befehl.AnClaude, Befehl.Unklar -> return "Nothing to do."
        }
        return null
    }
}
