package com.felixos.drohne

import com.felixos.drohne.plan.Budget
import com.felixos.drohne.plan.Geo
import com.felixos.drohne.plan.LatLon
import com.felixos.drohne.plan.Mission
import com.felixos.drohne.plan.Terrain
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Guards that watch a running plan (Felix 13.11.): every half second the real position is compared with the plan.
 *  1. Corridor: more than 10 m beside the planned route or more than 5 m outside its height → pause (hover).
 *  2. Ground clearance: less than 3 m above the LiDAR surface under the drone → pause.
 *  4. Battery turn-back: the way home would eat into the reserve → stop the plan and return home.
 * (3., the return path from every waypoint, is checked before takeoff in MissionController.prepare.)
 * Every action goes towards hovering or returning; the guards never steer the drone anywhere else.
 */
object FlightGuard {
    @Volatile var enabled = true
    /** Last guard action, shown in the status line until the plan runs again. */
    @Volatile var alert: String? = null
    var onAlert: ((String) -> Unit)? = null

    const val LATERAL_M = 10.0
    const val VERTICAL_M = 5.0
    const val GROUND_M = 3.0

    private var started = false
    private var lastCheck = 0L
    private var lastTrip = 0L
    private var offRoute = 0
    private var low = 0
    private var armed = false
    private var batteryTripped = false
    private var key: Mission? = null

    fun init() {
        if (started) return
        started = true
        DroneManager.addStateListener { s -> try { check(s) } catch (t: Throwable) { DroneManager.log("Flight guard error: ${t.javaClass.simpleName} ${t.message}") } }
    }

    private fun check(s: DroneState) {
        val now = System.currentTimeMillis()
        if (now - lastCheck < 500) return
        lastCheck = now
        val m = MissionController.currentMission
        if (!enabled || m == null || !MissionController.executing || !s.flying) { offRoute = 0; low = 0; if (!s.flying) batteryTripped = false; return }
        if (m !== key) { key = m; offRoute = 0; low = 0; armed = false; batteryTripped = false }
        val loc = s.location?.takeIf { it.latitude != 0.0 } ?: return
        val p = LatLon(loc.latitude, loc.longitude)
        val alt = loc.altitude
        battery(s, m, p, alt)
        if (!MissionController.executing) return

        // 1. Corridor
        val w = m.waypoints
        var nearest = Double.MAX_VALUE
        var dh = Double.MAX_VALUE
        for (i in 0 until w.size - 1) {
            val a = w[i]; val b = w[i + 1]
            val d = distToSegment(p, a.pos, b.pos)
            if (d < nearest) nearest = d
            if (d <= LATERAL_M) {
                val lo = min(a.heightM, b.heightM) - VERTICAL_M; val hi = max(a.heightM, b.heightM) + VERTICAL_M
                dh = min(dh, if (alt < lo) lo - alt else if (alt > hi) alt - hi else 0.0)
            }
        }
        val onRoute = nearest <= LATERAL_M && dh == 0.0
        if (onRoute) armed = true
        if (!armed) return  // still on the way to the route (DJI's own approach)
        offRoute = if (onRoute) 0 else offRoute + 1
        if (offRoute >= 3) return trip(if (nearest > LATERAL_M) "the drone is ${nearest.toInt()} m beside the planned route" else "height ${alt.toInt()} m is more than ${VERTICAL_M.toInt()} m off the plan")

        // 2. Ground clearance (not during the final hand-over descent in front of a balcony)
        val home = MissionController.preparedHome ?: return
        val g = Terrain.groundM(home) ?: return
        val top = (listOf(p) + listOf(0.0, 90.0, 180.0, 270.0).map { Geo.offset(p, 3.0, it) }).mapNotNull { Terrain.surfaceM(it) }.maxOrNull() ?: return
        val rel = top - g - MissionController.startAboveGroundM
        val tail = MissionController.hasLandingTail && MissionController.lastReachedIndex >= w.size - 2
        low = if (!tail && alt < rel + GROUND_M) low + 1 else 0
        if (low >= 3) trip("only ${(alt - rel).toInt()} m above the surface (roof, tree or ground) below the drone")
    }

    private fun trip(why: String) {
        val now = System.currentTimeMillis()
        offRoute = 0; low = 0
        if (now - lastTrip < 10_000) return
        lastTrip = now
        val text = "⚠ Guard paused the plan: $why. Hovering. You have control."
        alert = text
        DroneManager.log(text)
        MissionController.pause()
        onAlert?.invoke(text)
    }

    /** 4. Battery: what the way home costs from here (climb to return height, cruise, descent) plus the reserve. */
    private fun battery(s: DroneState, m: Mission, p: LatLon, alt: Double) {
        if (batteryTripped) return
        val home = MissionController.preparedHome ?: return
        val have = s.batteryPercent ?: return
        val rth = MissionController.rthHeightM
        val sec = Geo.distance(p, home) / 8.0 + max(0.0, rth - alt) / 3.0 + max(alt, rth) / 2.0 + 20
        val need = ceil(sec / 60 / Budget.MINUTES_PER_FULL_BATTERY * 100).toInt() + Budget.RESERVE_PERCENT
        if (have > need) return
        batteryTripped = true
        val balcony = (m.startAboveGroundM ?: 0.0) > 0.1 || MissionController.startAboveGroundM > 0.1
        val text = if (balcony) "⚠ Battery $have %: plan paused (the way back needs $need % incl. reserve). Fly back and land by hand now."
            else "⚠ Battery $have %: returning home now (the way back needs $need % incl. reserve)."
        alert = text
        DroneManager.log(text)
        if (balcony) MissionController.pause() else { MissionController.stop(); DroneManager.goHome() }
        onAlert?.invoke(text)
    }

    private fun distToSegment(p: LatLon, a: LatLon, b: LatLon): Double {
        val kx = 111_320.0 * cos(Math.toRadians(p.lat)); val ky = 110_540.0
        val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * ky
        val bx = (b.lon - p.lon) * kx; val by = (b.lat - p.lat) * ky
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy)
    }
}
