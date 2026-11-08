package com.felixos.drohne

import android.Manifest
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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
 * Oberfläche, hochkant, eine Hand:
 *  - Statusleiste oben (Drohne, Akku, GPS, Sensoren, Status + Kleinzeile)
 *  - Bildfläche: Livebild oder Karte (Umschalter), Telemetrie-Streifen unten
 *  - „Was passiert“: Missionszustand, Fortschritt, nächste Aktion, Kennzahlen, Wächter
 *  - Vorbereiten (Plan holen & auf Drohne, Testflug, Startprüfung als Liste)
 *  - Fliegen (Starten, Pause, Weiter, Folgen), Mehr, Protokoll
 *  - STOPP – Heimflug immer sichtbar unten
 * Die Sicherheitslogik (MissionController.prepare, preflightProblems, Wächter) ist unverändert.
 */
class MainActivity : AppCompatActivity() {

    /** Mindestwerte vor dem Start. Felix: sicher mit 15–20 % zurück (siehe Budget). */
    private val minSatellites = 10

    /** Vom Cloud-Planer erzeugter Plan (öffentliches Repo, daher ohne Login lesbar). */
    private val planUrl = "https://raw.githubusercontent.com/fiahless-beep/Drohne/main/plaene/aktuell.json"

    private var mission: Mission? = null
    private var missionText: String = ""

    // Statusleiste
    private lateinit var status: TextView
    private lateinit var tileLink: TextView
    private lateinit var tileBattery: TextView
    private lateinit var tileGps: TextView
    private lateinit var tileSensors: TextView
    private lateinit var tileMode: TextView
    // Bildfläche
    private lateinit var video: SurfaceView
    private lateinit var mapView: org.osmdroid.views.MapView
    private lateinit var mapPanel: MapPanel
    private lateinit var segMap: TextView
    private lateinit var segVideo: TextView
    private lateinit var btnCenter: TextView
    private lateinit var overlay: TextView
    private lateinit var videoHint: TextView
    // Was passiert
    private lateinit var missionCard: TextView
    private lateinit var progress: ProgressBar
    private lateinit var nextAction: TextView
    private lateinit var statWaypoint: TextView
    private lateinit var statHeight: TextView
    private lateinit var statSpeed: TextView
    private lateinit var statHome: TextView
    private lateinit var statBattery: TextView
    private lateinit var guardBuildings: TextView
    private lateinit var guardTerrain: TextView
    private lateinit var guardImage: TextView
    // Vorbereiten / Fliegen / Mehr
    private lateinit var planName: TextView
    private lateinit var planSummary: TextView
    private lateinit var checklist: LinearLayout
    private lateinit var btnLoad: Button
    private lateinit var btnStart: Button
    private lateinit var btnFollow: Button
    private lateinit var scroll: ScrollView
    private lateinit var log: TextView
    private val logLines = ArrayDeque<String>()

    private val colorOk by lazy { getColor(R.color.ok) }
    private val colorWarn by lazy { getColor(R.color.warn) }
    private val colorBad by lazy { getColor(R.color.bad) }
    private val colorNeutral by lazy { getColor(R.color.neutral) }
    private val colorText by lazy { getColor(R.color.text) }
    private val colorMuted by lazy { getColor(R.color.textMuted) }

    private val stateListener: (DroneState) -> Unit = { render(it) }
    private val logListener: (String) -> Unit = { line ->
        logLines.addLast(line); while (logLines.size > 6) logLines.removeFirst()
        log.text = logLines.joinToString("\n")
    }
    private val missionListener: (String) -> Unit = { text -> runOnUiThread { missionCard.text = text; renderNow(DroneManager.state) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        MapPanel.configure(applicationContext)
        setContentView(R.layout.activity_main)
        bindViews()
        MissionController.onStateText = missionListener

        ActivityCompat.requestPermissions(this, arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE,
        ), 1)

        CrashLog.takeReport(this)?.let { showCrash(it) }

        setupVideo(video)
        mapPanel = MapPanel(mapView)
        segMap.setOnClickListener { chooseView(map = true, byUser = true) }
        segVideo.setOnClickListener { chooseView(map = false, byUser = true) }
        btnCenter.setOnClickListener { mapPanel.fit() }

        btnLoad.setOnClickListener { loadPlan() }
        findViewById<Button>(R.id.btnPlan).setOnClickListener { planSquare() }
        findViewById<Button>(R.id.btnUpload).setOnClickListener { uploadCurrent() }
        btnStart.setOnClickListener { confirmStart() }
        findViewById<Button>(R.id.btnPause).setOnClickListener { MissionController.pause() }
        findViewById<Button>(R.id.btnResume).setOnClickListener { MissionController.resume() }
        // Langes Drücken auf die Statuszeile kopiert das ganze Protokoll (zum Schicken an Claude).
        status.setOnLongClickListener { copyLog(); true }
        findViewById<Button>(R.id.btnLog).setOnClickListener { showLog() }
        findViewById<View>(R.id.cardLog).setOnClickListener { showLog() }
        log.setOnClickListener { showLog() }
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
        findViewById<Button>(R.id.btnPair).setOnClickListener { showMore() }
        btnFollow.setOnClickListener { askFollow() }
        findViewById<Button>(R.id.btnHome).setOnClickListener {
            FollowController.stop()
            MissionController.stop()
            DroneManager.goHome()
        }
        findViewById<TextView>(R.id.version).text = "App ${BuildConfig.VERSION_NAME} · DJI MSDK 5.20.1 · Karte © OpenStreetMap"
        chooseView(map = true, byUser = false)
        renderChecklist(emptyList(), hasPlan = false)
    }

    private fun bindViews() {
        status = findViewById(R.id.status)
        tileLink = findViewById(R.id.tileLink); tileBattery = findViewById(R.id.tileBattery); tileGps = findViewById(R.id.tileGps)
        tileSensors = findViewById(R.id.tileSensors); tileMode = findViewById(R.id.tileMode)
        video = findViewById(R.id.video); mapView = findViewById(R.id.map)
        segMap = findViewById(R.id.segMap); segVideo = findViewById(R.id.segVideo); btnCenter = findViewById(R.id.btnCenter)
        overlay = findViewById(R.id.overlay); videoHint = findViewById(R.id.videoHint)
        missionCard = findViewById(R.id.missionText); progress = findViewById(R.id.progress); nextAction = findViewById(R.id.nextAction)
        statWaypoint = findViewById(R.id.statWaypoint); statHeight = findViewById(R.id.statHeight); statSpeed = findViewById(R.id.statSpeed)
        statHome = findViewById(R.id.statHome); statBattery = findViewById(R.id.statBattery)
        guardBuildings = findViewById(R.id.guardBuildings); guardTerrain = findViewById(R.id.guardTerrain); guardImage = findViewById(R.id.guardImage)
        planName = findViewById(R.id.planName); planSummary = findViewById(R.id.planSummary); checklist = findViewById(R.id.checklist)
        btnLoad = findViewById(R.id.btnLoad); btnStart = findViewById(R.id.btnStart); btnFollow = findViewById(R.id.btnFollow)
        scroll = findViewById(R.id.scroll); log = findViewById(R.id.log)
    }

    override fun onStart() {
        super.onStart()
        DroneManager.addStateListener(stateListener)
        logLines.clear(); log.text = ""  // Beim Wiederanmelden liefert der Manager die ganze Historie neu
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

    // ---------------------------------------------------------------- Bildfläche: Karte | Video

    /** null = automatisch (Video sobald verbunden, sonst Karte); true/false = von Hand gewählt. */
    private var userWantsMap: Boolean? = null
    private var showingMap = false

    private fun chooseView(map: Boolean, byUser: Boolean) {
        if (byUser) userWantsMap = map
        showingMap = map
        segMap.isSelected = map; segVideo.isSelected = !map
        mapView.visibility = if (map) View.VISIBLE else View.GONE
        btnCenter.visibility = if (map) View.VISIBLE else View.GONE
        // GONE zerstört die Bildfläche; beim Zurückschalten meldet surfaceChanged das Livebild neu an.
        video.visibility = if (map) View.GONE else View.VISIBLE
        videoHint.visibility = if (map) View.GONE else View.VISIBLE
        if (map) { mapPanel.update(DroneManager.state, MissionController.currentMission ?: mission); mapPanel.fit() }
    }

    private var lastConnected: Boolean? = null

    /** Automatik: beim Verbinden auf Video, beim Trennen auf Karte – solange nichts von Hand gewählt wurde. */
    private fun autoView(s: DroneState) {
        if (lastConnected == s.connected) return
        lastConnected = s.connected
        if (userWantsMap == null) chooseView(map = !s.connected, byUser = false)
    }

    // ---------------------------------------------------------------- Anzeige

    private var lastLoc: Triple<Double, Double, Long>? = null
    private var speedMs: Double? = null
    private var lastChecklistMs = 0L

    private fun render(s: DroneState) {
        attachVideo()
        autoView(s)
        renderTiles(s)
        renderOverlay(s)
        renderNow(s)
        if (showingMap) mapPanel.update(s, MissionController.currentMission ?: mission)
        val now = System.currentTimeMillis()
        if (now - lastChecklistMs > 1000) { lastChecklistMs = now; renderChecklist(preflightProblems(s), hasPlan = mission != null) }
        btnFollow.text = if (FollowController.active) "Folgen beenden" else "Folgen"
    }

    private fun tile(v: TextView, label: String, value: String, color: Int) {
        val sb = SpannableStringBuilder()
        sb.append(label, ForegroundColorSpan(0xDDFFFFFF.toInt()), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("\n")
        val start = sb.length
        sb.append(value)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(android.text.style.RelativeSizeSpan(1.35f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        v.text = sb
        v.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun renderTiles(s: DroneState) {
        tile(tileLink, "Drohne", when { s.connected -> "verbunden"; s.rcConnected -> "nur Contr."; else -> "getrennt" },
            when { s.connected -> colorOk; s.rcConnected -> colorWarn; else -> colorBad })
        val b = s.batteryPercent
        tile(tileBattery, "Akku", b?.let { "$it %" } ?: "–", when { b == null -> colorNeutral; b < 25 -> colorBad; b < 40 -> colorWarn; else -> colorOk })
        val sat = s.satellites
        val homeOk = s.home?.latitude != null && s.home.latitude != 0.0
        tile(tileGps, "GPS" + if (homeOk) " ⌂" else "", sat?.let { "$it Sat." } ?: "–",
            when { sat == null -> colorNeutral; sat < minSatellites -> colorWarn; !homeOk -> colorWarn; else -> colorOk })
        val sens = s.sensors
        tile(tileSensors, "Sensoren", when { sens == null -> "–"; sens.startsWith("blind") -> "blind"; else -> sens },
            when { sens == null -> colorNeutral; s.sensorsOk -> colorOk; else -> colorWarn })
        val mode = when { FollowController.active -> "folgt"; s.landing -> "landet"; s.flying -> "fliegt"; s.motorsOn -> "Motoren"; s.connected -> "am Boden"; else -> "–" }
        tile(tileMode, "Status", mode, when { FollowController.active || s.flying || s.motorsOn -> colorWarn; s.connected -> colorOk; else -> colorNeutral })

        val wind = when (s.windWarning) { 2 -> "Wind: STARK"; 1 -> "Wind: mäßig"; 0 -> "Wind: ruhig"; else -> null }
        status.text = listOfNotNull(
            when {
                s.registered -> "App ${BuildConfig.VERSION_NAME} · SDK ok"
                s.registerError != null -> "SDK-FEHLER: ${s.registerError}"
                else -> "App ${BuildConfig.VERSION_NAME} · SDK startet …"
            },
            "Funk " + (if (s.airLinkConnected) "ok" else "–"),
            s.heightLimitM?.let { "Höhenlimit $it m" },
            wind,
        ).joinToString(" · ")
        status.setTextColor(when { s.registerError != null || s.windWarning == 2 -> colorBad; s.windWarning == 1 -> colorWarn; else -> colorMuted })
    }

    private fun renderOverlay(s: DroneState) {
        val loc = s.location?.takeIf { it.latitude != 0.0 }
        videoHint.visibility = if (showingMap || (s.connected && videoAttached)) View.GONE else View.VISIBLE
        videoHint.text = if (s.connected) "Livebild kommt …" else "Kein Livebild – Drohne nicht verbunden"
        if (loc == null) { overlay.text = "Position: –"; speedMs = null; lastLoc = null; return }
        val now = System.currentTimeMillis()
        lastLoc?.let { (la, lo, t) ->
            val dt = (now - t) / 1000.0
            if (dt >= 0.5) { speedMs = Geo.distance(LatLon(la, lo), LatLon(loc.latitude, loc.longitude)) / dt; lastLoc = Triple(loc.latitude, loc.longitude, now) }
        } ?: run { lastLoc = Triple(loc.latitude, loc.longitude, now) }
        val home = s.home?.takeIf { it.latitude != 0.0 }
        val dist = home?.let { Geo.distance(LatLon(it.latitude, it.longitude), LatLon(loc.latitude, loc.longitude)) }
        overlay.text = listOfNotNull(
            String.format(Locale.GERMANY, "↑ %.1f m", loc.altitude ?: 0.0),
            dist?.let { "⌂ ${it.toInt()} m" },
            speedMs?.let { String.format(Locale.GERMANY, "%.1f m/s", it) },
            s.headingDeg?.let { "${it.toInt()}°" },
            String.format(Locale.US, "%.5f, %.5f", loc.latitude, loc.longitude),
        ).joinToString("  ·  ")
    }

    private fun stat(v: TextView, label: String, value: String, color: Int = colorText) {
        val sb = SpannableStringBuilder()
        sb.append(label, ForegroundColorSpan(colorMuted), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("\n")
        val start = sb.length
        sb.append(value)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(android.text.style.RelativeSizeSpan(1.5f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        v.text = sb
    }

    /** „Was passiert“: Missionszustand, Fortschritt, nächste Aktion, Kennzahlen, Wächter. */
    private fun renderNow(s: DroneState) {
        val m = MissionController.currentMission
        val idx = MissionController.currentWaypointIndex
        val n = m?.waypoints?.size ?: 0
        val st = MissionController.executeState
        val running = MissionController.executing || st == WaypointMissionExecuteState.RECOVERING
        if (missionCard.text.isNullOrBlank()) missionCard.text = "Kein Plan geladen"
        progress.visibility = if (m != null) View.VISIBLE else View.GONE
        progress.progress = when {
            st == WaypointMissionExecuteState.FINISHED || st == WaypointMissionExecuteState.RETURN_TO_START_POINT -> 100
            running && n > 0 && idx >= 0 -> ((idx + 1) * 100 / n).coerceIn(0, 100)
            else -> 0
        }
        nextAction.text = "Als Nächstes: " + nextActionText(s, m, idx, st)
        stat(statWaypoint, "Wegpunkt", if (running && idx >= 0 && n > 0) "${idx + 1}/$n" else if (n > 0) "–/$n" else "–")
        val loc = s.location?.takeIf { it.latitude != 0.0 }
        stat(statHeight, "Höhe", loc?.let { String.format(Locale.GERMANY, "%.0f m", it.altitude ?: 0.0) } ?: "–")
        stat(statSpeed, "Tempo", speedMs?.let { String.format(Locale.GERMANY, "%.1f", it) + " m/s" } ?: "–")
        val home = s.home?.takeIf { it.latitude != 0.0 }
        val dist = if (home != null && loc != null) Geo.distance(LatLon(home.latitude, home.longitude), LatLon(loc.latitude, loc.longitude)) else null
        stat(statHome, "Zum Start", dist?.let { "${it.toInt()} m" } ?: "–")
        val b = s.batteryPercent
        stat(statBattery, "Akku", b?.let { "$it %" } ?: "–", when { b == null -> colorText; b < 25 -> colorBad; b < 40 -> colorWarn; else -> colorText })

        // Wächter
        val bc = MissionController.conflicts.size
        tile(guardBuildings, "Gebäude-Wächter", when { m == null -> "–"; bc > 0 -> "$bc Konflikte"; else -> "frei" }, when { m == null -> colorNeutral; bc > 0 -> colorBad; else -> colorOk })
        val tc = MissionController.terrainConflicts.size
        val terrainLoaded = com.felixos.drohne.plan.Terrain.loaded
        tile(guardTerrain, "Oberflächen-Wächter", when { !terrainLoaded -> "kein Modell"; m == null -> "–"; tc > 0 -> "$tc Konflikte"; else -> "frei" },
            when { !terrainLoaded || m == null -> colorNeutral; tc > 0 -> colorBad; else -> colorOk })
        tile(guardImage, "Bildwächter", when { !ObstacleGuard.enabled -> "aus"; ObstacleGuard.armed -> "darf anhalten"; else -> "beobachtet" },
            when { !ObstacleGuard.enabled -> colorNeutral; ObstacleGuard.armed -> colorOk; else -> colorWarn })
    }

    /** Was die Drohne als Nächstes tut – aus Plan, Wegpunkt-Index und Zustand abgeleitet (nur Anzeige). */
    private fun nextActionText(s: DroneState, m: Mission?, idx: Int, st: WaypointMissionExecuteState?): String {
        if (FollowController.active) return "folgt dem Handy"
        if (m == null) return "Plan holen oder Testflug wählen"
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
                    w.hoverSec > 0 -> "schwebt ${w.hoverSec} s an Punkt ${idx + 1}"
                    next == null -> "letzter Punkt – dann Heimflug"
                    next.heightM < w.heightM - 0.5 -> "Abstieg auf ${next.heightM.toInt()} m zu Punkt ${idx + 2}"
                    next.heightM > w.heightM + 0.5 -> "Aufstieg auf ${next.heightM.toInt()} m zu Punkt ${idx + 2}"
                    else -> "weiter zu Punkt ${idx + 2} (${Geo.distance(w.pos, next.pos).toInt()} m)"
                }
            }
        }
    }

    // ---------------------------------------------------------------- Startprüfung als Liste

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

    private fun checkRow(ok: Boolean?, text: String): TextView = TextView(this).apply {
        val sb = SpannableStringBuilder()
        val (sym, col) = when (ok) { true -> "✓" to colorOk; false -> "✗" to colorBad; null -> "–" to colorMuted }
        sb.append("$sym  ", ForegroundColorSpan(col), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(text, ForegroundColorSpan(if (ok == null) colorMuted else colorText), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        this.text = sb
        textSize = 15f
        setPadding(0, 6, 0, 6)
    }

    /** Baut die Prüfliste: grün = in Ordnung, rot = Problem (mit Text), grau = erst mit Plan. */
    private fun fillChecklist(container: LinearLayout, problems: List<String>, hasPlan: Boolean) {
        container.removeAllViews()
        val unmatched = problems.toMutableList()
        checks.forEach { c ->
            val hit = unmatched.filter(c.matches)
            unmatched.removeAll(hit)
            when {
                hit.isNotEmpty() -> hit.forEach { container.addView(checkRow(false, it)) }
                c.needsPlan && !hasPlan -> container.addView(checkRow(null, c.label))
                else -> container.addView(checkRow(true, c.label))
            }
        }
        unmatched.forEach { container.addView(checkRow(false, it)) }
    }

    private fun renderChecklist(problems: List<String>, hasPlan: Boolean) {
        fillChecklist(checklist, problems, hasPlan)
        btnStart.alpha = if (problems.isEmpty() && hasPlan) 1f else 0.55f
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
        val items = arrayOf("Plan in Google Maps zeigen", "Fotos holen und hochladen", "GitHub-Token eintragen", "Landelampe an", "Landelampe auto (aus)", "Fernbedienung neu koppeln",
            "Folgen: Achsen tauschen (${if (swap) "an" else "aus"})",
            "Bildwächter darf Mission anhalten (${if (ObstacleGuard.armed) "an" else "nur beobachten"})",
            "Bildwächter ${if (ObstacleGuard.enabled) "ausschalten" else "einschalten"}")
        AlertDialog.Builder(this).setTitle("Mehr").setItems(items) { _, which ->
            when (which) {
                0 -> showOnMap()
                1 -> FlightReport.collectAndUpload(applicationContext)
                2 -> askToken()
                3 -> DroneManager.setBottomLight("an")
                4 -> DroneManager.setBottomLight("auto")
                5 -> AlertDialog.Builder(this).setTitle("Neu koppeln?")
                    .setMessage("Nur nötig, wenn die Fernbedienung die Drohne nicht findet. Die Fernbedienung piept dann, bis du den Einschaltknopf der Drohne ca. 4 s hältst.")
                    .setPositiveButton("Koppeln") { _, _ -> DroneManager.requestPairing() }
                    .setNegativeButton("Abbrechen", null).show()
                6 -> { FollowController.setSwapAxes(this, !swap); DroneManager.log("Folgen: Achsen tauschen ${if (!swap) "an" else "aus"}") }
                7 -> ObstacleGuard.setArmed(this, !ObstacleGuard.armed)
                8 -> ObstacleGuard.setEnabled(this, !ObstacleGuard.enabled)
            }
            renderNow(DroneManager.state)
        }.show()
    }

    /** Wegpunkte des aktuellen Plans in Google Maps öffnen (als Fußweg-Route, zeigt die Lage auf dem Satellitenbild). */
    private fun showOnMap() {
        val m = mission ?: return DroneManager.log("Kein Plan: erst Testflug oder Plan holen")
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

    private fun copyLog() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Protokoll", status.text.toString() + "\n\n" + DroneManager.logText()))
        android.widget.Toast.makeText(this, "Protokoll kopiert – an Claude schicken", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Ganzes Protokoll groß anzeigen, mit Kopieren-Knopf. */
    private fun showLog() {
        val text = status.text.toString() + "\n\n" + DroneManager.logText()
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
            s.heightLimitM?.let { if (it < top - 0.5) add("DJI-Höhenlimit $it m liegt unter der Flughöhe ${top.toInt()} m: die Drohne würde tiefer fliegen als geplant. Limit in DJI Fly anheben.") }
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

    private fun setPlanCard(name: String, text: String) { planName.text = name; planSummary.text = text }

    private fun loadPlan() {
        DroneManager.log("Lade Flugplan aus der Cloud …")
        btnLoad.isEnabled = false
        setPlanCard("Plan holen …", "Hole plaene/aktuell.json aus der Cloud …")
        Thread {
            try {
                val url = java.net.URL(planUrl + "?t=" + System.currentTimeMillis())
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 10_000; readTimeout = 10_000; useCaches = false
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val (m, beschreibung) = PlanJson.parse(text)
                runOnUiThread { btnLoad.isEnabled = true; useMission(m, beschreibung) }
            } catch (t: Throwable) {
                DroneManager.log("Plan laden fehlgeschlagen: ${t.javaClass.simpleName}: ${t.message}")
                runOnUiThread { btnLoad.isEnabled = true; setPlanCard("Plan holen fehlgeschlagen", "${t.javaClass.simpleName}: ${t.message}\nInternet prüfen, dann nochmal „Plan holen & auf Drohne“.") }
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
    private fun useMission(m: Mission, beschreibung: String) {
        val text = summary(m, beschreibung)
        if (m.name.startsWith("beispiel")) {
            setPlanCard("Beispielplan „${m.name}“ (nur Ansicht)", text + "\n\nWird nicht geflogen – Claude muss erst einen echten Plan ablegen.")
            DroneManager.log("Plan laden funktioniert (Beispiel, wird nicht geflogen)")
            return
        }
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
        uploadCurrent()
    }

    /** Plan (nochmal) auf die Drohne laden; Ergebnis im Plan-Feld. */
    private fun uploadCurrent() {
        val m = mission ?: return DroneManager.log("Kein Plan: erst „Plan holen & auf Drohne“ oder Testflug")
        val base = planSummary.text.toString().substringBefore("\n\nAuf der Drohne").substringBefore("\n\nUpload fehlgeschlagen")
        MissionController.upload { ok ->
            runOnUiThread {
                setPlanCard(if (ok) "Plan „${m.name}“ – auf der Drohne" else "Plan „${m.name}“ – Upload fehlgeschlagen",
                    base + if (ok) "\n\nAuf der Drohne. Startprüfung unten, dann „Mission starten“." else "\n\nUpload fehlgeschlagen – Drohne verbunden? „Nochmal auf Drohne“ versuchen.")
                renderChecklist(preflightProblems(DroneManager.state), hasPlan = true)
            }
        }
    }

    private fun confirmStart() {
        val problems = preflightProblems(DroneManager.state)
        renderChecklist(problems, hasPlan = mission != null)
        if (problems.isNotEmpty()) {
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 8) }
            fillChecklist(list, problems, hasPlan = mission != null)
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
