package com.felixos.drohne

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.felixos.drohne.CockpitZustand.Hauptaktion
import com.felixos.drohne.plan.Budget
import com.felixos.drohne.plan.Geo
import com.felixos.drohne.plan.LatLon
import com.felixos.drohne.plan.Mission
import com.felixos.drohne.plan.Missions
import com.felixos.drohne.plan.PlanJson
import com.felixos.drohne.plan.Preflight
import com.felixos.drohne.plan.RcLost
import com.felixos.drohne.plan.Takeoff
import com.felixos.drohne.plan.Terrain
import com.felixos.drohne.plan.Waypoint
import com.felixos.drohne.plan.Weather
import dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Die Tür der Oberfläche in den Flugkern (Zielbild Schritt 3a/3b, 22.11.2026).
 * Hier liegen der geladene Plan, die Startprüfung und jeder Weg, der einen Plan startet: Start vom Boden,
 * Umweg und Planrest aus dem Schweben. Die Abläufe sind unverändert aus der MainActivity hierher gezogen;
 * die Oberfläche zeigt nur noch [zustand] an und ruft [Aktionen].
 */
object FlugSitzung : Aktionen {
    /** Vom Cloud-Planer erzeugter Plan (öffentliches Repo, daher ohne Login lesbar). */
    private const val PLAN_URL = "https://raw.githubusercontent.com/fiahless-beep/Drohne/main/plaene/aktuell.json"
    private const val PLAN_API_URL = "https://api.github.com/repos/fiahless-beep/Drohne/contents/plaene/aktuell.json?ref=main"

    private lateinit var app: Context
    private var gestartet = false
    private val ui = Handler(Looper.getMainLooper())
    /** Die Oberfläche, solange eine sichtbar ist. */
    var anzeige: Anzeige? = null
    private val zuhoerer = CopyOnWriteArrayList<(CockpitZustand) -> Unit>()

    /** Plan, wie er geladen wurde (ohne Steigflug und Rückweg); geflogen wird MissionController.currentMission. */
    var mission: Mission? = null; private set
    var missionText: String = ""; private set
    var planName: String = "No plan"; private set
    var planZeile: String = "Get a plan"; private set
    /** Letzter Zustandstext des MissionControllers (fürs Protokoll). */
    var controllerText: String = ""; private set
    var loading = false; private set
    var uploading = false; private set
    /** Schritt, an dem die App gerade arbeitet (prüfen, hochladen, starten); null = nichts. */
    var busy: String? = null; private set
    /** Windvorhersage zum zuletzt geladenen Plan (Weather.line), null = nicht geholt oder Dienst nicht erreichbar. */
    @Volatile var forecastLine: String? = null; private set

    /** Hauptplan, der wartet, während ein Umweg fliegt: Plan wie geladen, sein Text, Index seines nächsten Wegpunkts. */
    private class Waiting(val plan: Mission, val text: String, val next: Int)
    private var waiting: Waiting? = null
    val planWartet: Boolean get() = waiting != null

    private fun aufUi(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else ui.post(block) }
    private fun melden() { anzeige?.aktualisieren(); verteilen() }
    private fun verteilen() { if (zuhoerer.isNotEmpty()) { val z = zustand(); zuhoerer.forEach { it(z) } } }
    @Volatile private var angestossen = false
    /** Werte der Drohne kommen in dichter Folge: den Zustand höchstens zehnmal je Sekunde neu rechnen und verteilen, auf dem UI-Thread. */
    private fun anstossen() { if (angestossen || zuhoerer.isEmpty()) return; angestossen = true; ui.postDelayed({ angestossen = false; verteilen() }, 100) }
    private fun info(titel: String, text: String) { anzeige?.meldung(titel, text) }

    fun addZuhoerer(l: (CockpitZustand) -> Unit) { zuhoerer += l; l(zustand()) }
    fun removeZuhoerer(l: (CockpitZustand) -> Unit) { zuhoerer -= l }

    /** Einmal je App-Start: Rückweg, Wächter, Karten-Raster; Fotos nach der Landung holen. */
    fun init(context: Context) {
        if (gestartet) return
        gestartet = true
        app = context.applicationContext
        MissionController.onStateText = { text -> aufUi { controllerText = text; melden() } }
        FlightReport.init(app)
        LiveLink.init(app)
        ObstacleGuard.init(app)
        FrameShots.init(app)
        MissionController.duskMode = app.getSharedPreferences("drohne", Context.MODE_PRIVATE).getBoolean("dusk_mode", false)
        FlightGuard.init()
        FlightGuard.onAlert = { t -> aufUi { anzeige?.hinweis(t); melden() } }
        Thread {
            try {
                val n = app.assets.open("gebaeude.bin").use { com.felixos.drohne.plan.Buildings.load(it) }
                DroneManager.log("Gebäudedatenbank geladen: $n Gebäude")
            } catch (t: Throwable) { DroneManager.log("Keine Gebäudedatenbank in der App (${t.javaClass.simpleName})") }
            try {
                // Mehrere Raster: hoehen.bin (Lissabon), hoehen_muehle.bin …; kleine zuerst, damit sie schnell da sind.
                val files = (app.assets.list("") ?: emptyArray()).filter { it.startsWith("hoehen") && it.endsWith(".bin") }.sortedByDescending { it.length }
                for (f in files) { val n = app.assets.open(f).use { Terrain.load(it) }; DroneManager.log("Höhenmodell geladen: $f, $n Zellen") }
                if (files.isEmpty()) DroneManager.log("Kein Höhenmodell in der App")
            } catch (t: Throwable) { DroneManager.log("Kein Höhenmodell in der App (${t.javaClass.simpleName})") }
        }.start()
        var collected = false
        MissionController.onFinished = {
            if (!collected) {
                collected = true
                DroneManager.log("Mission beendet → hole Fotos und lade hoch")
                // Erst nach der Landung, sonst stört der Wiedergabemodus den Heimflug.
                waitLandedThen { FlightReport.collectAndUpload(app) { collected = false } }
            }
        }
        MissionController.onHoverFinished = { aufUi { melden() } }
        DroneManager.addStateListener { s -> pflegen(s); anstossen() }
        ui.post(object : Runnable { override fun run() { verteilen(); ui.postDelayed(this, 1000) } })
    }

    /** Wartet, bis die Drohne am Boden ist (Motoren aus), dann [block]. */
    private fun waitLandedThen(block: () -> Unit) {
        var tries = 0
        val r = object : Runnable {
            override fun run() {
                val s = DroneManager.state
                if (!s.flying && !s.motorsOn) block()
                else if (++tries < 300) ui.postDelayed(this, 2000)
                else DroneManager.log("Photos: drone has not landed, use Settings → Data → Fetch photos later")
            }
        }
        ui.postDelayed(r, 5000)
    }

    private fun setBusy(text: String?) { busy = text; if (text != null) DroneManager.log(text); melden() }
    /** Die Oberfläche arbeitet an etwas (Copilot schaut aufs Bild): solange sind die Starttüren zu. null = fertig. */
    fun beschaeftigt(text: String?) = setBusy(text)

    /** Plan-Feld: Name, darunter eine Zeile Zustand; der lange Text bleibt, wenn [text] leer ist. */
    private fun setPlanCard(name: String, text: String) {
        planName = name.substringBefore(" – ")
        planZeile = name.substringAfter(" – ", "").ifBlank { metaLine() }
        if (text.isNotBlank()) missionText = text
    }

    private fun metaLine(): String {
        val m = mission ?: return "Get a plan"
        return "${m.waypoints.size} points · ${m.waypoints.count { it.takePhoto }} photos · ${m.lengthM.toInt()} m · ${m.waypoints.minOf { it.heightM }.toInt()}–${m.waypoints.maxOf { it.heightM }.toInt()} m"
    }

    // ---------------------------------------------------------------- Positionen

    fun homeLatLon(): LatLon? = DroneManager.state.home?.takeIf { it.latitude != 0.0 }?.let { LatLon(it.latitude, it.longitude) }

    private fun dronePos(s: DroneState): LatLon? = s.location?.takeIf { it.latitude != 0.0 }?.let { LatLon(it.latitude, it.longitude) }

    /** Letzte bekannte Handyposition (GPS oder Netz), null ohne Berechtigung/Fix. */
    fun phoneLatLon(): LatLon? = try {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let { LatLon(it.latitude, it.longitude) }
    } catch (_: Throwable) { null }

    fun sensorenText(v: String?): String = when { v == null -> "unknown"; v == "aktiv" -> "on"; v == "aus" -> "OFF"; else -> v.replace("vorn", "front").replace("hinten", "back").replace("links", "left").replace("rechts", "right").replace("unten", "down") }

    // ---------------------------------------------------------------- Startprüfung und Starttüren

    /** Der Zustand, über den die Startprüfung rechnet. */
    private fun lage(s: DroneState): Preflight.Lage {
        val home = s.home
        return Preflight.Lage(
            registered = s.registered, connected = s.connected,
            mission = mission, uploaded = MissionController.uploaded,
            home = if (home?.latitude != null && home.latitude != 0.0) LatLon(home.latitude, home.longitude) else null,
            position = dronePos(s),
            batteryPercent = s.batteryPercent, satellites = s.satellites, heightLimitM = s.heightLimitM, windWarning = s.windWarning,
            sensorsOk = s.sensorsOk, sensorsText = sensorenText(s.sensors),
            buildingConflicts = MissionController.conflicts.map { it.toString() },
            surfaceConflicts = MissionController.terrainConflicts.map { it.toString() },
            returnProblems = MissionController.returnProblems,
            cameraFreeMb = if (s.connected) { FlightReport.refreshStorage(); FlightReport.activeFreeMb } else null,
            rthHeightM = MissionController.rthHeightM, transitHeightM = MissionController.TRANSIT_HEIGHT_M,
            terrainCovered = MissionController.terrainCovered, preparedHome = MissionController.preparedHome,
            nowMs = System.currentTimeMillis())
    }

    override fun startProbleme(): List<String> = Preflight.startGate(lage(DroneManager.state))

    override fun pruefliste(voll: Boolean): List<Pruefzeile> = pruefliste(startProbleme(), voll)
    private fun pruefliste(probleme: List<String>, voll: Boolean) = Preflight.checklist(probleme, hasPlan = mission != null, full = voll).map { Pruefzeile(it.ok, it.text, it.detail) }

    /** Starttür am Boden. Die Prüfung läuft hier, im Augenblick des Starts, nicht in der Oberfläche. */
    override fun starten(): StartErgebnis {
        val problems = startProbleme()
        if (problems.isNotEmpty()) {
            DroneManager.log("Start verweigert: ${problems.size} Problem(e) in der Startprüfung")
            melden()
            return StartErgebnis.Verweigert(problems)
        }
        MissionController.start()
        return StartErgebnis.Gestartet
    }

    /** Am Boden, verbunden, nichts läuft und nichts wird gerade gestartet: nur dann gibt es das Abheben per Knopf. */
    private fun abhebenMoeglich(s: DroneState): Boolean =
        s.connected && !s.flying && !s.motorsOn && !MissionController.executing && !Simulator.active && !uploading && !loading && busy == null && !FollowController.active

    /** Abheben ohne Plan (Knopf 1,5 s gehalten). Verweigert mit leerer Liste = gerade nicht angeboten, nichts tun. */
    override fun abheben(): StartErgebnis {
        val s = DroneManager.state
        if (!abhebenMoeglich(s)) return StartErgebnis.Verweigert(emptyList())
        Takeoff.problem(s.connected, s.flying, s.motorsOn, s.satellites, s.home?.latitude?.let { it != 0.0 } == true, s.batteryPercent, s.windWarning)
            ?.let { return StartErgebnis.Verweigert(listOf(it)) }
        DroneManager.takeOff()
        return StartErgebnis.Gestartet
    }

    override fun pausieren() = MissionController.pause()

    override fun fortsetzen() {
        if (waiting != null && DroneManager.state.flying) resumePlan() else MissionController.resume()
    }

    override fun stoppUndHeim() {
        FollowController.stop()
        MissionController.stop()
        DroneManager.goHome()
    }

    override fun hauptaktionWaehlen(): Hauptaktion? {
        val st = MissionController.executeState
        return when {
            loading || uploading || busy != null -> null
            MissionController.executing -> Hauptaktion.PAUSE
            waiting != null && DroneManager.state.flying -> Hauptaktion.FORTSETZEN
            st == WaypointMissionExecuteState.INTERRUPTED && mission != null -> Hauptaktion.FORTSETZEN
            mission == null -> Hauptaktion.PLAN_HOLEN
            !MissionController.uploaded -> Hauptaktion.HOCHLADEN
            else -> Hauptaktion.START
        }
    }

    // ---------------------------------------------------------------- Im Flug: Motiv, Umweg, Planrest

    fun canPick(): Boolean = DroneManager.state.flying && !MissionController.executing && busy == null && !loading && !uploading && !FollowController.active

    /** Height of the surface (roof, tree, ground) at [p] relative to the start point; 0 if unknown. */
    fun surfaceRel(p: LatLon): Double {
        val home = homeLatLon() ?: return 0.0
        val g = Terrain.groundM(home) ?: return 0.0
        return (Terrain.surfaceM(p) ?: return 0.0) - g - MissionController.startAboveGroundM
    }

    /** Where the tapped pixel of the live view meets the surface: camera tilt + drone heading + height (approximate). */
    fun groundPoint(x: Float, y: Float, w: Int, h: Int): LatLon? {
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

    /** Entfernung und Flughöhe für ein angetipptes Motiv, oder der Grund, warum es nicht geht. */
    class Motiv(val p: LatLon, val distM: Int, val hoeheM: Double, val oberkanteM: Double)

    fun motiv(p: LatLon): Pair<Motiv?, Pair<String, String>?> {
        val loc = DroneManager.state.location?.takeIf { it.latitude != 0.0 } ?: return null to null
        val d = Geo.distance(LatLon(loc.latitude, loc.longitude), p).toInt()
        if (loc.altitude < 10) return null to ("Too low" to "Subjects can be picked from 10 m above the start point. The drone is at ${loc.altitude.toInt()} m.")
        if (d > 400) return null to ("Too far" to "The point is $d m away (max. 400 m).")
        val h = Math.round(loc.altitude).toDouble().coerceIn(MissionController.TRANSIT_HEIGHT_M, 118.0)
        return Motiv(p, d, h, surfaceRel(p)) to null
    }

    /** Flughöhe für einen Vorschlag des Copiloten: jetzige Höhe, mindestens Transit. */
    fun hoeheFuerUmweg(): Double = Math.round(DroneManager.state.location?.altitude ?: 0.0).toDouble().coerceIn(MissionController.TRANSIT_HEIGHT_M, 118.0)
    fun hoehe(): Double = DroneManager.state.location?.altitude ?: 0.0

    private fun circle(p: LatLon, r: Double, h: Double, targetTop: Double): Mission {
        val loc = DroneManager.state.location
        val from = if (loc != null) Geo.bearing(p, LatLon(loc.latitude, loc.longitude)) else 0.0  // start at the ring point nearest to the drone
        return Missions.orbit(p, r, h, points = 8, startBearingDeg = from, targetHeightM = targetTop.coerceIn(-100.0, h - 5), name = "photo-circle")
            .copy(startFromCurrent = true, hoverAtEnd = true, obstacles = mission?.obstacles ?: emptyList())
    }

    private fun lookDown(p: LatLon, h: Double): Mission = Mission(name = "look-closer", speedMs = 4.0, target = p, startFromCurrent = true, hoverAtEnd = true,
        obstacles = mission?.obstacles ?: emptyList(), waypoints = listOf(
            Waypoint(p, h, gimbalPitchDeg = -90.0, takePhoto = true, hoverSec = 6),
            Waypoint(Geo.offset(p, 2.0, 0.0), h, gimbalPitchDeg = -90.0, takePhoto = false)))

    /** Umweg: Fotokreis um [p]. */
    fun umwegKreis(p: LatLon, radiusM: Double, hoeheM: Double, oberkanteM: Double) = flyFromHere(circle(p, radiusM, hoeheM, oberkanteM), "photo circle", detour = true)
    /** Umweg: über [p] schweben, Kamera senkrecht, Foto. */
    fun umwegNahsicht(p: LatLon, hoeheM: Double) = flyFromHere(lookDown(p, hoeheM), "look closer", detour = true)

    /** Continue the waiting plan from its next waypoint, starting where the drone hovers now. */
    private fun resumePlan() {
        val w = waiting ?: return
        val n = w.plan.waypoints.size
        val from = w.next.coerceIn(0, n - 2)
        val rest = w.plan.copy(name = w.plan.name.removeSuffix("-rest") + "-rest", waypoints = w.plan.waypoints.drop(from), startFromCurrent = true, hoverAtEnd = false)
        flyFromHere(rest, "rest of plan “${w.plan.name}” from point ${from + 1}", detour = false, text = w.text)
    }

    /**
     * Starttür im Flug: prüfen, hochladen und starten, während die Drohne schwebt. Gleiche Prüfungen wie am Boden
     * (Wächter, Akku, Grenzen). Erst wenn alles besteht, wird der pausierte Plan gestoppt; ein abgelehnter Umweg lässt ihn unberührt.
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
        val problems = if (!ok) listOf(MissionController.rejectReason ?: "DJI file check failed") else Preflight.startGate(lage(s), inFlight = true)
        if (problems.isNotEmpty()) {
            mission = prev; MissionController.restore(snap); setBusy(null)
            info("Not possible: $what", problems.joinToString("\n\n") + "\n\nNothing was changed. The drone keeps hovering.")
            return
        }
        if (detour && waiting == null && prev != null && !prev.hoverAtEnd && paused) waiting = Waiting(prev, prevText, next)
        if (!detour) waiting = null
        missionText = if (text.isNotBlank()) text else summary(m, what)
        setPlanCard("${m.name} – uploading …", missionText)
        anzeige?.planUebernommen(vorschau = false)
        if (paused) { setBusy("Stopping the paused plan …"); MissionController.stop(prevId) }
        ui.postDelayed({
            setBusy("Uploading $what to the drone …")
            MissionController.upload { up ->
                aufUi {
                    if (up) { setBusy("Starting $what …"); MissionController.start(); ui.postDelayed({ setBusy(null) }, 2500) }
                    else { setBusy(null); MissionController.say("⚠ Upload failed: $what. Drone hovers."); info("Upload failed", "The drone keeps hovering. Try again, or press “Resume plan”.") }
                    setPlanCard(m.name + if (up) " – on the drone" else " – upload failed", "")
                    melden()
                }
            }
        }, if (paused && !Simulator.active) 1500L else 0L)
    }

    // ---------------------------------------------------------------- Folgen

    /** Folgen starten; null = läuft, sonst der Grund, warum nicht. */
    fun folgenStarten(hoeheM: Double, abstandM: Double): String? {
        val st = DroneManager.state
        val pos = dronePos(st) ?: st.home?.takeIf { it.latitude != 0.0 }?.let { LatLon(it.latitude, it.longitude) }
        Preflight.followProblem(pos, hoeheM, st.sensorsOk, st.sensors ?: "unknown", System.currentTimeMillis())?.let { return it }
        FollowController.start(app, FollowController.Settings(hoeheM, abstandM))
        melden()
        return null
    }

    fun folgenBeenden() { FollowController.stop(); melden() }

    // ---------------------------------------------------------------- Plan holen / Testflug / auf die Drohne

    /** Testflug um die jetzige Position; false = keine Position. [stufe] 1 = Schwebetest, 2 = Testquadrat. */
    fun testflug(stufe: Int): Boolean {
        val s = DroneManager.state
        val loc = s.location
        val home = s.home
        // Drohnenposition; falls die gerade fehlt, den gesetzten Startpunkt nehmen.
        val here = when {
            loc?.latitude != null && loc.latitude != 0.0 -> LatLon(loc.latitude, loc.longitude)
            home?.latitude != null && home.latitude != 0.0 -> LatLon(home.latitude, home.longitude).also {
                DroneManager.log("Drohnenposition fehlt gerade, nehme Startpunkt (Home)")
            }
            else -> { DroneManager.log("Keine Drohnenposition: GPS abwarten"); return false }
        }
        if (stufe == 1) useMission(Missions.hoverTest(here), "Hover test: climbs to 10 m, flies 5 m north, 1 photo, comes back and lands.")
        else useMission(Missions.testSquare(here, sideM = 30.0, heightM = 30.0), "Test square 30 × 30 m at 30 m height around the current position")
        return true
    }

    override fun planLaden() {
        DroneManager.ensureObstacleAvoidance("Plan holen")
        DroneManager.log("Lade Flugplan aus der Cloud …")
        askedStartPlace = false // eine unbeantwortete Frage (Bildschirm neu aufgebaut) darf die nächste nicht überspringen
        loading = true
        setPlanCard("Getting plan …", "Fetching plaene/aktuell.json from the cloud …")
        melden()
        Thread {
            try {
                // Zuerst über die GitHub-API (ohne Zwischenspeicher, Änderung nach Sekunden da), sonst die alte Adresse (bis 5 min alt).
                val text = try {
                    (java.net.URL(PLAN_API_URL).openConnection() as java.net.HttpURLConnection).run {
                        connectTimeout = 8_000; readTimeout = 8_000; useCaches = false
                        setRequestProperty("Accept", "application/vnd.github.raw")
                        FlightReport.token(app)?.let { setRequestProperty("Authorization", "Bearer $it") }
                        if (responseCode != 200) throw java.io.IOException("HTTP $responseCode")
                        inputStream.bufferedReader().use { it.readText() }
                    }
                } catch (t: Throwable) {
                    DroneManager.log("Plan direkt nicht erreichbar (${t.message}), nehme die zwischengespeicherte Adresse")
                    val conn = (java.net.URL(PLAN_URL + "?t=" + System.currentTimeMillis()).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 10_000; readTimeout = 10_000; useCaches = false
                    }
                    conn.inputStream.bufferedReader().use { it.readText() }
                }
                val (m, beschreibung) = PlanJson.parse(text)
                LiveLink.planLoaded()
                fetchForecast(m)
                ui.post { loading = false; useMission(m, beschreibung); melden() }
            } catch (t: Throwable) {
                DroneManager.log("Plan laden fehlgeschlagen: ${t.javaClass.simpleName}: ${t.message}")
                ui.post { loading = false; setPlanCard("Getting the plan failed", "${t.javaClass.simpleName}: ${t.message}\nCheck the internet connection, then Get plan again."); melden() }
            }
        }.start()
    }

    /** Holt die Vorhersage für den Startplatz des Plans; scheitert still (kein Netz am Feld ist normal). Läuft im Lade-Thread. */
    private fun fetchForecast(m: Mission) {
        forecastLine = null
        val pos = m.plannedStart ?: homeLatLon() ?: m.waypoints.first().pos
        try {
            val text = (java.net.URL(Weather.url(pos)).openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = 4_000; readTimeout = 4_000; useCaches = false
                if (responseCode != 200) throw java.io.IOException("HTTP $responseCode")
                inputStream.bufferedReader().use { it.readText() }
            }
            val r = Weather.parse(text)
            if (r == null) DroneManager.log("Wettervorhersage: Antwort nicht lesbar")
            else { forecastLine = r.line(); DroneManager.log("Wetter am Start (Vorhersage, 10 m Höhe, grob): " + r.line()) }
        } catch (t: Throwable) { DroneManager.log("Wettervorhersage nicht erreichbar (${t.javaClass.simpleName})") }
    }

    private fun rcLostText(m: Mission): String = if (m.rcLost == RcLost.CONTINUE) "finish the plan, then return home" else "return home at once"

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
        forecastLine?.let { appendLine(it) }
        m.target?.let { appendLine("Camera points at the target (${String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon)})") }
        if (m.obstacles.isNotEmpty()) appendLine("${m.obstacles.size} buildings known, the route is checked against them")
        if (m.light) appendLine("Landing light on")
        append("Signal loss: " + rcLostText(m))
    }

    /** Akku-Hinweis mit dem Stand von jetzt (nicht vom Laden des Plans): reicht der Akku nicht für den ganzen Plan? */
    override fun akkuHinweis(): String? {
        val m = mission ?: return null
        val have = DroneManager.state.batteryPercent ?: return null
        val home = homeLatLon() ?: dronePos(DroneManager.state) ?: return null
        val need = Budget.estimate(m, home).neededPercent
        return if (have < need) "Battery $have %: not enough for the whole plan (about $need % needed). The drone will turn back early at the ${Budget.RESERVE_PERCENT} % reserve; the rest is flown later as a rest plan." else null
    }

    private var askedStartPlace = false

    /**
     * Ein Schritt: Plan prüfen/vorbereiten (MissionController.prepare, unverändert) und auf die Drohne laden.
     * Zusammenfassung steht im Plan-Feld; Ergebnis (geprüft, auf der Drohne, Fehler) ebenfalls.
     */
    private fun useMission(m: Mission, beschreibung: String) {
        // In the air: a newly loaded plan starts where the drone is; the paused plan waits and can be resumed afterwards.
        // Läuft ein Plan (auch zwischen Start und Abheben), darf kein neues prepare() ihn und die Wächter ersetzen.
        if (MissionController.executing && !m.name.startsWith("beispiel")) { info("Pause first", "A plan is running. Press Pause, then load the new plan."); return }
        if (DroneManager.state.flying && !m.name.startsWith("beispiel")) {
            val a = anzeige ?: return
            a.frage("Fly “${m.name}” from here?",
                summary(m, beschreibung) + "\n\nThe drone flies this plan starting from where it hovers now. At the end it hovers. A paused plan waits and continues with “Resume plan”.",
                "Fly from here", "Cancel", abbrechbar = true) { ja ->
                if (ja) flyFromHere(m.copy(startFromCurrent = true, hoverAtEnd = true), "plan “${m.name}”", detour = true, text = summary(m, beschreibung))
            }
            return
        }
        waiting = null
        if (Simulator.active && homeLatLon() == null) Simulator.setHome(m.plannedStart ?: m.waypoints.first().pos)
        val text = summary(m, beschreibung)
        if (m.name.startsWith("beispiel")) {
            setPlanCard("Example plan “${m.name}” (view only)", text + "\n\nWill not be flown. Claude has to store a real plan first.")
            DroneManager.log("Plan laden funktioniert (Beispiel, wird nicht geflogen)")
            melden()
            return
        }
        // Erhöhter Startplatz (Felix 8.11.): zu Hause fragen, ob vom Balkon gestartet wird; Plan mit startUeberBoden fragt nicht.
        val place = if (m.startAboveGroundM == null) StartPlaces.near(app, homeLatLon() ?: m.plannedStart) else null
        val a = anzeige
        if (place != null && MissionController.startAboveOverrideM == null && !askedStartPlace && a != null) {
            askedStartPlace = true
            a.frage("Where does the drone take off?",
                "The remembered start place “${place.name}” is here, ${String.format(Locale.US, "%.0f", place.offsetM)} m above the street. Do you take off from there or at street level?",
                place.name, "Street level", abbrechbar = false) { ja ->
                MissionController.startAboveOverrideM = if (ja) place.offsetM else 0.0
                useMission(m, beschreibung)
            }
            return
        }
        askedStartPlace = false
        setPlanCard("Plan “${m.name}” – checking …", text)
        if (!MissionController.prepare(m)) {
            val why = MissionController.rejectReason
            if (why != null) {
                // Abgelehnt, nichts vorbereitet: auch den vorher geladenen Plan vergessen, damit „Upload again“/Start nichts Altes anbietet.
                mission = null; missionText = ""
                setPlanCard("Plan “${m.name}” – not loaded", "$why\n\nThin the plan out or split it, then get it again.")
                melden()
                return
            }
            setPlanCard("Plan “${m.name}” – DJI check failed", text + "\n\nNo variant without errors. Send the log to Claude.")
            melden()
            return
        }
        mission = m
        missionText = text
        val problems = listOf(MissionController.conflicts.size to "Building guard", MissionController.terrainConflicts.size to "Surface guard").filter { it.first > 0 }
        val guardNote = if (problems.isEmpty()) "Guards: route clear." else problems.joinToString(", ") { "${it.second}: ${it.first} conflict(s)" } + ", see the check list."
        setPlanCard("Plan “${m.name}” – uploading …", text + "\n\n" + guardNote)
        DroneManager.log("Plan übernommen, lade auf die Drohne …")
        // Felix 8.11.: nach „Plan holen“ gleich die 3D-Vorschau zeigen (nicht im Flug).
        anzeige?.planUebernommen(vorschau = !DroneManager.state.flying)
        hochladen()
    }

    /** Plan (nochmal) auf die Drohne laden; Ergebnis im Plan-Feld. */
    override fun hochladen() {
        val m = mission ?: return DroneManager.log("Kein Plan: erst „Plan holen“ oder Testflug")
        uploading = true; melden()
        // Antwortet die Drohne nicht, bliebe „Uploading“ für immer stehen und sperrte Start und Plan holen: nach 45 s freigeben.
        val run = ++uploadRun
        ui.postDelayed({
            if (uploading && run == uploadRun) {
                uploading = false
                DroneManager.log("Upload: nach 45 s keine Rückmeldung der Drohne")
                setPlanCard("Plan “${m.name}” – upload did not finish", "The drone did not answer within 45 s. Check the connection, then press “Upload again”.")
                melden()
            }
        }, 45_000)
        MissionController.upload { ok ->
            aufUi {
                // Eine Antwort auf einen älteren, schon abgelaufenen Versuch darf den laufenden nicht beenden.
                if (run != uploadRun) return@aufUi
                uploading = false; uploadRun++
                setPlanCard(if (ok) "Plan “${m.name}” – on the drone" else "Plan “${m.name}” – upload failed", "")
                melden()
            }
        }
    }
    private var uploadRun = 0

    // ---------------------------------------------------------------- Simulation

    enum class SimErgebnis { BEENDET, DROHNE_VERBUNDEN, GESTARTET_MIT_HANDY, GESTARTET_OHNE_ORT }

    /** Simulation (Felix 8.11.): Drohne vortäuschen, Plan echt prüfen, Route abfliegen. Startpunkt = letzte Handyposition, sonst kommt er mit dem Plan. */
    fun simulationUmschalten(): SimErgebnis {
        if (Simulator.active) {
            Simulator.stopSimulation()
            mission = null; missionText = ""; setPlanCard("No plan", "")
            melden()
            return SimErgebnis.BEENDET
        }
        if (DroneManager.state.connected) return SimErgebnis.DROHNE_VERBUNDEN
        MissionController.initOffline(app)
        val phone = phoneLatLon()
        Simulator.start(phone)
        melden()
        return if (phone != null) SimErgebnis.GESTARTET_MIT_HANDY else SimErgebnis.GESTARTET_OHNE_ORT
    }

    // ---------------------------------------------------------------- Texte aus dem Kern-Zustand (nur Anzeige)

    /** Lage in Worten für den Copiloten. */
    fun telemetryText(s: DroneState): String = buildString {
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

    fun appInfo(): String {
        val s = DroneManager.state
        return listOfNotNull("App ${BuildConfig.VERSION_NAME} · DJI MSDK 5.20.1", if (s.registered) "SDK ok" else "SDK: ${s.registerError ?: "starting"}",
            "Link " + (if (s.airLinkConnected) "ok" else "–"), s.heightLimitM?.let { "Height limit $it m" }, controllerText.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    // ---------------------------------------------------------------- CockpitZustand

    private var lastLoc: Triple<Double, Double, Long>? = null
    private var speedMs: Double? = null
    private var zuletztInDerLuft = false
    private var funkabriss = false
    private var statusKey = ""
    private var statusSince = 0L
    private var bahnFuer: Mission? = null
    private var bahnPunkte: List<CockpitZustand.Bahn.Punkt> = emptyList()

    /** Bei jedem Wert der Drohne: abgeleitete Größen fortschreiben (Tempo aus der Position, Funkabriss im Flug). */
    private fun pflegen(s: DroneState) {
        if (!s.flying && waiting != null && busy == null) waiting = null  // landed: nothing waits any more
        if (s.connected) { zuletztInDerLuft = s.flying || s.motorsOn; funkabriss = false }
        else if (zuletztInDerLuft && !Simulator.active) funkabriss = true
        val loc = s.location?.takeIf { it.latitude != 0.0 }
        if (loc == null) { speedMs = null; lastLoc = null; return }
        val now = System.currentTimeMillis()
        lastLoc?.let { (la, lo, t) ->
            val dt = (now - t) / 1000.0
            if (dt >= 0.5) { speedMs = Geo.distance(LatLon(la, lo), LatLon(loc.latitude, loc.longitude)) / dt / (if (Simulator.active) Simulator.timeScale else 1.0); lastLoc = Triple(loc.latitude, loc.longitude, now) } // Simulation: Zeitraffer herausrechnen
        } ?: run { lastLoc = Triple(loc.latitude, loc.longitude, now) }
    }

    private fun ostNord(von: LatLon, p: LatLon): CockpitZustand.Bahn.Punkt {
        val d = Geo.distance(von, p); val b = Math.toRadians(Geo.bearing(von, p))
        return CockpitZustand.Bahn.Punkt((d * Math.sin(b)).toFloat(), (d * Math.cos(b)).toFloat())
    }

    override fun zustand(): CockpitZustand {
        val s = DroneManager.state
        val now = System.currentTimeMillis()
        val st = MissionController.executeState
        val flown = MissionController.currentMission
        val m = mission
        val idx = MissionController.currentWaypointIndex
        val n = flown?.waypoints?.size ?: 0
        val running = MissionController.executing || st == WaypointMissionExecuteState.RECOVERING
        val paused = st == WaypointMissionExecuteState.INTERRUPTED
        val age = (now - DroneManager.lastUpdateMs) / 1000
        val stale = s.connected && !Simulator.active && DroneManager.lastUpdateMs > 0 && age >= 6
        val loc = s.location?.takeIf { it.latitude != 0.0 }
        val here = loc?.let { LatLon(it.latitude, it.longitude) }
        val home = homeLatLon()
        val b = s.batteryPercent
        val sens = s.sensors

        val verbindung = when {
            s.registerError != null -> CockpitZustand.Verbindung.SDK_FEHLER
            !s.registered && !Simulator.active -> CockpitZustand.Verbindung.SDK_STARTET
            s.connected -> CockpitZustand.Verbindung.VERBUNDEN
            s.rcConnected -> CockpitZustand.Verbindung.NUR_FERNSTEUERUNG
            else -> CockpitZustand.Verbindung.GETRENNT
        }
        val (phase, phaseText) = when {
            !s.connected -> CockpitZustand.Phase.GETRENNT to when (verbindung) {
                CockpitZustand.Verbindung.SDK_FEHLER -> "SDK error"; CockpitZustand.Verbindung.SDK_STARTET -> "SDK starting"
                CockpitZustand.Verbindung.NUR_FERNSTEUERUNG -> "Controller only"; else -> "Disconnected" }
            FollowController.active -> CockpitZustand.Phase.FOLGT to "Following phone"
            st == WaypointMissionExecuteState.RETURN_TO_START_POINT -> CockpitZustand.Phase.HEIMFLUG to "Returning home"
            s.landing -> CockpitZustand.Phase.LANDET to "Landing"
            MissionController.executing && flown != null && idx >= 0 -> CockpitZustand.Phase.PUNKT to "Point ${idx + 1}/${flown.waypoints.size}"
            MissionController.executing -> CockpitZustand.Phase.STEIGT to "Climbing to ${MissionController.TRANSIT_HEIGHT_M.toInt()} m"
            paused -> CockpitZustand.Phase.PAUSIERT to "Paused"
            s.flying -> CockpitZustand.Phase.FLIEGT to "Flying"
            s.motorsOn -> CockpitZustand.Phase.MOTOREN_AN to "Motors on"
            else -> CockpitZustand.Phase.AM_BODEN to "On the ground"
        }

        val status = activityText(s)
        val key = "${busy}|$st|$idx|${s.flying}|${s.connected}"
        if (key != statusKey) { statusKey = key; statusSince = now }

        val why = MissionController.interruptWhy
        val guard = FlightGuard.alert
        val warnung = when {
            s.registerError != null -> CockpitZustand.Warnung("SDK ERROR", s.registerError)
            funkabriss -> CockpitZustand.Warnung("LINK LOST", "No connection to the drone. Values and live view are off.")
            stale -> CockpitZustand.Warnung("NO DATA", "No data from the drone for $age s. Values are not shown.")
            guard != null -> CockpitZustand.Warnung("GUARD", guard)
            paused && s.flying && why != null -> CockpitZustand.Warnung(if (why.contains("obstacle", true)) "OBSTACLE" else "PAUSED BY DRONE", "Drone paused the plan: $why. Hovering, you have control.")
            s.connected && s.windWarning == 2 -> CockpitZustand.Warnung("WIND STRONG", "Strong wind warning from the drone.")
            s.connected && b != null && b < 25 -> CockpitZustand.Warnung("BATTERY $b %", "Battery low.")
            s.connected && s.windWarning == 1 -> CockpitZustand.Warnung("WIND", "Moderate wind warning from the drone.")
            else -> null
        }

        val haupt = when {
            busy != null -> Hauptaktion.ARBEITET
            loading -> Hauptaktion.PLAN_WIRD_GEHOLT
            uploading -> Hauptaktion.LAEDT_HOCH
            running -> Hauptaktion.PAUSE
            waiting != null && s.flying -> Hauptaktion.FORTSETZEN
            paused && m != null -> Hauptaktion.FORTSETZEN
            m == null -> Hauptaktion.PLAN_HOLEN
            !MissionController.uploaded -> Hauptaktion.HOCHLADEN
            else -> Hauptaktion.START
        }

        val shown = flown ?: m
        if (shown !== bahnFuer) { bahnFuer = shown; bahnPunkte = shown?.let { p -> p.waypoints.map { ostNord(p.waypoints[0].pos, it.pos) } } ?: emptyList() }
        val bahn = shown?.let { p ->
            val o = p.waypoints[0].pos
            CockpitZustand.Bahn(bahnPunkte, if (running || paused) MissionController.lastReachedIndex else -1, home?.let { ostNord(o, it) }, here?.let { ostNord(o, it) })
        }
        val fliegtPlan = MissionController.executing || FollowController.active
        val startProbleme = if (fliegtPlan) emptyList() else Preflight.problems(lage(s))

        return CockpitZustand(
            verbindung = verbindung,
            geraet = s.productType?.takeIf { it.isNotBlank() },
            simulation = Simulator.active,
            funkabriss = funkabriss,
            keineDatenSeitS = if (stale) age else null,
            letzteDatenS = if (DroneManager.lastUpdateMs > 0 && s.connected && !Simulator.active) (now - DroneManager.lastUpdateMs) / 1000.0 else null,
            fliegt = s.flying, motorenAn = s.motorsOn,
            phase = phase, phaseText = phaseText,
            statusText = status, statusSeitS = (now - statusSince) / 1000,
            warnung = warnung,
            hoeheM = loc?.altitude,
            tempoMs = if (loc != null) speedMs else null,
            akkuProzent = b,
            akkuStufe = when { b == null -> CockpitZustand.Stufe.UNBEKANNT; b < 25 -> CockpitZustand.Stufe.NIEDRIG; b < 40 -> CockpitZustand.Stufe.WARNUNG; else -> CockpitZustand.Stufe.OK },
            restMin = b?.let { Math.round(it * Budget.MINUTES_PER_FULL_BATTERY / 100.0).toInt() },
            kursGrad = s.headingDeg,
            distanzHeimM = if (home != null && here != null) Geo.distance(home, here).toInt() else null,
            peilungHeimGrad = if (home != null && here != null) Math.round(Geo.bearing(here, home)).toInt() % 360 else null,
            satelliten = s.satellites,
            gpsOk = (s.satellites ?: 0) >= Preflight.MIN_SATELLITES && home != null,
            heimGesetzt = home != null,
            sensoren = when { !s.connected -> null; sens == null -> null; sens.startsWith("blind") -> "blind"; sens == "aktiv" -> "on"; sens == "aus" -> "OFF"; else -> sens },
            sensorenOk = s.sensorsOk,
            wind = CockpitZustand.Wind(s.windWarning, s.windMs?.takeIf { s.flying }, s.windDir?.takeIf { s.flying && it != "WINDLESS" }, forecastLine),
            hoehenlimitM = s.heightLimitM,
            tageslicht = (here ?: home)?.let { Geo.sunElevationDeg(it, now) >= -3 },
            plan = CockpitZustand.Plan(
                name = planName, zeile = planZeile, text = missionText,
                geladen = m != null, aufDrohne = m != null && MissionController.uploaded, laeuft = running,
                punktIndex = idx, punkte = n,
                fortschrittProzent = when {
                    st == WaypointMissionExecuteState.FINISHED || st == WaypointMissionExecuteState.RETURN_TO_START_POINT -> 100
                    running && n > 0 && idx >= 0 -> ((idx + 1) * 100 / n).coerceIn(0, 100)
                    else -> 0
                },
                fortschrittZeigen = running || st == WaypointMissionExecuteState.FINISHED,
                naechstes = nextActionText(s, flown, idx, st),
                beiFunkabriss = (flown ?: m)?.let { rcLostText(it) },
                brauchtProzent = if (m != null && home != null) Budget.estimate(m, home).neededPercent else null,
                reserveProzent = Budget.RESERVE_PERCENT,
                transitHoeheM = MissionController.TRANSIT_HEIGHT_M.toInt(),
                rueckkehrHoeheM = if (flown != null) MissionController.rthHeightM.toInt() else null,
            ),
            hauptaktion = haupt,
            hauptaktionFrei = !loading && !uploading && busy == null,
            startProbleme = startProbleme,
            pruefliste = if (fliegtPlan) emptyList() else pruefliste(startProbleme, voll = false),
            startpruefungZeigen = !fliegtPlan,
            folgenAktiv = FollowController.active,
            abhebenMoeglich = abhebenMoeglich(s),
            abhebenHalteMs = Takeoff.HOLD_MS,
            waechter = CockpitZustand.Waechter(FlightGuard.enabled, FlightGuard.mayPause, ObstacleGuard.enabled, ObstacleGuard.armed, MissionController.duskMode),
            bahn = bahn,
        )
    }
}
