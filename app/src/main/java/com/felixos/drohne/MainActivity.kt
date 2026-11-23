package com.felixos.drohne

import dji.sdk.keyvalue.key.FlightControllerKey
import android.Manifest
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.felixos.drohne.cockpit.Ansicht
import com.felixos.drohne.cockpit.Cockpit
import com.felixos.drohne.cockpit.Gastgeber
import com.felixos.drohne.plan.LatLon
import com.felixos.drohne.plan.Mission
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import java.util.Locale

/**
 * Gastgeber des Cockpits im Querformat. Hält, was Android und das SDK brauchen: Bildfläche (Livebild, Karte, 3D),
 * Berechtigungen, Menüs und Dialoge der Einstellungen. Die Anzeige und die Flugknöpfe liegen im Paket cockpit und
 * kennen nur den Vertrag (CockpitZustand, Aktionen); Plan, Startprüfung und Startablauf liegen in der FlugSitzung.
 */
class MainActivity : AppCompatActivity() {

    // Lesende Abkürzungen in den Flugkern (für Karte, 3D und Copilot).
    private val mission: Mission? get() = FlugSitzung.mission
    private val busy: String? get() = FlugSitzung.busy

    /** Was der Flugkern von der Oberfläche braucht: Meldungen, Rückfragen, neu zeichnen. */
    /** Was der Flugkern von der Oberfläche braucht: Meldungen, Rückfragen, Karte und 3D-Ansicht neu füllen. */
    private val anzeige = object : Anzeige {
        override fun meldung(titel: String, text: String) = info(titel, text)
        override fun frage(titel: String, text: String, ja: String, nein: String, abbrechbar: Boolean, antwort: (Boolean) -> Unit) {
            AlertDialog.Builder(this@MainActivity).setTitle(titel).setMessage(text)
                .setPositiveButton(ja) { _, _ -> antwort(true) }.setNegativeButton(nein) { _, _ -> antwort(false) }
                .setCancelable(abbrechbar).show()
        }
        override fun hinweis(text: String) { android.widget.Toast.makeText(this@MainActivity, text, android.widget.Toast.LENGTH_LONG).show() }
        /** Das Cockpit hört selbst auf den Zustand der FlugSitzung. */
        override fun aktualisieren() {}
        override fun planUebernommen(vorschau: Boolean) {
            if (showingMap) { mapPanel.update(DroneManager.state, MissionController.currentMission); mapPanel.fit() }
            view3d.setMission(MissionController.currentMission, MissionController.preparedHome ?: homeLatLon())
            if (vorschau && mode != Mode.THREE) chooseView(Mode.THREE, byUser = false)
        }
    }

    private lateinit var video: SurfaceView
    private lateinit var mapView: org.osmdroid.views.MapView
    private lateinit var mapPanel: MapPanel
    private lateinit var view3d: View3D
    private lateinit var videoHint: TextView
    private lateinit var cockpit: Cockpit

    private val stateListener: (DroneState) -> Unit = { render(it) }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupEdgeToEdge()
        MapPanel.configure(applicationContext)
        setContentView(R.layout.activity_main)
        video = findViewById(R.id.video); mapView = findViewById(R.id.map); videoHint = findViewById(R.id.videoHint)
        view3d = View3D(findViewById(R.id.view3d))
        applyInsets()
        window.decorView.post { handleTokenLink(intent) }
        FlugSitzung.init(applicationContext)
        FlugSitzung.anzeige = anzeige

        ActivityCompat.requestPermissions(this, arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE,
        ), 1)

        CrashLog.takeReport(this)?.let { showCrash(it) }

        setupVideo(video)
        mapPanel = MapPanel(mapView)
        view3d.init()
        cockpit = Cockpit(this, findViewById(R.id.rail), findViewById(R.id.hudHost), findViewById(R.id.panel), findViewById(R.id.liveRoot), findViewById(R.id.systemRoot), findViewById(R.id.vollbildHost), findViewById(R.id.sprechHost), gastgeber)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (cockpit.vollbildVerlassen() || cockpit.tafelZurueck()) return; isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
        })

        // Startplätze: Kopie im Handyspeicher (überlebt eine Deinstallation). Ohne die Erlaubnis einmal je Installation fragen.
        if (StartPlaces.canMirror()) StartPlaces.syncMirror(this)
        else if (Build.VERSION.SDK_INT >= 30 && !StartPlaces.askedForMirror(this)) {
            StartPlaces.markAsked(this)
            AlertDialog.Builder(this).setTitle("Keep start places safe?")
                .setMessage("Remembered start places can be kept in the phone storage (Documents/FelixDrohne), so they survive a reinstall and come back by themselves. Android needs “All files access” for that: switch it on in the next screen, then go back.")
                .setPositiveButton("Open setting") { _, _ ->
                    try { startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
                    catch (_: Throwable) { try { startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Throwable) {} }
                }
                .setNegativeButton("Not now", null).show()
        }
        // Läuft die Ortswahl einer Mission, gehört der Tipp ihr (der Flugkern hält den Ort); sonst wählt er im Flug ein Motiv.
        mapPanel.onTap = { p -> if (MissionsLauf.kartenTipp(p)) mapPanel.auswahl(MissionsLauf.auswahl()) else pickSubject(p, "from map") }
        video.setOnTouchListener { _, e -> if (e.action == android.view.MotionEvent.ACTION_UP) onVideoTap(e.x, e.y); true }
        chooseView(Mode.MAP, byUser = false)
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
        cockpit.start()
    }

    override fun onResume() {
        super.onResume(); mapPanel.onResume()
        // Nach dem Erlauben von „All files access“ kommt man hierher zurück: Kopie abgleichen bzw. Plätze zurückholen.
        try { StartPlaces.syncMirror(this)?.let { n -> if (n > 0) info("Start places restored", "$n remembered start place(s) came back from the phone storage.") } } catch (_: Throwable) {}
    }
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); setIntent(intent); handleTokenLink(intent) }

    /**
     * Token-Link (QR-Code, felixdrohne://token?v=…): erst Rückfrage mit den letzten 4 Zeichen, dann derselbe Speicherweg wie
     * Settings → Data → Enter GitHub token. Der Token kommt nie ins Protokoll und nicht in den Live-Draht.
     */
    private fun handleTokenLink(i: android.content.Intent?) {
        if (i == null || i.action != android.content.Intent.ACTION_VIEW) return
        val link = i.dataString
        i.data = null   // einmal behandeln: kein zweites Fragen, der Token bleibt nicht im Intent liegen
        if (i.flags and android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        when (val r = TokenLink.parse(link)) {
            is TokenLink.Result.NotALink -> return
            is TokenLink.Result.Invalid -> { DroneManager.log("Token link rejected"); info("Token link not accepted", r.reason + "\n\nNothing was saved.") }
            is TokenLink.Result.Token -> AlertDialog.Builder(this).setTitle("GitHub token")
                .setMessage("Save GitHub token ending …${r.ending}?" + if (FlightReport.token(this) != null) "\n\nIt replaces the token stored now." else "")
                .setPositiveButton("Save") { _, _ -> FlightReport.saveToken(this, r.value); DroneManager.log("Token gespeichert (per Link)") }
                .setNegativeButton("Cancel") { _, _ -> DroneManager.log("Token link: not saved") }
                .setCancelable(false).show()
        }
    }
    override fun onPause() { mapPanel.onPause(); super.onPause() }
    override fun onDestroy() { if (FlugSitzung.anzeige === anzeige) FlugSitzung.anzeige = null; super.onDestroy() }

    override fun onStop() {
        cockpit.stopp()
        DroneManager.removeStateListener(stateListener)
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
        mapView.visibility = if (m == Mode.MAP) View.VISIBLE else View.GONE
        findViewById<View>(R.id.view3d).visibility = if (m == Mode.THREE) View.VISIBLE else View.GONE
        // GONE zerstört die Bildfläche; beim Zurückschalten meldet surfaceChanged das Livebild neu an.
        video.visibility = if (m == Mode.VIDEO) View.VISIBLE else View.GONE
        videoHint.visibility = if (m == Mode.VIDEO) View.VISIBLE else View.GONE
        if (m == Mode.MAP) { mapPanel.update(DroneManager.state, MissionController.currentMission ?: mission); mapPanel.fit() }
        if (m == Mode.THREE) { view3d.setMission(MissionController.currentMission ?: mission, MissionController.preparedHome ?: homeLatLon()); view3d.update(DroneManager.state) }
        if (::cockpit.isInitialized) cockpit.leisteZeigen()
    }

    /** Was das Cockpit vom Gastgeber braucht: Bildfläche umschalten und die Dialoge, die hier wohnen. */
    private val gastgeber = object : Gastgeber {
        override val ansicht: Ansicht get() = when (mode) { Mode.MAP -> Ansicht.KARTE; Mode.VIDEO -> Ansicht.LIVEBILD; Mode.THREE -> Ansicht.DREI_D }
        override val livebildAn: Boolean get() = videoAttached && DroneManager.state.connected
        override fun ansichtWechseln() = chooseView(when (mode) { Mode.MAP -> Mode.VIDEO; Mode.VIDEO -> Mode.THREE; Mode.THREE -> Mode.MAP }, byUser = true)
        override fun einpassen() { if (mode == Mode.THREE) view3d.cycleCamera {} else mapPanel.fit() }
        override fun bildRaender(links: Int, oben: Int, rechts: Int, unten: Int) = view3d.raender(links, oben, rechts, unten)
        /** Vollbild: nur die Ansicht samt den drei kleinen Bedienteilen (vollbildHost); Ränder des Rahmens fallen weg. */
        override fun vollbild(an: Boolean) {
            val weg = if (an) View.GONE else View.VISIBLE
            findViewById<View>(R.id.rail).visibility = weg; findViewById<View>(R.id.panel).visibility = weg; findViewById<View>(R.id.hudHost).visibility = weg
            findViewById<View>(R.id.vollbildHost).visibility = if (an) View.VISIBLE else View.GONE
            val d = resources.displayMetrics.density; val rand = if (an) 0 else Math.round(6 * d)
            findViewById<View>(R.id.rahmen).setPadding(rand, rand, rand, rand)
            findViewById<View>(R.id.mitte).let { v -> (v.layoutParams as android.view.ViewGroup.MarginLayoutParams).marginStart = rand; v.requestLayout() }
            if (mode == Mode.MAP) mapView.post { mapView.invalidate() }
        }
        override fun protokollText(): Pair<String, List<String>> = appInfo() to DroneManager.logText().lines().filter { it.isNotEmpty() }
        override fun protokollKopieren() = copyLog()
        override fun einstellungen() = showMore()
        override fun ccGesperrt(): String? = ccBlocked()
        override fun mikrofonErlauben() = ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        override fun ccFragen(frage: String, antwort: (String, Boolean, Pair<String, () -> Unit>?) -> Unit) = askCc(frage, antwort)
        override fun selbsttest() = showSelfTest()
        override fun karteZeigen() {
            if (mode != Mode.MAP) chooseView(Mode.MAP, byUser = true)
            // Ohne Drohne und ohne Plan stünde die Karte irgendwo: dann dorthin, wo das Handy ist.
            if (homeLatLon() == null && DroneManager.state.location?.takeIf { it.latitude != 0.0 } == null) phoneLatLon()?.let { mapPanel.zeigeOrt(it) }
        }
        override fun missionsOrte() = mapPanel.auswahl(MissionsLauf.auswahl())
    }

    private fun homeLatLon(): LatLon? = FlugSitzung.homeLatLon()
    private fun phoneLatLon(): LatLon? = FlugSitzung.phoneLatLon()

    private var lastConnected: Boolean? = null

    /** Automatik: beim Verbinden auf Video, beim Trennen auf Karte – solange nichts von Hand gewählt wurde. Simulation: 3D. */
    private fun autoView(s: DroneState) {
        if (lastConnected == s.connected) return
        lastConnected = s.connected
        if (userMode == null) chooseView(if (Simulator.active) Mode.THREE else if (s.connected) Mode.VIDEO else Mode.MAP, byUser = false)
    }

    // ---------------------------------------------------------------- Bildfläche nachführen (die Anzeige selbst zeichnet das Cockpit)

    private fun render(s: DroneState) {
        attachVideo()
        autoView(s)
        // Seriennummer nur merken (Settings → Device & test), nicht mehr auf dem Hauptbildschirm zeigen.
        s.serial?.let { sn -> val pr = getSharedPreferences("drohne", MODE_PRIVATE); if (pr.getString("letzte_sn", null) != sn) pr.edit().putString("letzte_sn", sn).apply() }
        val hint = mode == Mode.VIDEO && !(s.connected && videoAttached)
        videoHint.visibility = if (hint) View.VISIBLE else View.GONE
        if (hint) videoHint.text = if (s.connected) "Live view loading …" else "No live view"
        if (showingMap) mapPanel.update(s, MissionController.currentMission ?: mission)
        if (mode == Mode.THREE) view3d.update(s)
    }

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private fun setBusy(text: String?) = FlugSitzung.beschaeftigt(text)


    // ---------------------------------------------------------------- In flight: pick a subject, detour, resume the plan

    private fun canPick(): Boolean = FlugSitzung.canPick()
    private fun groundPoint(x: Float, y: Float, w: Int, h: Int): LatLon? = FlugSitzung.groundPoint(x, y, w, h)


    private fun onVideoTap(x: Float, y: Float) {
        if (!canPick()) return
        val p = groundPoint(x, y, video.width.coerceAtLeast(1), video.height.coerceAtLeast(1))
            ?: return info("No point found", "Tilt the camera further down so the subject is below the horizon, or pick it on the map.")
        pickSubject(p, "from live view, approximate")
    }

    private fun pickSubject(p: LatLon, how: String) {
        if (!canPick()) return
        val (mo, grund) = FlugSitzung.motiv(p)
        if (grund != null) return info(grund.first, grund.second)
        val s = mo ?: return
        val h = s.hoeheM
        menu("Subject ${s.distM} m away ($how)", listOf(
            "Photo circle · radius 30 m · ${h.toInt()} m high · 8 photos" to { FlugSitzung.umwegKreis(p, 30.0, h, s.oberkanteM) },
            "Photo circle · radius 50 m · ${h.toInt()} m high · 8 photos" to { FlugSitzung.umwegKreis(p, 50.0, h, s.oberkanteM) },
            "Look closer · hover above, camera straight down, photo" to { FlugSitzung.umwegNahsicht(p, h) },
            "Cancel" to { },
        ), dialog = true)
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

    private fun telemetryText(s: DroneState): String = FlugSitzung.telemetryText(s)

    /** Still image of the live view, or null when it is not on screen. */
    private fun grabFrame(done: (ByteArray?) -> Unit) {
        if (mode != Mode.VIDEO || !videoAttached || video.width <= 0 || video.height <= 0 || android.os.Build.VERSION.SDK_INT < 24) return done(null)
        try {
            val bmp = android.graphics.Bitmap.createBitmap(video.width, video.height, android.graphics.Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(video, bmp, { r -> done(if (r == android.view.PixelCopy.SUCCESS) runCatching { ClaudeLink.jpeg(bmp) }.getOrNull() else null) }, ui)
        } catch (t: Throwable) { done(null) }
    }

    /** Central Command (früher „Ask Claude“): warum gerade keine Frage geht; null = bereit. */
    private fun ccBlocked(): String? = when {
        ClaudeLink.key(this) == null -> "Key missing. Enter it once under Set → Data → Enter Claude key."
        MissionController.executing -> "Pause first. Central Command answers while the drone hovers or stands."
        busy != null -> "The app is busy. Try again in a moment."
        else -> null
    }

    private fun askCc(q: String, done: (String, Boolean, Pair<String, () -> Unit>?) -> Unit) {
        ccBlocked()?.let { return done(it, true, null) }
        val key = ClaudeLink.key(this) ?: return
        setBusy("Central Command is looking …")
        grabFrame { jpg ->
            val tele = telemetryText(DroneManager.state)
            Thread {
                val r = ClaudeLink.ask(key, q, tele, jpg)
                runOnUiThread { setBusy(null); ccReply(r, jpg != null, done) }
            }.start()
        }
    }

    private fun ccReply(r: ClaudeLink.Reply, hadImage: Boolean, done: (String, Boolean, Pair<String, () -> Unit>?) -> Unit) {
        if (r.error != null) { DroneManager.log(r.error); return done(r.error, true, null) }
        DroneManager.log("Central Command: ${r.text}" + (r.command?.let { " [$it ${r.input}]" } ?: ""))
        val inp = r.input
        val why = inp?.optString("why").orEmpty()
        fun point(): LatLon? = if (!hadImage || inp == null) null else groundPoint((inp.optDouble("x", 0.5) * video.width).toFloat(), (inp.optDouble("y", 0.5) * video.height).toFloat(), video.width.coerceAtLeast(1), video.height.coerceAtLeast(1))
        val loc = DroneManager.state.location
        val h = Math.round(loc?.altitude ?: 0.0).toDouble().coerceIn(MissionController.TRANSIT_HEIGHT_M, 118.0)
        val action: Pair<String, () -> Unit>? = when (r.command) {
            "look_closer" -> point()?.let { p -> "Look closer" to { if (canPick() && (loc?.altitude ?: 0.0) >= 10) FlugSitzung.umwegNahsicht(p, h) else info("Not possible now", "The drone must hover at 10 m or higher.") } }
            "photo_circle" -> point()?.let { p -> val rad = if ((inp?.optDouble("radius_m", 30.0) ?: 30.0) >= 40) 50.0 else 30.0
                "Photo circle ${rad.toInt()} m" to { if (canPick() && (loc?.altitude ?: 0.0) >= 10) FlugSitzung.umwegKreis(p, rad, h, FlugSitzung.surfaceRel(p)) else info("Not possible now", "The drone must hover at 10 m or higher.") } }
            "resume_plan" -> "Resume plan" to { if (FlugSitzung.hauptaktionWaehlen() == CockpitZustand.Hauptaktion.FORTSETZEN) FlugSitzung.fortsetzen() else info("Not possible now", "No plan is waiting to be resumed.") }
            "return_home" -> "Return home" to { FlugSitzung.stoppUndHeim() }
            else -> null
        }
        val msg = buildString {
            if (r.text.isNotBlank()) append(r.text)
            if (r.command == "ask_pilot") append((if (isNotEmpty()) "\n\n" else "") + (inp?.optString("question") ?: ""))
            else if (r.command != null) append((if (isNotEmpty()) "\n\n" else "") + "Suggestion: " + (action?.first ?: "${r.command} (no place found in the image)") + (if (why.isNotBlank()) ". $why" else ""))
            if (isEmpty()) append("No answer.")
        }
        done(msg, false, action)
    }

    /** Overview of the use cases and what this build can do for each. */
    private fun showUseCases() = info("Missions", "The seven missions live under Msn in the left bar: each one has a brief, a plan check, the flight and a result. Speak works in every view.")
    @Suppress("unused") private fun showUseCasesAlt() = menu("Use cases", listOf(
        "1 Fly a plan · ready" to { info("Fly a plan", "Get plan, check list, Start. Pause: button, any stick, or the pause button on the controller. Resume plan continues.") },
        "2 Subject on the way · ready (untested in flight)" to { info("Subject on the way", "Pause the plan. Tap the subject on the map or in the live view. Choose a photo circle. When it is done the drone hovers; Resume plan continues the plan from the point where you paused.") },
        "3 Go and look · ready (untested in flight)" to { info("Go and look", "Same as 2, choose “Look closer”: the drone flies above the point, camera straight down, hovers 6 s, takes a photo. Loading a new plan while hovering also works: it starts where the drone is.") },
        "4 Central Command · first step (needs the Claude key)" to { info("Central Command", "Pause or hover, press the round Speak key over the view you mean. Central Command (Claude) gets the image and telemetry, answers, and may suggest one action (look closer, photo circle, resume, return home). You confirm before anything happens. Watching in the background during the flight is not built yet.") },
        "5 Follow me / the car · tracking test first" to { info("Follow", "Run Settings → Device & test → Tracking test in flight and upload the result. It decides whether DJI tracking can be used or the app needs its own detection.") },
        "6 Plan over several batteries · not built yet" to { info("Several batteries", "Not in this build.") },
        "7 Survey a property · as a plan from Claude" to { info("Property", "Claude builds the plan from the boundary; you fly it as a plan (1). Boundary import in the app is not built.") },
        "8 Atmosphere over a crowd · not built yet" to { info("Atmosphere", "Not in this build.") },
    ))


    // ---------------------------------------------------------------- Menüs und Dialoge des Gastgebers


    /** Menüs wohnen in der Set-Fläche des Cockpits; [dialog] = als Pop-up (Auswahl mitten im Flug, die zur Karte gehört). */
    private fun menu(title: String, items: List<Pair<String, () -> Unit>>, dialog: Boolean = false) {
        if (!dialog && ::cockpit.isInitialized) return cockpit.menue(title, items)
        AlertDialog.Builder(this).setTitle(title).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton("Back", null).show()
    }

    private fun sensorsEn(v: String?): String = FlugSitzung.sensorenText(v)

    private fun info(title: String, msg: String) {
        if (::cockpit.isInitialized && cockpit.setOffen) return cockpit.setText(title, msg)
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    /** Auswahl aus festen Werten; der aktuelle Wert der Drohne wird vorher gelesen und markiert. */
    private fun <T> choose(title: String, key: dji.sdk.keyvalue.key.DJIKeyInfo<T>, options: List<Pair<String, T>>, hint: String = "") {
        DjiSettings.read(key) { cur -> runOnUiThread {
            val idx = options.indexOfFirst { it.second == cur }
            if (::cockpit.isInitialized && cockpit.setOffen) {
                cockpit.menue(title + if (hint.isNotBlank()) " – $hint" else "", options.mapIndexed { i, o -> (if (i == idx) o.first + " (now active)" else o.first) to {
                    DjiSettings.write(key, o.second, "$title: ${o.first}") { ok -> runOnUiThread {
                        android.widget.Toast.makeText(this, if (ok) "$title: ${o.first}" else "$title: not applied (see log)", android.widget.Toast.LENGTH_LONG).show() } }
                    cockpit.setZurueck()
                } })
                return@runOnUiThread
            }
            AlertDialog.Builder(this).setTitle(title + if (hint.isNotBlank()) " – $hint" else "")
                .setSingleChoiceItems(options.map { it.first }.toTypedArray(), idx) { d, i ->
                    DjiSettings.write(key, options[i].second, "$title: ${options[i].first}") { ok -> runOnUiThread {
                        android.widget.Toast.makeText(this, if (ok) "$title: ${options[i].first}" else "$title: not applied (see log)", android.widget.Toast.LENGTH_LONG).show() } }
                    d.dismiss()
                }.setNegativeButton("Cancel", null).show()
        } }
    }

    /** Einstellungen, sortiert (Felix 13.11.): Sicherheit, Kamera, Kalibrierung, Flug & Wächter, Daten, Gerät & Test. */
    private fun showMore(): Unit = menu("Settings", listOf(
        "Use cases (what works in this build)" to { showUseCases() },
        "Safety" to { showSafety() },
        "Camera" to { showCamera() },
        (if (MapPanel.satellit(this)) "Map: satellite (tap for drawn map)" else "Map: drawn map (tap for satellite)") to { mapPanel.satellitSetzen(!MapPanel.satellit(this)) },
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
            "Dusk mode: obstacle sensing off during a plan flight, only where height data shows 10 m clearance (${if (MissionController.duskMode) "on" else "off"})" to {
                MissionController.duskMode = !MissionController.duskMode
                getSharedPreferences("drohne", MODE_PRIVATE).edit().putBoolean("dusk_mode", MissionController.duskMode).apply()
                val why = if (MissionController.duskMode) MissionController.duskBlocker() else null
                DroneManager.log("Dusk mode ${if (MissionController.duskMode) "on" else "off"}" + (if (MissionController.duskMode && why != null) ". For the current plan it would NOT be used: $why" else ""))
            },
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
        "Flight stills: a still every 4 s in flight, sent to Claude (${if (FrameShots.enabled) "on" else "OFF"})" to { FrameShots.setEnabled(this, !FrameShots.enabled) },
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
        when (val r = FlugSitzung.simulationUmschalten()) {
            FlugSitzung.SimErgebnis.BEENDET -> { lastConnected = null; userMode = null }
            FlugSitzung.SimErgebnis.DROHNE_VERBUNDEN -> AlertDialog.Builder(this).setMessage("The drone is connected. Simulation works only without a drone.").setPositiveButton("OK", null).show()
            else -> {
                userMode = null; lastConnected = null
                chooseView(Mode.THREE, byUser = false)
                AlertDialog.Builder(this).setTitle("Simulation")
                    .setMessage("The app now pretends a drone is connected on the ground" + (if (r == FlugSitzung.SimErgebnis.GESTARTET_MIT_HANDY) " (home point = phone position)." else ". The home point comes with the plan.") +
                        "\n\nGet plan, check list, Start. The route is flown at the planned speeds; Pause, Resume plan and STOP work, and so does picking a subject on the map while paused. The 3D view shows buildings, route and drone. End: Settings → Device & test → Stop simulation.")
                    .setPositiveButton("OK", null).show()
            }
        }
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

    private fun appInfo(): String = FlugSitzung.appInfo()

    private fun copyLog() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Protokoll", appInfo() + "\n\n" + DroneManager.logText()))
        android.widget.Toast.makeText(this, "Log copied. Send it to Claude.", android.widget.Toast.LENGTH_SHORT).show()
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


}
