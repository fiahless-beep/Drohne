package com.felixos.drohne

import dji.sdk.keyvalue.key.FlightControllerKey
import android.Manifest
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.felixos.drohne.plan.Budget
import com.felixos.drohne.plan.Geo
import com.felixos.drohne.plan.LatLon
import com.felixos.drohne.plan.Mission
import com.felixos.drohne.plan.Missions
import com.felixos.drohne.plan.PlanJson
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import java.util.Locale

/**
 * Cockpit im Querformat: Karte oder Livebild füllen den Bildschirm, darüber Glasflächen.
 *  - oben: Verbindung · Phase (Punkt 3/12, Heimflug …) · GPS, Akku, Sensoren, Wind
 *  - links: Karte | Livebild, Karte einpassen, Protokoll, Mehr
 *  - unten links: Höhe, Abstand zum Start, Tempo
 *  - rechts: Plan, nächster Schritt, Startprüfung (nur Probleme), ein Hauptknopf je Zustand, STOPP
 * Die Sicherheitslogik (MissionController.prepare, preflightProblems, Wächter) ist unverändert.
 */
class MainActivity : AppCompatActivity() {

    /** Mindestwerte vor dem Start. Felix: sicher mit 15–20 % zurück (siehe Budget). */
    private val minSatellites = 10

    /** Vom Cloud-Planer erzeugter Plan (öffentliches Repo, daher ohne Login lesbar). */
    private val planUrl = "https://raw.githubusercontent.com/fiahless-beep/Drohne/main/plaene/aktuell.json"
    private val planApiUrl = "https://api.github.com/repos/fiahless-beep/Drohne/contents/plaene/aktuell.json?ref=main"

    private var mission: Mission? = null
    private var missionText: String = ""
    /** Letzter Zustandstext des MissionControllers (fürs Protokoll und die Phase). */
    private var controllerText: String = ""

    // oben
    private lateinit var linkDot: View
    private lateinit var linkText: TextView
    private lateinit var phase: TextView
    private lateinit var chipGps: TextView
    private lateinit var chipBattery: TextView
    private lateinit var chipSensors: TextView
    private lateinit var chipWind: TextView
    // Bildfläche + Leiste
    private lateinit var video: SurfaceView
    private lateinit var mapView: org.osmdroid.views.MapView
    private lateinit var mapPanel: MapPanel
    private lateinit var btnView: ImageView
    private lateinit var view3d: View3D
    private lateinit var btnCenter: ImageView
    private lateinit var videoHint: TextView
    private lateinit var telemetry: TextView
    private lateinit var statusLine: TextView
    // Aktionsfeld
    private lateinit var planName: TextView
    private lateinit var planMeta: TextView
    private lateinit var progress: ProgressBar
    private lateinit var nextAction: TextView
    private lateinit var checklist: LinearLayout
    private lateinit var btnPrimary: Button
    private lateinit var btnFollow: Button
    private lateinit var btnHome: Button
    private lateinit var secondaryRow: View
    private val logLines = ArrayDeque<String>()

    private val colorOk by lazy { getColor(R.color.ok) }
    private val colorWarn by lazy { getColor(R.color.warn) }
    private val colorBad by lazy { getColor(R.color.bad) }
    private val colorNeutral by lazy { getColor(R.color.neutral) }
    private val colorText by lazy { getColor(R.color.text) }
    private val colorMuted by lazy { getColor(R.color.textMuted) }

    private val stateListener: (DroneState) -> Unit = { render(it) }
    private val logListener: (String) -> Unit = { line -> logLines.addLast(line); while (logLines.size > 6) logLines.removeFirst() }
    private val missionListener: (String) -> Unit = { text -> runOnUiThread { controllerText = text; renderNow(DroneManager.state) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupEdgeToEdge()
        MapPanel.configure(applicationContext)
        setContentView(R.layout.activity_main)
        bindViews()
        applyInsets()
        MissionController.onStateText = missionListener

        ActivityCompat.requestPermissions(this, arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE,
        ), 1)

        CrashLog.takeReport(this)?.let { showCrash(it) }

        setupVideo(video)
        mapPanel = MapPanel(mapView)
        view3d.init()
        btnView.setOnClickListener { chooseView(when (mode) { Mode.MAP -> Mode.VIDEO; Mode.VIDEO -> Mode.THREE; Mode.THREE -> Mode.MAP }, byUser = true) }
        btnCenter.setOnClickListener { if (mode == Mode.THREE) view3d.cycleCamera {} else mapPanel.fit() }
        findViewById<View>(R.id.btnLog).setOnClickListener { showLog() }
        findViewById<View>(R.id.btnLog).setOnLongClickListener { copyLog(); true }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMore() }

        btnPrimary.setOnClickListener { primaryAction() }
        findViewById<Button>(R.id.btnPlan).setOnClickListener { planSquare() }
        planName.setOnClickListener { showPlanDetails() }
        planMeta.setOnClickListener { showPlanDetails() }
        FlightReport.init(applicationContext)
        LiveLink.init(applicationContext)
        ObstacleGuard.init(applicationContext)
        FlightGuard.init()
        FlightGuard.onAlert = { t -> runOnUiThread { android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show(); renderStatus(DroneManager.state) } }
        Thread {
            try {
                val n = assets.open("gebaeude.bin").use { com.felixos.drohne.plan.Buildings.load(it) }
                DroneManager.log("Gebäudedatenbank geladen: $n Gebäude")
            } catch (t: Throwable) { DroneManager.log("Keine Gebäudedatenbank in der App (${t.javaClass.simpleName})") }
            try {
                val n = assets.open("hoehen.bin").use { com.felixos.drohne.plan.Terrain.load(it) }
                DroneManager.log("Höhenmodell geladen: $n Zellen")
            } catch (t: Throwable) { DroneManager.log("Kein Höhenmodell in der App (${t.javaClass.simpleName})") }
        }.start()
        var collected = false
        MissionController.onFinished = {
            if (!collected) {
                collected = true
                DroneManager.log("Mission beendet → hole Fotos und lade hoch")
                // Erst nach der Landung, sonst stört der Wiedergabemodus den Heimflug.
                waitLandedThen { FlightReport.collectAndUpload(applicationContext) { collected = false } }
            }
        }
        btnFollow.setOnClickListener { askFollow() }
        findViewById<Button>(R.id.btnClaude).setOnClickListener { askClaude() }
        btnHome.setOnClickListener {
            FollowController.stop()
            MissionController.stop()
            DroneManager.goHome()
        }
        MissionController.onHoverFinished = { runOnUiThread { renderNow(DroneManager.state) } }
        mapPanel.onTap = { p -> pickSubject(p, "from map") }
        video.setOnTouchListener { _, e -> if (e.action == android.view.MotionEvent.ACTION_UP) onVideoTap(e.x, e.y); true }
        findViewById<View>(R.id.panel).post { statusLine.maxWidth = findViewById<View>(R.id.panel).left - statusLine.left - (8 * resources.displayMetrics.density).toInt() }
        ui.post(statusTicker)
        chooseView(Mode.MAP, byUser = false)
        renderChecklist(emptyList(), hasPlan = false)
        renderNow(DroneManager.state)
    }

    private fun bindViews() {
        linkDot = findViewById(R.id.linkDot); linkText = findViewById(R.id.linkText); phase = findViewById(R.id.phase)
        chipGps = findViewById(R.id.chipGps); chipBattery = findViewById(R.id.chipBattery); chipSensors = findViewById(R.id.chipSensors); chipWind = findViewById(R.id.chipWind)
        video = findViewById(R.id.video); mapView = findViewById(R.id.map)
        btnView = findViewById(R.id.btnView); btnCenter = findViewById(R.id.btnCenter)
        view3d = View3D(findViewById(R.id.view3d))
        videoHint = findViewById(R.id.videoHint); telemetry = findViewById(R.id.telemetry); statusLine = findViewById(R.id.statusLine)
        planName = findViewById(R.id.planName); planMeta = findViewById(R.id.planMeta); progress = findViewById(R.id.progress); nextAction = findViewById(R.id.nextAction)
        checklist = findViewById(R.id.checklist); btnPrimary = findViewById(R.id.btnPrimary); btnFollow = findViewById(R.id.btnFollow); btnHome = findViewById(R.id.btnHome)
        secondaryRow = findViewById(R.id.secondaryRow)
    }

    /** Randlos zeichnen, Systemleisten ausblenden (Wischen vom Rand holt sie kurz zurück), Notch-Bereich nutzen. */
    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
    }

    /** Alle Bedienflächen bleiben außerhalb von Notch und Systemleisten; Karte und Video laufen darunter durch. */
    private fun applyInsets() {
        val root = findViewById<View>(R.id.overlayRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        DroneManager.addStateListener(stateListener)
        logLines.clear()  // Beim Wiederanmelden liefert der Manager die ganze Historie neu
        DroneManager.addLogListener(logListener)
    }

    override fun onResume() { super.onResume(); mapPanel.onResume() }
    override fun onPause() { mapPanel.onPause(); super.onPause() }

    override fun onStop() {
        DroneManager.removeStateListener(stateListener)
        DroneManager.removeLogListener(logListener)
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (::mapPanel.isInitialized) mapPanel.enableMyLocation() // Handy-Position jetzt erlaubt
    }


    // ---------------------------------------------------------------- Bildfläche: Karte | Video | 3D

    enum class Mode { MAP, VIDEO, THREE }

    /** null = automatisch (Video sobald verbunden, sonst Karte); sonst von Hand gewählt. */
    private var userMode: Mode? = null
    private var mode = Mode.MAP
    private val showingMap: Boolean get() = mode == Mode.MAP

    private fun chooseView(m: Mode, byUser: Boolean) {
        if (byUser) userMode = m
        mode = m
        btnView.setImageResource(when (m) { Mode.MAP -> R.drawable.ic_map; Mode.VIDEO -> R.drawable.ic_video; Mode.THREE -> R.drawable.ic_cube })
        btnView.isSelected = true
        mapView.visibility = if (m == Mode.MAP) View.VISIBLE else View.GONE
        findViewById<View>(R.id.view3d).visibility = if (m == Mode.THREE) View.VISIBLE else View.GONE
        btnCenter.visibility = if (m == Mode.VIDEO) View.INVISIBLE else View.VISIBLE
        btnCenter.contentDescription = if (m == Mode.THREE) "Switch camera" else "Fit map"
        // GONE zerstört die Bildfläche; beim Zurückschalten meldet surfaceChanged das Livebild neu an.
        video.visibility = if (m == Mode.VIDEO) View.VISIBLE else View.GONE
        videoHint.visibility = if (m == Mode.VIDEO) View.VISIBLE else View.GONE
        if (m == Mode.MAP) { mapPanel.update(DroneManager.state, MissionController.currentMission ?: mission); mapPanel.fit() }
        if (m == Mode.THREE) { view3d.setMission(MissionController.currentMission ?: mission, MissionController.preparedHome ?: homeLatLon()); view3d.update(DroneManager.state) }
    }

    private fun homeLatLon(): LatLon? = DroneManager.state.home?.takeIf { it.latitude != 0.0 }?.let { LatLon(it.latitude, it.longitude) }

    /** Letzte bekannte Handyposition (GPS oder Netz), null ohne Berechtigung/Fix. */
    private fun phoneLatLon(): LatLon? = try {
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let { LatLon(it.latitude, it.longitude) }
    } catch (_: Throwable) { null }

    private var lastConnected: Boolean? = null

    /** Automatik: beim Verbinden auf Video, beim Trennen auf Karte – solange nichts von Hand gewählt wurde. Simulation: 3D. */
    private fun autoView(s: DroneState) {
        if (lastConnected == s.connected) return
        lastConnected = s.connected
        if (userMode == null) chooseView(if (Simulator.active) Mode.THREE else if (s.connected) Mode.VIDEO else Mode.MAP, byUser = false)
    }

    // ---------------------------------------------------------------- Anzeige

    private var lastLoc: Triple<Double, Double, Long>? = null
    private var speedMs: Double? = null
    private var lastChecklistMs = 0L

    private fun render(s: DroneState) {
        attachVideo()
        autoView(s)
        renderTop(s)
        renderTelemetry(s)
        renderStatus(s)
        renderNow(s)
        if (showingMap) mapPanel.update(s, MissionController.currentMission ?: mission)
        if (mode == Mode.THREE) view3d.update(s)
        val now = System.currentTimeMillis()
        if (now - lastChecklistMs > 1000) { lastChecklistMs = now; renderChecklist(preflightProblems(s), hasPlan = mission != null) }
    }

    /** Kennzahl oben: Beschriftung gedämpft, Wert fett in Statusfarbe. */
    private fun chip(v: TextView, label: String, value: String?, color: Int) {
        if (value == null) { v.visibility = View.GONE; return }
        v.visibility = View.VISIBLE
        val sb = SpannableStringBuilder()
        if (label.isNotEmpty()) sb.append("$label ", ForegroundColorSpan(colorMuted), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val start = sb.length
        sb.append(value)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        v.text = sb
    }

    private fun renderTop(s: DroneState) {
        val (link, linkColor) = when {
            s.registerError != null -> "SDK error" to colorBad
            !s.registered -> "SDK starting" to colorNeutral
            s.connected -> ((s.productType?.takeIf { it.isNotBlank() } ?: "Drone") + (s.serial?.let { " · SN $it" } ?: "")) to colorOk
            s.rcConnected -> "Controller only" to colorWarn
            else -> "Disconnected" to colorBad
        }
        s.serial?.let { sn -> val pr = getSharedPreferences("drohne", MODE_PRIVATE); if (pr.getString("letzte_sn", null) != sn) pr.edit().putString("letzte_sn", sn).apply() }
        linkText.text = link
        linkDot.backgroundTintList = ColorStateList.valueOf(linkColor)

        val sat = s.satellites
        val homeOk = s.home?.latitude != null && s.home.latitude != 0.0
        chip(chipGps, "GPS", sat?.toString() ?: "–", when { sat == null -> colorMuted; sat < minSatellites || !homeOk -> colorWarn; else -> colorText })
        val b = s.batteryPercent
        chip(chipBattery, "", b?.let { "$it %" } ?: "–", when { b == null -> colorMuted; b < 25 -> colorBad; b < 40 -> colorWarn; else -> colorText })
        val sens = s.sensors
        chip(chipSensors, "Sensors", when { !s.connected -> null; sens == null -> "–"; sens.startsWith("blind") -> "blind"; sens == "aktiv" -> "on"; sens == "aus" -> "OFF"; else -> sens },
            when { s.sensorsOk -> colorText; sens == null -> colorMuted; else -> colorWarn })
        chip(chipWind, "Wind", when (s.windWarning) { 2 -> "strong"; 1 -> "moderate"; else -> null }, if (s.windWarning == 2) colorBad else colorWarn)

        val m = MissionController.currentMission
        val idx = MissionController.currentWaypointIndex
        val st = MissionController.executeState
        phase.text = when {
            !s.connected -> ""
            FollowController.active -> "Following phone"
            st == WaypointMissionExecuteState.RETURN_TO_START_POINT -> "Returning home"
            s.landing -> "Landing"
            MissionController.executing && m != null && idx >= 0 -> "Point ${idx + 1} / ${m.waypoints.size}"
            MissionController.executing -> "Climbing to ${MissionController.TRANSIT_HEIGHT_M.toInt()} m"
            st == WaypointMissionExecuteState.INTERRUPTED -> "Paused"
            s.flying -> "Flying"
            s.motorsOn -> "Motors on"
            else -> "On the ground"
        }
        phase.setTextColor(if (s.flying || s.motorsOn || FollowController.active) colorWarn else colorText)
    }

    private fun renderTelemetry(s: DroneState) {
        val loc = s.location?.takeIf { it.latitude != 0.0 }
        videoHint.visibility = if (mode != Mode.VIDEO || (s.connected && videoAttached)) View.GONE else View.VISIBLE
        videoHint.text = if (s.connected) "Live view loading …" else "No live view"
        if (loc == null) { telemetry.visibility = View.GONE; speedMs = null; lastLoc = null; return }
        telemetry.visibility = View.VISIBLE
        val now = System.currentTimeMillis()
        lastLoc?.let { (la, lo, t) ->
            val dt = (now - t) / 1000.0
            if (dt >= 0.5) { speedMs = Geo.distance(LatLon(la, lo), LatLon(loc.latitude, loc.longitude)) / dt / (if (Simulator.active) Simulator.timeScale else 1.0); lastLoc = Triple(loc.latitude, loc.longitude, now) } // Simulation: Zeitraffer herausrechnen
        } ?: run { lastLoc = Triple(loc.latitude, loc.longitude, now) }
        val home = s.home?.takeIf { it.latitude != 0.0 }
        val dist = home?.let { Geo.distance(LatLon(it.latitude, it.longitude), LatLon(loc.latitude, loc.longitude)) }
        telemetry.text = listOfNotNull(
            String.format(Locale.US, "↑ %.0f m", loc.altitude ?: 0.0),
            dist?.let { "⌂ ${it.toInt()} m" },
            speedMs?.let { String.format(Locale.US, "%.1f m/s", it) },
        ).joinToString("    ")
    }

    /** Aktionsfeld: Fortschritt, nächster Schritt, Hauptknopf je Zustand. */
    private fun renderNow(s: DroneState) {
        val m = MissionController.currentMission
        val idx = MissionController.currentWaypointIndex
        val n = m?.waypoints?.size ?: 0
        val st = MissionController.executeState
        val running = MissionController.executing || st == WaypointMissionExecuteState.RECOVERING
        progress.visibility = if (running || st == WaypointMissionExecuteState.FINISHED) View.VISIBLE else View.GONE
        progress.progress = when {
            st == WaypointMissionExecuteState.FINISHED || st == WaypointMissionExecuteState.RETURN_TO_START_POINT -> 100
            running && n > 0 && idx >= 0 -> ((idx + 1) * 100 / n).coerceIn(0, 100)
            else -> 0
        }
        nextAction.text = nextActionText(s, m, idx, st)

        // Hauptknopf: genau eine sinnvolle Aktion je Zustand
        val paused = st == WaypointMissionExecuteState.INTERRUPTED
        if (!s.flying && waiting != null && busy == null) waiting = null  // landed: nothing waits any more
        val (label, bg) = when {
            busy != null -> "Working …" to R.drawable.btn_primary
            loading -> "Getting plan …" to R.drawable.btn_primary
            uploading -> "Uploading …" to R.drawable.btn_primary
            running -> "❚❚ Pause" to R.drawable.btn_secondary
            waiting != null && s.flying -> "▶ Resume plan" to R.drawable.btn_go
            paused && mission != null -> "▶ Resume plan" to R.drawable.btn_go
            mission == null -> "Get plan" to R.drawable.btn_primary
            !MissionController.uploaded -> "Upload again" to R.drawable.btn_primary
            else -> "Start" to R.drawable.btn_go
        }
        btnPrimary.text = label
        btnPrimary.setBackgroundResource(bg)
        btnPrimary.isEnabled = !loading && !uploading && busy == null
        secondaryRow.visibility = View.GONE // Testflug/Folgen vorerst raus (Felix 8.11.)
        btnFollow.text = if (FollowController.active) "Stop following" else "Follow"
        btnHome.alpha = if (s.flying || s.motorsOn || FollowController.active) 1f else 0.6f
    }

    private var loading = false
    private var uploading = false

    // ---------------------------------------------------------------- Status line (always visible feedback)

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    /** Step the app is working on right now (checking, uploading, starting); null = nothing. */
    private var busy: String? = null
    private var statusKey = ""
    private var statusSince = 0L
    private var beat = false
    private val statusTicker = object : Runnable { override fun run() { beat = !beat; renderStatus(DroneManager.state); ui.postDelayed(this, 1000) } }

    private fun setBusy(text: String?) { busy = text; if (text != null) DroneManager.log(text); renderStatus(DroneManager.state); renderNow(DroneManager.state) }

    /** One sentence: what is happening and who has control. */
    private fun activityText(s: DroneState): String {
        val st = MissionController.executeState
        val m = MissionController.currentMission
        busy?.let { return it }
        FlightGuard.alert?.let { if (!MissionController.executing) return it }
        return when {
            !s.connected -> "Not connected"
            FollowController.active -> "Following the phone"
            MissionController.executing || st == WaypointMissionExecuteState.RECOVERING -> (if (m?.hoverAtEnd == true) "Detour: " else "Plan: ") + nextActionText(s, m, MissionController.currentWaypointIndex, st)
            st == WaypointMissionExecuteState.RETURN_TO_START_POINT -> "Returning home, then landing"
            s.landing -> "Landing"
            s.flying && st == WaypointMissionExecuteState.INTERRUPTED -> (MissionController.interruptWhy?.let { "Paused by the drone: $it. Hovering, you have control. Resume plan or fly by hand." } ?: "Paused, hovering. You have control. Tap map or live view to pick a subject, or Resume plan.")
            s.flying && waiting != null -> "Hovering, plan waits. Tap map or live view for another subject, or Resume plan."
            s.flying -> "Hovering / manual flight. You have control."
            s.motorsOn -> "Motors on"
            else -> "On the ground"
        }
    }

    private fun renderStatus(s: DroneState) {
        val text = activityText(s)
        val key = "${busy}|${MissionController.executeState}|${MissionController.currentWaypointIndex}|${s.flying}|${s.connected}"
        val now = System.currentTimeMillis()
        if (key != statusKey) { statusKey = key; statusSince = now }
        val sec = (now - statusSince) / 1000
        val age = (now - DroneManager.lastUpdateMs) / 1000
        val stale = s.connected && !Simulator.active && DroneManager.lastUpdateMs > 0 && age >= 6
        val color = when { stale -> colorBad; busy != null -> colorWarn; s.connected -> colorOk; else -> colorNeutral }
        val sb = SpannableStringBuilder()
        sb.append(if (beat) "● " else "○ ", ForegroundColorSpan(color), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(if (stale) "NO DATA from the drone for $age s. Last: $text" else text)
        sb.append(String.format(Locale.US, "  %d:%02d", sec / 60, sec % 60), ForegroundColorSpan(colorMuted), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        statusLine.text = sb
    }

    // ---------------------------------------------------------------- In flight: pick a subject, detour, resume the plan

    /** Main plan that waits while a detour flies: the plan as loaded, its text, index of its next waypoint. */
    private class Waiting(val plan: Mission, val text: String, val next: Int)
    private var waiting: Waiting? = null

    private fun canPick(): Boolean = DroneManager.state.flying && !MissionController.executing && busy == null && !loading && !uploading && !FollowController.active

    /** Height of the surface (roof, tree, ground) at [p] relative to the start point; 0 if unknown. */
    private fun surfaceRel(p: LatLon): Double {
        val home = homeLatLon() ?: return 0.0
        val g = com.felixos.drohne.plan.Terrain.groundM(home) ?: return 0.0
        return (com.felixos.drohne.plan.Terrain.surfaceM(p) ?: return 0.0) - g - MissionController.startAboveGroundM
    }

    /** Where the tapped pixel of the live view meets the surface: camera tilt + drone heading + height (approximate). */
    private fun groundPoint(x: Float, y: Float, w: Int, h: Int): LatLon? {
        val s = DroneManager.state
        val loc = s.location?.takeIf { it.latitude != 0.0 } ?: return null
        val pitch0 = s.gimbalPitchDeg ?: return null
        val yawOff = Math.toDegrees(Math.atan((x / w - 0.5) * 2 * Math.tan(Math.toRadians(73.0 / 2))))
        val pitch = pitch0 - Math.toDegrees(Math.atan((y / h - 0.5) * 2 * Math.tan(Math.toRadians(46.0 / 2))))
        if (pitch > -5) return null
        val bearing = ((s.headingDeg ?: 0.0) + yawOff + 360) % 360
        val here = LatLon(loc.latitude, loc.longitude)
        val drop = Math.tan(Math.toRadians(-pitch))
        var d = 2.0
        while (d <= 600) {
            val p = Geo.offset(here, d, bearing)
            if (loc.altitude - d * drop <= surfaceRel(p)) return p
            d += 2.0
        }
        return null
    }

    private fun onVideoTap(x: Float, y: Float) {
        if (!canPick()) return
        val p = groundPoint(x, y, video.width.coerceAtLeast(1), video.height.coerceAtLeast(1))
            ?: return info("No point found", "Tilt the camera further down so the subject is below the horizon, or pick it on the map.")
        pickSubject(p, "from live view, approximate")
    }

    private fun pickSubject(p: LatLon, how: String) {
        if (!canPick()) return
        val loc = DroneManager.state.location?.takeIf { it.latitude != 0.0 } ?: return
        val d = Geo.distance(LatLon(loc.latitude, loc.longitude), p).toInt()
        if (loc.altitude < 10) return info("Too low", "Subjects can be picked from 10 m above the start point. The drone is at ${loc.altitude.toInt()} m.")
        if (d > 400) return info("Too far", "The point is $d m away (max. 400 m).")
        val h = Math.round(loc.altitude).toDouble().coerceIn(MissionController.TRANSIT_HEIGHT_M, 118.0)
        val top = surfaceRel(p)
        menu("Subject $d m away ($how)", listOf(
            "Photo circle · radius 30 m · ${h.toInt()} m high · 8 photos" to { flyFromHere(circle(p, 30.0, h, top), "photo circle", detour = true) },
            "Photo circle · radius 50 m · ${h.toInt()} m high · 8 photos" to { flyFromHere(circle(p, 50.0, h, top), "photo circle", detour = true) },
            "Look closer · hover above, camera straight down, photo" to { flyFromHere(lookDown(p, h), "look closer", detour = true) },
            "Cancel" to { },
        ))
    }

    private fun circle(p: LatLon, r: Double, h: Double, targetTop: Double): Mission {
        val loc = DroneManager.state.location
        val from = if (loc != null) Geo.bearing(p, LatLon(loc.latitude, loc.longitude)) else 0.0  // start at the ring point nearest to the drone
        return Missions.orbit(p, r, h, points = 8, startBearingDeg = from, targetHeightM = targetTop.coerceIn(-100.0, h - 5), name = "photo-circle")
            .copy(startFromCurrent = true, hoverAtEnd = true, obstacles = mission?.obstacles ?: emptyList())
    }

    private fun lookDown(p: LatLon, h: Double): Mission = Mission(name = "look-closer", speedMs = 4.0, target = p, startFromCurrent = true, hoverAtEnd = true,
        obstacles = mission?.obstacles ?: emptyList(), waypoints = listOf(
            com.felixos.drohne.plan.Waypoint(p, h, gimbalPitchDeg = -90.0, takePhoto = true, hoverSec = 6),
            com.felixos.drohne.plan.Waypoint(Geo.offset(p, 2.0, 0.0), h, gimbalPitchDeg = -90.0, takePhoto = false)))

    /** Continue the waiting plan from its next waypoint, starting where the drone hovers now. */
    private fun resumePlan() {
        val w = waiting ?: return
        val n = w.plan.waypoints.size
        val from = w.next.coerceIn(0, n - 2)
        val rest = w.plan.copy(name = w.plan.name.removeSuffix("-rest") + "-rest", waypoints = w.plan.waypoints.drop(from), startFromCurrent = true, hoverAtEnd = false)
        flyFromHere(rest, "rest of plan “${w.plan.name}” from point ${from + 1}", detour = false, text = w.text)
    }

    /**
     * Check, upload and start a plan while the drone is in the air. Same checks as on the ground (guards, battery, limits).
     * Only when everything passes is the paused plan stopped; a rejected detour leaves it untouched.
     */
    private fun flyFromHere(m: Mission, what: String, detour: Boolean, text: String = "") {
        val s = DroneManager.state
        if (!s.flying) return info("Not flying", "This works only while the drone is in the air.")
        if (MissionController.executing) return info("Pause first", "Press Pause, then choose.")
        if (busy != null) return
        val prev = mission; val prevText = missionText
        val prevId = MissionController.missionIdNow
        val paused = MissionController.executeState == WaypointMissionExecuteState.INTERRUPTED
        val next = MissionController.lastReachedIndex - MissionController.preCount
        val snap = MissionController.snapshot()
        setBusy("Checking $what …")
        val ok = try { MissionController.prepare(m) } catch (t: Throwable) { DroneManager.log("Check failed: ${t.message}"); false }
        mission = m
        val problems = if (!ok) listOf("DJI file check failed") else preflightProblems(s).filterNot { it.startsWith("No plan on the drone") }
        if (problems.isNotEmpty()) {
            mission = prev; MissionController.restore(snap); setBusy(null)
            AlertDialog.Builder(this).setTitle("Not possible: $what").setMessage(problems.joinToString("\n\n") + "\n\nNothing was changed. The drone keeps hovering.").setPositiveButton("OK", null).show()
            return
        }
        if (detour && waiting == null && prev != null && !prev.hoverAtEnd && paused) waiting = Waiting(prev, prevText, next)
        if (!detour) waiting = null
        missionText = if (text.isNotBlank()) text else summary(m, what)
        setPlanCard("${m.name} – uploading …", missionText)
        if (showingMap) { mapPanel.update(s, MissionController.currentMission); mapPanel.fit() }
        if (paused) { setBusy("Stopping the paused plan …"); MissionController.stop(prevId) }
        ui.postDelayed({
            setBusy("Uploading $what to the drone …")
            MissionController.upload { up ->
                runOnUiThread {
                    if (up) { setBusy("Starting $what …"); MissionController.start(); ui.postDelayed({ setBusy(null) }, 2500) }
                    else { setBusy(null); MissionController.say("⚠ Upload failed: $what. Drone hovers."); info("Upload failed", "The drone keeps hovering. Try again, or press “Resume plan”.") }
                    setPlanCard(m.name + if (up) " – on the drone" else " – upload failed", "")
                }
            }
        }, if (paused && !Simulator.active) 1500L else 0L)
    }

    // ---------------------------------------------------------------- Claude: look, answer, suggest one command

    private fun askClaudeKey() {
        val input = android.widget.EditText(this).apply {
            hint = "sk-ant-…"; setText(ClaudeLink.key(this@MainActivity) ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle("Claude key")
            .setMessage("Anthropic API key (console.anthropic.com). With it the app can send a still image and the telemetry to Claude and get an answer or a suggestion back. Stays on this phone.")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> ClaudeLink.saveKey(this, input.text.toString()); DroneManager.log(if (input.text.isBlank()) "Claude key deleted" else "Claude key saved") }
            .setNegativeButton("Cancel", null).show()
    }

    private fun telemetryText(s: DroneState): String = buildString {
        val loc = s.location?.takeIf { it.latitude != 0.0 }
        appendLine("state: " + when { MissionController.executing -> "plan running"; MissionController.executeState == WaypointMissionExecuteState.INTERRUPTED -> "plan paused, hovering"; waiting != null && s.flying -> "hovering, a plan waits to be resumed"; s.flying -> "hovering or manual flight"; else -> "on the ground" })
        if (loc != null) appendLine(String.format(Locale.US, "position: %.5f, %.5f, height above start: %.0f m", loc.latitude, loc.longitude, loc.altitude))
        homeLatLon()?.let { h -> if (loc != null) appendLine("distance from start: ${Geo.distance(h, LatLon(loc.latitude, loc.longitude)).toInt()} m") }
        s.headingDeg?.let { appendLine("heading: ${it.toInt()}° (0 = north)") }
        s.gimbalPitchDeg?.let { appendLine("camera tilt: ${it.toInt()}° (0 = horizon, -90 = straight down)") }
        s.batteryPercent?.let { appendLine("battery: $it %") }
        mission?.let { appendLine("plan: ${it.name}, ${it.waypoints.size} points" + (MissionController.lastReachedIndex.takeIf { i -> i >= 0 }?.let { i -> ", at point ${i + 1}" } ?: "")) }
        append("wind warning: " + when (s.windWarning) { 2 -> "strong"; 1 -> "moderate"; else -> "none" })
    }

    /** Still image of the live view, or null when it is not on screen. */
    private fun grabFrame(done: (ByteArray?) -> Unit) {
        if (mode != Mode.VIDEO || !videoAttached || video.width <= 0 || video.height <= 0 || android.os.Build.VERSION.SDK_INT < 24) return done(null)
        try {
            val bmp = android.graphics.Bitmap.createBitmap(video.width, video.height, android.graphics.Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(video, bmp, { r -> done(if (r == android.view.PixelCopy.SUCCESS) runCatching { ClaudeLink.jpeg(bmp) }.getOrNull() else null) }, ui)
        } catch (t: Throwable) { done(null) }
    }

    private fun askClaude() {
        val key = ClaudeLink.key(this) ?: return info("Claude key missing", "Enter the key once under Settings → Data → Enter Claude key.")
        if (MissionController.executing) return info("Pause first", "Claude answers while the drone hovers or stands. Press Pause, then ask.")
        if (busy != null) return
        val input = android.widget.EditText(this).apply { hint = "What do you see? What next?"; setSingleLine(false); maxLines = 3 }
        AlertDialog.Builder(this).setTitle("Ask Claude")
            .setMessage("Claude gets the live image (switch to the live view first), position, height and battery. Any action it suggests is shown to you before anything happens.")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val q = input.text.toString().ifBlank { "What do you see, and what do you suggest?" }
                setBusy("Claude is looking …")
                grabFrame { jpg ->
                    val tele = telemetryText(DroneManager.state)
                    Thread {
                        val r = ClaudeLink.ask(key, q, tele, jpg)
                        runOnUiThread { setBusy(null); showClaudeReply(r, jpg != null) }
                    }.start()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showClaudeReply(r: ClaudeLink.Reply, hadImage: Boolean) {
        if (r.error != null) { DroneManager.log(r.error); return info("Claude", r.error) }
        DroneManager.log("Claude: ${r.text}" + (r.command?.let { " [$it ${r.input}]" } ?: ""))
        val inp = r.input
        val why = inp?.optString("why").orEmpty()
        fun point(): LatLon? = if (!hadImage || inp == null) null else groundPoint((inp.optDouble("x", 0.5) * video.width).toFloat(), (inp.optDouble("y", 0.5) * video.height).toFloat(), video.width.coerceAtLeast(1), video.height.coerceAtLeast(1))
        val loc = DroneManager.state.location
        val h = Math.round(loc?.altitude ?: 0.0).toDouble().coerceIn(MissionController.TRANSIT_HEIGHT_M, 118.0)
        val action: Pair<String, () -> Unit>? = when (r.command) {
            "look_closer" -> point()?.let { p -> "Look closer" to { if (canPick() && (loc?.altitude ?: 0.0) >= 10) flyFromHere(lookDown(p, h), "look closer", detour = true) else info("Not possible now", "The drone must hover at 10 m or higher.") } }
            "photo_circle" -> point()?.let { p -> val rad = if ((inp?.optDouble("radius_m", 30.0) ?: 30.0) >= 40) 50.0 else 30.0
                "Photo circle ${rad.toInt()} m" to { if (canPick() && (loc?.altitude ?: 0.0) >= 10) flyFromHere(circle(p, rad, h, surfaceRel(p)), "photo circle", detour = true) else info("Not possible now", "The drone must hover at 10 m or higher.") } }
            "resume_plan" -> "Resume plan" to { primaryAction() }
            "return_home" -> "Return home" to { btnHome.performClick() }
            else -> null
        }
        val msg = buildString {
            if (r.text.isNotBlank()) append(r.text)
            if (r.command == "ask_pilot") append((if (isNotEmpty()) "\n\n" else "") + (inp?.optString("question") ?: ""))
            else if (r.command != null) append((if (isNotEmpty()) "\n\n" else "") + "Suggestion: " + (action?.first ?: "${r.command} (no place found in the image)") + (if (why.isNotBlank()) ". $why" else ""))
            if (isEmpty()) append("No answer.")
        }
        val dlg = AlertDialog.Builder(this).setTitle("Claude").setMessage(msg)
        if (action != null) dlg.setPositiveButton(action.first) { _, _ -> action.second() }.setNegativeButton("No", null)
        else if (r.command == "ask_pilot") dlg.setPositiveButton("Answer") { _, _ -> askClaude() }.setNegativeButton("Close", null)
        else dlg.setPositiveButton("OK", null)
        dlg.show()
    }

    /** Overview of the use cases and what this build can do for each. */
    private fun showUseCases() = menu("Use cases", listOf(
        "1 Fly a plan · ready" to { info("Fly a plan", "Get plan, check list, Start. Pause: button, any stick, or the pause button on the controller. Resume plan continues.") },
        "2 Subject on the way · ready (untested in flight)" to { info("Subject on the way", "Pause the plan. Tap the subject on the map or in the live view. Choose a photo circle. When it is done the drone hovers; Resume plan continues the plan from the point where you paused.") },
        "3 Go and look · ready (untested in flight)" to { info("Go and look", "Same as 2, choose “Look closer”: the drone flies above the point, camera straight down, hovers 6 s, takes a photo. Loading a new plan while hovering also works: it starts where the drone is.") },
        "4 Ask Claude · first step (needs the Claude key)" to { info("Ask Claude", "Pause or hover, switch to the live view, press Ask Claude. Claude gets the image and telemetry, answers, and may suggest one action (look closer, photo circle, resume, return home). You confirm before anything happens. Watching in the background during the flight is not built yet.") },
        "5 Follow me / the car · tracking test first" to { info("Follow", "Run Settings → Device & test → Tracking test in flight and upload the result. It decides whether DJI tracking can be used or the app needs its own detection.") },
        "6 Plan over several batteries · not built yet" to { info("Several batteries", "Not in this build.") },
        "7 Survey a property · as a plan from Claude" to { info("Property", "Claude builds the plan from the boundary; you fly it as a plan (1). Boundary import in the app is not built.") },
        "8 Atmosphere over a crowd · not built yet" to { info("Atmosphere", "Not in this build.") },
    ))

    private fun primaryAction() {
        val st = MissionController.executeState
        when {
            loading || uploading || busy != null -> return
            MissionController.executing -> MissionController.pause()
            waiting != null && DroneManager.state.flying -> resumePlan()
            st == WaypointMissionExecuteState.INTERRUPTED && mission != null -> MissionController.resume()
            mission == null -> loadPlan()
            !MissionController.uploaded -> uploadCurrent()
            else -> confirmStart()
        }
    }

    /** Langer Text zum Plan (Strecke, Höhe, Akku, Funkabriss) auf Antippen des Plannamens. */
    private fun showPlanDetails() {
        if (missionText.isBlank()) return
        AlertDialog.Builder(this).setTitle(mission?.name ?: "Plan").setMessage(missionText)
            .setPositiveButton("OK", null)
            .setNeutralButton("Reload plan") { _, _ -> loadPlan() }.show()
    }

    /** Was die Drohne als Nächstes tut – aus Plan, Wegpunkt-Index und Zustand abgeleitet (nur Anzeige). */
    private fun nextActionText(s: DroneState, m: Mission?, idx: Int, st: WaypointMissionExecuteState?): String {
        if (FollowController.active) return "following the phone"
        if (m == null) return "get a plan"
        return when {
            st == WaypointMissionExecuteState.FINISHED -> "done, returning home, then landing"
            st == WaypointMissionExecuteState.RETURN_TO_START_POINT -> "returning home, then landing"
            st == WaypointMissionExecuteState.INTERRUPTED -> "paused: Resume plan or STOP"
            s.landing -> "landing"
            !MissionController.executing && st != WaypointMissionExecuteState.RECOVERING -> if (MissionController.uploaded) "check list, then Start" else "upload the plan to the drone"
            idx < 0 -> "climbing to transit height (${MissionController.TRANSIT_HEIGHT_M.toInt()} m)"
            else -> {
                val w = m.waypoints.getOrNull(idx) ?: return "flying"
                val next = m.waypoints.getOrNull(idx + 1)
                val here = s.location?.takeIf { it.latitude != 0.0 }
                val away = here?.let { Geo.distance(LatLon(it.latitude, it.longitude), w.pos) } ?: 999.0
                when {
                    away > 5 -> "flying to point ${idx + 1} (${away.toInt()} m away, ${w.heightM.toInt()} m high)"
                    w.takePhoto -> "photo at point ${idx + 1}"
                    w.video == 1 -> "video recording from point ${idx + 1}"
                    w.video == 2 -> "video stopped at point ${idx + 1}"
                    w.hoverSec > 0 -> "hovering ${w.hoverSec} s at point ${idx + 1}"
                    next == null -> "last point, then returning home"
                    next.heightM < w.heightM - 0.5 -> "descending to ${next.heightM.toInt()} m, point ${idx + 2}"
                    next.heightM > w.heightM + 0.5 -> "climbing to ${next.heightM.toInt()} m, point ${idx + 2}"
                    else -> "on to point ${idx + 2} (${Geo.distance(w.pos, next.pos).toInt()} m)"
                }
            }
        }
    }


    // ---------------------------------------------------------------- Startprüfung

    /** Eine Prüfzeile: Beschriftung und Erkennung des zugehörigen Problemtexts (die Regeln selbst stehen in preflightProblems). */
    private class Check(val label: String, val needsPlan: Boolean, val matches: (String) -> Boolean)

    private val checks = listOf(
        Check("SDK registered", false) { it.startsWith("SDK not registered") },
        Check("Drone connected", false) { it.startsWith("Drone not connected") },
        Check("Home point set", false) { it.startsWith("Home point") },
        Check("GPS: at least $minSatellites satellites", false) { it.startsWith("GPS too weak") },
        Check("Plan on the drone", false) { it.startsWith("No plan on the drone") },
        Check("Plan matches the home point", true) { it.contains("prepared without this home point") || it.contains("checked for a start point") },
        Check("Battery sufficient (incl. reserve)", true) { it.startsWith("Battery ") },
        Check("All points within 1000 m", true) { it.contains("from the home point") },
        Check("Building guard: route clear", true) { it.startsWith("Building guard") },
        Check("Surface guard: route clear", true) { it.startsWith("Surface guard") },
        Check("Way home clear from every point", true) { it.startsWith("Return guard") },
        Check("DJI height limit sufficient", true) { it.startsWith("DJI height limit") },
        Check("No wind warning", true) { it.contains("ind warning") },
        Check("Low points: building data", true) { it.contains("no building data") },
        Check("Low points: daylight", true) { it.contains("it is dark") },
        Check("Low points: obstacle sensing on", true) { it.contains("obstacle sensing") },
    )


    private fun checkRow(ok: Boolean?, text: String, detail: String? = null): TextView = TextView(this).apply {
        val sb = SpannableStringBuilder()
        val (sym, col) = when (ok) { true -> "✓" to colorOk; false -> "✗" to colorBad; null -> "·" to colorMuted }
        sb.append("$sym  ", ForegroundColorSpan(col), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(text, ForegroundColorSpan(if (ok == null) colorMuted else colorText), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        this.text = sb
        textSize = 14f
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(0, 5, 0, 5)
        if (detail != null) setOnClickListener { AlertDialog.Builder(this@MainActivity).setMessage(detail).setPositiveButton("OK", null).show() }
    }

    /** Kurzfassung eines Problemtexts für die Liste; der volle Text kommt beim Antippen. */
    private fun shortProblem(p: String): String = p.substringBefore(" (").substringBefore(":").substringBefore(" →").substringBefore(". ").take(60)

    /**
     * Im Cockpit stehen nur die Probleme (rot, antippen = Details). Ist alles in Ordnung, eine grüne Zeile.
     * [full] = alle Regeln zeigen (Dialog „Start nicht möglich“).
     */
    private fun fillChecklist(container: LinearLayout, problems: List<String>, hasPlan: Boolean, full: Boolean = false) {
        container.removeAllViews()
        val unmatched = problems.toMutableList()
        checks.forEach { c ->
            val hit = unmatched.filter(c.matches)
            unmatched.removeAll(hit)
            when {
                hit.isNotEmpty() -> hit.forEach { container.addView(checkRow(false, if (full) it else shortProblem(it), it)) }
                !full -> {}
                c.needsPlan && !hasPlan -> container.addView(checkRow(null, c.label))
                else -> container.addView(checkRow(true, c.label))
            }
        }
        unmatched.forEach { container.addView(checkRow(false, if (full) it else shortProblem(it), it)) }
        if (!full && problems.isEmpty() && hasPlan) container.addView(checkRow(true, "Ready for takeoff"))
    }

    private fun renderChecklist(problems: List<String>, hasPlan: Boolean) {
        // Im Flug ist die Startprüfung erledigt; Platz fürs Wesentliche.
        if (MissionController.executing || FollowController.active) { checklist.removeAllViews(); return }
        fillChecklist(checklist, problems, hasPlan)
    }

    // ---------------------------------------------------------------- Aktionen

    /** Wartet, bis die Drohne am Boden ist (Motoren aus), dann [block]. */
    private fun waitLandedThen(block: () -> Unit) {
        val h = android.os.Handler(mainLooper)
        var tries = 0
        val r = object : Runnable {
            override fun run() {
                val s = DroneManager.state
                if (!s.flying && !s.motorsOn) block()
                else if (++tries < 300) h.postDelayed(this, 2000)
                else DroneManager.log("Photos: drone has not landed, use Settings → Data → Fetch photos later")
            }
        }
        h.postDelayed(r, 5000)
    }

    /** Folgen: Drohne hält Abstand und Höhe zum Handy. */
    private fun askFollow() {
        if (FollowController.active) {
            FollowController.stop(); btnFollow.text = "Follow"; return
        }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        val h = android.widget.EditText(this).apply { hint = "Height m"; setText("20"); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val d = android.widget.EditText(this).apply { hint = "Distance m"; setText("10"); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        layout.addView(h); layout.addView(d)
        AlertDialog.Builder(this).setTitle("Follow (phone = target)")
            .setMessage("The drone follows the phone's GPS: keeps distance and height, camera on the phone. If it is on the ground it takes off by itself. End: Stop following, Cancel or STOP. The sticks override at any time. Max. 3 m/s, walk slowly for the first test.")
            .setView(layout)
            .setPositiveButton("Follow") { _, _ ->
                val hm = (h.text.toString().toDoubleOrNull() ?: 20.0).coerceIn(5.0, 60.0)
                val dm = (d.text.toString().toDoubleOrNull() ?: 10.0).coerceIn(5.0, 50.0)
                val st = DroneManager.state
                val pos = st.location?.takeIf { it.latitude != 0.0 }?.let { LatLon(it.latitude, it.longitude) } ?: st.home?.takeIf { it.latitude != 0.0 }?.let { LatLon(it.latitude, it.longitude) }
                val dark = pos != null && Geo.sunElevationDeg(pos, System.currentTimeMillis()) < -3
                val problem = when {
                    pos == null -> "No drone position"
                    hm < MIN_LOW_HEIGHT_M && dark -> "Following below ${MIN_LOW_HEIGHT_M.toInt()} m only in daylight (sensors are blind at night)"
                    hm < MIN_LOW_HEIGHT_M && !st.sensorsOk -> "Following below ${MIN_LOW_HEIGHT_M.toInt()} m only with obstacle sensing on (now: ${st.sensors ?: "unknown"})"
                    else -> null
                }
                if (problem != null) { AlertDialog.Builder(this).setTitle("Cannot follow").setMessage(problem).setPositiveButton("OK", null).show(); return@setPositiveButton }
                FollowController.start(applicationContext, FollowController.Settings(hm, dm))
                btnFollow.text = "Stop following"
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun menu(title: String, items: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(this).setTitle(title).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second(); renderNow(DroneManager.state) }
            .setNegativeButton("Back", null).show()
    }

    private fun sensorsEn(v: String?): String = when { v == null -> "unknown"; v == "aktiv" -> "on"; v == "aus" -> "OFF"; else -> v.replace("vorn", "front").replace("hinten", "back").replace("links", "left").replace("rechts", "right").replace("unten", "down") }

    private fun info(title: String, msg: String) { AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show() }

    /** Auswahl aus festen Werten; der aktuelle Wert der Drohne wird vorher gelesen und markiert. */
    private fun <T> choose(title: String, key: dji.sdk.keyvalue.key.DJIKeyInfo<T>, options: List<Pair<String, T>>, hint: String = "") {
        DjiSettings.read(key) { cur -> runOnUiThread {
            val idx = options.indexOfFirst { it.second == cur }
            AlertDialog.Builder(this).setTitle(title + if (hint.isNotBlank()) " – $hint" else "")
                .setSingleChoiceItems(options.map { it.first }.toTypedArray(), idx) { d, i ->
                    DjiSettings.write(key, options[i].second, "$title: ${options[i].first}") { ok -> runOnUiThread {
                        android.widget.Toast.makeText(this, if (ok) "$title: ${options[i].first}" else "$title: not applied (see log)", android.widget.Toast.LENGTH_LONG).show() } }
                    d.dismiss()
                }.setNegativeButton("Cancel", null).show()
        } }
    }

    /** Einstellungen, sortiert (Felix 13.11.): Sicherheit, Kamera, Kalibrierung, Flug & Wächter, Daten, Gerät & Test. */
    private fun showMore() = menu("Settings", listOf(
        "Use cases (what works in this build)" to { showUseCases() },
        "Safety" to { showSafety() },
        "Camera" to { showCamera() },
        "Calibration" to { showCalibration() },
        "Flight & guards" to { showFlightMenu() },
        "Data" to { showDataMenu() },
        "Device & test" to { showDeviceMenu() }))

    private fun showSafety() {
        val s = DroneManager.state
        menu("Safety", listOf(
            "Height limit (now ${s.heightLimitM?.let { "$it m" } ?: "unknown"})" to { askHeightLimit() },
            "Max distance" to { askDistanceLimit() },
            "Obstacle sensing (now ${sensorsEn(s.sensors)})" to { askObstacle() },
            "On signal loss" to { choose("On signal loss", FlightControllerKey.KeyFailsafeAction, listOf(
                "Return home (recommended)" to dji.sdk.keyvalue.value.flightcontroller.FailsafeAction.GOHOME,
                "Hover" to dji.sdk.keyvalue.value.flightcontroller.FailsafeAction.HOVER,
                "Land" to dji.sdk.keyvalue.value.flightcontroller.FailsafeAction.LANDING), "when no plan is running") }))
    }

    private fun askObstacle() = menu("Obstacle sensing (now ${sensorsEn(DroneManager.state.sensors)})", listOf(
        "Brake (default)" to { DroneManager.setObstacleAvoidance(true, "von Hand") },
        "Bypass" to { DroneManager.setObstacleAvoidanceBypass() },
        "OFF (only for manual takeoff from balcony/car)" to {
            AlertDialog.Builder(this).setTitle("Turn obstacle sensing off?")
                .setMessage("Only for a manual takeoff under a roof or from the car: the drone will NOT brake for obstacles any more. Fly slowly by hand to an open spot. Get plan and Start switch it back on automatically.")
                .setPositiveButton("Turn off") { _, _ -> DroneManager.setObstacleAvoidance(false) }
                .setNegativeButton("Cancel", null).show() }))

    private fun askDistanceLimit() {
        DjiSettings.read(FlightControllerKey.KeyDistanceLimitEnabled) { en -> DjiSettings.read(FlightControllerKey.KeyDistanceLimit) { cur -> runOnUiThread {
            val inp = android.widget.EditText(this).apply { hint = "Metres (15–8000)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText((cur ?: 1000).toString()) }
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 40, 0); addView(inp) }
            AlertDialog.Builder(this).setTitle("Max distance")
                .setMessage("Now: ${when { en == null -> "unknown (connect the drone)"; en == false -> "no limit"; else -> "${cur ?: "?"} m" }}. The drone will not fly further from the home point than this. Our plans stay below 1000 m anyway.")
                .setView(box)
                .setPositiveButton("Set") { _, _ -> inp.text.toString().toIntOrNull()?.coerceIn(15, 8000)?.let { v ->
                    DjiSettings.write(FlightControllerKey.KeyDistanceLimitEnabled, true, "Distance limit on") { DjiSettings.write(FlightControllerKey.KeyDistanceLimit, v, "Distance limit $v m") } } }
                .setNeutralButton("No limit") { _, _ -> DjiSettings.write(FlightControllerKey.KeyDistanceLimitEnabled, false, "Distance limit off") }
                .setNegativeButton("Cancel", null).show()
        } } }
    }

    private fun showCamera() = menu("Camera", listOf(
        "Video resolution" to { askVideoFormat() },
        "Photo format" to { askPhotoFormat() }))

    private fun askVideoFormat() {
        val CK = dji.sdk.keyvalue.key.CameraKey.KeyVideoResolutionFrameRate
        DjiSettings.read(dji.sdk.keyvalue.key.CameraKey.KeyVideoResolutionFrameRateRange) { range -> DjiSettings.read(CK) { cur -> runOnUiThread {
            val opts = range.orEmpty()
            if (opts.isEmpty()) { info("Video resolution", "The drone reports no selectable formats (connected? camera in video mode?). Now: ${cur?.let { fmt(it) } ?: "unknown"}."); return@runOnUiThread }
            val idx = opts.indexOfFirst { it.resolution == cur?.resolution && it.frameRate == cur?.frameRate }
            AlertDialog.Builder(this).setTitle("Video resolution")
                .setSingleChoiceItems(opts.map { fmt(it) }.toTypedArray(), idx) { d, i -> DjiSettings.write(CK, opts[i], "Video ${fmt(opts[i])}"); d.dismiss() }
                .setNegativeButton("Cancel", null).show()
        } } }
    }

    private fun fmt(v: dji.sdk.keyvalue.value.camera.VideoResolutionFrameRate): String =
        "${v.resolution?.name?.removePrefix("RESOLUTION_")?.replace('x', '×') ?: "?"} · ${v.frameRate?.name?.removePrefix("RATE_")?.removeSuffix("FPS")?.replace("DOT", ".") ?: "?"} fps"

    private fun askPhotoFormat() {
        val CK = dji.sdk.keyvalue.key.CameraKey.KeyPhotoFileFormat
        DjiSettings.read(dji.sdk.keyvalue.key.CameraKey.KeyPhotoFileFormatRange) { range -> DjiSettings.read(CK) { cur -> runOnUiThread {
            val opts = range.orEmpty()
            if (opts.isEmpty()) { info("Photo format", "The drone reports no selectable formats (connected? camera in photo mode?). Now: ${cur?.name ?: "unknown"}."); return@runOnUiThread }
            AlertDialog.Builder(this).setTitle("Photo format")
                .setSingleChoiceItems(opts.map { it.name.replace("RAW_JPEG", "RAW + JPEG") }.toTypedArray(), opts.indexOf(cur)) { d, i -> DjiSettings.write(CK, opts[i], "Photo format ${opts[i].name}"); d.dismiss() }
                .setNegativeButton("Cancel", null).show()
        } } }
    }

    private fun showCalibration() = menu("Calibration", listOf(
        "Compass" to { calibrate("Compass", "Outdoors, away from metal and cars. After Start: hold the drone level and turn once around yourself, then nose down and turn once more.",
            FlightControllerKey.KeyStartCompassCalibration, FlightControllerKey.KeyStopCompassCalibration) { done ->
                DjiSettings.read(FlightControllerKey.KeyCompassCalibrationStatus) { st -> done(when (st) {
                    dji.sdk.keyvalue.value.flightcontroller.CompassCalibrationState.HORIZONTAL -> "Step 1: hold level and turn 360°"
                    dji.sdk.keyvalue.value.flightcontroller.CompassCalibrationState.VERTICAL -> "Step 2: nose down and turn 360°"
                    dji.sdk.keyvalue.value.flightcontroller.CompassCalibrationState.SUCCEEDED -> "DONE: compass calibrated"
                    dji.sdk.keyvalue.value.flightcontroller.CompassCalibrationState.FAILED -> "FAILED: choose another place and try again"
                    dji.sdk.keyvalue.value.flightcontroller.CompassCalibrationState.IDLE -> "Idle (not started or finished)"
                    else -> "Status unknown" }) } } },
        "IMU" to { calibrate("IMU", "Drone cold and on a level table, arms folded, propellers may stay on. After Start lay the drone on the requested sides one after another and leave it still each time.",
            FlightControllerKey.KeyStartIMUCalibration, null) { done ->
                DjiSettings.read(FlightControllerKey.KeyIMUCalibrationInfo) { i -> done(if (i == null) "Status unknown" else
                    "State: ${i.calibrationState?.name ?: "?"} · ${i.calibrationProgress ?: 0} %\nSides still to do: ${i.orientationsToCalibrate?.joinToString { it.name }?.ifBlank { "none" } ?: "?"}\nDone: ${i.orientationsCalibrated?.joinToString { it.name }?.ifBlank { "–" } ?: "–"}") } } },
        "Gimbal" to { calibrate("Gimbal", "Put the drone on a level surface, gimbal cover off, nothing touching the camera. Runs by itself for about a minute.",
            dji.sdk.keyvalue.key.GimbalKey.KeyGimbalCalibrate, null) { done ->
                DjiSettings.read(dji.sdk.keyvalue.key.GimbalKey.KeyGimbalCalibrationStatus) { g -> done(if (g == null) "Status unknown" else "State: ${g.status?.name ?: "?"} · ${g.progress ?: 0} %") } } }))

    /** Kalibrierdialog: Anleitung, Start, dann jede Sekunde den Status der Drohne anzeigen. Nur am Boden. */
    private fun calibrate(name: String, howTo: String,
                          start: dji.sdk.keyvalue.key.DJIActionKeyInfo<dji.sdk.keyvalue.value.common.EmptyMsg, dji.sdk.keyvalue.value.common.EmptyMsg>,
                          stop: dji.sdk.keyvalue.key.DJIActionKeyInfo<dji.sdk.keyvalue.value.common.EmptyMsg, dji.sdk.keyvalue.value.common.EmptyMsg>?,
                          status: ((String) -> Unit) -> Unit) {
        if (DroneManager.state.flying || DroneManager.state.motorsOn) { info("Calibrate $name", "Only on the ground with motors off."); return }
        AlertDialog.Builder(this).setTitle("Calibrate $name").setMessage(howTo)
            .setPositiveButton("Start") { _, _ ->
                DjiSettings.action(start, "$name calibration") { err -> runOnUiThread {
                    if (err != null) { info("Calibrate $name", "Start failed: $err\n\nIf this drone does not allow it from the app: DJI Fly → Safety → Sensors."); return@runOnUiThread }
                    val dlg = AlertDialog.Builder(this).setTitle("$name calibration running").setMessage("$howTo\n\nStatus: …")
                        .setPositiveButton("Close", null).apply { if (stop != null) setNegativeButton("Cancel") { _, _ -> DjiSettings.action(stop, "cancel $name calibration") } }.show()
                    val h = android.os.Handler(mainLooper)
                    var last = ""
                    val tick = object : Runnable { override fun run() {
                        if (!dlg.isShowing) return
                        status { t -> runOnUiThread { if (dlg.isShowing) { dlg.setMessage("$howTo\n\nStatus: $t"); if (t != last) { last = t; DroneManager.log("$name calibration: ${t.replace('\n', ' ')}") } } } }
                        h.postDelayed(this, 1000)
                    } }
                    h.post(tick)
                } }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showFlightMenu() {
        val swap = FollowController.swapAxes(this)
        menu("Flight & guards", listOf(
            "Flight guards: route, ground clearance, battery turn-back (${if (FlightGuard.enabled) "on" else "OFF"})" to { FlightGuard.enabled = !FlightGuard.enabled; DroneManager.log("Flight guards ${if (FlightGuard.enabled) "on" else "OFF"}") },
            "Route and ground guards may pause the plan (${if (FlightGuard.mayPause) "on" else "observe only"})" to { FlightGuard.mayPause = !FlightGuard.mayPause; DroneManager.log("Route and ground guards: ${if (FlightGuard.mayPause) "may pause the plan" else "observe only"}") },
            "Image guard may pause the plan (${if (ObstacleGuard.armed) "on" else "observe only"})" to { ObstacleGuard.setArmed(this, !ObstacleGuard.armed) },
            "Turn image guard ${if (ObstacleGuard.enabled) "off" else "on"}" to { ObstacleGuard.setEnabled(this, !ObstacleGuard.enabled) },
            "Remember this start place (balcony/roof)" to { askStartPlace() },
            "Landing light on" to { DroneManager.setBottomLight("an") },
            "Landing light auto (off)" to { DroneManager.setBottomLight("auto") },
            "Show plan in Google Maps" to { showOnMap() },
            "Follow: swap axes (${if (swap) "on" else "off"})" to { FollowController.setSwapAxes(this, !swap); DroneManager.log("Folgen: Achsen tauschen ${if (!swap) "an" else "aus"}") }))
    }

    private fun showDataMenu() = menu("Data", listOf(
        "Fetch photos and upload" to { FlightReport.collectAndUpload(applicationContext) },
        "Save flight records (SDK logs to the repo)" to {
            android.widget.Toast.makeText(this, "Looking for flight records and uploading …", android.widget.Toast.LENGTH_SHORT).show()
            FlightReport.uploadFlightRecords(applicationContext) { n -> runOnUiThread {
                info("Flight records", when { n < 0 -> "No GitHub token entered (Settings → Data → GitHub token)."; n == 0 -> "Nothing new, all records were already in the repo."; else -> "$n record(s) uploaded. Claude can read them now." })
            } } },
        "Live link: send the log to Claude by itself (${if (LiveLink.enabled) "on" else "OFF"})" to { LiveLink.setEnabled(this, !LiveLink.enabled) },
        "Enter GitHub token" to { askToken() },
        "Enter Google key (photorealistic 3D)" to { askGoogleKey() },
        "Enter Claude key (copilot)" to { askClaudeKey() }))

    private fun showDeviceMenu() = menu("Device & test", listOf(
        "Self test (sensors, DJI messages)" to { showSelfTest() },
        "Tracking test (read ActiveTrack values only)" to { showTrackingProbe() },
        "Drone serial number" to {
            val sn = DroneManager.state.serial ?: getSharedPreferences("drohne", MODE_PRIVATE).getString("letzte_sn", null)
            info("Serial number", sn?.let { "Aircraft: $it" } ?: "Not known yet, it is saved on the next connection.") },
        "Re-pair the controller" to {
            AlertDialog.Builder(this).setTitle("Re-pair?")
                .setMessage("Only needed if the controller does not find the drone. The controller beeps until you hold the drone's power button for about 4 s.")
                .setPositiveButton("Pair") { _, _ -> DroneManager.requestPairing() }
                .setNegativeButton("Cancel", null).show() },
        (if (Simulator.active) "Stop simulation" else "Start simulation (without drone)") to { toggleSimulation() },
        "Simulation: ${if (Simulator.timeScale > 1) "real time (now ${Simulator.timeScale.toInt()}×)" else "fast forward 4×"}" to { Simulator.timeScale = if (Simulator.timeScale > 1) 1.0 else 4.0; DroneManager.log("Simulation: Zeitraffer ${Simulator.timeScale.toInt()}×") }))

    private fun askHeightLimit() {
        val cur = DroneManager.state.heightLimitM
        val inp = android.widget.EditText(this).apply { hint = "Metres (20–120)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText((cur ?: 120).toString()) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 40, 0); addView(inp) }
        AlertDialog.Builder(this).setTitle("Height limit")
            .setMessage("Now: ${cur?.let { "$it m" } ?: "unknown (connect the drone)"}. More than 120 m is not allowed; in height zones DJI limits by itself. Plans with \"h\": \"max\" fly 2 m below this limit.")
            .setView(box)
            .setPositiveButton("Set") { _, _ -> inp.text.toString().toIntOrNull()?.coerceIn(20, 120)?.let { DroneManager.setHeightLimit(it) } }
            .setNeutralButton("Maximum 120 m") { _, _ -> DroneManager.setHeightLimit(120) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun askStartPlace() {
        val s = DroneManager.state
        val pos = s.location?.takeIf { it.latitude != 0.0 }?.let { LatLon(it.latitude, it.longitude) } ?: homeLatLon() ?: phoneLatLon()
        if (pos == null) { AlertDialog.Builder(this).setMessage("No position (neither drone nor phone).").setPositiveButton("OK", null).show(); return }
        val existing = StartPlaces.near(this, pos)
        val name = android.widget.EditText(this).apply { hint = "Name"; setText(existing?.name ?: "Balcony") }
        val hgt = android.widget.EditText(this).apply { hint = "Height above ground m"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(java.util.Locale.US, "%.1f", existing?.offsetM ?: s.location?.altitude?.takeIf { s.flying && it > 1 } ?: 10.0)) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 40, 0); addView(name); addView(hgt) }
        AlertDialog.Builder(this).setTitle("Remember start place")
            .setMessage("Here the app lowers all plan heights by this value and checks with the raised start. When a plan is loaded it asks: this place or street level?")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val off = hgt.text.toString().replace(',', '.').toDoubleOrNull()?.coerceIn(0.0, 60.0) ?: 10.0
                StartPlaces.save(this, StartPlaces.Place(name.text.toString().ifBlank { "Balcony" }, pos, off))
                DroneManager.log("Startplatz gemerkt: ${name.text} ${String.format(java.util.Locale.GERMANY, "%.1f", off)} m über Boden bei ${"%.5f".format(pos.lat)}, ${"%.5f".format(pos.lon)}")
            }
            .apply { if (existing != null) setNeutralButton("Delete") { _, _ -> StartPlaces.remove(this@MainActivity, existing); DroneManager.log("Startplatz gelöscht: ${existing.name}") } }
            .setNegativeButton("Cancel", null).show()
    }

    /**
     * Selbsttest (Felix 8.11., nach dem Absturz): Sensoren + DJI-Gesundheitsmeldungen als Ampel-Liste.
     * Ergebnis landet auch im Protokoll (Mehr … → Fotos holen lädt es mit hoch).
     */
    /** Tracking-Test: Kamera auf Person/Auto richten, 10 s warten, Ergebnis an Claude senden. */
    private fun showTrackingProbe() {
        val tv = TextView(this).apply { setPadding(40, 24, 40, 24); textSize = 13f; setTextIsSelectable(true); text = "Point the camera at a person or a car (5–20 m away).\n\nQuerying the drone … 10 s" }
        var result: String? = null
        val dlg = AlertDialog.Builder(this).setTitle("Tracking test").setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("OK", null).setNeutralButton("Again", null).setNegativeButton("Send to Claude", null).create()
        dlg.show()
        fun go() { result = null; tv.text = "Querying the drone … 10 s"; TrackingProbe.run { r -> runOnUiThread { result = r; if (dlg.isShowing) tv.text = r } } }
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { go() }
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            val r = result ?: run { android.widget.Toast.makeText(this, "No result yet", android.widget.Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.GERMANY).format(java.util.Date())
            val st = DroneManager.state
            FlightReport.uploadText(applicationContext, "fluege/selbsttest/tracking-$stamp.txt", "Tracking-Test $stamp · App ${BuildConfig.VERSION_NAME} · Typ ${st.productType} · fliegt=${st.flying}\n\n$r") { ok -> runOnUiThread {
                android.widget.Toast.makeText(this, if (ok) "Sent to Claude" else "Sending failed. GitHub token? Internet?", android.widget.Toast.LENGTH_LONG).show() } }
        }
        go()
    }

    private fun showSelfTest() {
        if (!DroneManager.state.connected && !Simulator.active) {
            AlertDialog.Builder(this).setTitle("Self test").setMessage("The drone is not connected. Switch on the drone, connect the controller to the phone by USB, quit DJI Fly, then try again.")
                .setPositiveButton("OK", null).show(); return
        }
        val tv = TextView(this).apply { setPadding(40, 24, 40, 24); textSize = 14f; setTextIsSelectable(true); text = "Checking sensors … (up to 6 s)" }
        var last: SelfTest.Result? = null
        val dlg = AlertDialog.Builder(this).setTitle("Self test").setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("OK", null).setNeutralButton("Again", null).setNegativeButton("Send to Claude", null).create()
        dlg.show()
        fun go() {
            tv.text = "Checking sensors … (up to 6 s)"; last = null
            SelfTest.run { r -> last = r; if (dlg.isShowing) tv.text = SelfTest.text(r) }
        }
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { go() }
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            val r = last ?: run { android.widget.Toast.makeText(this, "No result yet", android.widget.Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.GERMANY).format(java.util.Date())
            val s = DroneManager.state
            val head = "Selbsttest $stamp · App ${BuildConfig.VERSION_NAME} · Typ ${s.productType} · SN ${s.serial ?: "?"} · fliegt=${s.flying}\n\n"
            FlightReport.uploadText(applicationContext, "fluege/selbsttest/$stamp.txt", head + SelfTest.text(r)) { ok -> runOnUiThread {
                android.widget.Toast.makeText(this, if (ok) "Sent to Claude" else "Sending failed. GitHub token? Internet?", android.widget.Toast.LENGTH_LONG).show() } }
        }
        go()
    }

    /**
     * Simulation (Felix 8.11.): Drohne vortäuschen, Plan echt prüfen, Route abfliegen, 3D-Ansicht.
     * Startpunkt = letzte Handyposition; fehlt sie, kommt er mit dem Plan (geplanter Start bzw. erster Punkt).
     */
    private fun toggleSimulation() {
        if (Simulator.active) {
            Simulator.stopSimulation(); lastConnected = null; userMode = null
            mission = null; missionText = ""; setPlanCard("No plan", ""); renderChecklist(emptyList(), hasPlan = false); renderNow(DroneManager.state)
            return
        }
        if (DroneManager.state.connected) { AlertDialog.Builder(this).setMessage("The drone is connected. Simulation works only without a drone.").setPositiveButton("OK", null).show(); return }
        MissionController.initOffline(applicationContext)
        val phone = try {
            val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        } catch (_: Throwable) { null }
        Simulator.start(phone?.let { LatLon(it.latitude, it.longitude) })
        userMode = null; lastConnected = null
        chooseView(Mode.THREE, byUser = false)
        AlertDialog.Builder(this).setTitle("Simulation")
            .setMessage("The app now pretends a drone is connected on the ground" + (if (phone != null) " (home point = phone position)." else ". The home point comes with the plan.") +
                "\n\nGet plan, check list, Start. The route is flown at the planned speeds; Pause, Resume plan and STOP work, and so does picking a subject on the map while paused. The 3D view shows buildings, route and drone. End: Settings → Device & test → Stop simulation.")
            .setPositiveButton("OK", null).show()
    }

    /** Wegpunkte des aktuellen Plans in Google Maps öffnen (als Fußweg-Route, zeigt die Lage auf dem Satellitenbild). */
    private fun showOnMap() {
        val m = mission ?: return DroneManager.log("Kein Plan: erst Plan holen")
        val pts = m.waypoints.take(10).joinToString("/") { String.format(Locale.US, "%.6f,%.6f", it.pos.lat, it.pos.lon) }
        val url = "https://www.google.com/maps/dir/$pts/data=!4m2!4m1!3e2"
        try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
        catch (t: Throwable) { DroneManager.log("Karte: $url") }
    }

    private fun askToken() {
        val input = android.widget.EditText(this).apply {
            hint = "github_pat_…"; setText(FlightReport.token(this@MainActivity) ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle("GitHub-Token")
            .setMessage("Fine-grained token with write access “Contents” for the repo Drohne only. Stays on this phone.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                FlightReport.saveToken(this, input.text.toString())
                DroneManager.log(if (input.text.isBlank()) "Token gelöscht" else "Token gespeichert")
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Google-Schlüssel für die fotorealistische 3D-Ansicht (Maps JavaScript API). Nur auf dem Handy. */
    private fun askGoogleKey() {
        val input = android.widget.EditText(this).apply {
            hint = "AIza…"; setText(GoogleKey.get(this@MainActivity) ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle("Google key")
            .setMessage("API key from the Google Cloud Console (Maps JavaScript API + Map Tiles API). With a key the 3D view shows Lisbon photorealistically (needs internet); without it the block view from the building database. Stays on this phone.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                GoogleKey.save(this, input.text.toString())
                DroneManager.log(if (input.text.isBlank()) "Google-Schlüssel gelöscht" else "Google-Schlüssel gespeichert")
                view3d.reload(); if (mode == Mode.THREE) view3d.setMission(MissionController.currentMission ?: mission, MissionController.preparedHome ?: homeLatLon())
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun appInfo(): String {
        val s = DroneManager.state
        return listOfNotNull("App ${BuildConfig.VERSION_NAME} · DJI MSDK 5.20.1", if (s.registered) "SDK ok" else "SDK: ${s.registerError ?: "starting"}",
            "Link " + (if (s.airLinkConnected) "ok" else "–"), s.heightLimitM?.let { "Height limit $it m" }, controllerText.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    private fun copyLog() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Protokoll", appInfo() + "\n\n" + DroneManager.logText()))
        android.widget.Toast.makeText(this, "Log copied. Send it to Claude.", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Ganzes Protokoll groß anzeigen, mit Kopieren-Knopf. */
    private fun showLog() {
        val text = appInfo() + "\n\n" + DroneManager.logText()
        AlertDialog.Builder(this).setTitle("Log")
            .setMessage(text.takeLast(6000))
            .setPositiveButton("Copy") { _, _ -> copyLog() }
            .setNegativeButton("Close", null).show()
    }

    private fun showCrash(report: String) {
        AlertDialog.Builder(this).setTitle("Last app crash")
            .setMessage(report.take(3000))
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Absturz", report))
                android.widget.Toast.makeText(this, "Copied. Please send it to Claude.", android.widget.Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Close", null)
            .setOnDismissListener { startSdkIfNeeded() }
            .show()
    }

    /** Nach einem Absturzbericht wurde das SDK beim Start ausgelassen: jetzt nachholen. */
    private fun startSdkIfNeeded() {
        if (DroneManager.initStarted) return
        try { DroneManager.init(applicationContext) } catch (t: Throwable) {
            CrashLog.write(this, "Fehler beim Start des DJI SDK", t)
            DroneManager.log("SDK-Start fehlgeschlagen: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---------------------------------------------------------------- Livebild (DJI-Kamerastream)

    private var videoSurface: SurfaceHolder? = null
    private var videoSize = 0 to 0
    private var videoAttached = false
    private var videoForConnected = false

    /** Live-Bild anhängen, sobald SDK und Bildfläche bereit sind (wird bei jedem Status-Update erneut versucht). */
    private fun attachVideo() {
        val h = videoSurface ?: return
        val connected = DroneManager.state.connected
        // Bei (Wieder-)Verbindung der Drohne Bildfläche neu anmelden, vorher liefert die Kamera nichts.
        if (connected != videoForConnected) { videoAttached = false; videoForConnected = connected }
        if (videoAttached || !DroneManager.state.registered || !connected) return
        try { MediaDataCenter.getInstance().cameraStreamManager.removeCameraStreamSurface(h.surface) } catch (_: Throwable) {}
        try {
            MediaDataCenter.getInstance().cameraStreamManager.putCameraStreamSurface(
                ComponentIndexType.LEFT_OR_MAIN, h.surface, videoSize.first, videoSize.second,
                ICameraStreamManager.ScaleType.CENTER_INSIDE)
            videoAttached = true
            DroneManager.log("Live-Bild verbunden")
        } catch (t: Throwable) {
            DroneManager.log("Live-Bild noch nicht bereit: ${t.message}")
        }
    }

    private fun setupVideo(view: SurfaceView) {
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                videoSurface = holder; videoSize = width to height; videoAttached = false
                attachVideo()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                videoSurface = null; videoAttached = false
                if (!DroneManager.sdkReady) return
                try { MediaDataCenter.getInstance().cameraStreamManager.removeCameraStreamSurface(holder.surface) } catch (_: Throwable) {}
            }
        })
    }

    // ---------------------------------------------------------------- Startprüfung (Regeln unverändert)

    private val MIN_LOW_HEIGHT_M = 25.0

    private fun preflightProblems(s: DroneState): List<String> = buildList {
        if (!s.registered) add("SDK not registered (app key missing?)")
        if (!s.connected) add("Drone not connected")
        val m = mission
        if (m == null || !MissionController.uploaded) add("No plan on the drone (get a plan, then upload)")
        val home = s.home
        if (m != null && home?.latitude != null && home.latitude != 0.0) {
            val est = Budget.estimate(m, LatLon(home.latitude, home.longitude))
            val have = s.batteryPercent ?: 0
            if (have < est.neededPercent) add("Battery $have %, the plan needs about ${est.neededPercent} % (incl. ${Budget.RESERVE_PERCENT} % reserve)")
            if (est.maxDistanceFromHomeM > 1000) add("One point is ${est.maxDistanceFromHomeM.toInt()} m from the home point (max. 1000 m)")
        }
        if ((s.satellites ?: 0) < minSatellites) add("GPS too weak (at least $minSatellites satellites)")
        MissionController.conflicts.forEach { add("Building guard: $it") }
        MissionController.terrainConflicts.forEach { add("Surface guard: $it") }
        addAll(MissionController.returnProblems)
        if (m != null) {
            val top = maxOf(m.waypoints.maxOf { it.heightM }, MissionController.rthHeightM)
            s.heightLimitM?.let { if (it < top - 0.5) add("DJI height limit $it m is below the flight height ${top.toInt()} m. Settings → Safety → Height limit.") }
            if (s.heightLimitM == null) add("DJI height limit not read yet (wait a moment)")
            when (s.windWarning) {
                2 -> add("Strong wind warning from the drone: no takeoff")
                1 -> if (m.waypoints.any { it.heightM < MissionController.TRANSIT_HEIGHT_M }) add("Wind warning from the drone: no points below ${MissionController.TRANSIT_HEIGHT_M.toInt()} m today")
            }
        }
        // Absturz 7.11.: tiefe Punkte nur bei Tageslicht und mit arbeitenden Sensoren.
        if (m != null) {
            val low = m.waypoints.filter { it.heightM < MIN_LOW_HEIGHT_M }
            val pos = s.location?.takeIf { it.latitude != 0.0 }?.let { LatLon(it.latitude, it.longitude) } ?: home?.takeIf { it.latitude != 0.0 }?.let { LatLon(it.latitude, it.longitude) }
            val sun = Geo.sunElevationDeg(pos ?: m.waypoints.first().pos, System.currentTimeMillis())
            // Ohne Gebäudedaten und ohne ausdrücklich freie Umgebung: nichts unter Transithöhe.
            val underTransit = m.waypoints.filter { it.heightM < MissionController.TRANSIT_HEIGHT_M }
            if (underTransit.isNotEmpty() && m.obstacles.isEmpty() && !m.openArea) add("${underTransit.size} points below ${MissionController.TRANSIT_HEIGHT_M.toInt()} m, but the plan has no building data and is not marked as open area. Ask Claude to complete the plan.")
            if (low.isNotEmpty() && sun != null && sun < -3) add("${low.size} points below ${MIN_LOW_HEIGHT_M.toInt()} m, but it is dark: the sensors are blind at night. Daylight only.")
            if (low.isNotEmpty() && !s.sensorsOk) add("${low.size} points below ${MIN_LOW_HEIGHT_M.toInt()} m, obstacle sensing ${sensorsEn(s.sensors)}. Only once it reports on.")
        }
        if (s.home?.latitude == null || s.home.latitude == 0.0) add("Home point not set yet")
        else if (m != null) {
            val h = LatLon(s.home.latitude, s.home.longitude)
            val ph = MissionController.preparedHome
            if (ph == null || Geo.distance(h, ph) > 30) add("Plan was prepared without this home point (transit, building guard) → reload the plan")
            m.plannedStart?.let { if (Geo.distance(h, it) > 50) add("Plan was checked for a start point ${Geo.distance(h, it).toInt()} m from here (max. 50 m). Go to the planned start or have the plan checked again.") }
        }
    }

    // ---------------------------------------------------------------- Plan holen / Testflug / auf Drohne

    private fun planSquare() {
        val s = DroneManager.state
        val loc = s.location
        val home = s.home
        // Drohnenposition; falls die gerade fehlt, den gesetzten Startpunkt nehmen.
        val here = when {
            loc?.latitude != null && loc.latitude != 0.0 -> LatLon(loc.latitude, loc.longitude)
            home?.latitude != null && home.latitude != 0.0 -> LatLon(home.latitude, home.longitude).also {
                DroneManager.log("Drohnenposition fehlt gerade, nehme Startpunkt (Home)")
            }
            else -> { DroneManager.log("Keine Drohnenposition: GPS abwarten"); return }
        }
        val options = arrayOf("Step 1: hover test (10 m up, 5 m forward, 1 photo)", "Step 2: test square (30 × 30 m, 30 m high, 4 photos)")
        AlertDialog.Builder(this).setTitle("Choose test flight").setItems(options) { _, which ->
            if (which == 0) useMission(Missions.hoverTest(here), "Hover test: climbs to 10 m, flies 5 m north, 1 photo, comes back and lands.")
            else useMission(Missions.testSquare(here, sideM = 30.0, heightM = 30.0), "Test square 30 × 30 m at 30 m height around the current position")
        }.show()
    }


    /** Plan-Feld: Name fett, darunter eine Zeile Zustand; der lange Text kommt beim Antippen (showPlanDetails). */
    private fun setPlanCard(name: String, text: String) {
        planName.text = name.substringBefore(" – ")
        planMeta.text = name.substringAfter(" – ", "").ifBlank { metaLine() }
        if (text.isNotBlank()) missionText = text
    }

    private fun metaLine(): String {
        val m = mission ?: return "Get a plan"
        return "${m.waypoints.size} points · ${m.waypoints.count { it.takePhoto }} photos · ${m.lengthM.toInt()} m · ${m.waypoints.minOf { it.heightM }.toInt()}–${m.waypoints.maxOf { it.heightM }.toInt()} m"
    }

    private fun loadPlan() {
        DroneManager.ensureObstacleAvoidance("Plan holen")
        DroneManager.log("Lade Flugplan aus der Cloud …")
        loading = true; renderNow(DroneManager.state)
        setPlanCard("Getting plan …", "Fetching plaene/aktuell.json from the cloud …")
        Thread {
            try {
                // Zuerst über die GitHub-API (ohne Zwischenspeicher, Änderung nach Sekunden da), sonst die alte Adresse (bis 5 min alt).
                val text = try {
                    (java.net.URL(planApiUrl).openConnection() as java.net.HttpURLConnection).run {
                        connectTimeout = 8_000; readTimeout = 8_000; useCaches = false
                        setRequestProperty("Accept", "application/vnd.github.raw")
                        FlightReport.token(this@MainActivity)?.let { setRequestProperty("Authorization", "Bearer $it") }
                        if (responseCode != 200) throw java.io.IOException("HTTP $responseCode")
                        inputStream.bufferedReader().use { it.readText() }
                    }
                } catch (t: Throwable) {
                    DroneManager.log("Plan direkt nicht erreichbar (${t.message}), nehme die zwischengespeicherte Adresse")
                    val conn = (java.net.URL(planUrl + "?t=" + System.currentTimeMillis()).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 10_000; readTimeout = 10_000; useCaches = false
                    }
                    conn.inputStream.bufferedReader().use { it.readText() }
                }
                val (m, beschreibung) = PlanJson.parse(text)
                LiveLink.planLoaded()
                runOnUiThread { loading = false; useMission(m, beschreibung) }
            } catch (t: Throwable) {
                DroneManager.log("Plan laden fehlgeschlagen: ${t.javaClass.simpleName}: ${t.message}")
                runOnUiThread { loading = false; setPlanCard("Getting the plan failed", "${t.javaClass.simpleName}: ${t.message}\nCheck the internet connection, then Get plan again.") }
            }
        }.start()
    }

    private fun summary(m: Mission, beschreibung: String): String = buildString {
        if (beschreibung.isNotBlank()) appendLine(beschreibung).appendLine()
        appendLine("${m.waypoints.size} waypoints, ${m.waypoints.count { it.takePhoto }} photos")
        appendLine("Route ${m.lengthM.toInt()} m, height ${m.waypoints.minOf { it.heightM }.toInt()}–${m.waypoints.maxOf { it.heightM }.toInt()} m, ${m.speedMs} m/s")
        val home = DroneManager.state.home ?: DroneManager.state.location?.let { dji.sdk.keyvalue.value.common.LocationCoordinate2D(it.latitude, it.longitude) }
        if (home?.latitude != null && home.latitude != 0.0) {
            val e = Budget.estimate(m, LatLon(home.latitude, home.longitude))
            appendLine(String.format(Locale.US, "Flight time about %.1f min, battery needed about %d %%", e.totalMin, e.neededPercent))
            appendLine("Farthest point ${e.maxDistanceFromHomeM.toInt()} m from the start")
        } else {
            appendLine(String.format(Locale.US, "Plan about %.1f min (without approach and return)", m.durationSec / 60))
        }
        m.target?.let { appendLine("Camera points at the target (${String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon)})") }
        if (m.obstacles.isNotEmpty()) appendLine("${m.obstacles.size} buildings known, the route is checked against them")
        if (m.light) appendLine("Landing light on")
        append("Signal loss: " + if (m.rcLost == com.felixos.drohne.plan.RcLost.CONTINUE) "finish the plan, then return home" else "return home at once")
    }

    /**
     * Ein Schritt: Plan prüfen/vorbereiten (MissionController.prepare, unverändert) und auf die Drohne laden.
     * Zusammenfassung steht im Plan-Feld; Ergebnis (geprüft, auf der Drohne, Fehler) ebenfalls.
     */
    private var askedStartPlace = false

    private fun useMission(m: Mission, beschreibung: String) {
        // In the air: a newly loaded plan starts where the drone is; the paused plan waits and can be resumed afterwards.
        if (DroneManager.state.flying && !m.name.startsWith("beispiel")) {
            if (MissionController.executing) { info("Pause first", "Press Pause, then load the new plan."); return }
            AlertDialog.Builder(this).setTitle("Fly “${m.name}” from here?")
                .setMessage(summary(m, beschreibung) + "\n\nThe drone flies this plan starting from where it hovers now. At the end it hovers. A paused plan waits and continues with “Resume plan”.")
                .setPositiveButton("Fly from here") { _, _ -> flyFromHere(m.copy(startFromCurrent = true, hoverAtEnd = true), "plan “${m.name}”", detour = true, text = summary(m, beschreibung)) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        waiting = null
        if (Simulator.active && homeLatLon() == null) Simulator.setHome(m.plannedStart ?: m.waypoints.first().pos)
        val text = summary(m, beschreibung)
        if (m.name.startsWith("beispiel")) {
            setPlanCard("Example plan “${m.name}” (view only)", text + "\n\nWill not be flown. Claude has to store a real plan first.")
            DroneManager.log("Plan laden funktioniert (Beispiel, wird nicht geflogen)")
            return
        }
        // Erhöhter Startplatz (Felix 8.11.): zu Hause fragen, ob vom Balkon gestartet wird; Plan mit startUeberBoden fragt nicht.
        val place = if (m.startAboveGroundM == null) StartPlaces.near(this, homeLatLon() ?: m.plannedStart) else null
        if (place != null && MissionController.startAboveOverrideM == null && !askedStartPlace) {
            askedStartPlace = true
            AlertDialog.Builder(this).setTitle("Where does the drone take off?")
                .setMessage("The remembered start place “${place.name}” is here, ${String.format(java.util.Locale.US, "%.0f", place.offsetM)} m above the street. Do you take off from there or at street level?")
                .setPositiveButton(place.name) { _, _ -> MissionController.startAboveOverrideM = place.offsetM; useMission(m, beschreibung) }
                .setNegativeButton("Street level") { _, _ -> MissionController.startAboveOverrideM = 0.0; useMission(m, beschreibung) }
                .setCancelable(false).show()
            return
        }
        askedStartPlace = false
        setPlanCard("Plan “${m.name}” – checking …", text)
        if (!MissionController.prepare(m)) {
            setPlanCard("Plan “${m.name}” – DJI check failed", text + "\n\nNo variant without errors. Send the log to Claude.")
            renderChecklist(preflightProblems(DroneManager.state), hasPlan = false)
            return
        }
        mission = m
        missionText = text
        val problems = listOf(MissionController.conflicts.size to "Building guard", MissionController.terrainConflicts.size to "Surface guard").filter { it.first > 0 }
        val guardNote = if (problems.isEmpty()) "Guards: route clear." else problems.joinToString(", ") { "${it.second}: ${it.first} conflict(s)" } + ", see the check list."
        setPlanCard("Plan “${m.name}” – uploading …", text + "\n\n" + guardNote)
        DroneManager.log("Plan übernommen, lade auf die Drohne …")
        if (showingMap) { mapPanel.update(DroneManager.state, MissionController.currentMission); mapPanel.fit() }
        view3d.setMission(MissionController.currentMission, MissionController.preparedHome ?: homeLatLon())
        // Felix 8.11.: nach „Plan holen“ gleich die 3D-Vorschau zeigen (nicht im Flug).
        if (mode != Mode.THREE && !DroneManager.state.flying) chooseView(Mode.THREE, byUser = false)
        uploadCurrent()
    }

    /** Plan (nochmal) auf die Drohne laden; Ergebnis im Plan-Feld. */

    /** Plan (nochmal) auf die Drohne laden; Ergebnis im Plan-Feld. */
    private fun uploadCurrent() {
        val m = mission ?: return DroneManager.log("Kein Plan: erst „Plan holen“ oder Testflug")
        uploading = true; renderNow(DroneManager.state)
        MissionController.upload { ok ->
            runOnUiThread {
                uploading = false
                setPlanCard(if (ok) "Plan “${m.name}” – on the drone" else "Plan “${m.name}” – upload failed", "")
                renderChecklist(preflightProblems(DroneManager.state), hasPlan = true)
                renderNow(DroneManager.state)
            }
        }
    }

    private fun confirmStart() {
        val problems = preflightProblems(DroneManager.state)
        renderChecklist(problems, hasPlan = mission != null)
        if (problems.isNotEmpty()) {
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 8) }
            fillChecklist(list, problems, hasPlan = mission != null, full = true)
            AlertDialog.Builder(this).setTitle("Cannot start")
                .setView(ScrollView(this).apply { addView(list) })
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Start the plan?")
            .setMessage(missionText + "\n\nSurroundings clear? Thumbs on the sticks. Pause: button, any stick, or the pause button on the controller.")
            .setPositiveButton("Start") { _, _ -> MissionController.start() }
            .setNegativeButton("Cancel", null).show()
    }
}
