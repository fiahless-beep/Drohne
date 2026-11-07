package com.felixos.drohne

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
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
)

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

    fun log(msg: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date()) + "  " + msg
        Log.i(TAG, msg)
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 200) logLines.removeFirst()
        }
        main.post { logListeners.forEach { it(line) } }
    }

    private fun update(f: (DroneState) -> DroneState) {
        state = f(state)
        val s = state
        main.post { stateListeners.forEach { it(s) } }
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
        log("[$reason] Contr=${s.rcConnected} Funk=${s.airLinkConnected} Drohne=${s.connected} Typ=${s.productType} Akku=${s.batteryPercent}")
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
        watch(FlightControllerKey.KeyConnection) { s, v -> s.copy(connected = v == true) }
        watch(BatteryKey.KeyChargeRemainingInPercent) { s, v -> s.copy(batteryPercent = v) }
        watch(FlightControllerKey.KeyGPSSatelliteCount) { s, v -> s.copy(satellites = v) }
        watch(FlightControllerKey.KeyAircraftLocation3D) { s, v -> s.copy(location = v) }
        watch(FlightControllerKey.KeyHomeLocation) { s, v -> s.copy(home = v) }
        watch(FlightControllerKey.KeyCompassHeading) { s, v -> s.copy(headingDeg = v) }
        watch(FlightControllerKey.KeyIsFlying) { s, v -> s.copy(flying = v == true) }
        watch(FlightControllerKey.KeyAreMotorsOn) { s, v -> s.copy(motorsOn = v == true) }
        // Landebestätigung: DJI hält kurz über dem Boden an und wartet auf ein Ja. Das gibt die App selbst.
        try {
            KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyIsLandingConfirmationNeeded), holder) { _, v ->
                if (v == true) {
                    log("Drohne wartet auf Landebestätigung → bestätige Landung")
                    safe("Landebestätigung") {
                        KeyManager.getInstance().performAction(
                            KeyTools.createKey(FlightControllerKey.KeyConfirmLanding),
                            object : dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                                override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) { log("Landung bestätigt") }
                                override fun onFailure(error: IDJIError) { log("Landebestätigung fehlgeschlagen: ${error.description()}") }
                            })
                    }
                }
            }
        } catch (t: Throwable) { log("Landebestätigung nicht verfügbar: ${t.message}") }
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

    /** Rückkehr zum Startpunkt auslösen (Notfallknopf). */
    fun goHome() = safe("Rückkehr") {
        KeyManager.getInstance().performAction(
            KeyTools.createKey(FlightControllerKey.KeyStartGoHome),
            object : dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) { log("Rückkehr zum Startpunkt ausgelöst") }
                override fun onFailure(error: IDJIError) { log("Rückkehr fehlgeschlagen: ${error.description()}") }
            })
    }
}
