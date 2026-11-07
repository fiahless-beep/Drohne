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

    fun init(context: Context) {
        log("SDK wird gestartet …")
        SDKManager.getInstance().init(context, object : SDKManagerCallback {
            override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
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

    private fun <T> watch(info: DJIKeyInfo<T>, apply: (DroneState, T?) -> DroneState) {
        val key: DJIKey<T> = KeyTools.createKey(info)
        KeyManager.getInstance().listen(key, holder) { _, new -> update { apply(it, new) } }
        try {
            val v = KeyManager.getInstance().getValue(key)
            if (v != null) update { apply(it, v) }
        } catch (_: Throwable) {}
    }

    private fun startTelemetry() {
        watch(FlightControllerKey.KeyConnection) { s, v -> s.copy(connected = v == true) }
        watch(BatteryKey.KeyChargeRemainingInPercent) { s, v -> s.copy(batteryPercent = v) }
        watch(FlightControllerKey.KeyGPSSatelliteCount) { s, v -> s.copy(satellites = v) }
        watch(FlightControllerKey.KeyAircraftLocation3D) { s, v -> s.copy(location = v) }
        watch(FlightControllerKey.KeyHomeLocation) { s, v -> s.copy(home = v) }
        watch(FlightControllerKey.KeyCompassHeading) { s, v -> s.copy(headingDeg = v) }
        watch(FlightControllerKey.KeyIsFlying) { s, v -> s.copy(flying = v == true) }
        watch(FlightControllerKey.KeyAreMotorsOn) { s, v -> s.copy(motorsOn = v == true) }
        log("Telemetrie läuft")
    }

    /** Rückkehr zum Startpunkt auslösen (Notfallknopf). */
    fun goHome() {
        KeyManager.getInstance().performAction(
            KeyTools.createKey(FlightControllerKey.KeyStartGoHome),
            object : dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) { log("Rückkehr zum Startpunkt ausgelöst") }
                override fun onFailure(error: IDJIError) { log("Rückkehr fehlgeschlagen: ${error.description()}") }
            })
    }
}
