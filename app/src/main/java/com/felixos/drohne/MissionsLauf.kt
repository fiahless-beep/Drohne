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

    // Beobachtung des laufenden Flugs (jeder Plan, auch aus dem Chat)
    /** Der am Boden gestartete Plan dieses Flugs; Umwege und Planrest in der Luft ersetzen ihn nicht. */
    private var haupt: Mission? = null
    private var hauptFuer: Brief? = null
    /** Höchster erreichter (gemeldeter) Punkt des Hauptplans, 0-basiert. */
    private var flogBis = -1
    private var zuEnde = false
    private var inDerLuft = false
    private var getrenntSeit = 0L
    /** Die Verbindung riss in diesem Flug ab: wie weit der Plan kam, ist dann ungewiss. */
    private var funkWeg = false
    private var bildZeiger = 0
    private var bildNr = 0
    private var nachgesehen = false
    private var startSeit = 0L
    /** Eine gescheiterte Anfrage an Central Command, die noch einmal gestellt werden kann. */
    private var nochmal: (() -> Unit)? = null

    /** Ein Standbild der Suche mit der Lage, aus der es aufgenommen wurde. */
    private class Suchbild(val nr: Int, val jpeg: ByteArray, val hier: LatLon, val hoeheM: Double, val kursGrad: Double)
    /** Den Plan dieses Briefs von der Starttür nehmen, damit kein Plan startbar bleibt, zu dem es keinen Brief mehr gibt. */
    private fun planFallenLassen() { geplant?.let { FlugSitzung.planVerwerfen(it) }; geplant = null }
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
        if (FlugSitzung.uploading) return "The plan is still uploading. Try again in a moment."
        if (nr == 0) { if (phase != CockpitZustand.AuftragPhase.FLUG && arbeitet == null) { planFallenLassen(); brief = null; phase = CockpitZustand.AuftragPhase.KEINE; fehlt = null; ergebnis = null; ergebnisWarnung = false; nochmal = null; suchbilder.clear() }; melden(); return null }
        val art = Art.von(nr) ?: return "Unknown mission."
        val b = brief
        if (b != null && b.art == art && phase != CockpitZustand.AuftragPhase.KEINE) { melden(); return null }   // zurück zur offenen Mission
        val name = b?.art?.let { String.format(Locale.US, "%02d %s", it.nr, it.titel) }
        if (phase == CockpitZustand.AuftragPhase.FLUG) return "Mission $name is flying. Wait until the drone has landed."
        if (arbeitet != null) return "Central Command is still working on mission $name. Wait for the answer."
        if (phase == CockpitZustand.AuftragPhase.PLAN && b != null && geplant != null) return "Mission $name has a plan loaded. Fly it first or drop it."
        planFallenLassen(); nochmal = null; suchbilder.clear()
        brief = Brief(art); phase = CockpitZustand.AuftragPhase.BRIEF
        fehlt = null; ortHinweis = null; ergebnis = null; ergebnisWarnung = false
        if (art != Art.NACHSEHEN) kandidaten = emptyList()
        melden(); return null
    }

    fun feld(id: String, index: Int) {
        val b = brief ?: return
        if (phase != CockpitZustand.AuftragPhase.BRIEF && phase != CockpitZustand.AuftragPhase.ORT) return
        b.felder.firstOrNull { it.id == id }?.let { it.gewaehlt = index.coerceIn(0, it.werte.size - 1) }
        fehlt = null; melden()
    }

    fun text(t: String) { val b = brief ?: return; if (phase != CockpitZustand.AuftragPhase.BRIEF || b.art.ort == OrtArt.KEINER) return; b.text = t.trim().take(300); fehlt = null; melden() }

    fun ortWaehlen(): String? {
        val b = brief ?: return "Choose a mission first."
        if (b.art.ort == OrtArt.KEINER) return "This mission needs no place."
        if (phase != CockpitZustand.AuftragPhase.BRIEF) return "The brief is closed. Press “Change the brief” first."
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
        val home = heim(b) ?: return nein("No start point yet. Connect the drone and wait for GPS; the plan is built around the start point.")
        if ((b.art == Art.NACHSEHEN || b.art == Art.SUCHEN) && ClaudeLink.key(app) == null && !Simulator.active)
            return nein("Central Command has no key yet (Set → Data → Enter Claude key). Without it nobody looks at the stills.")
        val bau = try { Auftragsbau.bauen(b, home) } catch (e: Missionsbau.Abgelehnt) { return nein(e.grund) } catch (e: IllegalArgumentException) { return nein(e.message ?: "Plan not possible.") }
        fehlt = null; ergebnis = null; ergebnisWarnung = false; nochmal = null; kandidaten = if (b.art == Art.NACHSEHEN) kandidaten else emptyList()
        suchbilder.clear(); bildNr = 0
        geplant = bau.mission
        phase = CockpitZustand.AuftragPhase.PLAN
        DroneManager.log("Mission ${b.art.nr} ${b.art.titel}: plan built, ${bau.mission.waypoints.size} points")
        FlugSitzung.planUebernehmen(bau.mission, bau.beschreibung)
        // Hat die Starttür den Plan nicht angenommen (zu viele Punkte, DJI-Prüfung), steht der Grund im Plan-Feld.
        if (FlugSitzung.mission !== bau.mission && !FlugSitzung.startplatzFrageOffen) { geplant = null; phase = CockpitZustand.AuftragPhase.BRIEF; return nein("The plan was not accepted: " + (MissionController.rejectReason ?: FlugSitzung.planZeile)) }
        melden(); return null
    }

    /** Den Brief wieder öffnen: Werte, Ort und Gesprochenes bleiben; der Plan dazu gilt nicht mehr als Plan dieser Mission. */
    fun aendern() {
        if (brief == null || arbeitet != null || FlugSitzung.uploading) return
        if (phase != CockpitZustand.AuftragPhase.PLAN && phase != CockpitZustand.AuftragPhase.ERGEBNIS) return
        planFallenLassen(); nochmal = null
        phase = CockpitZustand.AuftragPhase.BRIEF; ergebnis = null; ergebnisWarnung = false; fehlt = null
        melden()
    }

    fun kandidatWaehlen(nr: Int) {
        val k = kandidaten.firstOrNull { it.nr == nr } ?: return
        if (phase == CockpitZustand.AuftragPhase.FLUG || arbeitet != null || FlugSitzung.uploading) return
        planFallenLassen(); nochmal = null; suchbilder.clear()
        val was = brief?.text.orEmpty()
        val alle = kandidaten
        brief = Brief(Art.NACHSEHEN).also { it.ort = k.ort; it.text = if (was.isBlank()) "What is at this spot?" else "Is this a good spot for: $was? What speaks against it?" }
        kandidaten = alle
        phase = CockpitZustand.AuftragPhase.BRIEF; fehlt = null; ergebnis = null; ergebnisWarnung = false
        melden()
    }

    /** Vermessungsflug über dem Gebiet des geladenen Plans als Mission 06 vorbereiten (Rechteck um alle Punkte, 20 m Rand). */
    fun vermessungVorbereiten(): String? {
        if (phase == CockpitZustand.AuftragPhase.FLUG) return "A mission is flying."
        if (arbeitet != null) return "Central Command is still working. Wait for the answer."
        if (DroneManager.state.flying) return "Prepare the survey flight on the ground."
        val m = FlugSitzung.mission ?: return "No plan is loaded."
        val pts = m.waypoints.map { it.pos }
        val c = Missionsbau.mitte(pts)
        val halbNord = (pts.maxOf { it.lat } - pts.minOf { it.lat }) * 110_540.0 / 2 + 20.0
        val halbOst = (pts.maxOf { it.lon } - pts.minOf { it.lon }) * 111_320.0 * Math.cos(Math.toRadians(c.lat)) / 2 + 20.0
        val b = Brief(Art.VERMESSEN)
        listOf(-1.0 to 1.0, 1.0 to 1.0, 1.0 to -1.0, -1.0 to -1.0).forEach { (x, y) -> b.ecken += Geo.offset(Geo.offset(c, halbNord * y, 0.0), halbOst * x, 90.0) }
        geplant = null; nochmal = null; suchbilder.clear()   // der geladene Plan bleibt: er ist der, für den vermessen wird
        brief = b; phase = CockpitZustand.AuftragPhase.BRIEF; fehlt = null; ergebnis = null; ergebnisWarnung = false; kandidaten = emptyList()
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

    private const val BILD_NAH_M = 6.0

    private fun takt() {
        val s = DroneManager.state
        val jetzt = System.currentTimeMillis()
        // Akkuwechsel: die Drohne war aus. Ein Plan, der „auf der Drohne“ hieß, ist es danach nicht mehr sicher.
        // Ein kurzer Wackler der Verbindung am Boden zählt nicht.
        if (!s.connected) {
            if (getrenntSeit == 0L) getrenntSeit = jetzt
            // Ein Wackler von unter 5 s zählt nicht als Funkabriss; nach einer Minute ohne Verbindung gilt der Flug als vorbei.
            if (inDerLuft && jetzt - getrenntSeit >= 5_000) funkWeg = true
            if (inDerLuft && jetzt - getrenntSeit >= 60_000) { inDerLuft = false; gelandet() }
        }
        else if (getrenntSeit != 0L) {
            val lang = jetzt - getrenntSeit >= 15_000
            getrenntSeit = 0L
            if (lang && !s.flying && !s.motorsOn && !MissionController.executing && MissionController.uploaded && !Simulator.active) {
                MissionController.planVerloren()
                FlugSitzung.planNichtMehrDrauf()
                DroneManager.log("Drone reconnected after a longer break: plan must be uploaded again")
                melden()
            }
        }
        val cur = FlugSitzung.mission
        // Der Plan zum Brief ist nicht mehr der geladene (abgelehnt nach der Startplatz-Frage, oder ein anderer Plan wurde geholt): zurück zum Brief.
        if (phase == CockpitZustand.AuftragPhase.PLAN && geplant != null && cur !== geplant && !FlugSitzung.startplatzFrageOffen && !MissionController.executing && !s.flying) {
            geplant = null; phase = CockpitZustand.AuftragPhase.BRIEF; melden()
        }
        if (MissionController.executing && cur != null) {
            // Ein Plan, der am Boden gestartet wurde, ist der Hauptplan dieses Flugs. Umwege und der Planrest nach „Resume“
            // (beide beginnen in der Luft) lassen ihn und seinen Brief stehen.
            if (!cur.startFromCurrent && haupt !== cur) {
                haupt = cur; flogBis = -1; zuEnde = false; funkWeg = false; restSatz = null; bildZeiger = 0; nachgesehen = false
                hauptFuer = brief?.takeIf { cur === geplant }
                if (hauptFuer != null) { phase = CockpitZustand.AuftragPhase.FLUG; ergebnis = null; ergebnisWarnung = false; nochmal = null; melden() }
            }
            val h = haupt
            if (h != null && MissionController.currentWaypointIndex >= 0) {
                val i = MissionController.currentWaypointIndex - MissionController.preCount
                // Index im Hauptplan: der Planrest nach „Resume“ ist das Ende des Hauptplans.
                val imHaupt = when {
                    cur === h -> i
                    cur.startFromCurrent && !cur.hoverAtEnd && cur.waypoints.size <= h.waypoints.size -> h.waypoints.size - cur.waypoints.size + i
                    else -> -1   // Umweg
                }
                if (imHaupt > flogBis && imHaupt < h.waypoints.size) flogBis = imHaupt
                if (imHaupt >= 0) bilder(h, s)
            }
        }
        val h = haupt
        if (h != null && !zuEnde && flogBis >= h.waypoints.size - 1) {
            // „Zu Ende“ heißt: der letzte Planpunkt ist erreicht, nicht: die Drohne fliegt heim (das tut sie auch nach STOP).
            val loc = s.location?.takeIf { it.latitude != 0.0 }
            val amLetzten = loc != null && Geo.distance(LatLon(loc.latitude, loc.longitude), h.waypoints.last().pos) < 8.0
            val st = MissionController.executeState
            // FINISHED eines Umwegs (endet schwebend) ist nicht das Ende des Hauptplans.
            if (amLetzten || (st == WaypointMissionExecuteState.FINISHED && cur?.hoverAtEnd != true)) { zuEnde = true; planZuEnde() }
        }
        if (s.flying || s.motorsOn) { inDerLuft = true; startSeit = 0L }
        else if (inDerLuft && s.connected) { inDerLuft = false; gelandet() }
        else if (phase == CockpitZustand.AuftragPhase.FLUG && !MissionController.executing) {
            // Start gemeldet, aber nie abgehoben (die Drohne hat ihn verweigert): nach 10 s zurück zum Plan-Check.
            if (startSeit == 0L) startSeit = jetzt
            else if (jetzt - startSeit >= 10_000) { startSeit = 0L; haupt = null; hauptFuer = null; phase = if (geplant != null && cur === geplant) CockpitZustand.AuftragPhase.PLAN else CockpitZustand.AuftragPhase.BRIEF; melden() }
        } else startSeit = 0L
    }

    /**
     * Standbilder für Central Command, ausgelöst nach dem Ort (näher als 6 m am Planpunkt und auf seiner Höhe), nicht nach
     * dem Wegpunkt-Index: ob der „erreicht“ oder „unterwegs zu“ bedeutet, ist am Gerät nicht belegt.
     */
    private fun bilder(h: Mission, s: DroneState) {
        val b = hauptFuer ?: return
        if (b.art != Art.NACHSEHEN && b.art != Art.SUCHEN) return
        val loc = s.location?.takeIf { it.latitude != 0.0 } ?: return
        val hier = LatLon(loc.latitude, loc.longitude)
        // Bei erhöhtem Startplatz (Balkon) fliegt die Drohne die Planhöhen um den Versatz tiefer: beide Höhen gelten.
        val versatz = MissionController.startAboveGroundM
        fun nah(i: Int) = Geo.distance(hier, h.waypoints[i].pos) < BILD_NAH_M && (Math.abs(loc.altitude - h.waypoints[i].heightM) < 5.0 || Math.abs(loc.altitude - (h.waypoints[i].heightM - versatz)) < 5.0)
        if (b.art == Art.NACHSEHEN) {
            if (!nachgesehen && nah(0)) { nachgesehen = true; ui.postDelayed({ nachsehen(b, h) }, 3000) }
            return
        }
        val jeder = Math.ceil(h.waypoints.size / MAX_SUCHBILDER.toDouble()).toInt().coerceAtLeast(1)
        val j = (bildZeiger until h.waypoints.size).firstOrNull { nah(it) } ?: return
        bildZeiger = j + 1
        if (j % jeder != 0 || suchbilder.size >= MAX_SUCHBILDER) return
        val kurs = s.headingDeg ?: 0.0; val hoehe = loc.altitude
        // Die Drohne steht an jedem Punkt 2 s: kurz warten, bis sie ruhig ist, dann das nächste Bild des Livebilds nehmen.
        ui.postDelayed({
            val l2 = DroneManager.state.location?.takeIf { it.latitude != 0.0 }
            val ort = if (l2 != null) LatLon(l2.latitude, l2.longitude) else hier
            FrameShots.naechstesBild(breite = 768) { jpg -> if (jpg != null && haupt === h) suchbilder += Suchbild(++bildNr, jpg, ort, l2?.altitude ?: hoehe, DroneManager.state.headingDeg ?: kurs) }
        }, 700)
    }

    private fun nachsehen(b: Brief, m: Mission) {
        if (haupt !== m) return
        arbeitet = "Central Command is looking at the place …"; melden()
        FrameShots.naechstesBild { jpg ->
            if (jpg == null) { arbeitet = null; fertig(b, "No still from the live view arrived" + (if (Simulator.active) " (the simulation has no camera)" else "") + ". The two photos are on the card.", true, "Check a place: no still. See Msn."); return@naechstesBild }
            val h = Math.round(DroneManager.state.location?.altitude ?: m.waypoints[0].heightM).toInt()
            fragen(b, "Central Command is looking at the place …") { key ->
                val (antwort, fehler) = ClaudeLink.nachsehen(key, b.text, jpg, h)
                val danach: () -> Unit = { DroneManager.log("Mission 03 answer: $antwort"); fertig(b, antwort, fehler, if (fehler) "Check a place: no answer. See Msn." else "Check a place: the answer is in Msn.") }
                danach
            }
        }
    }

    /**
     * Eine Anfrage an Central Command auf eigenem Thread. [anfrage] läuft dort und gibt zurück, was danach auf dem UI-Thread
     * geschieht. Scheitert sie, bleibt sie als „Ask again“ erhalten; [arbeitet] wird in jedem Fall wieder frei.
     */
    private fun fragen(b: Brief, text: String, anfrage: (String) -> (() -> Unit)) {
        val key = ClaudeLink.key(app)
        if (key == null) { arbeitet = null; fertig(b, "Central Command has no key (Set → Data → Enter Claude key).", true, null); nochmal = { fragen(b, text, anfrage) }; melden(); return }
        arbeitet = text; nochmal = null; melden()
        Thread({
            val danach: () -> Unit = try { anfrage(key) } catch (t: Throwable) { { fertig(b, "Central Command failed: ${t.javaClass.simpleName} ${t.message ?: ""}".trim(), true, null) } }
            ui.post {
                arbeitet = null
                try { danach() } catch (t: Throwable) { fertig(b, "Central Command gave no usable answer.", true, null) }
                if (ergebnisWarnung && brief === b) nochmal = { fragen(b, text, anfrage) }
                melden()
            }
        }, "CentralCommand").start()
    }

    /** Ergebnis in den Brief schreiben, wenn er noch der offene ist; sonst nur ins Protokoll. */
    private fun fertig(b: Brief, text: String, warnung: Boolean, hinweis: String?) {
        if (brief === b) { ergebnis = text; ergebnisWarnung = warnung; hinweis?.let { FlugSitzung.hinweis(it) } }
        else DroneManager.log("Mission ${b.art.nr} result (brief no longer open): $text")
        melden()
    }

    /** Das Fehlgeschlagene noch einmal fragen (Funkloch nach dem Flug): die Bilder liegen noch im Speicher. */
    fun nochmalFragen() { val n = nochmal ?: return; if (arbeitet != null) return; nochmal = null; n() }

    private fun planZuEnde() {
        val b = hauptFuer ?: return
        if (b.art == Art.SUCHEN) suchbilderAuswerten(b)
    }

    private fun suchbilderAuswerten(b: Brief) {
        val bilder = suchbilder.toList()
        if (bilder.isEmpty()) { fertig(b, "No stills from the live view arrived" + (if (Simulator.active) " (the simulation has no camera)" else "") + ". The photos are on the card.", true, "Find a spot: no stills. See Msn."); return }
        val h = Math.round(bilder.map { it.hoeheM }.average()).toInt()
        fragen(b, "Central Command is looking through ${bilder.size} stills …") { key ->
            val r = ClaudeLink.suchen(key, b.text, bilder.map { it.nr to it.jpeg }, h)
            val danach: () -> Unit = {
                if (r.fehler != null) fertig(b, r.fehler, true, "Find a spot: no answer. See Msn.")
                else {
                    val funde = r.funde.mapIndexedNotNull { n, f -> bilder.firstOrNull { it.nr == f.bild }?.let { sb -> Kandidat(n + 1, Auftragsbau.bildOrt(sb.hier, sb.hoeheM, sb.kursGrad, f.x, f.y), f.warum) } }
                    DroneManager.log("Mission 04: ${funde.size} candidates. ${r.text}")
                    if (brief === b) { kandidaten = funde; suchbilder.clear() }
                    fertig(b, r.text.ifBlank { if (funde.isEmpty()) "Nothing found that fits." else if (funde.size == 1) "1 candidate." else "${funde.size} candidates." }, false,
                        if (funde.isEmpty()) "Find a spot: nothing found. See Msn." else "Find a spot: ${funde.size} candidate${if (funde.size == 1) "" else "s"} in Msn.")
                }
            }
            danach
        }
    }

    private fun gelandet() {
        val m = haupt
        val b = hauptFuer
        haupt = null; hauptFuer = null
        val offen = b != null && brief === b
        // Eine Mission ist nach der Landung nie mehr „im Flug“, was auch immer in der Luft geschah.
        if (phase == CockpitZustand.AuftragPhase.FLUG) phase = CockpitZustand.AuftragPhase.ERGEBNIS
        if (m == null) { melden(); return }
        val n = m.waypoints.size
        if (zuEnde) {
            if (offen && ergebnis == null && arbeitet == null) ergebnis = when (b!!.art) {
                Art.VERMESSEN -> "Survey flown. The photos are on the card. Send them to Claude in the chat for the height model and the 3D model (Set → Data → Fetch photos and upload)."
                Art.STIMMUNG -> "Arc flown. Photos and video are on the card."
                else -> "Flight done."
            }
            melden(); return
        }
        // Vorzeitig gelandet (Akku-Umkehr, STOP, Wächter): der Rest wird von selbst der nächste Plan (Felix 22.11.2026).
        if (funkWeg) {
            // Bei Funkabriss fliegt die Drohne den Plan womöglich zu Ende, ohne dass die App es sieht: dann nichts raten.
            restSatz = "The link was lost during the flight, so the app cannot tell how far the plan got. No rest plan was loaded. Check the photos, then decide."
            if (offen) { ergebnis = restSatz; ergebnisWarnung = true }
            FlugSitzung.hinweis("Link was lost in flight: no rest plan loaded.")
            melden(); return
        }
        val rest = if (flogBis >= 0) Akkus.rest(m, flogBis) else null
        if (rest != null && rest.waypoints.size < n) {
            val von = flogBis + 1
            val vorher = FlugSitzung.missionText.substringBefore("\n\n")
            FlugSitzung.planUebernehmen(rest, "Rest of “${m.name}” from point $von.\n\n$vorher")
            if (FlugSitzung.mission === rest || FlugSitzung.startplatzFrageOffen) {
                restSatz = "The flight ended early at point $von of $n. The rest (points $von to $n) is loaded as “${rest.name}”. Swap the battery if needed. If the drone was off, press “Upload again” first, then Start."
                DroneManager.log("Rest plan: points $von to $n of ${m.name}")
                if (offen) { geplant = rest; phase = CockpitZustand.AuftragPhase.PLAN }
                FlugSitzung.hinweis("Rest plan loaded: points $von to $n.")
            } else {
                restSatz = "The flight ended early at point $von of $n. The rest plan could not be loaded: ${FlugSitzung.planZeile}"
                if (offen) { ergebnis = restSatz; ergebnisWarnung = true }
            }
            melden(); return
        }
        if (flogBis <= 0) {
            // Vor oder am ersten Punkt beendet: der „Rest“ wäre der ganze Plan. Er ist noch geladen und kann noch einmal gestartet werden.
            if (offen && FlugSitzung.mission === m) { geplant = m; phase = CockpitZustand.AuftragPhase.PLAN }
            else if (offen) { ergebnis = "The flight ended at the very beginning. Build the plan again to fly it."; ergebnisWarnung = true }
            melden(); return
        }
        // Es fehlt nur noch ein Punkt: kein Restplan. Was an Bildern da ist, wird ausgewertet.
        if (offen && b!!.art == Art.SUCHEN && suchbilder.isNotEmpty() && arbeitet == null && ergebnis == null) suchbilderAuswerten(b)
        else if (offen && ergebnis == null && arbeitet == null) { ergebnis = "The flight ended at point ${flogBis + 1} of $n, just before the end."; ergebnisWarnung = false }
        melden()
    }

    // ---------------------------------------------------------------- Zustand für die Oberfläche (nur Worte)

    fun zustand(): CockpitZustand.Auftrag {
        val b = brief ?: return CockpitZustand.Auftrag(restSatz = restSatz)
        if (phase == CockpitZustand.AuftragPhase.KEINE) return CockpitZustand.Auftrag(restSatz = restSatz)
        val home = FlugSitzung.homeLatLon()
        val ortText = when (b.art.ort) {
            OrtArt.PUNKT -> b.ort?.let { Auftragsbau.ortText(home, it) }
            OrtArt.FLAECHE -> if (b.ecken.isEmpty()) null else "${b.ecken.size} corner${if (b.ecken.size == 1) "" else "s"}" + (if (b.ecken.size >= 3) String.format(Locale.US, " · %.1f ha", Missionsbau.flaecheM2(b.ecken) / 10_000) else "")
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
            nochmalMoeglich = nochmal != null && arbeitet == null,
            vorschlaege = if (phase == CockpitZustand.AuftragPhase.BRIEF && b.text.isBlank()) Auftragsbau.vorschlaege(b.art) else emptyList(),
        )
    }
}
