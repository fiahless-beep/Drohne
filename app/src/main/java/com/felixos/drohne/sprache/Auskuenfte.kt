package com.felixos.drohne.sprache

import com.felixos.drohne.CockpitZustand
import java.util.Locale

/**
 * Antworten auf Zustandsfragen, allein aus dem [CockpitZustand]: kein Netz, kein Central Command. Werte der Drohne nur,
 * solange sie frisch sind ([CockpitZustand.datenDa]); sonst sagt die Antwort, dass keine Daten da sind. Texte englisch
 * wie die übrige Bedienung.
 */
object Auskuenfte {
    fun antwort(was: Auskunft, z: CockpitZustand): String {
        val da = z.datenDa
        val keine = if (z.verbindung != CockpitZustand.Verbindung.VERBUNDEN) "No data: the drone is not connected." else "No data from the drone right now."
        val p = z.plan
        val a = z.auftrag
        return when (was) {
            Auskunft.AKKU -> z.akkuProzent?.takeIf { da }?.let { b ->
                "Battery $b %" + (z.restMin?.let { ", about $it min of flight" } ?: "") + when (z.akkuStufe) {
                    CockpitZustand.Stufe.NIEDRIG -> ". That is low."; CockpitZustand.Stufe.WARNUNG -> ". Keep an eye on it."; else -> "." }
            } ?: keine
            Auskunft.RESTZEIT -> z.restMin?.takeIf { da }?.let { "About $it min of flight left" + (z.akkuProzent?.let { b -> " (battery $b %)" } ?: "") + ". A rough estimate." } ?: keine
            Auskunft.HOEHE -> z.hoeheM?.takeIf { da }?.let { "Height ${Math.round(it)} m above the start point." } ?: keine
            Auskunft.TEMPO -> z.tempoMs?.takeIf { da }?.let { String.format(Locale.US, "Speed %.1f m/s.", it) } ?: keine
            Auskunft.ENTFERNUNG_HEIM -> if (!da) keine else z.distanzHeimM?.let { d -> "The drone is $d m from the start point" + (z.peilungHeimGrad?.let { ", the way home is at bearing $it°" } ?: "") + "." }
                ?: "No home point yet, so no distance."
            Auskunft.WIND -> {
                val w = z.wind
                val mess = w.ms?.takeIf { da }?.let { String.format(Locale.US, "Wind %.0f m/s", it) + (w.richtung?.let { r -> " (" + r.lowercase(Locale.US).replace('_', '-') + ")" } ?: "") + ", measured by the drone" }
                val stufe = if (!da) null else when (w.warnung) { 2 -> "strong wind warning from the drone"; 1 -> "moderate wind warning from the drone"; 0 -> "no wind warning from the drone"; else -> null }
                val teile = listOfNotNull(mess, stufe, w.vorhersage)
                if (teile.isEmpty()) keine else teile.joinToString(" ") { t -> t.replaceFirstChar { it.uppercase(Locale.US) }.trimEnd('.') + "." }
            }
            Auskunft.GPS -> z.satelliten?.takeIf { da }?.let { s -> "$s satellites. " + when { z.gpsOk -> "GPS is good."; !z.heimGesetzt -> "No home point yet."; else -> "Too few for a start." } } ?: keine
            Auskunft.VERBINDUNG -> when {
                z.funkabriss -> "Link lost. No connection to the drone."
                z.keineDatenSeitS != null -> "Connected, but no data from the drone for ${z.keineDatenSeitS} s."
                else -> when (z.verbindung) {
                    CockpitZustand.Verbindung.VERBUNDEN -> "Connected" + (if (z.simulation) " (simulation)" else z.geraet?.let { " to $it" } ?: "") + "."
                    CockpitZustand.Verbindung.NUR_FERNSTEUERUNG -> "Only the controller is connected, not the drone."
                    CockpitZustand.Verbindung.SDK_STARTET -> "The DJI SDK is still starting."
                    CockpitZustand.Verbindung.SDK_FEHLER -> "DJI SDK error. See Sys and Log."
                    CockpitZustand.Verbindung.GETRENNT -> "Not connected."
                }
            }
            Auskunft.SENSOREN -> z.sensoren?.takeIf { da }?.let { "Obstacle sensing: $it." + if (z.sensorenOk) "" else " That is not OK for a start." } ?: keine
            Auskunft.STARTKLAR -> when {
                z.fliegt || z.motorenAn || p.laeuft -> "The drone is already flying. " + z.statusText
                z.startProbleme.isNotEmpty() -> "Not ready. Missing: " + z.startProbleme.joinToString(" · ")
                !p.geladen -> "No plan is loaded. Get a plan first."
                !p.aufDrohne -> "Plan “${p.name}” is loaded but not on the drone yet. Upload it."
                else -> "Ready for takeoff: “${p.name}”." + (if (da && z.akkuProzent != null && p.brauchtProzent != null && z.akkuProzent < p.brauchtProzent) " Battery ${z.akkuProzent} % is less than the ${p.brauchtProzent} % the whole plan needs." else "") +
                    " Start stays with your thumb."
            }
            Auskunft.PLAN -> if (!p.geladen) "No plan is loaded." else "Plan “${p.name}”: ${p.zeile}" + (if (p.aufDrohne) "" else " (not on the drone yet)")
            Auskunft.FORTSCHRITT -> when {
                !p.geladen -> "No plan is loaded."
                p.laeuft || p.fortschrittZeigen -> "${p.fortschrittProzent} % of the plan" + (if (p.punktIndex >= 0 && p.punkte > 0) ", point ${p.punktIndex + 1} of ${p.punkte}" else "") + "."
                z.phase == CockpitZustand.Phase.PAUSIERT -> "The plan is paused" + (if (p.punktIndex >= 0 && p.punkte > 0) " at point ${p.punktIndex + 1} of ${p.punkte}" else "") + "."
                else -> "The plan is not running."
            }
            Auskunft.NAECHSTES -> p.naechstes.ifBlank { if (p.geladen) "Nothing is planned next." else "No plan is loaded." }
            Auskunft.WARNUNG -> z.warnung?.let { "${it.titel}: ${it.text}" } ?: "No warning right now."
            Auskunft.BRIEF_FEHLT -> when {
                a.art == 0 -> "No mission is open. Choose one under Msn."
                a.phase == CockpitZustand.AuftragPhase.BRIEF || a.phase == CockpitZustand.AuftragPhase.ORT -> a.fehlt ?: "Nothing is missing in the brief of mission ${nr(a.art)}. The plan can be built."
                else -> "The brief of mission ${nr(a.art)} is closed (${a.phase.name.lowercase(Locale.US)})."
            }
            Auskunft.FUNKABRISS -> p.beiFunkabriss?.let { "If the link is lost, the drone will $it." } ?: "No plan is loaded, so nothing is set for a lost link."
            Auskunft.HOEHENLIMIT -> z.hoehenlimitM?.takeIf { da }?.let { "Height limit of the drone: $it m." } ?: keine
            Auskunft.TAGESLICHT -> when (z.tageslicht) {
                true -> "It is daylight."
                false -> "It is dark or dusk. Fly only in daylight: obstacle sensing is blind in the dark."
                null -> "Unknown: there is no position yet."
            }
            Auskunft.STATUS -> z.statusText.trimEnd('.') + "." + (z.warnung?.let { " ${it.titel}: ${it.text}" } ?: "")
        }
    }

    internal fun nr(art: Int): String = String.format(Locale.US, "%02d", art)
}
