package com.felixos.drohne

import android.Manifest
import android.app.AlertDialog
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.felixos.drohne.plan.Budget
import com.felixos.drohne.plan.LatLon
import com.felixos.drohne.plan.Mission
import com.felixos.drohne.plan.Missions
import com.felixos.drohne.plan.PlanJson
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import java.util.Locale

class MainActivity : AppCompatActivity() {

    /** Mindestwerte vor dem Start. Felix: sicher mit 15–20 % zurück (siehe Budget). */
    private val minSatellites = 10

    /** Vom Cloud-Planer erzeugter Plan (öffentliches Repo, daher ohne Login lesbar). */
    private val planUrl = "https://raw.githubusercontent.com/fiahless-beep/Drohne/main/plaene/aktuell.json"

    private var mission: Mission? = null
    private var missionText: String = ""

    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var missionCard: TextView
    private lateinit var overlay: TextView
    private lateinit var videoHint: TextView
    private val logLines = ArrayDeque<String>()

    private val stateListener: (DroneState) -> Unit = { render(it) }
    private val logListener: (String) -> Unit = { line ->
        logLines.addLast(line); while (logLines.size > 6) logLines.removeFirst()
        log.text = logLines.joinToString("\n")
    }
    private val missionListener: (String) -> Unit = { text -> runOnUiThread { missionCard.text = text } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)
        missionCard = findViewById(R.id.missionText)
        overlay = findViewById(R.id.overlay)
        videoHint = findViewById(R.id.videoHint)
        MissionController.onStateText = missionListener

        ActivityCompat.requestPermissions(this, arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE,
        ), 1)

        CrashLog.takeReport(this)?.let { showCrash(it) }

        setupVideo(findViewById(R.id.video))

        findViewById<Button>(R.id.btnPlan).setOnClickListener { planSquare() }
        findViewById<Button>(R.id.btnLoad).setOnClickListener { loadPlan() }
        findViewById<Button>(R.id.btnUpload).setOnClickListener { MissionController.upload { } }
        findViewById<Button>(R.id.btnStart).setOnClickListener { confirmStart() }
        findViewById<Button>(R.id.btnPause).setOnClickListener { MissionController.pause() }
        findViewById<Button>(R.id.btnResume).setOnClickListener { MissionController.resume() }
        // Langes Drücken auf das Statusfeld kopiert das ganze Protokoll (zum Schicken an Claude).
        status.setOnLongClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Protokoll", status.text.toString() + "\n\n" + log.text))
            android.widget.Toast.makeText(this, "Protokoll kopiert", android.widget.Toast.LENGTH_SHORT).show()
            true
        }
        findViewById<Button>(R.id.btnLog).setOnClickListener { showLog() }
        FlightReport.init(applicationContext)
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
        findViewById<Button>(R.id.btnFollow).setOnClickListener { askFollow() }
        findViewById<Button>(R.id.btnHome).setOnClickListener {
            FollowController.stop()
            MissionController.stop()
            DroneManager.goHome()
        }
    }

    override fun onStart() {
        super.onStart()
        DroneManager.addStateListener(stateListener)
        logLines.clear(); log.text = ""  // Beim Wiederanmelden liefert der Manager die ganze Historie neu
        DroneManager.addLogListener(logListener)
    }

    override fun onStop() {
        DroneManager.removeStateListener(stateListener)
        DroneManager.removeLogListener(logListener)
        super.onStop()
    }

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
            FollowController.stop(); return
        }
        val layout = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        val h = android.widget.EditText(this).apply { hint = "Höhe m"; setText("20"); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val d = android.widget.EditText(this).apply { hint = "Abstand m"; setText("10"); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        layout.addView(h); layout.addView(d)
        AlertDialog.Builder(this).setTitle("Folgen (Handy = Ziel)")
            .setMessage("Die Drohne folgt dem GPS des Handys: hält Abstand und Höhe, Kamera aufs Handy. Steht sie am Boden, startet sie selbst. Beenden: „Folgen“ erneut, Abbrechen oder Heimflug. Die Sticks greifen jederzeit ein. Max. 3 m/s, erster Test langsam gehen.")
            .setView(layout)
            .setPositiveButton("Folgen") { _, _ ->
                val hm = (h.text.toString().toDoubleOrNull() ?: 20.0).coerceIn(5.0, 60.0)
                val dm = (d.text.toString().toDoubleOrNull() ?: 10.0).coerceIn(5.0, 50.0)
                FollowController.start(applicationContext, FollowController.Settings(hm, dm))
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    /** Mehr-Menü: Fotos holen, Token, Koppeln. */
    private fun showMore() {
        val swap = FollowController.swapAxes(this)
        val items = arrayOf("Fotos holen und hochladen", "GitHub-Token eintragen", "Landelampe an", "Landelampe auto (aus)", "Fernbedienung neu koppeln",
            "Folgen: Achsen tauschen (${if (swap) "an" else "aus"})")
        AlertDialog.Builder(this).setTitle("Mehr").setItems(items) { _, which ->
            when (which) {
                0 -> FlightReport.collectAndUpload(applicationContext)
                1 -> askToken()
                2 -> DroneManager.setBottomLight("an")
                3 -> DroneManager.setBottomLight("auto")
                4 -> AlertDialog.Builder(this).setTitle("Neu koppeln?")
                    .setMessage("Nur nötig, wenn die Fernbedienung die Drohne nicht findet. Die Fernbedienung piept dann, bis du den Einschaltknopf der Drohne ca. 4 s hältst.")
                    .setPositiveButton("Koppeln") { _, _ -> DroneManager.requestPairing() }
                    .setNegativeButton("Abbrechen", null).show()
                5 -> { FollowController.setSwapAxes(this, !swap); DroneManager.log("Folgen: Achsen tauschen ${if (!swap) "an" else "aus"}") }
            }
        }.show()
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

    /** Ganzes Protokoll groß anzeigen, mit Kopieren-Knopf. */
    private fun showLog() {
        val text = status.text.toString() + "\n\n" + DroneManager.logText()
        AlertDialog.Builder(this).setTitle("Protokoll")
            .setMessage(text.takeLast(6000))
            .setPositiveButton("Kopieren") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Protokoll", text))
                android.widget.Toast.makeText(this, "Kopiert – an Claude schicken", android.widget.Toast.LENGTH_SHORT).show()
            }
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

    private fun render(s: DroneState) {
        attachVideo()
        val loc = s.location
        val green = 0xFF2E7D32.toInt(); val red = 0xFFC62828.toInt(); val amber = 0xFFF9A825.toInt(); val grey = 0xFF37474F.toInt()
        fun chip(id: Int, text: String, color: Int) = findViewById<TextView>(id).apply { this.text = text; backgroundTintList = android.content.res.ColorStateList.valueOf(color) }
        chip(R.id.chipLink, "Drohne\n" + when { s.connected -> "verbunden"; s.rcConnected -> "Contr. ok"; else -> "getrennt" },
            when { s.connected -> green; s.rcConnected -> amber; else -> red })
        val b = s.batteryPercent
        chip(R.id.chipBattery, "Akku\n" + (b?.let { "$it %" } ?: "–"), when { b == null -> grey; b < 25 -> red; b < 40 -> amber; else -> green })
        val sat = s.satellites
        val homeOk = s.home?.latitude != null && s.home.latitude != 0.0
        chip(R.id.chipGps, "GPS\n" + (sat?.let { "$it Sat." } ?: "–") + if (homeOk) " ⌂" else "",
            when { sat == null -> grey; sat < minSatellites -> amber; !homeOk -> amber; else -> green })
        val mode = when { FollowController.active -> "folgt"; s.flying -> "fliegt"; s.motorsOn -> "Motoren an"; s.connected -> "am Boden"; else -> "–" }
        chip(R.id.chipMode, "Status\n$mode", when { FollowController.active || s.flying -> amber; s.connected -> green; else -> grey })
        status.text = buildString {
            append(when {
                s.registered -> "App ${BuildConfig.VERSION_NAME} · SDK ok"
                s.registerError != null -> "SDK-FEHLER: ${s.registerError}"
                else -> "SDK startet …"
            })
            append(" · Funk " + (if (s.airLinkConnected) "ok" else "–"))
            s.productType?.let { append(" · $it") }
        }
        overlay.text = if (loc?.latitude != null && loc.latitude != 0.0)
            String.format(Locale.US, "%.1f m · %.5f, %.5f", loc.altitude ?: 0.0, loc.latitude, loc.longitude) else ""
        videoHint.visibility = if (s.connected && videoAttached) android.view.View.GONE else android.view.View.VISIBLE
        videoHint.text = if (s.connected) "Livebild kommt …" else "Kein Livebild – Drohne nicht verbunden"
    }

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
            if (est.maxDistanceFromHomeM > 500) add("Ein Punkt ist ${est.maxDistanceFromHomeM.toInt()} m vom Startpunkt entfernt (max. 500 m)")
        }
        if ((s.satellites ?: 0) < minSatellites) add("Zu wenig GPS (mind. $minSatellites Satelliten)")
        if (s.home?.latitude == null || s.home.latitude == 0.0) add("Startpunkt (Home) noch nicht gesetzt")
    }

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

    private fun loadPlan() {
        DroneManager.log("Lade Flugplan aus der Cloud …")
        Thread {
            try {
                val url = java.net.URL(planUrl + "?t=" + System.currentTimeMillis())
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 10_000; readTimeout = 10_000; useCaches = false
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val (m, beschreibung) = PlanJson.parse(text)
                runOnUiThread { useMission(m, beschreibung) }
            } catch (t: Throwable) {
                DroneManager.log("Plan laden fehlgeschlagen: ${t.javaClass.simpleName}: ${t.message}")
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
        if (m.light) appendLine("Landelampe an")
        append("Funkabriss: " + if (m.rcLost == com.felixos.drohne.plan.RcLost.CONTINUE) "zu Ende fliegen, dann heim" else "sofort heim")
    }

    private fun useMission(m: Mission, beschreibung: String) {
        val text = summary(m, beschreibung)
        if (m.name.startsWith("beispiel")) {
            AlertDialog.Builder(this).setTitle("Beispielplan (nur Ansicht)").setMessage(text)
                .setPositiveButton("OK", null).show()
            DroneManager.log("Plan laden funktioniert (Beispiel, wird nicht geflogen)")
            return
        }
        AlertDialog.Builder(this).setTitle("Plan „${m.name}“ übernehmen?")
            .setMessage(text)
            .setPositiveButton("Übernehmen + auf Drohne") { _, _ ->
                if (MissionController.prepare(m)) {
                    mission = m
                    missionText = text
                    DroneManager.log("Plan übernommen, lade auf die Drohne …")
                    MissionController.upload { }
                }
            }
            .setNegativeButton("Verwerfen", null).show()
    }

    private fun confirmStart() {
        val problems = preflightProblems(DroneManager.state)
        if (problems.isNotEmpty()) {
            AlertDialog.Builder(this).setTitle("Start nicht möglich")
                .setMessage(problems.joinToString("\n• ", "• ")).setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Mission starten?")
            .setMessage(missionText + "\n\nUmgebung frei? Daumen an den Sticks.")
            .setPositiveButton("Start") { _, _ -> MissionController.start() }
            .setNegativeButton("Abbrechen", null).show()
    }
}
