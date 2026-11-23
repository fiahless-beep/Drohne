package com.felixos.drohne

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.felixos.drohne.plan.Akkus
import com.felixos.drohne.plan.Art
import com.felixos.drohne.plan.Auftragsbau
import com.felixos.drohne.plan.Brief
import com.felixos.drohne.plan.Geo
import com.felixos.drohne.plan.LatLon
import com.felixos.drohne.plan.Mission
import com.felixos.drohne.plan.Missionsbau
import com.felixos.drohne.plan.OrtArt
import com.felixos.drohne.plan.Preflight
import dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState
import java.util.Locale

/**
 * Der Lauf einer Mission im Flugkern (Felix 23.11.2026: sieben Missionen, jede Brief → Plan-Check → Flug → Ergebnis).
 * Hält Brief und Orte, baut den Plan über die reinen Rechner in plan/ und gibt ihn durch dieselbe Starttür wie jeden
 * anderen Plan ([FlugSitzung.planUebernehmen]: prepare, Wächter, Upload; Start nur am Hauptknopf). Im Flug steuert hier
 * NICHTS: der Lauf beobachtet nur (welcher Punkt ist erreicht, ist der Plan zu Ende, ist die Drohne gelandet), holt
 * Standbilder für Central Command und legt nach einer vorzeitigen Landung den Restplan bereit.
 * Alles läuft auf dem UI-Thread; nur die Anfragen an Central Command laufen auf eigenen Threads.
 */
object MissionsLauf {
    private lateinit var app: Context
    private val ui = Handler(Looper.getMainLooper())
    private var gestartet = false

    private var brief: Brief? = null
    private var phase = CockpitZustand.AuftragPhase.KEINE
    private var fehlt: String? = null
    private var ortHinweis: String? = null
    private var arbeitet: String? = null
    private var ergebnis: String? = null
    private var ergebnisWarnung = false
    private var restSatz: String? = null
    /** Der Plan, der zu diesem Brief gebaut und übernommen wurde. */
    private var geplant: Mission? = null

    private class Kandidat(val nr: Int, val ort: LatLon, val text: String)
    private var kandidaten: List<Kandidat> = emptyList()

    // Beobachtung des laufenden Plans (jeder Plan, auch aus dem Chat)
    private var flog: Mission? = null
    private var flogFuer: Brief? = null
    private var flogBis = -1
    private var zuEnde = false
    private var inDerLuft = false
    private var getrennt = false

    /** Ein Standbild der Suche mit der Lage, aus der es aufgenommen wurde. */
    private class Suchbild(val nr: Int, val jpeg: ByteArray, val hier: LatLon, val hoeheM: Double, val kursGrad: Double)
    private val suchbilder = mutableListOf<Suchbild>()
    private const val MAX_SUCHBILDER = 40

    /** Die Orte der Ortswahl für die Karte des Gastgebers (nie für das Cockpit): Punkt, Ecken, Radius, Sperrradius. */
    class Auswahl(val punkt: LatLon?, val ecken: List<LatLon>, val radiusM: Double?, val sperrRadiusM: Double?, val kandidaten: List<Pair<Int, LatLon>>)
    fun auswahl(): Auswahl? {
        val b = brief
        val k = kandidaten.map { it.nr to it.ort }
        if (b == null) return if (k.isEmpty()) null else Auswahl(null, emptyList(), null, null, k)
        if (phase == CockpitZustand.AuftragPhase.KEINE) return null
        return Auswahl(b.ort, b.ecken.toList(),
            when (b.art) { Art.SUCHEN -> b.wert("r"); Art.STIMMUNG -> b.wert("r") + b.wert("d"); else -> null },
            if (b.art == Art.STIMMUNG) b.wert("r") else null, k)
    }
    /** Der Gastgeber soll die Karte zeigen (Ortswahl läuft). */
    val ortswahlAktiv: Boolean get() = phase == CockpitZustand.AuftragPhase.ORT

    fun init(c: Context) {
        if (gestartet) return
        gestartet = true
        app = c.applicationContext
        ui.post(object : Runnable { override fun run() { try { takt() } catch (t: Throwable) { DroneManager.log("Mission run error: ${t.javaClass.simpleName} ${t.message}") }; ui.postDelayed(this, 500) } })
    }

    private fun melden() = FlugSitzung.zustandGeaendert()

    // ---------------------------------------------------------------- Brief

    fun waehlen(nr: Int): String? {
        if (nr == 0) { if (phase != CockpitZustand.AuftragPhase.FLUG && arbeitet == null) { brief = null; phase = CockpitZustand.AuftragPhase.KEINE; geplant = null; fehlt = null; ergebnis = null; ergebnisWarnung = false }; melden(); return null }
        val art = Art.von(nr) ?: return "Unknown mission."
        val b = brief
        if (b != null && b.art == art && phase != CockpitZustand.AuftragPhase.KEINE) { melden(); return null }   // zurück zur offenen Mission
        val name = b?.art?.let { String.format(Locale.US, "%02d %s", it.nr, it.titel) }
        if (phase == CockpitZustand.AuftragPhase.FLUG) return "Mission $name is flying. Wait until the drone has landed."
        if (arbeitet != null) return "Central Command is still working on mission $name. Wait for the answer."
        if (phase == CockpitZustand.AuftragPhase.PLAN && b != null && geplant != null) return "Mission $name has a plan loaded. Fly it first or drop it."
        brief = Brief(art); phase = CockpitZustand.AuftragPhase.BRIEF
        fehlt = null; ortHinweis = null; ergebnis = null; ergebnisWarnung = false; arbeitet = null; geplant = null
        if (art != Art.NACHSEHEN) kandidaten = emptyList()
        melden(); return null
    }

    fun feld(id: String, index: Int) {
        val b = brief ?: return
        if (phase != CockpitZustand.AuftragPhase.BRIEF && phase != CockpitZustand.AuftragPhase.ORT) return
        b.felder.firstOrNull { it.id == id }?.let { it.gewaehlt = index.coerceIn(0, it.werte.size - 1) }
        fehlt = null; melden()
    }

    fun text(t: String) { val b = brief ?: return; if (phase != CockpitZustand.AuftragPhase.BRIEF) return; b.text = t.trim().take(300); fehlt = null; melden() }

    fun ortWaehlen(): String? {
        val b = brief ?: return "Choose a mission first."
        if (b.art.ort == OrtArt.KEINER) return "This mission needs no place."
        if (phase != CockpitZustand.AuftragPhase.BRIEF) return "The brief is closed. Open the mission again to change the place."
        if (DroneManager.state.flying) return "Places are chosen on the ground. In flight a tap on the map picks a subject."
        phase = CockpitZustand.AuftragPhase.ORT
        ortHinweis = null; fehlt = null
        melden(); return null
    }

    /** Tipp auf die Karte während der Ortswahl; true = der Tipp gehörte der Ortswahl. */
    fun kartenTipp(p: LatLon): Boolean {
        val b = brief ?: return false
        if (phase != CockpitZustand.AuftragPhase.ORT) return false
        ortHinweis = Auftragsbau.tippen(b, p, FlugSitzung.homeLatLon())
        melden(); return true
    }

    fun ortZurueck() {
        val b = brief ?: return
        if (phase != CockpitZustand.AuftragPhase.ORT) return
        if (b.art.ort == OrtArt.FLAECHE) { if (b.ecken.isNotEmpty()) b.ecken.removeAt(b.ecken.size - 1) } else b.ort = null
        ortHinweis = null; melden()
    }

    fun ortFertig() { if (phase == CockpitZustand.AuftragPhase.ORT) { phase = CockpitZustand.AuftragPhase.BRIEF; ortHinweis = null; melden() } }

    // ---------------------------------------------------------------- Plan

    /** Startpunkt für die Rechnung: der Heimpunkt der Drohne; in der Simulation ohne Heimpunkt einer 150 m südlich des Orts. */
    private fun heim(b: Brief): LatLon? {
        FlugSitzung.homeLatLon()?.let { return it }
        if (!Simulator.active) return null
        val o = b.ort ?: b.ecken.firstOrNull() ?: return null
        Simulator.setHome(Geo.offset(o, 150.0, 180.0))
        return FlugSitzung.homeLatLon() ?: Simulator.homeOrNull
    }

    fun planen(): String? {
        val b = brief ?: return "Choose a mission first."
        if (phase != CockpitZustand.AuftragPhase.BRIEF) return "Open the brief first."
        fun nein(g: String): String { fehlt = g; melden(); return g }
        Auftragsbau.fehlt(b)?.let { return nein(it) }
        if (DroneManager.state.flying) return nein("Plans from a brief start on the ground. Land first.")
        if (FlugSitzung.loading || FlugSitzung.uploading || FlugSitzung.busy != null) return nein("The app is busy. Try again in a moment.")
        val home = heim(b) ?: return nein("No home point yet. Connect the drone and wait for GPS; the plan is built around the start point.")
        if ((b.art == Art.NACHSEHEN || b.art == Art.SUCHEN) && ClaudeLink.key(app) == null && !Simulator.active)
            return nein("Central Command has no key yet (Set → Data → Enter Claude key). Without it nobody looks at the stills.")
        val bau = try { Auftragsbau.bauen(b, home) } catch (e: Missionsbau.Abgelehnt) { return nein(e.grund) } catch (e: IllegalArgumentException) { return nein(e.message ?: "Plan not possible.") }
        fehlt = null; ergebnis = null; ergebnisWarnung = false; kandidaten = if (b.art == Art.NACHSEHEN) kandidaten else emptyList()
        geplant = bau.mission
        phase = CockpitZustand.AuftragPhase.PLAN
        DroneManager.log("Mission ${b.art.nr} ${b.art.titel}: plan built, ${bau.mission.waypoints.size} points")
        FlugSitzung.planUebernehmen(bau.mission, bau.beschreibung)
        // Hat die Starttür den Plan nicht angenommen (zu viele Punkte, DJI-Prüfung), steht der Grund im Plan-Feld.
        if (FlugSitzung.mission !== bau.mission && !FlugSitzung.startplatzFrageOffen) { geplant = null; phase = CockpitZustand.AuftragPhase.BRIEF; return nein("The plan was not accepted: " + FlugSitzung.planZeile) }
        melden(); return null
    }

    fun kandidatWaehlen(nr: Int) {
        val k = kandidaten.firstOrNull { it.nr == nr } ?: return
        if (phase == CockpitZustand.AuftragPhase.FLUG) return
        val was = brief?.text.orEmpty()
        val alle = kandidaten
        brief = Brief(Art.NACHSEHEN).also { it.ort = k.ort; it.text = if (was.isBlank()) "What is at this spot?" else "Is this a good spot for: $was? What speaks against it?" }
        kandidaten = alle
        phase = CockpitZustand.AuftragPhase.BRIEF; fehlt = null; ergebnis = null; ergebnisWarnung = false; geplant = null
        melden()
    }

    /** Vermessungsflug über dem Gebiet des geladenen Plans als Mission 06 vorbereiten (Rechteck um alle Punkte, 20 m Rand). */
    fun vermessungVorbereiten(): String? {
        if (phase == CockpitZustand.AuftragPhase.FLUG) return "A mission is flying."
        val m = FlugSitzung.mission ?: return "No plan is loaded."
        val pts = m.waypoints.map { it.pos }
        val c = Missionsbau.mitte(pts)
        val halbNord = (pts.maxOf { it.lat } - pts.minOf { it.lat }) * 110_540.0 / 2 + 20.0
        val halbOst = (pts.maxOf { it.lon } - pts.minOf { it.lon }) * 111_320.0 * Math.cos(Math.toRadians(c.lat)) / 2 + 20.0
        val b = Brief(Art.VERMESSEN)
        listOf(-1.0 to 1.0, 1.0 to 1.0, 1.0 to -1.0, -1.0 to -1.0).forEach { (x, y) -> b.ecken += Geo.offset(Geo.offset(c, halbNord * y, 0.0), halbOst * x, 90.0) }
        brief = b; phase = CockpitZustand.AuftragPhase.BRIEF; fehlt = null; ergebnis = null; geplant = null; kandidaten = emptyList()
        melden(); return null
    }

    // ---------------------------------------------------------------- Folgen (05)

    private fun folgenSperren(b: Brief): List<String> {
        val s = DroneManager.state
        val pos = s.location?.takeIf { it.latitude != 0.0 }?.let { LatLon(it.latitude, it.longitude) }
        return Preflight.followGate(s.connected, s.flying, MissionController.executing, pos, FlugSitzung.homeLatLon() != null, b.wert("h"), b.wert("d"),
            s.batteryPercent, s.satellites, s.windWarning, s.heightLimitM, s.sensorsOk, FlugSitzung.sensorenText(s.sensors), System.currentTimeMillis())
    }

    fun folgenBeginnen(): List<String> {
        val b = brief?.takeIf { it.art == Art.FOLGEN } ?: return listOf("Open mission 05 first.")
        if (FollowController.active) return emptyList()
        val sperren = folgenSperren(b)
        if (sperren.isNotEmpty()) return sperren
        FlugSitzung.folgenStarten(b.wert("h"), b.wert("d"))?.let { return listOf(it) }
        melden(); return emptyList()
    }

    // ---------------------------------------------------------------- Beobachten: Punkt erreicht, Plan zu Ende, gelandet

    private fun takt() {
        val s = DroneManager.state
        // Akkuwechsel: die Drohne war aus. Ein Plan, der „auf der Drohne“ hieß, ist es danach nicht mehr sicher.
        if (!s.connected) { if (!s.flying && !MissionController.executing) getrennt = true }
        else if (getrennt) {
            getrennt = false
            if (!s.flying && !s.motorsOn && !MissionController.executing && MissionController.uploaded && !Simulator.active) {
                MissionController.planVerloren()
                DroneManager.log("Drone reconnected: plan must be uploaded again")
                melden()
            }
        }
        val cur = FlugSitzung.mission
        val st = MissionController.executeState
        // Der Plan zum Brief ist nicht mehr der geladene (abgelehnt nach der Startplatz-Frage, oder ein anderer Plan wurde geholt): zurück zum Brief.
        if (phase == CockpitZustand.AuftragPhase.PLAN && geplant != null && cur !== geplant && !FlugSitzung.startplatzFrageOffen && !MissionController.executing) {
            geplant = null; phase = CockpitZustand.AuftragPhase.BRIEF; melden()
        }
        if (MissionController.executing && cur != null) {
            if (flog !== cur) {
                flog = cur; flogBis = -1; zuEnde = false; restSatz = null
                flogFuer = brief?.takeIf { cur === geplant }
                suchbilder.clear()
                if (flogFuer != null) { phase = CockpitZustand.AuftragPhase.FLUG; ergebnis = null; ergebnisWarnung = false; melden() }
            }
            val i = MissionController.currentWaypointIndex - MissionController.preCount
            if (MissionController.currentWaypointIndex >= 0 && i > flogBis && i < cur.waypoints.size) { flogBis = i; punktErreicht(cur, i) }
        }
        if ((st == WaypointMissionExecuteState.FINISHED || st == WaypointMissionExecuteState.RETURN_TO_START_POINT) && flog != null && !zuEnde && flog === cur) {
            zuEnde = true; planZuEnde()
        }
        if (s.flying || s.motorsOn) inDerLuft = true
        else if (inDerLuft && s.connected) { inDerLuft = false; gelandet() }
    }

    private fun punktErreicht(m: Mission, i: Int) {
        val b = flogFuer ?: return
        when (b.art) {
            Art.NACHSEHEN -> if (i == 0) ui.postDelayed({ nachsehen(b, m) }, 4000)
            Art.SUCHEN -> {
                val jeder = Math.ceil(m.waypoints.size / MAX_SUCHBILDER.toDouble()).toInt().coerceAtLeast(1)
                if (i % jeder == 0 && suchbilder.size < MAX_SUCHBILDER) ui.postDelayed({
                    val s = DroneManager.state
                    val loc = s.location?.takeIf { it.latitude != 0.0 } ?: return@postDelayed
                    val hier = LatLon(loc.latitude, loc.longitude); val h = loc.altitude; val kurs = s.headingDeg ?: 0.0
                    FrameShots.naechstesBild(breite = 768) { jpg -> if (jpg != null && flog === m) suchbilder += Suchbild(i + 1, jpg, hier, h, kurs) }
                }, 1200)
            }
            else -> {}
        }
    }

    private fun nachsehen(b: Brief, m: Mission) {
        if (flog !== m) return
        arbeitet = "Central Command is looking at the place …"; melden()
        FrameShots.naechstesBild { jpg ->
            if (jpg == null) { arbeitet = null; ergebnis = "No still from the live view arrived" + (if (Simulator.active) " (simulation has no camera)" else "") + ". The two photos are on the card; ask Central Command with them later."; ergebnisWarnung = true; melden(); return@naechstesBild }
            val key = ClaudeLink.key(app)
            if (key == null) { arbeitet = null; ergebnis = "Central Command has no key (Set → Data → Enter Claude key). The photos are on the card."; ergebnisWarnung = true; melden(); return@naechstesBild }
            val h = Math.round(DroneManager.state.location?.altitude ?: m.waypoints[0].heightM).toInt()
            Thread({
                val (antwort, fehler) = ClaudeLink.nachsehen(key, b.text, jpg, h)
                ui.post {
                    DroneManager.log("Mission 03 answer: $antwort")
                    if (brief === b) { arbeitet = null; ergebnis = antwort; ergebnisWarnung = fehler; melden(); FlugSitzung.hinweis(if (fehler) "Check a place: no answer. See Msn." else "Check a place: the answer is in Msn.") }
                }
            }, "Nachsehen").start()
        }
    }

    private fun planZuEnde() {
        val b = flogFuer ?: return
        if (b.art != Art.SUCHEN) return
        val m = flog ?: return
        val bilder = suchbilder.toList()
        if (bilder.isEmpty()) { ergebnis = "No stills from the live view arrived" + (if (Simulator.active) " (simulation has no camera)" else "") + ". The photos are on the card."; ergebnisWarnung = true; melden(); return }
        val key = ClaudeLink.key(app)
        if (key == null) { ergebnis = "Central Command has no key (Set → Data → Enter Claude key). The photos are on the card."; ergebnisWarnung = true; melden(); return }
        arbeitet = "Central Command is looking through ${bilder.size} stills …"; melden()
        val h = Math.round(m.waypoints[0].heightM).toInt()
        Thread({
            val r = ClaudeLink.suchen(key, b.text, bilder.map { it.nr to it.jpeg }, h)
            ui.post {
                suchbilder.clear()
                if (brief !== b) return@post
                arbeitet = null
                if (r.fehler != null) { ergebnis = r.fehler; ergebnisWarnung = true }
                else {
                    kandidaten = r.funde.mapIndexedNotNull { n, f -> bilder.firstOrNull { it.nr == f.bild }?.let { sb -> Kandidat(n + 1, Auftragsbau.bildOrt(sb.hier, sb.hoeheM, sb.kursGrad, f.x, f.y), f.warum) } }
                    ergebnis = r.text.ifBlank { if (kandidaten.isEmpty()) "Nothing found that fits." else "${kandidaten.size} candidate(s)." }
                    ergebnisWarnung = false
                    DroneManager.log("Mission 04: ${kandidaten.size} candidates. ${r.text}")
                    FlugSitzung.hinweis(if (kandidaten.isEmpty()) "Find a spot: nothing found. See Msn." else "Find a spot: ${kandidaten.size} candidate(s) in Msn.")
                }
                melden()
            }
        }, "Suchen").start()
    }

    private fun gelandet() {
        val m = flog ?: return
        val b = flogFuer
        flog = null; flogFuer = null
        // Vorzeitig gelandet (Akku-Umkehr, STOP, Wächter): der Rest wird von selbst der nächste Plan (Felix 22.11.2026).
        val rest = if (!zuEnde && flogBis >= 0 && !m.hoverAtEnd && !m.startFromCurrent && FlugSitzung.mission === m) Akkus.rest(m, flogBis) else null
        if (rest != null && rest.waypoints.size < m.waypoints.size) {
            val von = flogBis + 1
            restSatz = "The flight ended early at point $von of ${m.waypoints.size}. The rest (points $von to ${m.waypoints.size}) is loaded as “${rest.name}”. Swap the battery if needed, then Start."
            DroneManager.log("Rest plan: points $von to ${m.waypoints.size} of ${m.name}")
            if (b != null) geplant = rest
            FlugSitzung.planUebernehmen(rest, "Rest of “${m.name}” from point $von.\n\n" + FlugSitzung.missionText.substringBefore("\n\n"))
            if (b != null && brief === b) { phase = CockpitZustand.AuftragPhase.PLAN; melden() }
            FlugSitzung.hinweis("Rest plan loaded: points $von to ${m.waypoints.size}.")
            return
        }
        if (b != null && brief === b) {
            phase = CockpitZustand.AuftragPhase.ERGEBNIS
            if (ergebnis == null && arbeitet == null) ergebnis = when (b.art) {
                Art.VERMESSEN -> "Survey flown: ${m.waypoints.count { it.takePhoto }} photos are on the card. Send them to Claude in the chat for the height model and the 3D model (Set → Data → Fetch photos and upload)."
                Art.STIMMUNG -> "Arc flown: ${m.waypoints.count { it.takePhoto }} photos and one video are on the card."
                else -> if (zuEnde) "Flight done." else "The flight ended before the first point. Nothing was recorded; the plan is still loaded."
            }
            melden()
        }
    }

    // ---------------------------------------------------------------- Zustand für die Oberfläche (nur Worte)

    fun zustand(): CockpitZustand.Auftrag {
        val b = brief ?: return CockpitZustand.Auftrag(restSatz = restSatz)
        if (phase == CockpitZustand.AuftragPhase.KEINE) return CockpitZustand.Auftrag(restSatz = restSatz)
        val home = FlugSitzung.homeLatLon()
        val ortText = when (b.art.ort) {
            OrtArt.PUNKT -> b.ort?.let { Auftragsbau.ortText(home, it) }
            OrtArt.FLAECHE -> if (b.ecken.isEmpty()) null else "${b.ecken.size} corner(s)" + (if (b.ecken.size >= 3) String.format(Locale.US, " · %.1f ha", Missionsbau.flaecheM2(b.ecken) / 10_000) else "")
            OrtArt.KEINER -> null
        }
        return CockpitZustand.Auftrag(
            art = b.art.nr, titel = b.art.titel, phase = phase,
            felder = b.felder.map { f -> CockpitZustand.Auftrag.Feld(f.id, f.name, f.werte.map { "$it ${f.einheit}" }, f.gewaehlt) },
            ortArt = when (b.art.ort) { OrtArt.KEINER -> 0; OrtArt.PUNKT -> 1; OrtArt.FLAECHE -> 2 },
            ortText = ortText, ecken = b.ecken.size,
            text = b.text, textName = when (b.art) { Art.NACHSEHEN -> "Your question"; Art.SUCHEN -> "What you are looking for"; else -> null },
            fehlt = if (phase == CockpitZustand.AuftragPhase.BRIEF || phase == CockpitZustand.AuftragPhase.ORT) Auftragsbau.fehlt(b) else null,
            abgelehnt = fehlt,
            ortHinweis = ortHinweis,
            arbeitet = arbeitet, ergebnis = ergebnis, ergebnisWarnung = ergebnisWarnung,
            kandidaten = kandidaten.map { CockpitZustand.Auftrag.Kandidat(it.nr, it.text, Auftragsbau.ortText(home, it.ort)) },
            folgenSperren = if (b.art == Art.FOLGEN && !FollowController.active) folgenSperren(b) else emptyList(),
            restSatz = restSatz,
        )
    }
}
