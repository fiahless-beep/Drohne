package com.felixos.drohne

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Standbilder aus dem Livebild (Felix 14.11.: „Bau das so ein, dass du die Videodaten auch bekommst“, für die Manöverkritik):
 * Im Flug alle paar Sekunden ein JPEG in den Flugordner (bilder/), dazu je Bild eine Zeile in bilder/index.txt mit
 * Position, Höhe, Blickrichtung, Kameraneigung und Wegpunkt. Der Live-Draht lädt sie hoch (im Flug das jeweils neueste,
 * nach der Landung alle). Das 4K-Video bleibt auf der SD-Karte der Drohne.
 *
 * Das Repo ist öffentlich: rund um gemerkte Startplätze (Zuhause) werden keine Bilder gespeichert.
 */
object FrameShots {
    private const val INTERVAL_MS = 4000L
    private const val MAX_WIDTH = 1280
    private const val PRIVATE_RADIUS_M = 400.0
    private const val KEY_ON = "frame_shots"

    private lateinit var app: Context
    @Volatile var enabled = true; private set
    private var listening = false
    @Volatile private var lastShotMs = 0L
    @Volatile private var working = false
    private var announcedDir: String? = null

    private val listener = ICameraStreamManager.CameraFrameListener { data, offset, _, width, height, _ ->
        try { onFrame(data, offset, width, height) } catch (_: Throwable) {}
    }

    fun init(c: Context) {
        app = c.applicationContext
        enabled = app.getSharedPreferences("drohne", Context.MODE_PRIVATE).getBoolean(KEY_ON, true)
        DroneManager.addStateListener { s -> if (s.connected && s.registered) start() }
    }

    fun setEnabled(c: Context, on: Boolean) {
        enabled = on
        c.getSharedPreferences("drohne", Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        DroneManager.log(if (on) "Flight stills on: a still every 4 s in flight, sent to Claude" else "Flight stills OFF")
    }

    private fun start() {
        if (listening) return
        try {
            MediaDataCenter.getInstance().cameraStreamManager.addFrameListener(ComponentIndexType.LEFT_OR_MAIN, ICameraStreamManager.FrameFormat.NV21, listener)
            listening = true
        } catch (t: Throwable) { DroneManager.log("Flight stills: no access to the live picture (${t.message})") }
    }

    private fun onFrame(data: ByteArray, offset: Int, width: Int, height: Int) {
        if (!enabled || working || width < 64 || height < 36) return
        val now = System.currentTimeMillis()
        if (now - lastShotMs < INTERVAL_MS) return
        val s = DroneManager.state
        if (!s.flying) return
        val dir = FlightReport.currentFlightDir() ?: return
        val l = s.location ?: return
        val size = width * height * 3 / 2
        if (data.size - offset < size) return
        val here = com.felixos.drohne.plan.LatLon(l.latitude, l.longitude)
        val private = try { StartPlaces.all(app).any { com.felixos.drohne.plan.Geo.distance(it.pos, here) <= PRIVATE_RADIUS_M } } catch (_: Throwable) { false }
        if (private) return
        lastShotMs = now
        working = true
        val copy = data.copyOfRange(offset, offset + size)   // der Puffer gehört dem SDK und wird wiederverwendet
        val line = indexLine(now, s)
        Thread({
            try {
                val out = File(dir, "bilder").apply { mkdirs() }
                val name = "b_" + SimpleDateFormat("HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now)) + ".jpg"
                File(out, name).writeBytes(jpeg(copy, width, height))
                File(out, "index.txt").appendText("$name $line\n")
                if (announcedDir != dir.name) { announcedDir = dir.name; DroneManager.log("Flight stills: saving a still every ${INTERVAL_MS / 1000} s (${width}x$height)") }
            } catch (t: Throwable) {
                if (announcedDir != "!" + dir.name) { announcedDir = "!" + dir.name; DroneManager.log("Flight stills: could not save (${t.javaClass.simpleName}: ${t.message})") }
            } finally { working = false }
        }, "FrameShot").start()
    }

    /** Alles, was die Manöverkritik zu einem Bild wissen muss. */
    internal fun indexLine(nowMs: Long, s: DroneState): String {
        val l = s.location
        val utc = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(nowMs))
        fun d(v: Double?, f: String) = v?.let { String.format(Locale.US, f, it) } ?: "?"
        return "utc=$utc pos=${d(l?.latitude, "%.6f")},${d(l?.longitude, "%.6f")} h=${d(l?.altitude, "%.1f")} kurs=${d(s.headingDeg, "%.0f")} neigung=${d(s.gimbalPitchDeg, "%.0f")}" +
            " wp=${MissionController.currentWaypointIndex} mission=${MissionController.executeState ?: "-"} plan=${MissionController.currentMission?.name ?: "-"}"
    }

    private fun jpeg(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val full = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), if (width > MAX_WIDTH) 90 else 72, full)
        if (width <= MAX_WIDTH) return full.toByteArray()
        // Größere Livebilder (1080p) halbieren: 960 px reichen für die Kritik und sparen Upload-Zeit am Hotspot.
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bmp = BitmapFactory.decodeByteArray(full.toByteArray(), 0, full.size(), opts) ?: return full.toByteArray()
        val small = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, small)
        bmp.recycle()
        return small.toByteArray()
    }

    // ---- Upload (vom Live-Draht aufgerufen, läuft auf dessen Thread) ----

    private fun sentFile(dir: File) = File(dir, "hochgeladen.txt")
    private fun sentNames(dir: File): Set<String> = try { sentFile(dir).readLines().toSet() } catch (_: Throwable) { emptySet() }

    /**
     * Lädt Bilder des laufenden bzw. letzten Flugs hoch: im Flug nur das neueste (damit Claude mitsehen kann),
     * am Boden bis zu [max] noch fehlende je Aufruf, älteste zuerst, danach die Indexdatei. Gibt die Zahl der hochgeladenen Bilder zurück.
     */
    fun uploadPending(token: String, flying: Boolean, max: Int): Int {
        if (!enabled) return 0
        val flight = FlightReport.currentFlightDir() ?: return 0
        val dir = File(flight, "bilder")
        val all = dir.listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name } ?: return 0
        val sent = sentNames(dir)
        val todo = all.filter { it.name !in sent }
        if (todo.isEmpty()) return 0
        val batch = if (flying) listOf(todo.last()) else todo.take(max)
        var n = 0
        for (f in batch) {
            if (!FlightReport.uploadFile(token, "fluege/${flight.name}/bilder/${f.name}", f, quiet = true)) break
            try { sentFile(dir).appendText(f.name + "\n") } catch (_: Throwable) {}
            n++
        }
        val left = todo.size - n
        if (n > 0 && (flying || left == 0)) {
            FlightReport.uploadFile(token, "fluege/${flight.name}/bilder/index.txt", File(dir, "index.txt"), quiet = true)
            if (!flying) DroneManager.log("Flight stills: all ${all.size} sent to Claude")
        }
        return n
    }
}
