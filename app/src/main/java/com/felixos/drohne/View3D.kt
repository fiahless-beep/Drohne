package com.felixos.drohne

import android.annotation.SuppressLint
import android.webkit.WebView
import com.felixos.drohne.plan.Buildings
import com.felixos.drohne.plan.Geo
import com.felixos.drohne.plan.LatLon
import com.felixos.drohne.plan.Mission
import com.felixos.drohne.plan.Obstacles
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos

/**
 * 3D-Ansicht in der App (assets/view3d.html, three.js): Gebäude aus der Stadtdatenbank im Umkreis des Plans,
 * Flugweg mit Transit, Startpunkt, Drohne live mit Spur. Koordinaten in Metern relativ zum Bezugspunkt.
 */
class View3D(private val web: WebView) {
    private var origin: LatLon? = null
    private var ready = false
    private var pendingScene: String? = null
    private var lastDroneMs = 0L

    @SuppressLint("SetJavaScriptEnabled")
    fun init() {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.setBackgroundColor(0xFF0B0F14.toInt())
        web.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { ready = true; pendingScene?.let { js("setScene($it)") }; pendingScene = null }
        }
        web.loadUrl("file:///android_asset/view3d.html")
    }

    private fun js(code: String) { if (ready) web.evaluateJavascript(code, null) }

    private fun local(p: LatLon): Pair<Double, Double> {
        val o = origin ?: return 0.0 to 0.0
        val x = (p.lon - o.lon) * 111_320.0 * cos(Math.toRadians(o.lat))
        val y = (p.lat - o.lat) * 110_540.0
        return x to y
    }

    /** Szene neu aufbauen: Plan (mit Transit, so wie vorbereitet) und Gebäude im Umkreis; [home] = Bezugspunkt. */
    fun setMission(m: Mission?, home: LatLon?, radiusM: Double = 350.0) {
        val o = home ?: m?.waypoints?.firstOrNull()?.pos ?: origin ?: return
        origin = o
        val pts = (m?.waypoints?.map { it.pos } ?: emptyList()) + o
        val obstacles = (m?.obstacles ?: emptyList()).ifEmpty { Buildings.near(pts, radiusM) }
        val b = JSONArray()
        obstacles.forEach { ob ->
            if (ob.polygon.size < 3) return@forEach
            val poly = JSONArray(); ob.polygon.forEach { p -> val (x, y) = local(p); poly.put(JSONArray().put(Math.round(x * 10) / 10.0).put(Math.round(y * 10) / 10.0)) }
            val bo = JSONObject().put("h", ob.heightM).put("p", poly)
            val g = if (ob.groundRelM != 0.0) ob.groundRelM else (if (com.felixos.drohne.plan.Terrain.loaded) com.felixos.drohne.plan.Terrain.riseM(o, ob.polygon[0]) ?: 0.0 else 0.0)
            if (g != 0.0) bo.put("g", Math.round(g * 10) / 10.0)
            b.put(bo)
        }
        val r = JSONArray()
        m?.waypoints?.forEach { w -> val (x, y) = local(w.pos); r.put(JSONArray().put(Math.round(x * 10) / 10.0).put(Math.round(y * 10) / 10.0).put(w.heightM).put(w.headingDeg ?: JSONObject.NULL).put(w.gimbalPitchDeg).put((if (w.takePhoto) 2 else 0) + w.hoverSec)) }
        val scene = JSONObject().put("b", b).put("r", r).put("clear", Obstacles.CLEARANCE_M).put("mar", Obstacles.MARGIN_M)
        terrainJson(pts, o)?.let { scene.put("t", it) }
        val sceneStr = scene.toString()
        if (ready) js("setScene($sceneStr)") else pendingScene = sceneStr
    }

    /** Gelände aus dem Höhenmodell als 10-m-Raster relativ zum Startboden (wie tools/plan_artifact.py); Gebäude stehen in der Ansicht auf 0, das Raster zeigt das Gefälle. */
    private fun terrainJson(pts: List<LatLon>, o: LatLon): JSONObject? {
        if (!com.felixos.drohne.plan.Terrain.loaded) return null
        val g0 = com.felixos.drohne.plan.Terrain.groundM(o) ?: return null
        val xs = pts.map { local(it).first }; val ys = pts.map { local(it).second }
        val m = 300.0; val step = 10.0
        val x0 = Math.floor((xs.min() - m) / step) * step; val y0 = Math.floor((ys.min() - m) / step) * step
        val nx = ((xs.max() + m - x0) / step).toInt() + 2; val ny = ((ys.max() + m - y0) / step).toInt() + 2
        if (nx * ny > 40_000) return null
        val cosLat = cos(Math.toRadians(o.lat))
        val z = JSONArray()
        for (j in 0 until ny) for (i in 0 until nx) {
            val p = LatLon(o.lat + (y0 + j * step) / 110_540.0, o.lon + (x0 + i * step) / (111_320.0 * cosLat))
            val v = com.felixos.drohne.plan.Terrain.groundM(p)
            z.put(if (v == null) 0.0 else Math.round((v - g0) * 10) / 10.0)
        }
        return JSONObject().put("x0", x0).put("y0", y0).put("dx", step).put("dy", step).put("nx", nx).put("ny", ny).put("z", z)
    }



    private var lastFlying: Boolean? = null

    /** Drohne setzen (max. 10× je Sekunde); Kamera folgt, sobald sie fliegt. */
    fun update(s: DroneState) {
        val fly = s.flying || s.motorsOn
        if (fly != lastFlying) { lastFlying = fly; js("setFlying($fly)") }
        val loc = s.location?.takeIf { it.latitude != 0.0 } ?: run { js("clearDrone()"); return }
        val now = System.currentTimeMillis()
        if (now - lastDroneMs < 100) return
        lastDroneMs = now
        if (origin == null) origin = LatLon(loc.latitude, loc.longitude)
        val (x, y) = local(LatLon(loc.latitude, loc.longitude))
        js("setDrone(${"%.1f".format(java.util.Locale.US, x)}, ${"%.1f".format(java.util.Locale.US, loc.altitude ?: 0.0)}, ${"%.1f".format(java.util.Locale.US, y)}, ${(s.headingDeg ?: 0.0).toInt()}, ${s.gimbalPitchDeg?.toInt() ?: "null"})")
    }

    fun follow(on: Boolean) = js("setFollow($on)")

    /** Kamera weiterschalten: Drohnensicht → Verfolger → Übersicht; [onMode] bekommt "fpv", "chase" oder "orbit". */
    fun cycleCamera(onMode: (String) -> Unit) { if (ready) web.evaluateJavascript("cycleCamera()") { r -> onMode(r?.trim('"') ?: "fpv") } }
}
