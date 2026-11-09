package com.felixos.drohne

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightAssistantKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.flightassistant.AuxiliaryLightMode
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.KeyManager
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import dji.v5.network.DJINetworkManager
import dji.v5.network.IDJINetworkStatusListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Aktueller Zustand von SDK und Drohne, so wie ihn die Oberfläche anzeigt. */
data class DroneState(
    val registered: Boolean = false,
    val registerError: String? = null,
    val connected: Boolean = false,
    val batteryPercent: Int? = null,
    val satellites: Int? = null,
    val location: LocationCoordinate3D? = null,
    val home: LocationCoordinate2D? = null,
    val headingDeg: Double? = null,
    val flying: Boolean = false,
    val motorsOn: Boolean = false,
    val rcConnected: Boolean = false,
    val airLinkConnected: Boolean = false,
    val productType: String? = null,
    /** Seriennummer des Fluggeräts (für Polizei, Versicherung, DJI-Reparatur). */
    val serial: String? = null,
    val landingConfirmationNeeded: Boolean = false,
    val landing: Boolean = false,
    /** Hinderniserkennung: null = unbekannt, "aktiv", "aus", oder "blind: vorn, hinten, …" (Sensoren sehen nichts, z. B. im Dunkeln). */
    val sensors: String? = null,
    /** DJI-Höhenlimit in m (Einstellung/Zone); fliegt die Mission höher, kappt die Drohne stillschweigend. */
    val heightLimitM: Int? = null,
    /** Windwarnung der Drohne: 0 = keine, 1 = mäßig, 2 = stark. */
    val windWarning: Int? = null,
    /** Kameraneigung (Gimbal) in Grad, −90 = senkrecht nach unten; null = unbekannt (nur Simulation gesetzt). */
    val gimbalPitchDeg: Double? = null,
) {
    val sensorsOk: Boolean get() = sensors == "aktiv"
}

/**
 * Verbindet die App mit dem DJI Mobile SDK v5: Initialisierung, Registrierung (App-Key),
 * Verbindung zur Drohne und Telemetrie (Akku, GPS, Position, Flugstatus).
 */
object DroneManager {
    private const val TAG = "Drohne"
    private val main = Handler(Looper.getMainLooper())

    @Volatile var state = DroneState()
        private set

    private val stateListeners = CopyOnWriteArrayList<(DroneState) -> Unit>()
    private val logListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val logLines = ArrayDeque<String>()

    fun addStateListener(l: (DroneState) -> Unit) { stateListeners += l; l(state) }
    fun removeStateListener(l: (DroneState) -> Unit) { stateListeners -= l }
    fun addLogListener(l: (String) -> Unit) { logListeners += l; synchronized(logLines) { logLines.forEach(l) } }
    fun removeLogListener(l: (String) -> Unit) { logListeners -= l }
    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    fun log(msg: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date()) + "  " + msg
        Log.i(TAG, msg)
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 200) logLines.removeFirst()
        }
        main.post { logListeners.forEach { it(line) } }
    }

    /** Echter Zustand (SDK/Drohne), auch während der Simulation weitergeführt. */
    @Volatile private var realState = DroneState()

    private fun update(f: (DroneState) -> DroneState) {
        realState = f(realState)
        if (Simulator.active) return  // Simulation zeigt ihren eigenen Zustand
        state = realState
        val s = state
        main.post { stateListeners.forEach { it(s) } }
    }

    /** Zustand aus der Simulation anzeigen; null = Simulation beendet, zurück zum echten Zustand. */
    fun simulated(s: DroneState?) {
        state = s ?: realState
        val v = state
        main.post { stateListeners.forEach { it(v) } }
    }

    /** true, sobald SDKManager.init aufgerufen wurde (nur einmal pro App-Start). */
    @Volatile var initStarted = false
        private set
    /** true, sobald das SDK fertig initialisiert ist. Vorher dürfen keine SDK-Funktionen aufgerufen werden. */
    @Volatile var sdkReady = false
        private set

    /** Prüft vor einer Drohnen-Aktion, ob das SDK bereit ist; sonst Hinweis im Protokoll. */
    fun requireSdk(what: String): Boolean {
        if (sdkReady) return true
        log("$what geht noch nicht: DJI-SDK ist noch nicht gestartet")
        return false
    }

    /** Führt eine SDK-Aktion aus, ohne dass ein Fehler die App beendet. */
    fun safe(what: String, block: () -> Unit) {
        if (!requireSdk(what)) return
        try { block() } catch (t: Throwable) {
            log("$what fehlgeschlagen: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun init(context: Context) {
        if (initStarted) return
        initStarted = true
        log("SDK wird gestartet …")
        SDKManager.getInstance().init(context, object : SDKManagerCallback {
            override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    sdkReady = true
                    log("SDK bereit, registriere App bei DJI …")
                    SDKManager.getInstance().registerApp()
                }
            }

            override fun onRegisterSuccess() {
                log("Registrierung erfolgreich (App-Key gültig)")
                update { it.copy(registered = true, registerError = null) }
                startTelemetry()
                SelfTest.startListening()
                MissionController.init(context)
            }

            override fun onRegisterFailure(error: IDJIError?) {
                log("Registrierung fehlgeschlagen: ${error?.description() ?: error}")
                update { it.copy(registered = false, registerError = error?.description() ?: "unbekannt") }
            }

            override fun onProductConnect(productId: Int) {
                log("Drohne/Controller verbunden (Produkt $productId)")
                main.postDelayed({ logSnapshot("3 s nach Verbinden") }, 3000)
                main.postDelayed({ logSnapshot("10 s nach Verbinden") }, 10000)
            }

            override fun onProductDisconnect(productId: Int) {
                log("Verbindung getrennt (Produkt $productId)")
            }

            override fun onProductChanged(productId: Int) {
                log("Produkt gewechselt: $productId")
            }

            override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                if (total > 0 && current == total) log("Flugsperrzonen-Datenbank aktualisiert")
            }

            override fun onRemoteServerStateUpdated(state: dji.open.pilot.client.manager.ServerState?) {}
            override fun onRemoteServerCompatibleError(error: IDJIError?) {}
        })

        // Ohne Internet beim ersten Start schlägt die Registrierung fehl; sobald Netz da ist, erneut versuchen.
        DJINetworkManager.getInstance().addNetworkStatusListener(object : IDJINetworkStatusListener {
            override fun isNetworkAvailable(available: Boolean) {
                if (available && !SDKManager.getInstance().isRegistered) {
                    log("Netz verfügbar, Registrierung erneut")
                    SDKManager.getInstance().registerApp()
                }
            }
        })
    }

    private val holder = Any()

    /** Alle beobachteten Werte, damit sie zusätzlich regelmäßig abgefragt werden können. */
    private val polls = CopyOnWriteArrayList<() -> Unit>()
    private val pollErrors = HashSet<String>()
    private var polling = false

    private fun <T> watch(info: DJIKeyInfo<T>, apply: (DroneState, T?) -> DroneState) {
        val key: DJIKey<T> = KeyTools.createKey(info)
        val name = info.toString().substringAfterLast('.')
        try {
            KeyManager.getInstance().listen(key, holder) { _, new -> update { apply(it, new) } }
        } catch (t: Throwable) { log("Beobachten von $name fehlgeschlagen: ${t.message}") }
        // Rückfall: Wert auch direkt abfragen (falls Meldungen ausbleiben).
        polls += {
            try {
                val v = KeyManager.getInstance().getValue(key)
                if (v != null) update { apply(it, v) }  // null = kein Wert, nicht „Wert weg“
            } catch (t: Throwable) {
                if (pollErrors.add(name)) log("Abfrage $name: ${t.javaClass.simpleName} ${t.message}")
            }
        }
        polls[polls.size - 1]()
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        val tick = object : Runnable {
            override fun run() {
                Thread { polls.forEach { it() } }.start()
                main.postDelayed(this, 2000)
            }
        }
        main.postDelayed(tick, 2000)
    }

    /** Einmalige Momentaufnahme ins Protokoll, um Verbindungsprobleme einzugrenzen. */
    fun logSnapshot(reason: String) {
        val s = state
        log("[$reason] Contr=${s.rcConnected} Funk=${s.airLinkConnected} Drohne=${s.connected} Typ=${s.productType} SN=${s.serial} Akku=${s.batteryPercent}")
    }

    private fun startTelemetry() {
        watch(RemoteControllerKey.KeyConnection) { s, v -> s.copy(rcConnected = v == true) }
        try {
            KeyManager.getInstance().listen(KeyTools.createKey(RemoteControllerKey.KeyPairingStatus), this) { _, v ->
                log("Kopplung: $v")
            }
        } catch (t: Throwable) { log("Kopplungsstatus nicht lesbar: ${t.message}") }
        watch(AirLinkKey.KeyConnection) { s, v -> s.copy(airLinkConnected = v == true) }
        watch(ProductKey.KeyProductType) { s, v -> s.copy(productType = v?.name) }
        watch(FlightControllerKey.KeySerialNumber) { s, v -> val sn = v?.toString()?.takeIf { it.isNotBlank() }; if (sn != null && sn != s.serial) log("Seriennummer Fluggerät: $sn"); s.copy(serial = sn ?: s.serial) }
        watch(FlightControllerKey.KeyConnection) { s, v -> s.copy(connected = v == true) }
        watch(BatteryKey.KeyChargeRemainingInPercent) { s, v -> s.copy(batteryPercent = v) }
        watch(FlightControllerKey.KeyGPSSatelliteCount) { s, v -> s.copy(satellites = v) }
        watch(FlightControllerKey.KeyAircraftLocation3D) { s, v -> s.copy(location = v) }
        watch(FlightControllerKey.KeyHomeLocation) { s, v -> s.copy(home = v) }
        watch(FlightControllerKey.KeyCompassHeading) { s, v -> s.copy(headingDeg = v) }
        watch(FlightControllerKey.KeyIsFlying) { s, v -> s.copy(flying = v == true) }
        watch(FlightControllerKey.KeyAreMotorsOn) { s, v -> s.copy(motorsOn = v == true) }
        // Landebestätigung: DJI hält kurz über dem Boden an und wartet auf ein Ja. Das gibt die App selbst.
        watch(FlightControllerKey.KeyHeightLimit) { s, v -> s.copy(heightLimitM = v) }
        watch(FlightControllerKey.KeyWindWarning) { s, v ->
            val lvl = when (v) { dji.sdk.keyvalue.value.flightcontroller.WindWarning.LEVEL_1 -> 1; dji.sdk.keyvalue.value.flightcontroller.WindWarning.LEVEL_2 -> 2; dji.sdk.keyvalue.value.flightcontroller.WindWarning.LEVEL_0 -> 0; else -> null }
            if (lvl != null && lvl != s.windWarning && (lvl > 0 || s.windWarning != null)) log(when (lvl) { 0 -> "Wind: keine Warnung mehr"; 1 -> "WIND: mäßige Windwarnung, nahe Gebäuden nicht fliegen"; else -> "WIND: STARKER WIND, sofort zurück und landen" })
            s.copy(windWarning = lvl ?: s.windWarning)
        }
        watch(FlightControllerKey.KeyIsLandingConfirmationNeeded) { s, v -> s.copy(landingConfirmationNeeded = v == true) }
        watch(FlightControllerKey.KeyIsInLandingMode) { s, v -> s.copy(landing = v == true) }
        addStateListener { s -> if (s.landingConfirmationNeeded && !confirmSent) { confirmSent = true; confirmLanding("Drohne wartet auf Landebestätigung") } else if (!s.landingConfirmationNeeded) confirmSent = false }
        try {
            dji.v5.manager.aircraft.perception.PerceptionManager.getInstance().addPerceptionInformationListener { info ->
                val blind = buildList {
                    if (info.forwardObstacleAvoidanceWorking == false) add("vorn")
                    if (info.backwardObstacleAvoidanceWorking == false) add("hinten")
                    if (info.leftSideObstacleAvoidanceWorking == false) add("links")
                    if (info.rightSideObstacleAvoidanceWorking == false) add("rechts")
                    if (info.downwardObstacleAvoidanceWorking == false) add("unten")
                }
                val text = when {
                    !info.isOverallObstacleAvoidanceEnabled || info.obstacleAvoidanceType == dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType.CLOSE -> "aus"
                    blind.isNotEmpty() -> "blind: " + blind.joinToString(", ")
                    else -> "aktiv"
                }
                if (text != state.sensors) { log("Hinderniserkennung: $text (${info.obstacleAvoidanceType})"); update { it.copy(sensors = text) } }
            }
        } catch (t: Throwable) { log("Hinderniserkennung nicht abfragbar: ${t.message}") }
        startPolling()
        log("Telemetrie läuft")
    }

    /** Fernbedienung in den Kopplungsmodus schalten (danach Einschaltknopf der Drohne ~4 s halten). */
    fun requestPairing() = safe("Koppeln") {
        KeyManager.getInstance().performAction(
            KeyTools.createKey(RemoteControllerKey.KeyRequestPairing),
            object : dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                    log("Fernbedienung ist im Kopplungsmodus: jetzt Einschaltknopf der Drohne ca. 4 s halten, bis sie piept")
                }
                override fun onFailure(error: IDJIError) { log("Koppeln fehlgeschlagen: ${error.description()}") }
            })
    }

    private var confirmSent = false

    /** Landung bestätigen (Drohne hängt ~0,5 m über dem Boden und wartet). */
    fun confirmLanding(reason: String) { if (Simulator.active) return; confirmLandingReal(reason) }
    private fun confirmLandingReal(reason: String) = safe("Landebestätigung") {
        log("$reason → bestätige Landung")
        KeyManager.getInstance().performAction(
            KeyTools.createKey(FlightControllerKey.KeyConfirmLanding),
            object : dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) { log("Landung bestätigt") }
                override fun onFailure(error: IDJIError) { log("Landebestätigung fehlgeschlagen: ${error.description()}") }
            })
    }

    /** Videoaufnahme beenden (nach Missionsende/Abbruch, falls ein Plan-Video noch läuft). Fehler = lief keine Aufnahme. */
    fun stopRecording(reason: String) { if (Simulator.active) return; safe("Video stoppen") {
        KeyManager.getInstance().performAction(
            KeyTools.createKey(dji.sdk.keyvalue.key.CameraKey.KeyStopRecord),
            object : dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) { log("$reason: Videoaufnahme beendet") }
                override fun onFailure(error: IDJIError) { }
            })
    } }

    /** Landung auslösen (falls die Drohne nach der Mission nur schwebt). */
    fun startLanding() { if (Simulator.active) return; startLandingReal() }
    private fun startLandingReal() = safe("Landen") {
        KeyManager.getInstance().performAction(
            KeyTools.createKey(FlightControllerKey.KeyStartAutoLanding),
            object : dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) { log("Landung eingeleitet") }
                override fun onFailure(error: IDJIError) { log("Landen fehlgeschlagen: ${error.description()}") }
            })
    }

    /**
     * Landehelfer nach einer Mission: Schwebt die Drohne nach dem Heimflug tief und bewegt sich 8 s nicht,
     * bestätigt die App die Landung bzw. leitet sie ein. Endet, sobald die Drohne am Boden ist (max. 5 min).
     */
    fun landingHelper() {
        if (Simulator.active) return
        var stableSince = 0L
        var lastAlt = Double.NaN
        var tries = 0
        val tick = object : Runnable {
            override fun run() {
                val s = state
                if (!s.flying && !s.motorsOn) { log("Landehelfer: Drohne ist am Boden"); return }
                if (tries++ > 150) { log("Landehelfer: aufgegeben (5 min)"); return }
                val alt = s.location?.altitude ?: Double.NaN
                val now = System.currentTimeMillis()
                if (s.landingConfirmationNeeded) {
                    confirmLanding("Landehelfer")
                } else if (!alt.isNaN() && alt < 8.0 && !lastAlt.isNaN() && kotlin.math.abs(alt - lastAlt) < 0.4) {
                    if (stableSince == 0L) stableSince = now
                    if (now - stableSince > 8_000) {
                        log(String.format(Locale.GERMANY, "Landehelfer: schwebt seit 8 s auf %.1f m", alt))
                        confirmLanding("Landehelfer"); startLanding(); stableSince = now
                    }
                } else stableSince = 0L
                lastAlt = alt
                main.postDelayed(this, 2000)
            }
        }
        main.postDelayed(tick, 2000)
    }

    /** Rückkehrhöhe (m) setzen, damit der Heimflug nicht unnötig hoch steigt. */
    fun setGoHomeHeight(h: Int) { if (Simulator.active) { log("Simulation: Rückkehrhöhe $h m"); return }; setGoHomeHeightReal(h) }
    private fun setGoHomeHeightReal(h: Int) = safe("Rückkehrhöhe") {
        KeyManager.getInstance().setValue(KeyTools.createKey(FlightControllerKey.KeyGoHomeHeight), h,
            object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
                override fun onSuccess() { log("Rückkehrhöhe: $h m") }
                override fun onFailure(error: IDJIError) { log("Rückkehrhöhe $h m fehlgeschlagen: ${error.description()}") }
            })
    }

    private var heightLimitAskedAt = 0L
    /** DJI-Höhenlimit (Max Altitude) anheben, wenn der Plan höher fliegt (Felix 9.11.: stand in DJI Fly auf 15 m). Höchstens alle 5 s. */
    fun raiseHeightLimit(h: Int) { if (Simulator.active) return; val now = System.currentTimeMillis(); if (now - heightLimitAskedAt < 5000) return; heightLimitAskedAt = now; safe("Höhenlimit") {
        KeyManager.getInstance().setValue(KeyTools.createKey(FlightControllerKey.KeyHeightLimit), h,
            object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
                override fun onSuccess() { log("DJI-Höhenlimit auf $h m gesetzt") }
                override fun onFailure(error: IDJIError) { log("Höhenlimit $h m setzen fehlgeschlagen: ${error.description()} – bitte in DJI Fly unter Safety → Max Altitude") }
            })
    } }

    /** Landelampe unten: "an", "aus" oder "auto" (Standard: nur beim Landen). */
    fun setBottomLight(mode: String) { if (Simulator.active) { log("Simulation: Landelampe $mode"); return }; setBottomLightReal(mode) }
    private fun setBottomLightReal(mode: String) = safe("Licht") {
        val m = when (mode) { "an" -> AuxiliaryLightMode.ON; "aus" -> AuxiliaryLightMode.OFF; else -> AuxiliaryLightMode.AUTO }
        KeyManager.getInstance().setValue(
            KeyTools.createKey(FlightAssistantKey.KeyBottomAuxiliaryLightMode), m,
            object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
                override fun onSuccess() { log("Landelampe: $mode") }
                override fun onFailure(error: IDJIError) { log("Landelampe $mode fehlgeschlagen: ${error.description()}") }
            })
    }

    /** Rückkehr zum Startpunkt auslösen (Notfallknopf). */
    fun goHome() { if (Simulator.active) { Simulator.goHome(); return }; goHomeReal() }
    private fun goHomeReal() = safe("Rückkehr") {
        KeyManager.getInstance().performAction(
            KeyTools.createKey(FlightControllerKey.KeyStartGoHome),
            object : dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) { log("Rückkehr zum Startpunkt ausgelöst"); landingHelper() }
                override fun onFailure(error: IDJIError) { log("Rückkehr fehlgeschlagen: ${error.description()}") }
            })
    }
}
