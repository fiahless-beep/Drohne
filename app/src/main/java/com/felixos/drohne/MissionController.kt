package com.felixos.drohne

import android.content.Context
import com.dji.wpmzsdk.manager.WPMZManager
import com.felixos.drohne.plan.Mission
import com.felixos.drohne.plan.Wpml
import com.felixos.drohne.plan.WpmlOptions
import com.felixos.drohne.plan.Geo
import com.felixos.drohne.plan.LatLon
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
    var currentMission: Mission? = null
    /** Konflikte des aktuellen Plans mit bekannten Gebäuden (Obstacles.check). */
    var conflicts: List<com.felixos.drohne.plan.Obstacles.Conflict> = emptyList()
    /** Startpunkt, mit dem der aktuelle Plan vorbereitet (Transit, Gebäude-Wächter) wurde; null = ohne Startpunkt. */
    var preparedHome: LatLon? = null
    /** Startplatz über dem Boden des Höhenmodells (Balkon); 0 = ebenerdig. Wird in prepare() gesetzt, View3D nutzt ihn. */
    var startAboveGroundM = 0.0
    /** Antwort auf „Start vom Balkon?“ für den nächsten prepare(): null = Plan/kein erhöhter Platz, sonst Meter. */
    var startAboveOverrideM: Double? = null
    /** Konflikte mit dem Höhenmodell (Gelände, Dächer, Bäume). */
    var terrainConflicts: List<com.felixos.drohne.plan.Terrain.Conflict> = emptyList()
        private set
    var uploaded = false
        private set
    /** Wird einmal aufgerufen, wenn die Mission abgeflogen ist (vor dem Heimflug). */
    var onFinished: (() -> Unit)? = null
    /** Kurztext für die Missions-Kachel in der Oberfläche. */
    var onStateText: ((String) -> Unit)? = null
    private var stateText = ""
    @Volatile var executing = false; private set
    /** Nur Anzeige: letzter von DJI gemeldeter Ausführungszustand. */
    @Volatile var executeState: WaypointMissionExecuteState? = null; private set
    /** Nur Anzeige: Index des aktuellen Wegpunkts in [currentMission] (0-basiert), -1 = keiner gemeldet. */
    @Volatile var currentWaypointIndex = -1; private set
    /** Last waypoint index DJI reported for the running plan (survives a pause; -1 = none yet). */
    @Volatile var lastReachedIndex = -1; private set
    /** Number of points prepare() put in front of the plan's own waypoints (climb, approach). */
    var preCount = 0; private set
    /** Called when an in-flight plan with hoverAtEnd has finished (drone hovers). */
    var onHoverFinished: (() -> Unit)? = null
    val missionIdNow: String get() = missionId

    /** Everything prepare() changes, so a rejected detour leaves the paused plan untouched. */
    class Snapshot(val kmz: File?, val mission: Mission?, val conflicts: List<com.felixos.drohne.plan.Obstacles.Conflict>, val terrain: List<com.felixos.drohne.plan.Terrain.Conflict>,
                   val home: LatLon?, val above: Double, val uploaded: Boolean, val rth: Double, val tail: Boolean, val pre: Int, val text: String)
    fun snapshot() = Snapshot(currentKmz, currentMission, conflicts, terrainConflicts, preparedHome, startAboveGroundM, uploaded, rthHeightM, landingTail, preCount, stateText)
    fun restore(s: Snapshot) { currentKmz = s.kmz; currentMission = s.mission; conflicts = s.conflicts; terrainConflicts = s.terrain; preparedHome = s.home
        startAboveGroundM = s.above; uploaded = s.uploaded; rthHeightM = s.rth; landingTail = s.tail; preCount = s.pre; show(s.text) }
    fun say(text: String) = show(text)
    val hasLandingTail: Boolean get() = landingTail
    /** Return-path problems (surface between a waypoint and home higher than any allowed return height). */
    var returnProblems: List<String> = emptyList(); private set
    private fun show(text: String) { stateText = text; onStateText?.invoke(text) }

    private var initialized = false

    /** Ordner und KMZ-Prüfer ohne Drohne (Simulation vor der SDK-Registrierung). */
    fun initOffline(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.getExternalFilesDir(null), "missions").apply { mkdirs() }
        try { WPMZManager.getInstance().init(context); wpmzReady = true } catch (t: Throwable) { DroneManager.log("KMZ-Prüfer nicht verfügbar: ${t.message}") }
    }

    /** Zustandswechsel verarbeiten (von DJI gemeldet oder von der Simulation). */
    private fun applyState(state: WaypointMissionExecuteState?, index: Int?) {
        executeState = state
        if (index != null) { currentWaypointIndex = index; if (index >= 0) lastReachedIndex = index }
        else if (state == WaypointMissionExecuteState.EXECUTING || state == WaypointMissionExecuteState.ENTER_WAYLINE) { if (currentWaypointIndex < 0) currentWaypointIndex = 0 } else if (state != WaypointMissionExecuteState.RECOVERING) currentWaypointIndex = -1
        executing = state == WaypointMissionExecuteState.EXECUTING || state == WaypointMissionExecuteState.ENTER_WAYLINE
        DroneManager.log("Mission: $state")
        val m = currentMission
        val name = m?.name ?: missionId
        show(when (state) {
            WaypointMissionExecuteState.EXECUTING -> "▶ $name running"
            WaypointMissionExecuteState.RECOVERING -> "$name: resuming"
            WaypointMissionExecuteState.RETURN_TO_START_POINT -> "$name: returning home"
            WaypointMissionExecuteState.FINISHED -> "✓ $name done – photos follow after landing"
            WaypointMissionExecuteState.INTERRUPTED -> "⚠ $name paused"
            null -> ""
            else -> "$name: $state"
        })
        if (state == WaypointMissionExecuteState.FINISHED || state == WaypointMissionExecuteState.RETURN_TO_START_POINT) {
            if (currentMission?.light == true) DroneManager.setBottomLight("auto")
            if (currentMission?.waypoints?.any { it.video != 0 } == true) DroneManager.stopRecording("Missionsende")
            if (currentMission?.hoverAtEnd == true) {
                if (state == WaypointMissionExecuteState.FINISHED) { show("✓ $name done – hovering"); DroneManager.log("Detour finished: drone hovers, main plan waits"); onHoverFinished?.invoke() }
                return
            }
            // Erhöhter/überdachter Startplatz (Balkon, Felix 9.11.): kein automatischer Heimflug/Abstieg, Drohne schwebt über dem Haus, Landung von Hand.
            if ((currentMission?.startAboveGroundM ?: 0.0) > 0.1 || startAboveGroundM > 0.1) DroneManager.log("Plan fertig: Drohne schwebt über dem Startplatz. Balkon – bitte von Hand landen.")
            else DroneManager.landingHelper()
            if (!Simulator.active) onFinished?.invoke()
        }
    }

    private var lastSimState: WaypointMissionExecuteState? = null
    private var lastSimIndex = -2

    /** Von der Simulation: Zustand und Wegpunkt setzen (gleiche Anzeige wie bei DJI-Meldungen). */
    fun simulatedState(state: WaypointMissionExecuteState?, index: Int) {
        if (state == lastSimState && index == lastSimIndex) return
        val idxChanged = index != lastSimIndex && index >= 0
        lastSimState = state; lastSimIndex = index
        if (state != executeState || index < 0) applyState(state, index)
        if (idxChanged) { currentWaypointIndex = index; lastReachedIndex = index; val n = currentMission?.waypoints?.size; DroneManager.log("Wegpunkt ${index + 1}"); show("▶ ${currentMission?.name ?: missionId}: point ${index + 1}" + (n?.let { c -> " of $c" } ?: "")) }
    }

    @Volatile private var stateFromDrone = 0L
    /** Wegpunkt-Fortschritt und Unterbrechungsgrund der Drohne. */
    private val infoListener = object : WaylineExecutingInfoListener {
        override fun onWaylineExecutingInfoUpdate(info: WaylineExecutingInfo?) {
            if (Simulator.active) return
            info?.let {
                DroneManager.log("Wegpunkt ${it.currentWaypointIndex + 1}")
                currentWaypointIndex = it.currentWaypointIndex; lastReachedIndex = it.currentWaypointIndex
                val n = currentMission?.waypoints?.size
                show("▶ ${currentMission?.name ?: missionId}: point ${it.currentWaypointIndex + 1}" + (n?.let { c -> " of $c" } ?: ""))
            }
        }
        override fun onWaylineExecutingInterruptReasonUpdate(error: IDJIError?) {
            error?.let { val why = "${it.errorCode() ?: ""} ${it.description() ?: ""}".trim(); DroneManager.log("Mission unterbrochen: $why"); if (why.isNotBlank()) show("⚠ Drone interrupted the plan: $why") }
        }
    }

    private val stateListener = object : WaypointMissionExecuteStateListener {
        private var last: WaypointMissionExecuteState? = null
        override fun onMissionStateUpdate(state: WaypointMissionExecuteState?) {
            if (state == last) return
            last = state
            if (Simulator.active) return
            stateFromDrone = System.currentTimeMillis()
            applyState(state, null)
        }
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        initOffline(context)
        val mgr = WaypointMissionManager.getInstance()
        mgr.addWaypointMissionExecuteStateListener(stateListener)
        mgr.addWaylineExecutingInfoListener(infoListener)
    }

    /** Eine Prüf-Variante: Beschreibung, abgewandelte Mission, Datei-Optionen. */
    private data class Variant(val label: String, val m: Mission, val o: WpmlOptions)

    /**
     * Mission als KMZ schreiben und mit DJIs eigenem Prüfer kontrollieren.
     * Meldet der Prüfer Fehler, werden einzelne Abwandlungen durchprobiert und protokolliert,
     * damit die Ursache sichtbar wird; die erste fehlerfreie Variante wird übernommen.
     */
    /** Sichere Transithöhe: Anflug und Rückweg nie unter dieser Höhe (Absturz 7.11.: 12 m gegen Gebäude). */
    const val TRANSIT_HEIGHT_M = 30.0
    /** Tempo-Regeln (Felix 8.11.): unter Transithöhe max. 4 m/s, im Transit max. 5 m/s; nahe Gebäuden gedrosselt bis 1,5 m/s am 8-m-Abstand. */
    const val LOW_SPEED_MAX = 4.0
    const val TRANSIT_SPEED_MAX = 5.0
    const val NEAR_SPEED_MIN = 1.5
    const val NEAR_DIST_M = 30.0
    /** Heimflughöhe für diesen Plan (Transit bzw. höchstes Gebäude + Abstand). */
    var rthHeightM = TRANSIT_HEIGHT_M

    private fun speedFor(w: com.felixos.drohne.plan.Waypoint, next: com.felixos.drohne.plan.Waypoint?, m: Mission): Double {
        val cap = minOf(m.speedMs, if (w.heightM < TRANSIT_HEIGHT_M - 0.5 || (next != null && next.heightM < TRANSIT_HEIGHT_M - 0.5)) LOW_SPEED_MAX else TRANSIT_SPEED_MAX)
        if (m.obstacles.isEmpty()) return cap
        val pts = listOfNotNull(w, next) + (next?.let { listOf(w.copy(pos = LatLon((w.pos.lat + it.pos.lat) / 2, (w.pos.lon + it.pos.lon) / 2), heightM = minOf(w.heightM, it.heightM))) } ?: emptyList())
        val d = pts.mapNotNull { com.felixos.drohne.plan.Obstacles.nearestRelevant(it.pos, it.heightM, m.obstacles) }.minOrNull() ?: return cap
        if (d >= NEAR_DIST_M) return cap
        val t = ((d - com.felixos.drohne.plan.Obstacles.MARGIN_M) / (NEAR_DIST_M - com.felixos.drohne.plan.Obstacles.MARGIN_M)).coerceIn(0.0, 1.0)
        return (NEAR_SPEED_MIN + t * (cap - NEAR_SPEED_MIN)).coerceAtMost(cap)
    }

    /** Letzter Abschnitt ist der senkrechte Abstieg vor den Balkon (Landeanflug, von Hand fortgesetzt) – nicht gegen Fassaden prüfen. */
    private var landingTail = false

    fun prepare(mIn0: Mission): Boolean {
        uploaded = false
        landingTail = false
        val h = DroneManager.state.home
        val home = if (h?.latitude != null && h.latitude != 0.0) LatLon(h.latitude, h.longitude) else null
        preparedHome = home
        // Erhöhter Startplatz (Felix 8.11.: Balkon 3. Stock): Planhöhen gelten ab Boden → für die Drohne um den Versatz senken,
        // Gelände und Gebäude entsprechend tiefer relativ zur Drohne. Herkunft: Planfeld startUeberBoden, sonst Antwort im Dialog.
        // "h": "max" → 2 m unter dem DJI-Höhenlimit (höchstens 120 m; GEO-Höhenzonen begrenzt DJI selbst). Gilt über dem Startpunkt der Drohne.
        val maxH = ((DroneManager.state.heightLimitM ?: 120).coerceAtMost(120) - 2).toDouble()
        if (mIn0.waypoints.any { it.heightM >= com.felixos.drohne.plan.Waypoint.MAX_HEIGHT }) DroneManager.log("Plan „so hoch wie möglich“: ${maxH.toInt()} m über dem Start (Höhenlimit ${DroneManager.state.heightLimitM ?: "unbekannt"} m)")
        val above = (mIn0.startAboveGroundM ?: startAboveOverrideM ?: 0.0).coerceIn(0.0, 60.0)
        startAboveGroundM = above
        val mB0 = if (above > 0.1) {
            DroneManager.log("Startplatz ${String.format(java.util.Locale.GERMANY, "%.0f", above)} m über dem Boden (Balkon): alle Planhöhen um diesen Wert gesenkt")
            mIn0.copy(waypoints = mIn0.waypoints.map { if (it.heightM >= com.felixos.drohne.plan.Waypoint.MAX_HEIGHT) it else it.copy(heightM = (it.heightM - above).coerceAtLeast(-100.0)) }, transitHeightM = mIn0.transitHeightM?.let { it - above })
        } else mIn0
        // Gebäudedatenbank der Stadt dazu (alle Gebäude im Umkreis von 300 m um Strecke und Start).
        val mB = mB0.copy(waypoints = mB0.waypoints.map { if (it.heightM >= com.felixos.drohne.plan.Waypoint.MAX_HEIGHT) it.copy(heightM = maxH) else it })
        val city0 = com.felixos.drohne.plan.Buildings.near(mB.waypoints.map { it.pos } + listOfNotNull(home), 300.0)
        // Gelände: Gebäudehöhen gelten über dem Boden des Gebäudes, Flughöhen über dem Start → Dachhöhe relativ zum Start (Lissabon: 48 m Gefälle sind normal)
        val city = if (home != null && com.felixos.drohne.plan.Terrain.loaded) city0.map { it.copy(groundRelM = (com.felixos.drohne.plan.Terrain.riseM(home, it.polygon[0]) ?: 0.0) - above) } else city0
        if (city !== city0 && city.isNotEmpty()) DroneManager.log("Gelände: Gebäude ${String.format(java.util.Locale.GERMANY, "%.0f", city.minOf { it.groundRelM })} bis ${String.format(java.util.Locale.GERMANY, "%+.0f", city.maxOf { it.groundRelM })} m gegenüber dem Start")
        val m0 = if (city.isNotEmpty()) mB.copy(obstacles = mB.obstacles + city).also { DroneManager.log("Gebäudedatenbank: ${city.size} Gebäude im Umkreis der Strecke") }
            else mB.also { if (com.felixos.drohne.plan.Buildings.loaded) DroneManager.log("Gebäudedatenbank: kein Gebäude im Umkreis (außerhalb Lissabon?)") else DroneManager.log("Gebäudedatenbank nicht geladen") }
        // Absturz 7.11.: die Drohne steigt beim Start nur bis zur Höhe des ersten Punkts und fliegt dann gerade.
        // Deshalb: erster Punkt = senkrecht über dem Startpunkt auf Transithöhe (unabhängig von takeOffSecurityHeight),
        // dann über den ersten Missionspunkt, dort absteigen; am Ende wieder hoch und über den Startpunkt (Heimflug = nur Abstieg).
        val transit = maxOf(TRANSIT_HEIGHT_M, m0.transitHeightM ?: m0.waypoints.maxOf { it.heightM })  // Plan darf eine eigene Transithöhe setzen (Hügelziele: nicht auf die höchste Planhöhe steigen)
        val m1 = if (m0.waypoints.size <= 196) {
            val first = m0.waypoints.first(); val last = m0.waypoints.last()
            // Übereinanderliegende Punkte 1,5 m versetzen (DJI mag keine Punkte ohne waagerechten Abstand).
            fun beside(p: LatLon, towards: LatLon) = Geo.offset(p, 1.5, Geo.bearing(p, towards))
            val pre = buildList {
                val a0 = DroneManager.state.location?.takeIf { (above > 0.1 || mIn0.startFromCurrent) && DroneManager.state.flying && it.latitude != 0.0 }?.let { LatLon(it.latitude, it.longitude) }
                if (a0 != null) add(first.copy(pos = a0, heightM = transit, takePhoto = false, hoverSec = 0, headingDeg = null, video = 0))
                else if (home != null) add(first.copy(pos = home, heightM = transit, takePhoto = false, hoverSec = 0, headingDeg = null, video = 0))
                if (first.heightM < transit - 1) add(first.copy(pos = beside(first.pos, home ?: last.pos), heightM = transit, takePhoto = false, hoverSec = 0, video = 0))
            }
            // Balkonstart (Felix 9.11.): Felix fliegt von Hand raus und übernimmt den Plan im Flug. Die Stelle, an der die Drohne
            // dabei schwebt (vor dem Balkon über der Straße), wird Anker: dort beginnt und endet der Plan. Am Ende schwebt sie dort
            // auf Oberfläche (Bäume/Dächer im 8-m-Umkreis) + 8 m, Felix landet von Hand auf dem Balkon.
            val loc = DroneManager.state.location?.takeIf { it.latitude != 0.0 }
            val anchor = if (above > 0.1 && DroneManager.state.flying && loc != null) LatLon(loc.latitude, loc.longitude) else null
            val anchorH = anchor?.let { a ->
                val g = home?.let { com.felixos.drohne.plan.Terrain.groundM(it) }
                val top = (0 until 16).mapNotNull { k -> com.felixos.drohne.plan.Terrain.surfaceM(Geo.offset(a, 8.0, k * 22.5)) } + listOfNotNull(com.felixos.drohne.plan.Terrain.surfaceM(a))
                if (g == null || top.isEmpty()) null else (top.max() - g + com.felixos.drohne.plan.Obstacles.CLEARANCE_M - above).coerceIn(2.0, transit)
            }
            if (anchor != null) DroneManager.log("Balkon: Plan beginnt und endet an der jetzigen Position; Ende schwebend ${anchorH?.toInt() ?: transit.toInt()} m über dem Balkon, Landung von Hand")
            preCount = pre.size
            val post = if (mIn0.hoverAtEnd) emptyList() else buildList {
                if (last.heightM < transit - 1) add(last.copy(pos = beside(last.pos, anchor ?: home ?: first.pos), heightM = transit, takePhoto = false, hoverSec = 0, headingDeg = null, video = 0))
                if (anchor != null) {
                    add(last.copy(pos = anchor, heightM = transit, takePhoto = false, hoverSec = 0, headingDeg = null, video = 0))
                    if (anchorH != null && anchorH < transit - 1) landingTail = true
                    if (anchorH != null && anchorH < transit - 1) add(last.copy(pos = beside(anchor, last.pos), heightM = anchorH, takePhoto = false, hoverSec = 0, headingDeg = null, video = 0))
                } else if (home != null) add(last.copy(pos = home, heightM = transit, takePhoto = false, hoverSec = 0, headingDeg = null, video = 0))
            }
            if (home == null) DroneManager.log("ACHTUNG: Startpunkt unbekannt, Transit nur über dem Ziel. Vor dem Start Plan erneut übernehmen!")
            else DroneManager.log("Transithöhe ${transit.toInt()} m: senkrecht hoch über dem Start, Anflug und Rückweg oben, Abstieg erst über dem Ziel")
            // Video, das im Plan nicht beendet wird, spätestens am ersten Rückweg-Punkt stoppen.
            val open = m0.waypoints.lastOrNull { it.video != 0 }?.video == 1
            val post2 = if (open && post.isNotEmpty()) listOf(post[0].copy(video = 2)) + post.drop(1) else post
            m0.copy(waypoints = pre + m0.waypoints + post2, takeOffSecurityHeightM = transit)
        } else m0
        // Tempo je Abschnitt
        val wps = m1.waypoints.mapIndexed { i, w -> w.copy(speedMs = speedFor(w, m1.waypoints.getOrNull(i + 1), m1)) }
        val m = m1.copy(waypoints = wps, speedMs = minOf(m1.speedMs, TRANSIT_SPEED_MAX))
        val slow = wps.filter { (it.speedMs ?: 99.0) < LOW_SPEED_MAX - 0.1 }
        DroneManager.log("Tempo: max ${m.speedMs} m/s" + if (slow.isNotEmpty()) ", ${slow.size} Abschnitte nahe Gebäuden gedrosselt (min ${String.format(java.util.Locale.GERMANY, "%.1f", slow.minOf { it.speedMs!! })} m/s)" else "")
        rthHeightM = maxOf(transit, (m.obstacles.maxOfOrNull { it.topM } ?: 0.0) + com.felixos.drohne.plan.Obstacles.CLEARANCE_M).coerceIn(20.0, 120.0)
        // Return path from EVERY waypoint against the LiDAR surface (signal loss, STOP, battery turn-back mid-plan):
        // raise the return height until the straight line home clears roofs and trees by the safety margin.
        returnProblems = emptyList()
        val gHome = home?.let { com.felixos.drohne.plan.Terrain.groundM(it) }
        if (home != null && gHome != null && above <= 0.1) {
            var need = 0.0; var worst = -1
            m.waypoints.forEachIndexed { i, w ->
                val n = maxOf(1, (Geo.distance(w.pos, home) / 5.0).toInt())
                for (k in 0..n) {
                    val t = k.toDouble() / n
                    val surf = com.felixos.drohne.plan.Terrain.surfaceM(LatLon(w.pos.lat + (home.lat - w.pos.lat) * t, w.pos.lon + (home.lon - w.pos.lon) * t)) ?: continue
                    val rel = surf - gHome + com.felixos.drohne.plan.Obstacles.CLEARANCE_M
                    if (rel > need) { need = rel; worst = i }
                }
            }
            if (need > 120.0) returnProblems = listOf("Return guard: the way home from point ${worst + 1} needs ${need.toInt()} m, more than the 120 m allowed")
            else if (need > rthHeightM) { DroneManager.log("Return guard: return height raised from ${rthHeightM.toInt()} to ${Math.ceil(need).toInt()} m (surface on the way home from point ${worst + 1})"); rthHeightM = Math.ceil(need) }
            else DroneManager.log("Return guard: way home clear from every point at ${rthHeightM.toInt()} m")
        }
        val homeLeg = !(mIn0.startFromCurrent && DroneManager.state.flying)  // in-flight plans begin at the drone, not at home
        conflicts = com.felixos.drohne.plan.Obstacles.check(m, home, homeLeg = homeLeg) + (home?.let { com.felixos.drohne.plan.Obstacles.checkReturn(m, it, rthHeightM) } ?: emptyList())
        terrainConflicts = if (com.felixos.drohne.plan.Terrain.loaded) com.felixos.drohne.plan.Terrain.check(m, home, homeAboveGroundM = above, homeLeg = homeLeg) else emptyList()
        if (landingTail) {
            val lastIdx = m.waypoints.size - 1 + (if (home != null) 1 else 0)
            conflicts = conflicts.filter { it.toHome || it.to < lastIdx }
            terrainConflicts = terrainConflicts.filter { it.to < lastIdx }
            DroneManager.log("Balkon: letzter Abstieg (Landeanflug) von der Gebäudeprüfung ausgenommen")
        }
        if (com.felixos.drohne.plan.Terrain.loaded && home != null) {
            val rises = m0.waypoints.mapNotNull { com.felixos.drohne.plan.Terrain.riseM(home, it.pos) }
            if (rises.isNotEmpty()) DroneManager.log("Höhenmodell: Gelände am Ziel ${if (rises.max() >= 0) "+" else ""}${rises.max().toInt()} m gegenüber dem Start")
            if (terrainConflicts.isNotEmpty()) DroneManager.log("OBERFLÄCHEN-WÄCHTER: ${terrainConflicts.size} Konflikt(e): " + terrainConflicts.joinToString("; ")) else DroneManager.log("Oberflächen-Wächter: Strecke frei")
        } else if (!com.felixos.drohne.plan.Terrain.loaded) DroneManager.log("Höhenmodell nicht in der App")
        if (conflicts.isNotEmpty()) {
            DroneManager.log("GEBÄUDE-WÄCHTER: ${conflicts.size} Konflikt(e): " + conflicts.joinToString("; "))
        } else if (m.obstacles.isNotEmpty()) DroneManager.log("Gebäude-Wächter: Strecke frei (${m.obstacles.size} Gebäude geprüft)")
        val base = Variant("Standard", m, WpmlOptions())
        var errs = check(base, "${m.name}.kmz")
        DroneManager.log("KMZ erstellt: ${m.waypoints.size} Punkte, ${m.lengthM.toInt()} m, ca. ${(m.durationSec / 60).toInt() + 1} min")
        if (errs.isEmpty()) { DroneManager.log("DJI-Prüfung: in Ordnung"); return true }
        DroneManager.log("DJI-Prüfung meldet: $errs – probiere Abwandlungen …")
        val shifted = m.copy(waypoints = m.waypoints.mapIndexed { i, w ->
            w.copy(pos = Geo.offset(w.pos, 5.0 * (i + 1), 0.0))
        })
        val singles = listOf(
            Variant("ohne Kurvenflug", m, WpmlOptions(smooth = false)),
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
        if (fileName != "probe.kmz") { currentKmz = f; currentMission = v.m; show("${v.m.name}: checked (${v.m.waypoints.size} points, ${v.m.lengthM.toInt()} m)") }
        if (!wpmzReady) return emptyList()
        return try {
            WPMZManager.getInstance().checkValidation(f.absolutePath)?.value ?: emptyList()
        } catch (t: Throwable) { listOf("Prüfer-Fehler: ${t.message}") }
    }

    fun upload(onDone: (Boolean) -> Unit) {
        val f = currentKmz ?: return DroneManager.log("Erst Mission planen").also { onDone(false) }
        if (Simulator.active) { uploaded = Simulator.upload(); show("${currentMission?.name ?: missionId}: on the drone (simulation) → Start"); onDone(uploaded); return }
        if (!DroneManager.requireSdk("Hochladen")) return onDone(false)
        DroneManager.log("Lade Mission auf die Drohne …")
        DroneManager.safe("Hochladen") { WaypointMissionManager.getInstance().pushKMZFileToAircraft(f.absolutePath,
            object : CommonCallbacks.CompletionCallbackWithProgress<Double> {
                private var last = -1
                override fun onProgressUpdate(p: Double?) {
                    val pct = ((p ?: 0.0) * if ((p ?: 0.0) <= 1.0) 100 else 1).toInt()
                    if (pct / 25 != last) { last = pct / 25; DroneManager.log("Upload $pct %") }
                }
                override fun onSuccess() { uploaded = true; DroneManager.log("Mission ist auf der Drohne"); show("${currentMission?.name ?: missionId}: on the drone → Start"); onDone(true) }
                override fun onFailure(error: IDJIError) { DroneManager.log("Upload fehlgeschlagen: ${error.description()}"); onDone(false) }
            }) }
    }

    private val missionId get() = currentKmz?.nameWithoutExtension ?: ""

    fun start() {
        val f = currentKmz ?: return DroneManager.log("Keine Mission")
        if (!uploaded) return DroneManager.log("Mission erst hochladen")
        lastReachedIndex = -1; FlightGuard.alert = null
        if (Simulator.active) { currentMission?.let { Simulator.fly(it, rthHeightM) }; return }
        DroneManager.ensureObstacleAvoidance("Mission startet")
        FlightReport.startFlight(missionId)
        currentMission?.let { m ->
            val h = DroneManager.state.home
            if (h?.latitude != null && h.latitude != 0.0) DroneManager.log(String.format(java.util.Locale.US, "Startpunkt: %.6f, %.6f", h.latitude, h.longitude))
            val pts = m.waypoints.take(10).joinToString("/") { String.format(java.util.Locale.US, "%.6f,%.6f", it.pos.lat, it.pos.lon) }
            DroneManager.log("Karte: https://www.google.com/maps/dir/$pts/data=!4m2!4m1!3e2")
        }
        if (currentMission?.light == true) DroneManager.setBottomLight("an")
        DroneManager.setGoHomeHeight(rthHeightM.toInt().coerceIn(20, 120))
        DroneManager.safe("Start") {
            val mgr = WaypointMissionManager.getInstance()
            // 13.11.: auf der echten Drohne kam nie eine Zustandsmeldung an → Listener vor jedem Start neu anmelden.
            try { mgr.removeWaypointMissionExecuteStateListener(stateListener); mgr.addWaypointMissionExecuteStateListener(stateListener); mgr.removeWaylineExecutingInfoListener(infoListener); mgr.addWaylineExecutingInfoListener(infoListener) } catch (t: Throwable) { DroneManager.log("Missions-Listener: ${t.message}") }
            val ids = mgr.getAvailableWaylineIDs(f.absolutePath)
            val t0 = System.currentTimeMillis()
            mgr.startMission(missionId, ids, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    DroneManager.log("Start: ok")
                    // Meldet die Drohne den Zustand nicht selbst, führt die App ihn: läuft ab jetzt, bis Abbruch oder Landung.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (stateFromDrone < t0 && !executing) { DroneManager.log("Mission: keine Zustandsmeldung von der Drohne, App nimmt EXECUTING an"); assumed = true; applyState(WaypointMissionExecuteState.EXECUTING, null) }
                    }, 3000)
                }
                override fun onFailure(error: IDJIError) { DroneManager.log("Start fehlgeschlagen: ${error.description()}"); show("⚠ Start failed: ${error.description()}") }
            })
        }
    }

    fun pause() { if (Simulator.active) return Simulator.pause(); DroneManager.safe("Pause") { WaypointMissionManager.getInstance().pauseMission(cb("Pause")) } }
    fun resume() { FlightGuard.rearm(); if (Simulator.active) return Simulator.resume(); DroneManager.safe("Weiter") { WaypointMissionManager.getInstance().resumeMission(cb("Weiter")) } }
    fun stop(id: String = missionId) { if (Simulator.active) return Simulator.hover(); DroneManager.safe("Abbruch") { WaypointMissionManager.getInstance().stopMission(id, cb("Abbruch")) }
        if (currentMission?.waypoints?.any { it.video != 0 } == true) DroneManager.stopRecording("Abbruch") }

    /** Zustand von der App angenommen (Drohne meldet nichts): Pause/Weiter/Abbruch/Landung führen ihn weiter. */
    @Volatile private var assumed = false
    fun landed() { if (assumed && executing) { assumed = false; applyState(WaypointMissionExecuteState.READY, null) } }
    private fun cb(what: String) = object : CommonCallbacks.CompletionCallback {
        override fun onSuccess() { DroneManager.log("$what: ok")
            if (assumed) when (what) { "Pause" -> applyState(WaypointMissionExecuteState.INTERRUPTED, null); "Weiter" -> applyState(WaypointMissionExecuteState.EXECUTING, null); "Abbruch" -> { assumed = false; applyState(WaypointMissionExecuteState.READY, null) } } }
        override fun onFailure(error: IDJIError) { DroneManager.log("$what fehlgeschlagen: ${error.description()}"); show("⚠ $what failed: ${error.description()}") }
    }

    private var lastStickPause = 0L
    /** Stick moved while a plan is running: pause at once, the pilot has control (Felix 13.11.). */
    fun stickMoved() {
        val now = System.currentTimeMillis()
        if (!executing || Simulator.active || now - lastStickPause < 3000) return
        lastStickPause = now
        DroneManager.log("Stick moved during the plan → pause")
        pause()
    }
}
