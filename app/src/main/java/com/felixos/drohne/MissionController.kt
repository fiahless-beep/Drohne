package com.felixos.drohne

import android.content.Context
import com.dji.wpmzsdk.manager.WPMZManager
import com.felixos.drohne.plan.Mission
import com.felixos.drohne.plan.Wpml
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
            override fun onMissionStateUpdate(state: WaypointMissionExecuteState?) {
                DroneManager.log("Mission: $state")
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

    /** Mission als KMZ schreiben und (wenn möglich) mit DJIs eigenem Prüfer kontrollieren. */
    fun prepare(m: Mission): Boolean {
        val f = Wpml.writeKmz(m, File(dir, "${m.name}.kmz"))
        currentKmz = f
        uploaded = false
        DroneManager.log("KMZ erstellt: ${m.waypoints.size} Punkte, ${m.lengthM.toInt()} m, ca. ${(m.durationSec / 60).toInt() + 1} min")
        if (wpmzReady) {
            val res = WPMZManager.getInstance().checkValidation(f.absolutePath)
            val errs = res?.value ?: emptyList()
            if (errs.isNotEmpty()) {
                DroneManager.log("DJI-Prüfung meldet: $errs")
                return false
            }
            DroneManager.log("DJI-Prüfung: in Ordnung")
        }
        return true
    }

    fun upload(onDone: (Boolean) -> Unit) {
        val f = currentKmz ?: return DroneManager.log("Erst Mission planen").also { onDone(false) }
        DroneManager.log("Lade Mission auf die Drohne …")
        WaypointMissionManager.getInstance().pushKMZFileToAircraft(f.absolutePath,
            object : CommonCallbacks.CompletionCallbackWithProgress<Double> {
                private var last = -1
                override fun onProgressUpdate(p: Double?) {
                    val pct = ((p ?: 0.0) * if ((p ?: 0.0) <= 1.0) 100 else 1).toInt()
                    if (pct / 25 != last) { last = pct / 25; DroneManager.log("Upload $pct %") }
                }
                override fun onSuccess() { uploaded = true; DroneManager.log("Mission ist auf der Drohne"); onDone(true) }
                override fun onFailure(error: IDJIError) { DroneManager.log("Upload fehlgeschlagen: ${error.description()}"); onDone(false) }
            })
    }

    private val missionId get() = currentKmz?.nameWithoutExtension ?: ""

    fun start() {
        val f = currentKmz ?: return DroneManager.log("Keine Mission")
        if (!uploaded) return DroneManager.log("Mission erst hochladen")
        val ids = WaypointMissionManager.getInstance().getAvailableWaylineIDs(f.absolutePath)
        WaypointMissionManager.getInstance().startMission(missionId, ids, cb("Start"))
    }

    fun pause() = WaypointMissionManager.getInstance().pauseMission(cb("Pause"))
    fun resume() = WaypointMissionManager.getInstance().resumeMission(cb("Weiter"))
    fun stop() = WaypointMissionManager.getInstance().stopMission(missionId, cb("Abbruch"))

    private fun cb(what: String) = object : CommonCallbacks.CompletionCallback {
        override fun onSuccess() { DroneManager.log("$what: ok") }
        override fun onFailure(error: IDJIError) { DroneManager.log("$what fehlgeschlagen: ${error.description()}") }
    }
}
