package com.felixos.drohne

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Direkter Draht zu Claude (Felix 13.11.: „bau es so, dass du selber debuggen kannst, dass ich nicht immer sagen muss,
 * jetzt Data schicken“): Die App schreibt von der ersten Sekunde an ein Sitzungsprotokoll (jede Protokollzeile plus
 * alle paar Sekunden eine Zustandszeile mit Position und Startpunkt) und lädt es von selbst ins Repo, ohne Knopf.
 * Im Flug wird stattdessen das Flugprotokoll (mit Flugdaten alle 2 s) laufend hochgeladen.
 * Außerdem meldet sie, wenn in der Cloud ein neuer Plan liegt.
 *
 * Braucht das GitHub-Token (Settings → Data) und Netz am Handy; ohne beides bleibt alles lokal und der Flug unberührt.
 */
object LiveLink {
    private const val PLAN_API = "https://api.github.com/repos/fiahless-beep/Drohne/contents/plaene/aktuell.json?ref=main"
    private const val GROUND_UPLOAD_MS = 30_000L
    private const val FLIGHT_UPLOAD_MS = 15_000L
    private const val STATUS_GROUND_MS = 30_000L
    private const val STATUS_FLIGHT_MS = 10_000L
    private const val PLAN_CHECK_MS = 20_000L
    private const val PRIVATE_RADIUS_M = 400.0

    private lateinit var app: Context
    private var session: File? = null
    private var started = false
    @Volatile var enabled = true
    /** Zuletzt hochgeladene Länge je Repo-Pfad. */
    private val sent = HashMap<String, Long>()
    private var lastUploadMs = 0L
    private var lastStatusMs = 0L
    private var lastStatusKey = ""
    private var lastPlanCheckMs = 0L
    private var planEtag: String? = null
    private var announced = false
    private var failures = 0
    /** Name des neuesten Plans in der Cloud, wenn er sich seit dem Start der App geändert hat (für die Anzeige). */
    @Volatile var newPlanName: String? = null; private set
    fun planLoaded() { newPlanName = null }

    val sessionName: String? get() = session?.name

    fun init(c: Context) {
        if (started) return
        started = true
        app = c.applicationContext
        enabled = app.getSharedPreferences("drohne", Context.MODE_PRIVATE).getBoolean("live_link", true)
        val dir = File(app.getExternalFilesDir(null), "sitzungen").apply { mkdirs() }
        // Alte Sitzungen aufräumen (die letzten 20 bleiben auf dem Handy).
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val f = File(dir, "${stamp}Z.txt")
        session = f
        try { f.writeText("Sitzung $stamp UTC · App ${BuildConfig.VERSION_NAME} · ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} · Handy-Zeitzone ${TimeZone.getDefault().id}\n") } catch (_: Throwable) {}
        DroneManager.addLogListener { line -> try { f.appendText(line + "\n") } catch (_: Throwable) {} }
        Thread({ while (true) { try { tick() } catch (_: Throwable) {}; try { Thread.sleep(5000) } catch (_: InterruptedException) {} } }, "LiveLink").apply { isDaemon = true; start() }
    }

    fun setEnabled(c: Context, on: Boolean) {
        enabled = on
        c.getSharedPreferences("drohne", Context.MODE_PRIVATE).edit().putBoolean("live_link", on).apply()
        DroneManager.log(if (on) "Live link on: the app sends its log to Claude by itself" else "Live link OFF: nothing is sent by itself")
    }

    /** Eine Zustandszeile fürs Sitzungsprotokoll: alles, was Claude zum Planen und Debuggen braucht. */
    internal fun statusLine(s: DroneState, nowMs: Long): String {
        val l = s.location
        val utc = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(nowMs))
        // Das Repo ist öffentlich: rund um gemerkte Startplätze (Zuhause) keine Koordinaten, nur der Name.
        val places = try { StartPlaces.all(app) } catch (_: Throwable) { emptyList() }
        fun p(lat: Double?, lon: Double?): String {
            if (lat == null || lon == null || lat == 0.0) return "?"
            val here = com.felixos.drohne.plan.LatLon(lat, lon)
            places.firstOrNull { com.felixos.drohne.plan.Geo.distance(it.pos, here) <= PRIVATE_RADIUS_M }?.let { return "bei:" + it.name.replace(' ', '_') }
            return String.format(Locale.US, "%.6f,%.6f", lat, lon)
        }
        return "S utc=$utc verbunden=${s.connected} fliegt=${s.flying} pos=${p(l?.latitude, l?.longitude)} h=${l?.altitude?.let { String.format(Locale.US, "%.1f", it) } ?: "?"}" +
            " start=${p(s.home?.latitude, s.home?.longitude)} akku=${s.batteryPercent ?: "?"} sat=${s.satellites ?: "?"} sensoren=${s.sensors ?: "?"}" +
            " hindernis=${DroneManager.obstacleSummary()} mission=${MissionController.executeState ?: "-"} wp=${MissionController.currentWaypointIndex}" +
            " plan=${MissionController.currentMission?.name ?: "-"} wind=${s.windWarning ?: "?"}"
    }

    private fun tick() {
        val f = session ?: return
        val now = System.currentTimeMillis()
        val s = DroneManager.state
        // Zustandszeile: im Flug alle 10 s, am Boden alle 30 s und sofort, wenn sich etwas Wesentliches ändert.
        val key = "${s.connected}/${s.flying}/${s.home?.latitude}/${s.sensors}/${MissionController.executeState}/${MissionController.currentMission?.name}"
        val due = now - lastStatusMs >= (if (s.flying) STATUS_FLIGHT_MS else STATUS_GROUND_MS)
        if ((s.connected && due) || key != lastStatusKey) {
            lastStatusMs = now; lastStatusKey = key
            try { f.appendText(SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date(now)) + "  " + statusLine(s, now) + "\n") } catch (_: Throwable) {}
        }
        if (!enabled) return
        val token = FlightReport.token(app) ?: return
        if (now - lastPlanCheckMs >= PLAN_CHECK_MS) { lastPlanCheckMs = now; checkPlan(token) }
        if (now - lastUploadMs < (if (s.flying) FLIGHT_UPLOAD_MS else GROUND_UPLOAD_MS)) return
        lastUploadMs = now
        // Im Flug das Flugprotokoll (enthält alle Zeilen plus Flugdaten), sonst das Sitzungsprotokoll; nach der Landung beide einmal.
        val flight = FlightReport.currentFlightFile()
        val targets = buildList {
            if (flight != null) add("fluege/${flight.parentFile?.name}/protokoll.txt" to flight)
            if (!s.flying || flight == null) add("fluege/live/${f.name}" to f)
        }
        for ((path, file) in targets) {
            val len = file.length()
            if (len == 0L || sent[path] == len) continue
            val ok = try { FlightReport.uploadFile(token, path, file, quiet = true) } catch (_: Throwable) { false }
            if (ok) {
                sent[path] = len; failures = 0
                if (!announced) { announced = true; DroneManager.log("Live link: Claude reads along (fluege/live/${f.name})") }
            } else if (++failures == 3) DroneManager.log("Live link: no upload right now (no network?). The flight is not affected; the log stays on the phone.")
        }
    }

    /** Liegt in der Cloud ein anderer Plan als beim letzten Blick? Bedingte Abfrage (304 kostet nichts). */
    private fun checkPlan(token: String) {
        try {
            val c = (URL(PLAN_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000; readTimeout = 6000; useCaches = false
                setRequestProperty("Accept", "application/vnd.github.raw")
                setRequestProperty("Authorization", "Bearer $token")
                planEtag?.let { setRequestProperty("If-None-Match", it) }
            }
            if (c.responseCode != 200) { c.disconnect(); return }
            val etag = c.getHeaderField("ETag")
            val text = c.inputStream.bufferedReader().use { it.readText() }
            val first = planEtag == null
            planEtag = etag
            val name = planName(text) ?: return
            if (first) return
            newPlanName = name
            DroneManager.log("New plan in the cloud: $name. Tap the plan name → Get plan to load it.")
        } catch (_: Throwable) {}
    }

    internal fun planName(json: String): String? = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
}
