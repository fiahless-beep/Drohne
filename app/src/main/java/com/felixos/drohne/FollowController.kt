package com.felixos.drohne

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.felixos.drohne.plan.Geo
import com.felixos.drohne.plan.LatLon
import com.felixos.drohne.plan.Missions
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * „Folgen“: Die Drohne hält Abstand und Höhe zum Handy (GPS des Handys = Ziel) und richtet
 * Kamera und Nase darauf. Steuerung über Virtual Stick (Geschwindigkeit, 10 Hz).
 * Stufe 1 des Verfolgens: Ziel = Handy-GPS. Stufe 2 (später): Ziel aus Bilderkennung.
 */
object FollowController {
    data class Settings(val heightM: Double = 30.0, val distanceM: Double = 15.0, val maxSpeedMs: Double = 3.0)

    private const val PREFS = "drohne"
    private const val KEY_SWAP = "follow_swap_axes"

    @Volatile var active = false; private set
    private var settings = Settings()
    private var target: Location? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lm: LocationManager? = null
    private var ticks = 0
    private var swapAxes = false
    /** Jede Sitzung hat eine Nummer: ein verspäteter Rückruf einer beendeten Sitzung startet nichts mehr (Befund Zweitprüfung 26.11.2026). */
    @Volatile private var sitzung = 0
    private var fehlerInFolge = 0
    private var ohneZielSeit = 0L
    /** Virtual Stick ließ sich nicht abschalten: sichtbar für die Oberfläche, bis es gelingt. */
    @Volatile var abschaltenOffen = false; private set

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) { target = l }
        @Deprecated("alt") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) { DroneManager.log("Folgen: Handy-GPS aus") }
    }

    fun swapAxes(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SWAP, false)
    fun setSwapAxes(c: Context, v: Boolean) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SWAP, v).apply()

    @SuppressLint("MissingPermission")
    fun start(c: Context, s: Settings) {
        if (active) return DroneManager.log("Folgen läuft schon")
        if (!DroneManager.requireSdk("Folgen")) return
        val st = DroneManager.state
        if (!st.connected) return DroneManager.log("Folgen: Drohne nicht verbunden")
        if (st.location == null || st.location.latitude == 0.0) return DroneManager.log("Folgen: keine Drohnenposition")
        // Abheben bleibt beim gehaltenen Knopf (Felix 23.11.2026): Folgen beginnt nur aus dem Schweben.
        if (!st.flying) return DroneManager.log("Folgen: Drohne ist nicht in der Luft, kein Start")
        // Die Grenzen gelten an der Tür, die wirklich startet, nicht nur in der Oberfläche.
        if (!s.heightM.isFinite() || !s.distanceM.isFinite() || s.heightM < 30.0 || s.distanceM < 15.0) return DroneManager.log("Folgen: Höhe unter 30 m oder Abstand unter 15 m, kein Start")
        settings = s
        val meine = ++sitzung
        fehlerInFolge = 0; ohneZielSeit = 0L; ticks = 0
        swapAxes = swapAxes(c)
        target = null
        lm = (c.getSystemService(Context.LOCATION_SERVICE) as LocationManager).also {
            it.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, listener, Looper.getMainLooper())
            it.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { l -> target = l }
        }
        FlightReport.startFlight("folgen")
        active = true
        DroneManager.log("Folgen: Höhe ${s.heightM.toInt()} m, Abstand ${s.distanceM.toInt()} m, max ${s.maxSpeedMs} m/s" + if (swapAxes) " (Achsen getauscht)" else "")
        val vs = VirtualStickManager.getInstance()
        vs.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                if (meine != sitzung || !active) {
                    // Inzwischen beendet: was dieser späte Erfolg eingeschaltet hat, wieder ausschalten.
                    DroneManager.log("Virtual Stick an, aber Folgen wurde schon beendet → wieder aus")
                    if (!active) abschalten(0)
                    return
                }
                DroneManager.log("Virtual Stick an")
                vs.setVirtualStickAdvancedModeEnabled(true)
                if (!DroneManager.state.flying) { DroneManager.log("Folgen: nicht mehr in der Luft, beendet"); stop() } else loop(meine)
            }
            override fun onFailure(error: IDJIError) { if (meine != sitzung) return; DroneManager.log("Virtual Stick fehlgeschlagen: ${error.description()}"); stop() }
        })
    }

    private fun loop(meine: Int) {
        thread?.quitSafely()
        val t = HandlerThread("folgen").also { it.start() }
        thread = t
        handler = Handler(t.looper).also { h ->
            h.post(object : Runnable {
                override fun run() {
                    if (!active || meine != sitzung) return
                    try { tick(meine); fehlerInFolge = 0 } catch (e: Throwable) {
                        DroneManager.log("Folgen-Fehler: ${e.message}")
                        // Ein Regler, der nur noch Fehler wirft, steuert nicht: nach einer halben Sekunde beenden, die Drohne schwebt.
                        if (++fehlerInFolge >= 5) { DroneManager.log("Folgen: fünf Fehler in Folge → beendet"); stop(); return }
                    }
                    h.postDelayed(this, 100)
                }
            })
        }
    }

    /** Ein Stick wurde bewegt: Folgen endet sofort, der Pilot hat die Drohne (wie die Pause im Planflug). */
    fun stickMoved() { if (active && !Simulator.active) { DroneManager.log("Stick moved while following → following ended, you have control"); stop() } }

    private fun tick(meine: Int) {
        val st = DroneManager.state
        // Gelandet, im Landen oder ohne Verbindung: Folgen ist vorbei. Nie am Boden weiter „Höhe 30 m“ senden.
        if (!st.connected || !st.flying || st.landing) { DroneManager.log("Folgen: nicht mehr im Flug oder keine Verbindung → beendet"); stop(); return }
        val drone = st.location
        val tgt = target
        val stale = tgt == null || System.currentTimeMillis() - tgt.time > 5_000
        ticks++
        // Sicherheitsnetz
        // Akku: unbekannt heißt nicht „genug“. Die Schwelle ist der Heimweg von hier samt Reserve, mindestens 25 %.
        val akku = st.batteryPercent
        if (akku == null) { DroneManager.log("Folgen: Akkustand unbekannt → beendet, Drohne schwebt"); stop(); return }
        val heimM = st.home?.takeIf { it.latitude != 0.0 && drone != null }?.let { h -> Geo.distance(LatLon(drone!!.latitude, drone.longitude), LatLon(h.latitude, h.longitude)) } ?: 0.0
        val schwelle = maxOf(25, com.felixos.drohne.plan.Budget.heimwegProzent(heimM, drone?.altitude ?: settings.heightM, settings.heightM))
        if (akku < schwelle) { DroneManager.log("Folgen: Akku $akku % (Schwelle $schwelle %) → Heimflug"); stop(); DroneManager.goHome(); return }
        // GPS der Drohne schwach oder Hinderniserkennung aus: Folgen endet, die Drohne schwebt, der Pilot hat sie.
        st.satellites?.let { if (it < 8) { DroneManager.log("Folgen: nur $it Satelliten → beendet, Drohne schwebt"); stop(); return } }
        if (!st.sensorsOk && !Simulator.active) { DroneManager.log("Folgen: Hinderniserkennung nicht aktiv → beendet, Drohne schwebt"); stop(); return }
        // Kein frisches Handy-GPS oder keine Drohnenposition: 10 s schweben und warten, dann beenden.
        if (drone == null || stale) {
            val jetzt = android.os.SystemClock.elapsedRealtime()
            if (ohneZielSeit == 0L) ohneZielSeit = jetzt
            else if (jetzt - ohneZielSeit > 10_000) { DroneManager.log("Folgen: 10 s ohne ${if (drone == null) "Drohnenposition" else "Handy-GPS"} → beendet, Drohne schwebt"); stop(); return }
        } else ohneZielSeit = 0L
        st.home?.let { h -> if (drone != null && h.latitude != 0.0 && Geo.distance(LatLon(drone.latitude, drone.longitude), LatLon(h.latitude, h.longitude)) > 400) {
            DroneManager.log("Folgen: über 400 m vom Start → Stopp"); stop(); return } }

        var vNorth = 0.0; var vEast = 0.0; var yaw = st.headingDeg ?: 0.0
        if (!st.flying) {
            // Noch am Boden/Startphase: nur Höhe halten lassen
        } else if (drone == null || stale) {
            if (ticks % 30 == 0) DroneManager.log("Folgen: warte auf ${if (stale) "Handy-GPS" else "Drohnenposition"} (schwebe)")
        } else {
            val dp = LatLon(drone.latitude, drone.longitude)
            val tp = LatLon(tgt!!.latitude, tgt.longitude)
            val dist = Geo.distance(dp, tp)
            val bearing = Geo.bearing(dp, tp)
            yaw = bearing
            val err = dist - settings.distanceM
            // Erst auf Höhe, dann zur Seite: unterhalb der Folgehöhe (2 m Spiel) steigt die Drohne nur senkrecht.
            val aufHoehe = drone.altitude >= settings.heightM - 2.0
            if (!aufHoehe && ticks % 30 == 0) DroneManager.log(String.format(java.util.Locale.US, "Folgen: steige erst auf %.0f m (jetzt %.0f m), keine Bewegung zur Seite", settings.heightM, drone.altitude))
            val speed = if (!aufHoehe || abs(err) < 1.5) 0.0 else (0.5 * err).coerceIn(-settings.maxSpeedMs, settings.maxSpeedMs)
            val b = Math.toRadians(bearing)
            vNorth = speed * cos(b); vEast = speed * sin(b)
            if (ticks % 10 == 0) {
                setGimbal(Missions.pitchTo(dist, settings.heightM))
                if (ticks % 50 == 0) DroneManager.log(String.format(java.util.Locale.GERMANY, "Folgen: Ziel %.0f m, Richtung %.0f°, v=%.1f m/s, GPS ±%.0f m", dist, bearing, speed, tgt.accuracy))
            }
        }
        val p = VirtualStickFlightControlParam()
        p.verticalControlMode = VerticalControlMode.POSITION
        p.rollPitchControlMode = RollPitchControlMode.VELOCITY
        p.yawControlMode = YawControlMode.ANGLE
        p.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
        // DJI: im Boden-Koordinatensystem ist roll = Geschwindigkeit nach Norden (X), pitch = nach Osten (Y).
        if (!swapAxes) { p.roll = vNorth; p.pitch = vEast } else { p.roll = vEast; p.pitch = vNorth }
        var y = yaw % 360; if (y > 180) y -= 360
        p.yaw = y
        // Ohne gültiges Ziel kein neues Höhenziel: die Drohne hält die Höhe, die sie hat.
        p.verticalThrottle = if (drone != null && stale) drone.altitude else settings.heightM
        if (!active || meine != sitzung || drone == null) return
        VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(p)
    }

    private fun setGimbal(pitch: Double) {
        val r = GimbalAngleRotation()
        r.mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
        r.pitch = pitch; r.roll = 0.0; r.yaw = 0.0
        r.pitchIgnored = false; r.rollIgnored = true; r.yawIgnored = true
        r.duration = 0.5
        KeyManager.getInstance().performAction(KeyTools.createKey(GimbalKey.KeyRotateByAngle), r,
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) {}
                override fun onFailure(error: IDJIError) {}
            })
    }

    /** Folgen beenden: Virtual Stick aus, Drohne schwebt, Sticks haben wieder das Sagen. */
    fun stop() {
        if (!active) { if (abschaltenOffen) abschalten(0); return }
        active = false
        sitzung++
        try { lm?.removeUpdates(listener) } catch (_: Throwable) {}
        thread?.quitSafely(); thread = null; handler = null
        abschalten(0)
    }

    /** Virtual Stick ausschalten, bei Fehlschlag bis zu drei weitere Versuche; bleibt es an, sagt es die App laut. */
    private fun abschalten(versuch: Int) {
        try {
            VirtualStickManager.getInstance().disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { abschaltenOffen = false; DroneManager.log("Folgen beendet, Drohne schwebt") }
                override fun onFailure(error: IDJIError) {
                    DroneManager.log("Virtual Stick aus fehlgeschlagen (Versuch ${versuch + 1}): ${error.description()}")
                    if (active) return   // inzwischen neu gestartet: nichts abschalten
                    if (versuch < 3) Handler(Looper.getMainLooper()).postDelayed({ if (!active) abschalten(versuch + 1) }, 500)
                    else { abschaltenOffen = true; FlugSitzung.hinweis("Following ended, but the app could not hand control back. Move a stick or press the pause button on the controller.") }
                }
            })
        } catch (t: Throwable) { DroneManager.log("Folgen beendet: ${t.message}") }
    }
}
