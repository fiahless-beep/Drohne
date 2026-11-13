package com.felixos.drohne

import android.os.Handler
import android.os.Looper
import com.felixos.drohne.plan.Geo
import com.felixos.drohne.plan.LatLon
import com.felixos.drohne.plan.Mission
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Simulationsmodus (Felix 8.11.): Die App tut so, als wäre eine Drohne verbunden, nimmt den Plan durch
 * alle Prüfungen (prepare, Startprüfung, Wächter – alles echt) und fliegt ihn nach „Start“ mit den
 * geplanten Geschwindigkeiten ab: Start senkrecht, Transit, Wegpunkte mit Foto/Schweben, Heimflug, Landung.
 * Kein DJI-Aufruf; MissionController und DroneManager leiten in diesem Modus hierher um.
 */
object Simulator {
    @Volatile var active = false; private set
    /** Zeitraffer (1 = Echtzeit). */
    @Volatile var timeScale = 4.0

    private val main = Handler(Looper.getMainLooper())
    private const val TICK_MS = 100L
    private const val CLIMB_MS = 2.5   // m/s senkrecht beim Start
    private const val DESCEND_MS = 1.5 // m/s beim Landen
    private const val PHOTO_SEC = 2.0

    private var home: LatLon? = null
    private var pos: LatLon? = null
    private var alt = 0.0
    private var heading = 0.0
    private var gimbal = -20.0
    private var battery = 100.0

    // Flug
    private enum class Phase { IDLE, TAKEOFF, ROUTE, HOLD, HOVER, RTH_CLIMB, RTH_FLY, LAND }
    private var phase = Phase.IDLE
    private var route: List<Pair<LatLon, Double>> = emptyList() // Position, Höhe
    private var speeds: List<Double> = emptyList()               // Tempo ab Punkt i
    private var holdSec: List<Double> = emptyList()              // Aufenthalt an Punkt i (Foto/Schweben)
    private var photo: List<Boolean> = emptyList()
    private var yaws: List<Double?> = emptyList()
    private var pitches: List<Double> = emptyList()
    private var seg = 0            // aktueller Abschnitt seg → seg+1
    private var holdLeft = 0.0
    private var paused = false
    private var rthHeight = 30.0
    private var mission: Mission? = null

    /** Simulation einschalten; [at] = Startpunkt (Handyposition oder geplanter Start), null = später beim Plan setzen. */
    fun start(at: LatLon?) {
        active = true
        timeScale = 4.0
        battery = 100.0
        setHome(at)
        DroneManager.log("SIMULATION an" + (at?.let { String.format(java.util.Locale.US, " – Startpunkt %.5f, %.5f", it.lat, it.lon) } ?: " – Startpunkt kommt mit dem Plan"))
        push()
    }

    fun stopSimulation() {
        main.removeCallbacks(ticker)
        phase = Phase.IDLE
        active = false
        MissionController.simulatedState(null, -1)
        DroneManager.simulated(null)
        DroneManager.log("SIMULATION aus")
    }

    fun setHome(at: LatLon?) {
        home = at; pos = at; alt = 0.0
        push()
    }

    val homeOrNull: LatLon? get() = home

    /** Zustand an die Oberfläche: wie eine verbundene Mini 4 Pro am Boden bzw. im Flug. */
    private fun push() {
        val h = home; val p = pos
        val flying = phase != Phase.IDLE
        DroneManager.simulated(DroneState(
            registered = true, connected = true, rcConnected = true, airLinkConnected = true,
            productType = "Simulation", batteryPercent = battery.toInt(), satellites = 16,
            location = p?.let { LocationCoordinate3D(it.lat, it.lon, alt) },
            home = h?.let { LocationCoordinate2D(it.lat, it.lon) },
            headingDeg = heading, gimbalPitchDeg = gimbal, flying = flying, motorsOn = flying, landing = phase == Phase.LAND,
            sensors = "aktiv", heightLimitM = 120, windWarning = 0,
        ))
    }

    // ---------------------------------------------------------------- Mission

    fun upload(): Boolean { DroneManager.log("Simulation: Mission „hochgeladen“"); return true }

    fun fly(m: Mission, rthHeightM: Double) {
        val h = home ?: return DroneManager.log("Simulation: kein Startpunkt")
        mission = m
        rthHeight = rthHeightM
        route = m.waypoints.map { it.pos to it.heightM }
        speeds = m.waypoints.map { it.speedMs ?: m.speedMs }
        holdSec = m.waypoints.map { (if (it.takePhoto) PHOTO_SEC else 0.0) + it.hoverSec }
        photo = m.waypoints.map { it.takePhoto }
        yaws = m.waypoints.map { it.headingDeg }
        pitches = m.waypoints.map { it.gimbalPitchDeg }
        if (phase == Phase.IDLE || !m.startFromCurrent) { pos = h; alt = 0.0 }  // in-flight plans continue from where the drone hovers
        seg = 0; paused = false
        phase = Phase.TAKEOFF
        MissionController.simulatedState(WaypointMissionExecuteState.ENTER_WAYLINE, -1)
        DroneManager.log("Simulation: Start, steigt auf ${route.first().second.toInt()} m")
        main.removeCallbacks(ticker); main.postDelayed(ticker, TICK_MS)
        push()
    }

    fun pause() { if (phase != Phase.IDLE) { paused = true; MissionController.simulatedState(WaypointMissionExecuteState.INTERRUPTED, seg); DroneManager.log("Simulation: Pause") } }
    fun resume() { if (paused) { paused = false; MissionController.simulatedState(WaypointMissionExecuteState.EXECUTING, seg); DroneManager.log("Simulation: weiter") } }

    /** Plan stopped in flight: the drone hovers where it is. */
    fun hover() { if (phase != Phase.IDLE && phase != Phase.LAND) { phase = Phase.HOVER; paused = false; DroneManager.log("Simulation: plan stopped, hovering") } }

    /** STOPP / Heimflug: hoch auf Rückkehrhöhe, über den Start, landen. */
    fun goHome() {
        val h = home ?: return
        if (phase == Phase.IDLE) { DroneManager.log("Simulation: am Boden, nichts zu tun"); return }
        paused = false
        phase = if (alt < rthHeight - 0.5 && Geo.distance(pos ?: h, h) > 2) Phase.RTH_CLIMB else Phase.RTH_FLY
        MissionController.simulatedState(WaypointMissionExecuteState.RETURN_TO_START_POINT, -1)
        DroneManager.log("Simulation: Heimflug auf ${rthHeight.toInt()} m")
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!active || phase == Phase.IDLE) return
            if (!paused) step(TICK_MS / 1000.0 * timeScale)
            push()
            main.postDelayed(this, TICK_MS)
        }
    }

    /** Bewegt die Drohne um [dt] Sekunden weiter. */
    private fun step(dt: Double) {
        val h = home ?: return
        battery = max(0.0, battery - dt / 20.0) // ca. 1 % je 20 s
        when (phase) {
            Phase.TAKEOFF -> {
                val target = route.first()
                if (moveTo(target.first, target.second, min(CLIMB_MS, speeds.first()), dt)) {
                    phase = Phase.ROUTE; seg = 0
                    MissionController.simulatedState(WaypointMissionExecuteState.EXECUTING, 0)
                    arrive(0)
                }
            }
            Phase.ROUTE -> {
                if (seg + 1 >= route.size) { endRoute(); return }
                val target = route[seg + 1]
                if (moveTo(target.first, target.second, speeds[seg], dt)) {
                    seg += 1
                    MissionController.simulatedState(WaypointMissionExecuteState.EXECUTING, seg)
                    arrive(seg)
                }
            }
            Phase.HOLD -> {
                holdLeft -= dt
                if (holdLeft <= 0) { if (seg + 1 >= route.size) endRoute() else phase = Phase.ROUTE }
            }
            Phase.RTH_CLIMB -> if (moveTo(pos ?: h, rthHeight, CLIMB_MS, dt)) phase = Phase.RTH_FLY
            Phase.RTH_FLY -> if (moveTo(h, alt, MissionController.TRANSIT_SPEED_MAX, dt)) { phase = Phase.LAND; DroneManager.log("Simulation: über dem Start, landet") }
            Phase.LAND -> if (moveTo(h, 0.0, DESCEND_MS, dt)) {
                phase = Phase.IDLE
                main.removeCallbacks(ticker)
                MissionController.simulatedState(WaypointMissionExecuteState.FINISHED, -1)
                DroneManager.log("Simulation: gelandet")
            }
            Phase.IDLE, Phase.HOVER -> {}
        }
    }

    private fun arrive(i: Int) {
        val hold = holdSec.getOrElse(i) { 0.0 }
        // Am Punkt: Nase in die geplante Blickrichtung drehen, Gimbal auf die geplante Neigung
        yaws.getOrNull(i)?.let { heading = it }
        pitches.getOrNull(i)?.let { gimbal = it }
        if (photo.getOrElse(i) { false }) DroneManager.log("Simulation: Foto an Punkt ${i + 1}")
        if (hold > 0) { holdLeft = hold; phase = Phase.HOLD }
    }

    private fun endRoute() {
        if (mission?.hoverAtEnd == true) { phase = Phase.HOVER; MissionController.simulatedState(WaypointMissionExecuteState.FINISHED, -1); DroneManager.log("Simulation: detour done, hovering") }
        else startRth()
    }

    private fun startRth() {
        phase = Phase.RTH_FLY
        MissionController.simulatedState(WaypointMissionExecuteState.RETURN_TO_START_POINT, -1)
        DroneManager.log("Simulation: letzter Punkt erreicht, Heimflug")
    }

    /** Fliegt in Richtung Ziel mit [speed] (waagerecht) bzw. begrenztem Steigen/Sinken; true = angekommen. */
    private fun moveTo(target: LatLon, targetAlt: Double, speed: Double, dt: Double): Boolean {
        val p = pos ?: return true
        val dist = Geo.distance(p, target)
        val dAlt = targetAlt - alt
        val vClimb = if (dAlt > 0) CLIMB_MS else DESCEND_MS
        // Höhe und Strecke gleichzeitig, aber keins schneller als erlaubt; Senkrechtflug nur Höhe.
        val total = max(dist / max(speed, 0.1), abs(dAlt) / vClimb)
        if (total <= dt) { pos = target; alt = targetAlt; return true }
        val f = dt / total
        if (dist > 0.01) { heading = Geo.bearing(p, target); pos = Geo.offset(p, dist * f, heading); gimbal = -20.0 }
        alt += dAlt * f
        return false
    }
}
