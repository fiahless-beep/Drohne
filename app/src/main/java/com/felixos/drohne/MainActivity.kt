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
import com.felixos.drohne.plan.LatLon
import com.felixos.drohne.plan.Missions
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import java.util.Locale

class MainActivity : AppCompatActivity() {

    /** Mindestwerte vor dem Start. Felix: sicher mit 15–20 % zurück. */
    private val minBatteryForStart = 60
    private val minSatellites = 10

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
            appendLine("Drohne:  " + if (s.connected) "verbunden" else "nicht verbunden")
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
        if ((s.batteryPercent ?: 0) < minBatteryForStart) add("Akku unter $minBatteryForStart %")
        if ((s.satellites ?: 0) < minSatellites) add("Zu wenig GPS (mind. $minSatellites Satelliten)")
        if (s.home?.latitude == null || s.home.latitude == 0.0) add("Startpunkt (Home) noch nicht gesetzt")
    }

    private fun planSquare() {
        val loc = DroneManager.state.location
        if (loc?.latitude == null || loc.latitude == 0.0) {
            DroneManager.log("Keine Drohnenposition: GPS abwarten")
            return
        }
        val m = Missions.testSquare(LatLon(loc.latitude, loc.longitude), sideM = 30.0, heightM = 30.0)
        MissionController.prepare(m)
    }

    private fun confirmStart() {
        val problems = preflightProblems(DroneManager.state)
        if (problems.isNotEmpty()) {
            AlertDialog.Builder(this).setTitle("Start nicht möglich")
                .setMessage(problems.joinToString("\n• ", "• ")).setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Mission starten?")
            .setMessage("Testquadrat 30 × 30 m in 30 m Höhe, 4 Fotos, danach Rückkehr zum Startpunkt.\nUmgebung frei? Daumen an den Sticks.")
            .setPositiveButton("Start") { _, _ -> MissionController.start() }
            .setNegativeButton("Abbrechen", null).show()
    }
}
