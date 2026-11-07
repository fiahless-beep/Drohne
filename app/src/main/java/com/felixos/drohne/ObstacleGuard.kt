package com.felixos.drohne

import android.content.Context
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import java.util.Locale

/**
 * Hinderniswächter auf dem Handy (nach dem Absturz 7.11.2026): schaut ins Livebild und hält die Mission an,
 * wenn sich die Bildmitte über ~1 s hinweg stetig aufbläht (eine Fläche kommt schnell näher), während die
 * Drohne waagerecht fliegt. Sicherheitsnetz, kein Ersatz für Licht, Höhe und DJIs Sensoren.
 */
object ObstacleGuard {
    private const val PREFS = "drohne"
    private const val KEY_ON = "guard_on"
    private const val KEY_ARMED = "guard_armed"   // armed = darf Mission anhalten; sonst nur protokollieren

    @Volatile var enabled = true; private set
    @Volatile var armed = false; private set
    @Volatile var lastExpansion: Double? = null; private set

    private var prev: FloatArray? = null
    private var frameNo = 0
    private var hits = 0
    private var lastTriggerMs = 0L
    private var lastLogMs = 0L
    private var listening = false
    private var lastAlt = Double.NaN; private var lastLat = 0.0; private var lastLon = 0.0; private var lastPosMs = 0L
    @Volatile var descending = false; private set

    private val listener = ICameraStreamManager.CameraFrameListener { data, offset, _, width, height, format ->
        try { onFrame(data, offset, width, height, format) } catch (_: Throwable) {}
    }

    fun init(c: Context) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = p.getBoolean(KEY_ON, true); armed = p.getBoolean(KEY_ARMED, true)
        DroneManager.addStateListener { s -> if (s.connected && s.registered) start() }
    }

    fun setEnabled(c: Context, v: Boolean) { enabled = v; c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, v).apply(); DroneManager.log("Wächter ${if (v) "an" else "aus"}") }
    fun setArmed(c: Context, v: Boolean) { armed = v; c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ARMED, v).apply(); DroneManager.log("Wächter ${if (v) "darf anhalten" else "nur beobachten"}") }

    private fun start() {
        if (listening) return
        try {
            MediaDataCenter.getInstance().cameraStreamManager.addFrameListener(ComponentIndexType.LEFT_OR_MAIN, ICameraStreamManager.FrameFormat.NV21, listener)
            listening = true
            DroneManager.log("Wächter: Livebild wird mitgelesen")
        } catch (t: Throwable) { DroneManager.log("Wächter: kein Bildzugriff (${t.message})") }
    }

    private fun onFrame(data: ByteArray, offset: Int, width: Int, height: Int, format: ICameraStreamManager.FrameFormat) {
        if (!enabled || width < 64 || height < 36) return
        if (++frameNo % 3 != 0) return  // ~10 Bilder/s reichen
        val cur = Looming.downscale(data, offset, width, height)
        val p = prev
        prev = cur
        if (p == null) return
        val s = Looming.expansion(p, cur) ?: return
        lastExpansion = s
        val st = DroneManager.state
        val now = System.currentTimeMillis()
        // Reiner Abstieg mit Kamera nach unten sieht auch wie „Fläche kommt näher“ aus: dann nicht werten.
        st.location?.let { l ->
            if (lastPosMs > 0 && now - lastPosMs >= 400) {
                val dt = (now - lastPosMs) / 1000.0
                val vz = if (lastAlt.isNaN()) 0.0 else (l.altitude - lastAlt) / dt
                val vh = com.felixos.drohne.plan.Geo.distance(com.felixos.drohne.plan.LatLon(lastLat, lastLon), com.felixos.drohne.plan.LatLon(l.latitude, l.longitude)) / dt
                descending = vz < -0.4 && vh < 0.6
                lastAlt = l.altitude; lastLat = l.latitude; lastLon = l.longitude; lastPosMs = now
            } else if (lastPosMs == 0L) { lastAlt = l.altitude; lastLat = l.latitude; lastLon = l.longitude; lastPosMs = now }
        }
        if (descending) { hits = 0; return }
        val moving = st.flying && (MissionController.executing || FollowController.active)
        if (s >= 1.06) hits++ else hits = maxOf(0, hits - 1)
        if (now - lastLogMs > 5000 && moving) { lastLogMs = now; DroneManager.log(String.format(Locale.GERMANY, "Wächter: Vergrößerung %.2f/Bild, Treffer %d", s, hits)) }
        if (hits >= 6 && moving && now - lastTriggerMs > 10_000) {
            lastTriggerMs = now; hits = 0
            if (armed) {
                DroneManager.log("WÄCHTER: Fläche kommt schnell näher → Mission angehalten, Drohne schwebt. „Weiter“ zum Fortsetzen.")
                if (MissionController.executing) MissionController.pause()
                if (FollowController.active) FollowController.stop()
            } else {
                DroneManager.log("WÄCHTER (nur beobachten): Fläche kommt schnell näher – hätte angehalten")
            }
        }
    }
}
