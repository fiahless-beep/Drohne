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
    private var pendingJs: String? = null
    private var lastDroneMs = 0L
    /** true = fotorealistische Google-Seite (assets/google3d.html, braucht Schlüssel + Internet), false = Klötzchen (view3d.html). */
    private var google = false
    private var loader: androidx.webkit.WebViewAssetLoader? = null

    private fun wantGoogle(): Boolean = GoogleKey.get(web.context) != null && (web.context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)?.activeNetworkInfo?.isConnected == true

    @SuppressLint("SetJavaScriptEnabled")
    fun init() {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.setBackgroundColor(0xFF0B0F14.toInt())
        loader = androidx.webkit.WebViewAssetLoader.Builder().addPathHandler("/assets/", androidx.webkit.WebViewAssetLoader.AssetsPathHandler(web.context)).build()
        web.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? =
                request?.url?.let { loader?.shouldInterceptRequest(it) } ?: super.shouldInterceptRequest(view, request)
            override fun onPageFinished(view: WebView?, url: String?) {
                ready = true
                pendingScene?.let { js("setScene($it)") }; pendingScene = null
                pendingJs?.let { js(it) }; pendingJs = null
            }
        }
        load(wantGoogle())
    }

    /** Seite (neu) laden – Google-Seite über https://appassets… (Maps JavaScript API braucht eine echte Herkunft), Klötzchen aus file://. */
    private fun load(g: Boolean) {
        google = g; ready = false
        web.loadUrl(if (g) "https://appassets.androidplatform.net/assets/google3d.html?app=1" else "file:///android_asset/view3d.html")
    }
    fun reload() { load(wantGoogle()) }

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
        if (google != wantGoogle()) load(wantGoogle())
        if (google) { setMissionGoogle(m, o); return }
        val pts = (m?.waypoints?.map { it.pos } ?: emptyList()) + o
        val obstacles = (m?.obstacles ?: emptyList()).ifEmpty { Buildings.near(pts, radiusM) }
        val b = JSONArray()
        obstacles.forEach { ob ->
            if (ob.polygon.size < 3) return@forEach
            val poly = JSONArray(); ob.polygon.forEach { p -> val (x, y) = local(p); poly.put(JSONArray().put(Math.round(x * 10) / 10.0).put(Math.round(y * 10) / 10.0)) }
            val bo = JSONObject().put("h", ob.heightM).put("p", poly)
            val g = if (ob.groundRelM != 0.0) ob.groundRelM else (if (com.felixos.drohne.plan.Terrain.loaded) (com.felixos.drohne.plan.Terrain.riseM(o, ob.polygon[0]) ?: 0.0) - MissionController.startAboveGroundM else 0.0)
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
        val g0 = (com.felixos.drohne.plan.Terrain.groundM(o) ?: return null) + MissionController.startAboveGroundM
        val xs = pts.map { local(it).first }; val ys = pts.map { local(it).second }
        val m = 300.0; val step = 10.0
        val x0 = Math.floor((xs.min() - m) / step) * step; val y0 = Math.floor((ys.min() - m) / step) * step
        val nx = ((xs.max() + m - x0) / step).toInt() + 2; val ny = ((ys.max() + m - y0) / step).toInt() + 2
        if (nx * ny > 40_000) return null
        val cosLat = cos(Math.toRadians(o.lat))
        val z = JSONArray(); val c = JSONArray()
        for (j in 0 until ny) for (i in 0 until nx) {
            val p = LatLon(o.lat + (y0 + j * step) / 110_540.0, o.lon + (x0 + i * step) / (111_320.0 * cosLat))
            val v = com.felixos.drohne.plan.Terrain.groundM(p)
            z.put(if (v == null) 0.0 else Math.round((v - g0) * 10) / 10.0)
            // Baumkronen/Hohes je Zelle: Oberfläche − Boden (≥ 3 m); Gebäude stehen als Klötze ohnehin darüber
            val s = com.felixos.drohne.plan.Terrain.surfaceM(p); val ch = if (v != null && s != null) s - v else 0.0
            c.put(if (ch >= 3.0) Math.round(ch).toInt() else 0)
        }
        return JSONObject().put("x0", x0).put("y0", y0).put("dx", step).put("dy", step).put("nx", nx).put("ny", ny).put("z", z).put("c", c)
    }



    /** Plan für die Google-Seite: Höhen relativ zum Start (h), über dem Boden am Punkt (agl, DGT) und absolut (alt = Startboden DGT + h), wie tools/plan_artifact.py. */
    private fun setMissionGoogle(m: Mission?, o: LatLon) {
        val t = com.felixos.drohne.plan.Terrain
        val g0 = if (t.loaded) t.groundM(o) else null
        val pts = JSONArray()
        m?.waypoints?.forEachIndexed { i, w ->
            val rise = if (t.loaded) t.riseM(o, w.pos) ?: 0.0 else 0.0
            pts.put(JSONObject().put("lat", w.pos.lat).put("lon", w.pos.lon).put("h", w.heightM).put("agl", Math.round((w.heightM - rise) * 10) / 10.0)
                .put("alt", Math.round(((g0 ?: 0.0) + w.heightM) * 10) / 10.0).put("yaw", w.headingDeg ?: JSONObject.NULL).put("pitch", w.gimbalPitchDeg)
                .put("hold", (if (w.takePhoto) 2 else 0) + w.hoverSec).put("speed", w.speedMs ?: m.speedMs).put("kind", if (i == 0 || i == m.waypoints.size - 1) "transit" else "plan").put("photo", w.takePhoto))
        }
        val plan = JSONObject().put("name", m?.name ?: "Kein Plan").put("beschreibung", "").put("home", JSONObject().put("lat", o.lat).put("lon", o.lon).put("alt", g0 ?: JSONObject.NULL))
            .put("laenge_m", m?.lengthM?.toInt() ?: 0).put("dauer_s", 0).put("punkte", pts)
        val key = JSONObject.quote(GoogleKey.get(web.context) ?: "")
        val call = "appStart(${plan}, $key)"
        if (ready) js(call) else pendingJs = call
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
        if (google) {
            val t = com.felixos.drohne.plan.Terrain; val g0 = (if (t.loaded) origin?.let { t.groundM(it) } else null) ?: 0.0
            js("appDrone(${loc.latitude}, ${loc.longitude}, ${"%.1f".format(java.util.Locale.US, g0 + (loc.altitude ?: 0.0))}, ${(s.headingDeg ?: 0.0).toInt()}, ${s.gimbalPitchDeg?.toInt() ?: "null"}, $fly)")
            return
        }
        val (x, y) = local(LatLon(loc.latitude, loc.longitude))
        js("setDrone(${"%.1f".format(java.util.Locale.US, x)}, ${"%.1f".format(java.util.Locale.US, loc.altitude ?: 0.0)}, ${"%.1f".format(java.util.Locale.US, y)}, ${(s.headingDeg ?: 0.0).toInt()}, ${s.gimbalPitchDeg?.toInt() ?: "null"})")
    }

    fun follow(on: Boolean) = js("setFollow($on)")

    /** Kamera weiterschalten: Drohnensicht → Verfolger → Übersicht; [onMode] bekommt "fpv", "chase" oder "orbit". */
    fun cycleCamera(onMode: (String) -> Unit) { if (ready) web.evaluateJavascript("cycleCamera()") { r -> onMode(r?.trim('"') ?: "fpv") } }
}
