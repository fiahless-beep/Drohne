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
    data class Settings(val heightM: Double = 20.0, val distanceM: Double = 10.0, val maxSpeedMs: Double = 3.0)

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
        settings = s
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
                DroneManager.log("Virtual Stick an")
                vs.setVirtualStickAdvancedModeEnabled(true)
                if (!DroneManager.state.flying) takeoff() else loop()
            }
            override fun onFailure(error: IDJIError) { DroneManager.log("Virtual Stick fehlgeschlagen: ${error.description()}"); stop() }
        })
    }

    private fun takeoff() {
        DroneManager.log("Folgen: starte Drohne")
        KeyManager.getInstance().performAction(KeyTools.createKey(FlightControllerKey.KeyStartTakeoff),
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) { loop() }
                override fun onFailure(error: IDJIError) { DroneManager.log("Start fehlgeschlagen: ${error.description()}"); stop() }
            })
    }

    private fun loop() {
        val t = HandlerThread("folgen").also { it.start() }
        thread = t
        handler = Handler(t.looper).also { h ->
            h.post(object : Runnable {
                override fun run() { if (!active) return; try { tick() } catch (e: Throwable) { DroneManager.log("Folgen-Fehler: ${e.message}") }; h.postDelayed(this, 100) }
            })
        }
    }

    private fun tick() {
        val st = DroneManager.state
        val drone = st.location
        val tgt = target
        val stale = tgt == null || System.currentTimeMillis() - tgt.time > 5_000
        ticks++
        // Sicherheitsnetz
        st.batteryPercent?.let { if (it < 25) { DroneManager.log("Folgen: Akku $it % → Heimflug"); stop(); DroneManager.goHome(); return } }
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
            val speed = if (abs(err) < 1.5) 0.0 else (0.5 * err).coerceIn(-settings.maxSpeedMs, settings.maxSpeedMs)
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
        p.verticalThrottle = settings.heightM
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
        if (!active) return
        active = false
        try { lm?.removeUpdates(listener) } catch (_: Throwable) {}
        thread?.quitSafely(); thread = null; handler = null
        try {
            VirtualStickManager.getInstance().disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { DroneManager.log("Folgen beendet, Drohne schwebt") }
                override fun onFailure(error: IDJIError) { DroneManager.log("Virtual Stick aus fehlgeschlagen: ${error.description()}") }
            })
        } catch (t: Throwable) { DroneManager.log("Folgen beendet: ${t.message}") }
    }
}
