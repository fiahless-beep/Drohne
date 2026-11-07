package com.felixos.drohne

import android.content.Context
import com.dji.wpmzsdk.manager.WPMZManager
import com.felixos.drohne.plan.Mission
import com.felixos.drohne.plan.Wpml
import com.felixos.drohne.plan.WpmlOptions
import com.felixos.drohne.plan.Geo
import com.felixos.drohne.plan.RcLost
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.waypoint3.WaylineExecutingInfoListener
import dji.v5.manager.aircraft.waypoint3.WaypointMissionExecuteStateListener
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager
import dji.v5.manager.aircraft.waypoint3.model.WaylineExecutingInfo
import dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState
import java.io.File

/** Mission erzeugen (KMZ), prüfen, auf die Drohne laden, starten, pausieren, abbrechen. */
object MissionController {
    private lateinit var dir: File
    private var wpmzReady = false
    var currentKmz: File? = null
        private set
    var uploaded = false
        private set
    /** Wird einmal aufgerufen, wenn die Mission abgeflogen ist (vor dem Heimflug). */
    var onFinished: (() -> Unit)? = null

    fun init(context: Context) {
        dir = File(context.getExternalFilesDir(null), "missions").apply { mkdirs() }
        try {
            WPMZManager.getInstance().init(context)
            wpmzReady = true
        } catch (t: Throwable) {
            DroneManager.log("KMZ-Prüfer nicht verfügbar: ${t.message}")
        }
        val mgr = WaypointMissionManager.getInstance()
        mgr.addWaypointMissionExecuteStateListener(object : WaypointMissionExecuteStateListener {
            private var last: WaypointMissionExecuteState? = null
            override fun onMissionStateUpdate(state: WaypointMissionExecuteState?) {
                if (state == last) return
                last = state
                DroneManager.log("Mission: $state")
                if (state == WaypointMissionExecuteState.FINISHED || state == WaypointMissionExecuteState.RETURN_TO_START_POINT) {
                    onFinished?.invoke()
                }
            }
        })
        mgr.addWaylineExecutingInfoListener(object : WaylineExecutingInfoListener {
            override fun onWaylineExecutingInfoUpdate(info: WaylineExecutingInfo?) {
                info?.let { DroneManager.log("Wegpunkt ${it.currentWaypointIndex + 1}") }
            }
            override fun onWaylineExecutingInterruptReasonUpdate(error: IDJIError?) {
                error?.let { DroneManager.log("Mission unterbrochen: ${it.description()}") }
            }
        })
    }

    /** Eine Prüf-Variante: Beschreibung, abgewandelte Mission, Datei-Optionen. */
    private data class Variant(val label: String, val m: Mission, val o: WpmlOptions)

    /**
     * Mission als KMZ schreiben und mit DJIs eigenem Prüfer kontrollieren.
     * Meldet der Prüfer Fehler, werden einzelne Abwandlungen durchprobiert und protokolliert,
     * damit die Ursache sichtbar wird; die erste fehlerfreie Variante wird übernommen.
     */
    fun prepare(m: Mission): Boolean {
        uploaded = false
        val base = Variant("Standard", m, WpmlOptions())
        var errs = check(base, "${m.name}.kmz")
        DroneManager.log("KMZ erstellt: ${m.waypoints.size} Punkte, ${m.lengthM.toInt()} m, ca. ${(m.durationSec / 60).toInt() + 1} min")
        if (errs.isEmpty()) { DroneManager.log("DJI-Prüfung: in Ordnung"); return true }
        DroneManager.log("DJI-Prüfung meldet: $errs – probiere Abwandlungen …")
        val home = m.waypoints.first().pos
        val shifted = m.copy(waypoints = m.waypoints.mapIndexed { i, w ->
            w.copy(pos = Geo.offset(w.pos, 5.0 * (i + 1), 0.0))
        })
        val singles = listOf(
            Variant("Funkverlust=Heimflug", m.copy(rcLost = RcLost.GO_HOME), WpmlOptions()),
            Variant("ohne Heimflugaktion", m, WpmlOptions(alwaysRcLostAction = false)),
            Variant("ohne Aktionen", m, WpmlOptions(noActions = true)),
            Variant("Punkte 5 m weiter weg", shifted, WpmlOptions()),
            Variant("Gerät 77 (M3E)", m, WpmlOptions(droneEnum = 77)),
            Variant("Gerät 67 (M30, DJI-Beispiel)", m, WpmlOptions(droneEnum = 67)),
            Variant("WPML 1.0.0", m, WpmlOptions(version = "1.0.0")),
            Variant("mit payloadInfo", m, WpmlOptions(payloadInfo = true)),
        )
        val results = singles.map { v -> v to check(v, "probe.kmz") }
        results.forEach { (v, e) -> DroneManager.log("  ${v.label}: ${if (e.isEmpty()) "OK" else e.toString()}") }
        // Erste Abwandlung, die alle Fehler beseitigt
        results.firstOrNull { it.second.isEmpty() }?.let { (v, _) ->
            check(v, "${m.name}.kmz"); DroneManager.log("Übernommen: ${v.label}"); return true
        }
        // Sonst: alle Abwandlungen kombinieren, die je einen Fehler weniger hatten
        val helpful = results.filter { it.second.size < errs.size }.map { it.first }
        if (helpful.isNotEmpty()) {
            var mm = m; var oo = WpmlOptions()
            helpful.forEach { v ->
                if (v.m !== m) mm = if (v.m.rcLost == RcLost.GO_HOME) mm.copy(rcLost = RcLost.GO_HOME) else mm.copy(waypoints = v.m.waypoints)
                oo = oo.copy(
                    alwaysRcLostAction = oo.alwaysRcLostAction || v.o.alwaysRcLostAction,
                    noActions = oo.noActions || v.o.noActions,
                    version = if (v.o.version != "1.0.6") v.o.version else oo.version,
                    droneEnum = v.o.droneEnum ?: oo.droneEnum,
                    payloadInfo = oo.payloadInfo || v.o.payloadInfo,
                )
            }
            val combo = Variant("Kombination: " + helpful.joinToString(" + ") { it.label }, mm, oo)
            val e = check(combo, "${m.name}.kmz")
            DroneManager.log("  ${combo.label}: ${if (e.isEmpty()) "OK" else e.toString()}")
            if (e.isEmpty()) { DroneManager.log("Übernommen: ${combo.label}"); return true }
        }
        check(base, "${m.name}.kmz")
        DroneManager.log("Keine Variante fehlerfrei – Protokoll an Claude schicken")
        return false
    }

    /** Schreibt die Variante als Datei und gibt DJIs Fehlerliste zurück (leer = in Ordnung). */
    private fun check(v: Variant, fileName: String): List<Any> {
        val f = Wpml.writeKmz(v.m, File(dir, fileName), options = v.o)
        if (fileName != "probe.kmz") currentKmz = f
        if (!wpmzReady) return emptyList()
        return try {
            WPMZManager.getInstance().checkValidation(f.absolutePath)?.value ?: emptyList()
        } catch (t: Throwable) { listOf("Prüfer-Fehler: ${t.message}") }
    }

    fun upload(onDone: (Boolean) -> Unit) {
        val f = currentKmz ?: return DroneManager.log("Erst Mission planen").also { onDone(false) }
        if (!DroneManager.requireSdk("Hochladen")) return onDone(false)
        DroneManager.log("Lade Mission auf die Drohne …")
        DroneManager.safe("Hochladen") { WaypointMissionManager.getInstance().pushKMZFileToAircraft(f.absolutePath,
            object : CommonCallbacks.CompletionCallbackWithProgress<Double> {
                private var last = -1
                override fun onProgressUpdate(p: Double?) {
                    val pct = ((p ?: 0.0) * if ((p ?: 0.0) <= 1.0) 100 else 1).toInt()
                    if (pct / 25 != last) { last = pct / 25; DroneManager.log("Upload $pct %") }
                }
                override fun onSuccess() { uploaded = true; DroneManager.log("Mission ist auf der Drohne"); onDone(true) }
                override fun onFailure(error: IDJIError) { DroneManager.log("Upload fehlgeschlagen: ${error.description()}"); onDone(false) }
            }) }
    }

    private val missionId get() = currentKmz?.nameWithoutExtension ?: ""

    fun start() {
        val f = currentKmz ?: return DroneManager.log("Keine Mission")
        if (!uploaded) return DroneManager.log("Mission erst hochladen")
        FlightReport.startFlight(missionId)
        DroneManager.safe("Start") {
            val ids = WaypointMissionManager.getInstance().getAvailableWaylineIDs(f.absolutePath)
            WaypointMissionManager.getInstance().startMission(missionId, ids, cb("Start"))
        }
    }

    fun pause() = DroneManager.safe("Pause") { WaypointMissionManager.getInstance().pauseMission(cb("Pause")) }
    fun resume() = DroneManager.safe("Weiter") { WaypointMissionManager.getInstance().resumeMission(cb("Weiter")) }
    fun stop() = DroneManager.safe("Abbruch") { WaypointMissionManager.getInstance().stopMission(missionId, cb("Abbruch")) }

    private fun cb(what: String) = object : CommonCallbacks.CompletionCallback {
        override fun onSuccess() { DroneManager.log("$what: ok") }
        override fun onFailure(error: IDJIError) { DroneManager.log("$what fehlgeschlagen: ${error.description()}") }
    }
}
