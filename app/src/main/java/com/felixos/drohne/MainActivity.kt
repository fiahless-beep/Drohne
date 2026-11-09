package com.felixos.drohne

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
        ObstacleGuard.init(applicationContext)
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
        btnHome.setOnClickListener {
            FollowController.stop()
            MissionController.stop()
            DroneManager.goHome()
        }
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
        videoHint = findViewById(R.id.videoHint); telemetry = findViewById(R.id.telemetry)
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
        btnCenter.contentDescription = if (m == Mode.THREE) "Kamera wechseln" else "Karte einpassen"
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
            s.registerError != null -> "SDK-Fehler" to colorBad
            !s.registered -> "SDK startet" to colorNeutral
            s.connected -> ((s.productType?.takeIf { it.isNotBlank() } ?: "Drohne") + (s.serial?.let { " · SN $it" } ?: "")) to colorOk
            s.rcConnected -> "Nur Controller" to colorWarn
            else -> "Getrennt" to colorBad
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
        chip(chipSensors, "Sensoren", when { !s.connected -> null; sens == null -> "–"; sens.startsWith("blind") -> "blind"; else -> sens },
            when { s.sensorsOk -> colorText; sens == null -> colorMuted; else -> colorWarn })
        chip(chipWind, "Wind", when (s.windWarning) { 2 -> "stark"; 1 -> "mäßig"; else -> null }, if (s.windWarning == 2) colorBad else colorWarn)

        val m = MissionController.currentMission
        val idx = MissionController.currentWaypointIndex
        val st = MissionController.executeState
        phase.text = when {
            !s.connected -> ""
            FollowController.active -> "Folgt dem Handy"
            st == WaypointMissionExecuteState.RETURN_TO_START_POINT -> "Heimflug"
            s.landing -> "Landet"
            MissionController.executing && m != null && idx >= 0 -> "Punkt ${idx + 1} / ${m.waypoints.size}"
            MissionController.executing -> "Steigt auf ${MissionController.TRANSIT_HEIGHT_M.toInt()} m"
            st == WaypointMissionExecuteState.INTERRUPTED -> "Pause"
            s.flying -> "Fliegt"
            s.motorsOn -> "Motoren an"
            else -> "Am Boden"
        }
        phase.setTextColor(if (s.flying || s.motorsOn || FollowController.active) colorWarn else colorText)
    }

    private fun renderTelemetry(s: DroneState) {
        val loc = s.location?.takeIf { it.latitude != 0.0 }
        videoHint.visibility = if (mode != Mode.VIDEO || (s.connected && videoAttached)) View.GONE else View.VISIBLE
        videoHint.text = if (s.connected) "Livebild kommt …" else "Kein Livebild"
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
            String.format(Locale.GERMANY, "↑ %.0f m", loc.altitude ?: 0.0),
            dist?.let { "⌂ ${it.toInt()} m" },
            speedMs?.let { String.format(Locale.GERMANY, "%.1f m/s", it) },
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
        val (label, bg) = when {
            loading -> "Hole Plan …" to R.drawable.btn_primary
            uploading -> "Lade auf Drohne …" to R.drawable.btn_primary
            running -> "Pause" to R.drawable.btn_secondary
            paused && mission != null -> "Weiter" to R.drawable.btn_go
            mission == null -> "Plan holen" to R.drawable.btn_primary
            !MissionController.uploaded -> "Nochmal auf Drohne" to R.drawable.btn_primary
            else -> "Start" to R.drawable.btn_go
        }
        btnPrimary.text = label
        btnPrimary.setBackgroundResource(bg)
        btnPrimary.isEnabled = !loading && !uploading
        secondaryRow.visibility = View.GONE // Testflug/Folgen vorerst raus (Felix 8.11.)
        btnFollow.text = if (FollowController.active) "Folgen beenden" else "Folgen"
        btnHome.alpha = if (s.flying || s.motorsOn || FollowController.active) 1f else 0.6f
    }

    private var loading = false
    private var uploading = false

    private fun primaryAction() {
        val st = MissionController.executeState
        when {
            loading || uploading -> return
            MissionController.executing -> MissionController.pause()
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
            .setNeutralButton("Plan neu holen") { _, _ -> loadPlan() }.show()
    }

    /** Was die Drohne als Nächstes tut – aus Plan, Wegpunkt-Index und Zustand abgeleitet (nur Anzeige). */
    private fun nextActionText(s: DroneState, m: Mission?, idx: Int, st: WaypointMissionExecuteState?): String {
        if (FollowController.active) return "folgt dem Handy"
        if (m == null) return "Plan holen"
        return when {
            st == WaypointMissionExecuteState.FINISHED -> "fertig – Heimflug, dann Landung"
            st == WaypointMissionExecuteState.RETURN_TO_START_POINT -> "Heimflug, dann Landung"
            st == WaypointMissionExecuteState.INTERRUPTED -> "unterbrochen – „Weiter“ oder STOPP"
            s.landing -> "landet"
            !MissionController.executing && st != WaypointMissionExecuteState.RECOVERING -> if (MissionController.uploaded) "Startprüfung, dann „Mission starten“" else "Plan auf die Drohne laden"
            idx < 0 -> "steigt auf Transithöhe (${MissionController.TRANSIT_HEIGHT_M.toInt()} m)"
            else -> {
                val w = m.waypoints.getOrNull(idx) ?: return "fliegt"
                val next = m.waypoints.getOrNull(idx + 1)
                val here = s.location?.takeIf { it.latitude != 0.0 }
                val away = here?.let { Geo.distance(LatLon(it.latitude, it.longitude), w.pos) } ?: 999.0
                when {
                    away > 5 -> "Anflug Punkt ${idx + 1} (${away.toInt()} m, ${w.heightM.toInt()} m hoch)"
                    w.takePhoto -> "Foto an Punkt ${idx + 1}"
                    w.video == 1 -> "Video läuft ab Punkt ${idx + 1}"
                    w.video == 2 -> "Video beendet an Punkt ${idx + 1}"
                    w.hoverSec > 0 -> "schwebt ${w.hoverSec} s an Punkt ${idx + 1}"
                    next == null -> "letzter Punkt – dann Heimflug"
                    next.heightM < w.heightM - 0.5 -> "Abstieg auf ${next.heightM.toInt()} m zu Punkt ${idx + 2}"
                    next.heightM > w.heightM + 0.5 -> "Aufstieg auf ${next.heightM.toInt()} m zu Punkt ${idx + 2}"
                    else -> "weiter zu Punkt ${idx + 2} (${Geo.distance(w.pos, next.pos).toInt()} m)"
                }
            }
        }
    }


    // ---------------------------------------------------------------- Startprüfung

    /** Eine Prüfzeile: Beschriftung und Erkennung des zugehörigen Problemtexts (die Regeln selbst stehen in preflightProblems). */
    private class Check(val label: String, val needsPlan: Boolean, val matches: (String) -> Boolean)

    private val checks = listOf(
        Check("SDK registriert", false) { it.startsWith("SDK nicht registriert") },
        Check("Drohne verbunden", false) { it.startsWith("Drohne nicht verbunden") },
        Check("Startpunkt (Home) gesetzt", false) { it.startsWith("Startpunkt (Home)") },
        Check("GPS: mind. $minSatellites Satelliten", false) { it.startsWith("Zu wenig GPS") },
        Check("Plan auf der Drohne", false) { it.startsWith("Keine Mission auf der Drohne") },
        Check("Plan passt zum Startpunkt", true) { it.contains("ohne diesen Startpunkt vorbereitet") || it.contains("für einen Startpunkt") },
        Check("Akku reicht (inkl. Reserve)", true) { it.startsWith("Akku ") },
        Check("Alle Punkte unter 1000 m", true) { it.contains("vom Startpunkt entfernt") },
        Check("Gebäude-Wächter: Strecke frei", true) { it.startsWith("Gebäude-Wächter") },
        Check("Oberflächen-Wächter: Strecke frei", true) { it.startsWith("Oberflächen-Wächter") },
        Check("DJI-Höhenlimit reicht", true) { it.startsWith("DJI-Höhenlimit") },
        Check("Kein Wind", true) { it.contains("Windwarnung") },
        Check("Tiefe Punkte: Gebäudedaten", true) { it.contains("keine Gebäudedaten") },
        Check("Tiefe Punkte: Tageslicht", true) { it.contains("es ist dunkel") },
        Check("Tiefe Punkte: Hinderniserkennung aktiv", true) { it.contains("Hinderniserkennung") },
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
        if (!full && problems.isEmpty() && hasPlan) container.addView(checkRow(true, "Startbereit"))
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
                else DroneManager.log("Fotos: Drohne nicht gelandet, später „Fotos holen“ im Mehr-Menü")
            }
        }
        h.postDelayed(r, 5000)
    }

    /** Folgen: Drohne hält Abstand und Höhe zum Handy. */
    private fun askFollow() {
        if (FollowController.active) {
            FollowController.stop(); btnFollow.text = "Folgen"; return
        }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        val h = android.widget.EditText(this).apply { hint = "Höhe m"; setText("20"); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val d = android.widget.EditText(this).apply { hint = "Abstand m"; setText("10"); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        layout.addView(h); layout.addView(d)
        AlertDialog.Builder(this).setTitle("Folgen (Handy = Ziel)")
            .setMessage("Die Drohne folgt dem GPS des Handys: hält Abstand und Höhe, Kamera aufs Handy. Steht sie am Boden, startet sie selbst. Beenden: „Folgen beenden“, Abbrechen oder STOPP. Die Sticks greifen jederzeit ein. Max. 3 m/s, erster Test langsam gehen.")
            .setView(layout)
            .setPositiveButton("Folgen") { _, _ ->
                val hm = (h.text.toString().toDoubleOrNull() ?: 20.0).coerceIn(5.0, 60.0)
                val dm = (d.text.toString().toDoubleOrNull() ?: 10.0).coerceIn(5.0, 50.0)
                val st = DroneManager.state
                val pos = st.location?.takeIf { it.latitude != 0.0 }?.let { LatLon(it.latitude, it.longitude) } ?: st.home?.takeIf { it.latitude != 0.0 }?.let { LatLon(it.latitude, it.longitude) }
                val dark = pos != null && Geo.sunElevationDeg(pos, System.currentTimeMillis()) < -3
                val problem = when {
                    pos == null -> "Keine Drohnenposition"
                    hm < MIN_LOW_HEIGHT_M && dark -> "Folgen unter ${MIN_LOW_HEIGHT_M.toInt()} m nur bei Tageslicht (Sensoren nachts blind)"
                    hm < MIN_LOW_HEIGHT_M && !st.sensorsOk -> "Folgen unter ${MIN_LOW_HEIGHT_M.toInt()} m nur mit Hinderniserkennung „aktiv“ (jetzt: ${st.sensors ?: "unbekannt"})"
                    else -> null
                }
                if (problem != null) { AlertDialog.Builder(this).setTitle("Folgen nicht möglich").setMessage(problem).setPositiveButton("OK", null).show(); return@setPositiveButton }
                FollowController.start(applicationContext, FollowController.Settings(hm, dm))
                btnFollow.text = "Folgen beenden"
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    /** Mehr-Menü: Karte extern, Fotos holen, Token, Landelampe, Koppeln, Achsen, Wächter. */
    private fun showMore() {
        val swap = FollowController.swapAxes(this)
        val items = arrayOf(
            if (Simulator.active) "Simulation beenden" else "Simulation starten (ohne Drohne)",
            "Simulation: ${if (Simulator.timeScale > 1) "Echtzeit (jetzt ${Simulator.timeScale.toInt()}×)" else "Zeitraffer 4×"}",
            "Plan in Google Maps zeigen", "Fotos holen und hochladen", "GitHub-Token eintragen", "Google-Schlüssel eintragen (3D fotorealistisch)", "Landelampe an", "Landelampe auto (aus)", "Fernbedienung neu koppeln",
            "Folgen: Achsen tauschen (${if (swap) "an" else "aus"})",
            "Bildwächter darf Mission anhalten (${if (ObstacleGuard.armed) "an" else "nur beobachten"})",
            "Bildwächter ${if (ObstacleGuard.enabled) "ausschalten" else "einschalten"}",
            "Seriennummer der Drohne",
            "Selbsttest (Sensoren, DJI-Meldungen)",
            "Flugaufzeichnungen sichern (SDK-Logs ins Repo)",
            "Startplatz hier merken (Balkon/Dach)",
            "Höhenlimit einstellen (jetzt ${DroneManager.state.heightLimitM?.let { "$it m" } ?: "unbekannt"})",
            if (DroneManager.state.sensors == "aus") "Hinderniserkennung wieder EIN" else "Hinderniserkennung AUS (Handstart Balkon/Auto)")
        AlertDialog.Builder(this).setTitle("Mehr").setItems(items) { _, which ->
            when (which - 2) {
                -2 -> toggleSimulation()
                -1 -> { Simulator.timeScale = if (Simulator.timeScale > 1) 1.0 else 4.0; DroneManager.log("Simulation: Zeitraffer ${Simulator.timeScale.toInt()}×") }
                0 -> showOnMap()
                1 -> FlightReport.collectAndUpload(applicationContext)
                2 -> askToken()
                3 -> askGoogleKey()
                4 -> DroneManager.setBottomLight("an")
                5 -> DroneManager.setBottomLight("auto")
                6 -> AlertDialog.Builder(this).setTitle("Neu koppeln?")
                    .setMessage("Nur nötig, wenn die Fernbedienung die Drohne nicht findet. Die Fernbedienung piept dann, bis du den Einschaltknopf der Drohne ca. 4 s hältst.")
                    .setPositiveButton("Koppeln") { _, _ -> DroneManager.requestPairing() }
                    .setNegativeButton("Abbrechen", null).show()
                7 -> { FollowController.setSwapAxes(this, !swap); DroneManager.log("Folgen: Achsen tauschen ${if (!swap) "an" else "aus"}") }
                8 -> ObstacleGuard.setArmed(this, !ObstacleGuard.armed)
                9 -> ObstacleGuard.setEnabled(this, !ObstacleGuard.enabled)
                10 -> { val sn = DroneManager.state.serial ?: getSharedPreferences("drohne", MODE_PRIVATE).getString("letzte_sn", null)
                    AlertDialog.Builder(this).setTitle("Seriennummer").setMessage(sn?.let { "Fluggerät: $it" } ?: "Noch unbekannt – wird beim nächsten Verbinden gespeichert.").setPositiveButton("OK", null).show() }
                11 -> showSelfTest()
                12 -> {
                    android.widget.Toast.makeText(this, "Flugaufzeichnungen werden gesucht und hochgeladen …", android.widget.Toast.LENGTH_SHORT).show()
                    FlightReport.uploadFlightRecords(applicationContext) { n -> runOnUiThread {
                        val msg = when { n < 0 -> "Kein GitHub-Token eingetragen (Mehr … → GitHub-Token)."; n == 0 -> "Nichts Neues – alle Aufzeichnungen waren schon im Repo."; else -> "$n Aufzeichnung(en) hochgeladen → Claude kann sie jetzt lesen." }
                        AlertDialog.Builder(this).setTitle("Flugaufzeichnungen").setMessage(msg).setPositiveButton("OK", null).show()
                    } }
                }
                13 -> askStartPlace()
                14 -> askHeightLimit()
                15 -> if (DroneManager.state.sensors == "aus") DroneManager.setObstacleAvoidance(true, "von Hand") else AlertDialog.Builder(this).setTitle("Hinderniserkennung aus?")
                    .setMessage("Nur für den Handstart unter Dach oder aus dem Auto: Die Drohne bremst dann NICHT mehr vor Hindernissen. Fliege von Hand langsam an einen freien Ort. Bei „Plan holen“ und „Start“ geht sie automatisch wieder an.")
                    .setPositiveButton("Ausschalten") { _, _ -> DroneManager.setObstacleAvoidance(false) }
                    .setNegativeButton("Abbrechen", null).show()
            }
            renderNow(DroneManager.state)
        }.show()
    }

    /**
     * Erhöhten Startplatz merken (Felix 8.11.: Balkon 3. Stock). Position = Drohne (wenn verbunden) sonst Handy;
     * Höhe über Boden vorbelegt mit der aktuellen Flughöhe der Drohne (vom Gehweg gestartet, vor dem Balkon schwebend) oder 10 m.
     */
    /** Höhenlimit der Drohne (DJI Max Altitude) von Hand. 120 m = gesetzliches Maximum (EU, offene Kategorie); GEO-Höhenzonen begrenzt DJI zusätzlich selbst. */
    private fun askHeightLimit() {
        val cur = DroneManager.state.heightLimitM
        val inp = android.widget.EditText(this).apply { hint = "Meter (20–120)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText((cur ?: 120).toString()) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 40, 0); addView(inp) }
        AlertDialog.Builder(this).setTitle("Höhenlimit")
            .setMessage("Jetzt: ${cur?.let { "$it m" } ?: "unbekannt (Drohne verbinden)"}. Höher als 120 m ist nicht erlaubt; in Höhenzonen begrenzt DJI selbst. Pläne mit \"h\": \"max\" fliegen 2 m unter diesem Limit.")
            .setView(box)
            .setPositiveButton("Setzen") { _, _ -> inp.text.toString().toIntOrNull()?.coerceIn(20, 120)?.let { DroneManager.setHeightLimit(it) } }
            .setNeutralButton("Maximum 120 m") { _, _ -> DroneManager.setHeightLimit(120) }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun askStartPlace() {
        val s = DroneManager.state
        val pos = s.location?.takeIf { it.latitude != 0.0 }?.let { LatLon(it.latitude, it.longitude) } ?: homeLatLon() ?: phoneLatLon()
        if (pos == null) { AlertDialog.Builder(this).setMessage("Keine Position (weder Drohne noch Handy).").setPositiveButton("OK", null).show(); return }
        val existing = StartPlaces.near(this, pos)
        val name = android.widget.EditText(this).apply { hint = "Name"; setText(existing?.name ?: "Balkon") }
        val hgt = android.widget.EditText(this).apply { hint = "Höhe über Boden m"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(java.util.Locale.US, "%.1f", existing?.offsetM ?: s.location?.altitude?.takeIf { s.flying && it > 1 } ?: 10.0)) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 40, 0); addView(name); addView(hgt) }
        AlertDialog.Builder(this).setTitle("Startplatz merken")
            .setMessage("Die App senkt hier alle Planhöhen um diesen Wert und prüft mit dem erhöhten Start. Beim Übernehmen eines Plans fragt sie: Balkon oder Straße?")
            .setView(box)
            .setPositiveButton("Speichern") { _, _ ->
                val off = hgt.text.toString().replace(',', '.').toDoubleOrNull()?.coerceIn(0.0, 60.0) ?: 10.0
                StartPlaces.save(this, StartPlaces.Place(name.text.toString().ifBlank { "Balkon" }, pos, off))
                DroneManager.log("Startplatz gemerkt: ${name.text} ${String.format(java.util.Locale.GERMANY, "%.1f", off)} m über Boden bei ${"%.5f".format(pos.lat)}, ${"%.5f".format(pos.lon)}")
            }
            .apply { if (existing != null) setNeutralButton("Löschen") { _, _ -> StartPlaces.remove(this@MainActivity, existing); DroneManager.log("Startplatz gelöscht: ${existing.name}") } }
            .setNegativeButton("Abbrechen", null).show()
    }

    /**
     * Selbsttest (Felix 8.11., nach dem Absturz): Sensoren + DJI-Gesundheitsmeldungen als Ampel-Liste.
     * Ergebnis landet auch im Protokoll (Mehr … → Fotos holen lädt es mit hoch).
     */
    private fun showSelfTest() {
        if (!DroneManager.state.connected && !Simulator.active) {
            AlertDialog.Builder(this).setTitle("Selbsttest").setMessage("Drohne ist nicht verbunden. Drohne einschalten, Fernbedienung per USB ans Handy, DJI Fly beenden – dann erneut.")
                .setPositiveButton("OK", null).show(); return
        }
        val tv = TextView(this).apply { setPadding(40, 24, 40, 24); textSize = 14f; setTextIsSelectable(true); text = "Prüfe Sensoren … (bis zu 6 s)" }
        var last: SelfTest.Result? = null
        val dlg = AlertDialog.Builder(this).setTitle("Selbsttest").setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("OK", null).setNeutralButton("Nochmal", null).setNegativeButton("An Claude senden", null).create()
        dlg.show()
        fun go() {
            tv.text = "Prüfe Sensoren … (bis zu 6 s)"; last = null
            SelfTest.run { r -> last = r; if (dlg.isShowing) tv.text = SelfTest.text(r) }
        }
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { go() }
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            val r = last ?: run { android.widget.Toast.makeText(this, "Noch kein Ergebnis", android.widget.Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.GERMANY).format(java.util.Date())
            val s = DroneManager.state
            val head = "Selbsttest $stamp · App ${BuildConfig.VERSION_NAME} · Typ ${s.productType} · SN ${s.serial ?: "?"} · fliegt=${s.flying}\n\n"
            FlightReport.uploadText(applicationContext, "fluege/selbsttest/$stamp.txt", head + SelfTest.text(r)) { ok -> runOnUiThread {
                android.widget.Toast.makeText(this, if (ok) "An Claude gesendet" else "Senden fehlgeschlagen – GitHub-Token? Internet?", android.widget.Toast.LENGTH_LONG).show() } }
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
            mission = null; missionText = ""; setPlanCard("Kein Plan", ""); renderChecklist(emptyList(), hasPlan = false); renderNow(DroneManager.state)
            return
        }
        if (DroneManager.state.connected) { AlertDialog.Builder(this).setMessage("Drohne ist verbunden – Simulation nur ohne Drohne.").setPositiveButton("OK", null).show(); return }
        MissionController.initOffline(applicationContext)
        val phone = try {
            val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        } catch (_: Throwable) { null }
        Simulator.start(phone?.let { LatLon(it.latitude, it.longitude) })
        userMode = null; lastConnected = null
        chooseView(Mode.THREE, byUser = false)
        AlertDialog.Builder(this).setTitle("Simulation")
            .setMessage("Die App tut jetzt so, als wäre eine Drohne am Boden verbunden" + (if (phone != null) " (Startpunkt = Handyposition)." else ". Der Startpunkt kommt mit dem Plan.") +
                "\n\nPlan holen, Startprüfung, Start. Die Route wird mit den geplanten Geschwindigkeiten abgeflogen; Pause, Weiter und STOPP wirken. Ansicht 3D zeigt Gebäude, Weg und Drohne. Beenden: Mehr → Simulation beenden.")
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
            .setMessage("Fine-grained Token mit Schreibrecht „Contents“ nur für das Repo Drohne. Bleibt auf diesem Handy.")
            .setView(input)
            .setPositiveButton("Speichern") { _, _ ->
                FlightReport.saveToken(this, input.text.toString())
                DroneManager.log(if (input.text.isBlank()) "Token gelöscht" else "Token gespeichert")
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    /** Google-Schlüssel für die fotorealistische 3D-Ansicht (Maps JavaScript API). Nur auf dem Handy. */
    private fun askGoogleKey() {
        val input = android.widget.EditText(this).apply {
            hint = "AIza…"; setText(GoogleKey.get(this@MainActivity) ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle("Google-Schlüssel")
            .setMessage("API-Schlüssel aus der Google Cloud Console (Maps JavaScript API + Map Tiles API). Mit Schlüssel zeigt die 3D-Ansicht Lissabon fotorealistisch (braucht Internet); ohne Schlüssel die Klötzchen-Ansicht aus der Gebäudedatenbank. Bleibt auf diesem Handy.")
            .setView(input)
            .setPositiveButton("Speichern") { _, _ ->
                GoogleKey.save(this, input.text.toString())
                DroneManager.log(if (input.text.isBlank()) "Google-Schlüssel gelöscht" else "Google-Schlüssel gespeichert")
                view3d.reload(); if (mode == Mode.THREE) view3d.setMission(MissionController.currentMission ?: mission, MissionController.preparedHome ?: homeLatLon())
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun appInfo(): String {
        val s = DroneManager.state
        return listOfNotNull("App ${BuildConfig.VERSION_NAME} · DJI MSDK 5.20.1", if (s.registered) "SDK ok" else "SDK: ${s.registerError ?: "startet"}",
            "Funk " + (if (s.airLinkConnected) "ok" else "–"), s.heightLimitM?.let { "Höhenlimit $it m" }, controllerText.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    private fun copyLog() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Protokoll", appInfo() + "\n\n" + DroneManager.logText()))
        android.widget.Toast.makeText(this, "Protokoll kopiert – an Claude schicken", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Ganzes Protokoll groß anzeigen, mit Kopieren-Knopf. */
    private fun showLog() {
        val text = appInfo() + "\n\n" + DroneManager.logText()
        AlertDialog.Builder(this).setTitle("Protokoll")
            .setMessage(text.takeLast(6000))
            .setPositiveButton("Kopieren") { _, _ -> copyLog() }
            .setNegativeButton("Schließen", null).show()
    }

    private fun showCrash(report: String) {
        AlertDialog.Builder(this).setTitle("Letzter Absturz")
            .setMessage(report.take(3000))
            .setPositiveButton("Kopieren") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Absturz", report))
                android.widget.Toast.makeText(this, "Kopiert – bitte an Claude schicken", android.widget.Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Schließen", null)
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
        if (!s.registered) add("SDK nicht registriert (App-Key fehlt?)")
        if (!s.connected) add("Drohne nicht verbunden")
        val m = mission
        if (m == null || !MissionController.uploaded) add("Keine Mission auf der Drohne (planen/laden, dann hochladen)")
        val home = s.home
        if (m != null && home?.latitude != null && home.latitude != 0.0) {
            val est = Budget.estimate(m, LatLon(home.latitude, home.longitude))
            val have = s.batteryPercent ?: 0
            if (have < est.neededPercent) add("Akku $have %, Mission braucht ca. ${est.neededPercent} % (inkl. ${Budget.RESERVE_PERCENT} % Reserve)")
            if (est.maxDistanceFromHomeM > 1000) add("Ein Punkt ist ${est.maxDistanceFromHomeM.toInt()} m vom Startpunkt entfernt (max. 1000 m)")
        }
        if ((s.satellites ?: 0) < minSatellites) add("Zu wenig GPS (mind. $minSatellites Satelliten)")
        MissionController.conflicts.forEach { add("Gebäude-Wächter: $it") }
        MissionController.terrainConflicts.forEach { add("Oberflächen-Wächter: $it") }
        if (m != null) {
            val top = maxOf(m.waypoints.maxOf { it.heightM }, MissionController.rthHeightM)
            s.heightLimitM?.let { if (it < top - 0.5) add("DJI-Höhenlimit $it m liegt unter der Flughöhe ${top.toInt()} m. Mehr … → Höhenlimit einstellen.") }
            if (s.heightLimitM == null) add("DJI-Höhenlimit noch nicht gelesen (kurz warten)")
            when (s.windWarning) {
                2 -> add("Starke Windwarnung der Drohne: kein Start")
                1 -> if (m.waypoints.any { it.heightM < MissionController.TRANSIT_HEIGHT_M }) add("Windwarnung der Drohne: Punkte unter ${MissionController.TRANSIT_HEIGHT_M.toInt()} m heute nicht")
            }
        }
        // Absturz 7.11.: tiefe Punkte nur bei Tageslicht und mit arbeitenden Sensoren.
        if (m != null) {
            val low = m.waypoints.filter { it.heightM < MIN_LOW_HEIGHT_M }
            val pos = s.location?.takeIf { it.latitude != 0.0 }?.let { LatLon(it.latitude, it.longitude) } ?: home?.takeIf { it.latitude != 0.0 }?.let { LatLon(it.latitude, it.longitude) }
            val sun = Geo.sunElevationDeg(pos ?: m.waypoints.first().pos, System.currentTimeMillis())
            // Ohne Gebäudedaten und ohne ausdrücklich freie Umgebung: nichts unter Transithöhe.
            val underTransit = m.waypoints.filter { it.heightM < MissionController.TRANSIT_HEIGHT_M }
            if (underTransit.isNotEmpty() && m.obstacles.isEmpty() && !m.openArea) add("${underTransit.size} Punkte unter ${MissionController.TRANSIT_HEIGHT_M.toInt()} m, aber der Plan hat keine Gebäudedaten (hindernisse) und ist nicht als freie Umgebung markiert. Plan von Claude ergänzen lassen.")
            if (low.isNotEmpty() && sun != null && sun < -3) add("${low.size} Punkte unter ${MIN_LOW_HEIGHT_M.toInt()} m, aber es ist dunkel: die Hinderniserkennung ist nachts blind. Nur bei Tageslicht.")
            if (low.isNotEmpty() && !s.sensorsOk) add("${low.size} Punkte unter ${MIN_LOW_HEIGHT_M.toInt()} m, Hinderniserkennung ${s.sensors ?: "unbekannt"}. Erst wenn sie „aktiv“ meldet.")
        }
        if (s.home?.latitude == null || s.home.latitude == 0.0) add("Startpunkt (Home) noch nicht gesetzt")
        else if (m != null) {
            val h = LatLon(s.home.latitude, s.home.longitude)
            val ph = MissionController.preparedHome
            if (ph == null || Geo.distance(h, ph) > 30) add("Plan wurde ohne diesen Startpunkt vorbereitet (Transit, Gebäude-Wächter) → Plan erneut übernehmen")
            m.plannedStart?.let { if (Geo.distance(h, it) > 50) add("Plan wurde für einen Startpunkt ${Geo.distance(h, it).toInt()} m von hier geprüft (max. 50 m). Zum geplanten Start gehen oder Plan neu prüfen lassen.") }
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
        val options = arrayOf("Stufe 1: Schwebetest (10 m hoch, 5 m vor, 1 Foto)", "Stufe 2: Testquadrat (30 × 30 m, 30 m hoch, 4 Fotos)")
        AlertDialog.Builder(this).setTitle("Testflug wählen").setItems(options) { _, which ->
            if (which == 0) useMission(Missions.hoverTest(here), "Schwebetest: steigt auf 10 m, fliegt 5 m nach Norden, 1 Foto, kommt zurück und landet.")
            else useMission(Missions.testSquare(here, sideM = 30.0, heightM = 30.0), "Testquadrat 30 × 30 m in 30 m Höhe um die aktuelle Position")
        }.show()
    }


    /** Plan-Feld: Name fett, darunter eine Zeile Zustand; der lange Text kommt beim Antippen (showPlanDetails). */
    private fun setPlanCard(name: String, text: String) {
        planName.text = name.substringBefore(" – ")
        planMeta.text = name.substringAfter(" – ", "").ifBlank { metaLine() }
        if (text.isNotBlank()) missionText = text
    }

    private fun metaLine(): String {
        val m = mission ?: return "Plan holen oder Testflug"
        return "${m.waypoints.size} Punkte · ${m.waypoints.count { it.takePhoto }} Fotos · ${m.lengthM.toInt()} m · ${m.waypoints.minOf { it.heightM }.toInt()}–${m.waypoints.maxOf { it.heightM }.toInt()} m"
    }

    private fun loadPlan() {
        DroneManager.ensureObstacleAvoidance("Plan holen")
        DroneManager.log("Lade Flugplan aus der Cloud …")
        loading = true; renderNow(DroneManager.state)
        setPlanCard("Plan holen …", "Hole plaene/aktuell.json aus der Cloud …")
        Thread {
            try {
                val url = java.net.URL(planUrl + "?t=" + System.currentTimeMillis())
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 10_000; readTimeout = 10_000; useCaches = false
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val (m, beschreibung) = PlanJson.parse(text)
                runOnUiThread { loading = false; useMission(m, beschreibung) }
            } catch (t: Throwable) {
                DroneManager.log("Plan laden fehlgeschlagen: ${t.javaClass.simpleName}: ${t.message}")
                runOnUiThread { loading = false; setPlanCard("Plan holen fehlgeschlagen", "${t.javaClass.simpleName}: ${t.message}\nInternet prüfen, dann nochmal „Plan holen & auf Drohne“.") }
            }
        }.start()
    }

    private fun summary(m: Mission, beschreibung: String): String = buildString {
        if (beschreibung.isNotBlank()) appendLine(beschreibung).appendLine()
        appendLine("${m.waypoints.size} Wegpunkte, ${m.waypoints.count { it.takePhoto }} Fotos")
        appendLine("Strecke ${m.lengthM.toInt()} m, Höhe ${m.waypoints.minOf { it.heightM }.toInt()}–${m.waypoints.maxOf { it.heightM }.toInt()} m, ${m.speedMs} m/s")
        val home = DroneManager.state.home ?: DroneManager.state.location?.let { dji.sdk.keyvalue.value.common.LocationCoordinate2D(it.latitude, it.longitude) }
        if (home?.latitude != null && home.latitude != 0.0) {
            val e = Budget.estimate(m, LatLon(home.latitude, home.longitude))
            appendLine(String.format(Locale.GERMANY, "Flugzeit ca. %.1f min, Akku nötig ca. %d %%", e.totalMin, e.neededPercent))
            appendLine("Weitester Punkt ${e.maxDistanceFromHomeM.toInt()} m vom Start")
        } else {
            appendLine(String.format(Locale.GERMANY, "Mission ca. %.1f min (ohne An-/Rückflug)", m.durationSec / 60))
        }
        m.target?.let { appendLine("Kamera zeigt aufs Ziel (${String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon)})") }
        if (m.obstacles.isNotEmpty()) appendLine("${m.obstacles.size} Gebäude bekannt, Strecke wird dagegen geprüft")
        if (m.light) appendLine("Landelampe an")
        append("Funkabriss: " + if (m.rcLost == com.felixos.drohne.plan.RcLost.CONTINUE) "zu Ende fliegen, dann heim" else "sofort heim")
    }

    /**
     * Ein Schritt: Plan prüfen/vorbereiten (MissionController.prepare, unverändert) und auf die Drohne laden.
     * Zusammenfassung steht im Plan-Feld; Ergebnis (geprüft, auf der Drohne, Fehler) ebenfalls.
     */
    private var askedStartPlace = false

    private fun useMission(m: Mission, beschreibung: String) {
        if (Simulator.active && homeLatLon() == null) Simulator.setHome(m.plannedStart ?: m.waypoints.first().pos)
        val text = summary(m, beschreibung)
        if (m.name.startsWith("beispiel")) {
            setPlanCard("Beispielplan „${m.name}“ (nur Ansicht)", text + "\n\nWird nicht geflogen – Claude muss erst einen echten Plan ablegen.")
            DroneManager.log("Plan laden funktioniert (Beispiel, wird nicht geflogen)")
            return
        }
        // Erhöhter Startplatz (Felix 8.11.): zu Hause fragen, ob vom Balkon gestartet wird; Plan mit startUeberBoden fragt nicht.
        val place = if (m.startAboveGroundM == null) StartPlaces.near(this, homeLatLon() ?: m.plannedStart) else null
        if (place != null && MissionController.startAboveOverrideM == null && !askedStartPlace) {
            askedStartPlace = true
            AlertDialog.Builder(this).setTitle("Wo startet die Drohne?")
                .setMessage("Hier gibt es den gemerkten Startplatz „${place.name}“, ${String.format(java.util.Locale.GERMANY, "%.0f", place.offsetM)} m über der Straße. Startest du von dort oder ebenerdig?")
                .setPositiveButton(place.name) { _, _ -> MissionController.startAboveOverrideM = place.offsetM; useMission(m, beschreibung) }
                .setNegativeButton("Straße / ebenerdig") { _, _ -> MissionController.startAboveOverrideM = 0.0; useMission(m, beschreibung) }
                .setCancelable(false).show()
            return
        }
        askedStartPlace = false
        setPlanCard("Plan „${m.name}“ – wird geprüft …", text)
        if (!MissionController.prepare(m)) {
            setPlanCard("Plan „${m.name}“ – DJI-Prüfung fehlgeschlagen", text + "\n\nKeine Variante fehlerfrei – Protokoll an Claude schicken.")
            renderChecklist(preflightProblems(DroneManager.state), hasPlan = false)
            return
        }
        mission = m
        missionText = text
        val problems = listOf(MissionController.conflicts.size to "Gebäude-Wächter", MissionController.terrainConflicts.size to "Oberflächen-Wächter").filter { it.first > 0 }
        val guardNote = if (problems.isEmpty()) "Wächter: Strecke frei." else problems.joinToString(", ") { "${it.second}: ${it.first} Konflikt(e)" } + " – siehe Startprüfung."
        setPlanCard("Plan „${m.name}“ – lade auf die Drohne …", text + "\n\n" + guardNote)
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
                setPlanCard(if (ok) "Plan „${m.name}“ – auf der Drohne" else "Plan „${m.name}“ – Upload fehlgeschlagen", "")
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
            AlertDialog.Builder(this).setTitle("Start nicht möglich")
                .setView(ScrollView(this).apply { addView(list) })
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Mission starten?")
            .setMessage(missionText + "\n\nUmgebung frei? Daumen an den Sticks.")
            .setPositiveButton("Starten") { _, _ -> MissionController.start() }
            .setNegativeButton("Abbrechen", null).show()
    }
}
