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
    private lateinit var logScroll: ScrollView

    private val stateListener: (DroneState) -> Unit = { render(it) }
    private val logListener: (String) -> Unit = { line ->
        log.append(line + "\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)

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
        findViewById<Button>(R.id.btnHome).setOnClickListener {
            MissionController.stop()
            DroneManager.goHome()
        }
    }

    override fun onStart() {
        super.onStart()
        DroneManager.addStateListener(stateListener)
        DroneManager.addLogListener(logListener)
    }

    override fun onStop() {
        DroneManager.removeStateListener(stateListener)
        DroneManager.removeLogListener(logListener)
        super.onStop()
    }

    private fun showCrash(report: String) {
        AlertDialog.Builder(this).setTitle("Letzter Absturz")
            .setMessage(report.take(3000))
            .setPositiveButton("Kopieren") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Absturz", report))
                android.widget.Toast.makeText(this, "Kopiert – bitte an Claude schicken", android.widget.Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Schließen", null).show()
    }

    private fun setupVideo(view: SurfaceView) {
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                try {
                    MediaDataCenter.getInstance().cameraStreamManager.putCameraStreamSurface(
                        ComponentIndexType.LEFT_OR_MAIN, holder.surface, width, height,
                        ICameraStreamManager.ScaleType.CENTER_INSIDE)
                } catch (t: Throwable) {
                    DroneManager.log("Live-Bild noch nicht bereit: ${t.message}")
                }
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                try { MediaDataCenter.getInstance().cameraStreamManager.removeCameraStreamSurface(holder.surface) } catch (_: Throwable) {}
            }
        })
    }

    private fun render(s: DroneState) {
        val loc = s.location
        status.text = buildString {
            appendLine("SDK:     " + when {
                s.registered -> "registriert"
                s.registerError != null -> "FEHLER: ${s.registerError}"
                else -> "startet …"
            })
            appendLine("Contr.:  " + (if (s.rcConnected) "verbunden" else "nicht verbunden") + "  Funk: " + (if (s.airLinkConnected) "ok" else "kein Signal"))
            appendLine("Drohne:  " + (if (s.connected) "verbunden" else "nicht verbunden") + (s.productType?.let { " ($it)" } ?: ""))
            appendLine("Akku:    " + (s.batteryPercent?.let { "$it %" } ?: "–"))
            appendLine("GPS:     " + (s.satellites?.let { "$it Satelliten" } ?: "–"))
            if (loc?.latitude != null && loc.latitude != 0.0) {
                appendLine(String.format(Locale.US, "Pos:     %.6f, %.6f", loc.latitude, loc.longitude))
                appendLine(String.format(Locale.US, "Höhe:    %.1f m", loc.altitude ?: 0.0))
            }
            appendLine("Home:    " + if (s.home?.latitude != null && s.home.latitude != 0.0) "gesetzt" else "–")
            append("Status:  " + when { s.flying -> "fliegt"; s.motorsOn -> "Motoren an"; else -> "am Boden" })
        }
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
        val loc = DroneManager.state.location
        if (loc?.latitude == null || loc.latitude == 0.0) {
            DroneManager.log("Keine Drohnenposition: GPS abwarten")
            return
        }
        val here = LatLon(loc.latitude, loc.longitude)
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
            .setPositiveButton("Übernehmen") { _, _ ->
                if (MissionController.prepare(m)) {
                    mission = m
                    missionText = text
                    DroneManager.log("Plan übernommen, jetzt „Hochladen“")
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
