package com.felixos.drohne

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.felixos.drohne.plan.Mission
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

/**
 * Kleine Karte (OpenStreetMap über osmdroid): Drohne mit Kurs, Startpunkt, geplante Wegpunkte als
 * nummerierte Linie und – wenn erlaubt – die Position des Handys (Felix).
 * Reine Anzeige, greift nicht in Drohne oder Mission ein.
 */
class MapPanel(private val map: MapView) {
    private val ctx: Context get() = map.context
    private val route = Polyline(map).apply {
        outlinePaint.color = 0xFF2F80ED.toInt(); outlinePaint.strokeWidth = 6f
        outlinePaint.strokeCap = Paint.Cap.ROUND
    }
    private val numberMarkers = mutableListOf<Marker>()
    private val homeMarker = Marker(map).apply { icon = ContextCompat.getDrawable(ctx, R.drawable.ic_home); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setInfoWindow(null); title = "Startpunkt" }
    private val droneMarker = Marker(map).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setInfoWindow(null); title = "Drohne" }
    private var me: MyLocationNewOverlay? = null
    private val droneIcon: Drawable? = ContextCompat.getDrawable(ctx, R.drawable.ic_drone)
    private var lastHeadingKey = Int.MIN_VALUE
    private var routeKey: Any? = null
    private var fitted = false

    companion object {
        /** Muss vor dem Aufblasen des Layouts laufen (Kachel-Cache im App-Cache, kein Speicherrecht nötig). */
        fun configure(c: Context) {
            val cfg = Configuration.getInstance()
            cfg.userAgentValue = "FelixDrohne"
            val base = File(c.cacheDir, "osmdroid")
            cfg.osmdroidBasePath = base
            cfg.osmdroidTileCache = File(base, "tiles")
        }
    }

    init {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 20.0
        map.controller.setZoom(16.0)
        map.controller.setCenter(GeoPoint(38.72, -9.14)) // Lissabon, bis eine Position da ist
        map.overlays.add(route)
        map.overlays.add(homeMarker); homeMarker.isEnabled = false
        map.overlays.add(droneMarker); droneMarker.isEnabled = false
    }

    fun onResume() { map.onResume(); enableMyLocation() }

    /** Handy-Position einblenden, sobald das Standortrecht da ist (auch nach der Rechteabfrage nachholbar). */
    fun enableMyLocation() {
        if (me == null && ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                val o = MyLocationNewOverlay(GpsMyLocationProvider(ctx), map)
                ContextCompat.getDrawable(ctx, R.drawable.ic_phone)?.let { d -> o.setPersonIcon(toBitmap(d, 0f)); o.setDirectionIcon(toBitmap(d, 0f)) }
                o.setPersonAnchor(0.5f, 0.5f); o.setDirectionAnchor(0.5f, 0.5f)
                o.enableMyLocation()
                map.overlays.add(0, o)
                me = o
            } catch (t: Throwable) { DroneManager.log("Karte: Handy-Position nicht verfügbar (${t.javaClass.simpleName})") }
        }
    }

    fun onPause() { me?.disableMyLocation(); me = null; map.onPause() }

    /** Zeichnet Drohne, Startpunkt und Plan neu. Beim ersten Mal und bei neuem Plan wird eingepasst. */
    fun update(s: DroneState, mission: Mission?) {
        var changed = false
        val loc = s.location?.takeIf { it.latitude != 0.0 }
        if (loc != null) {
            droneMarker.position = GeoPoint(loc.latitude, loc.longitude)
            val key = ((s.headingDeg ?: 0.0) / 5).toInt()
            if (key != lastHeadingKey || droneMarker.icon == null) {
                lastHeadingKey = key
                droneIcon?.let { droneMarker.icon = BitmapDrawable(ctx.resources, toBitmap(it, (s.headingDeg ?: 0.0).toFloat())) }
            }
            if (!droneMarker.isEnabled) { droneMarker.isEnabled = true; changed = true }
        } else if (droneMarker.isEnabled) { droneMarker.isEnabled = false; changed = true }
        val home = s.home?.takeIf { it.latitude != 0.0 }
        if (home != null) {
            homeMarker.position = GeoPoint(home.latitude, home.longitude)
            if (!homeMarker.isEnabled) { homeMarker.isEnabled = true; changed = true }
        } else if (homeMarker.isEnabled) { homeMarker.isEnabled = false; changed = true }
        if (mission !== routeKey) {
            routeKey = mission
            numberMarkers.forEach { map.overlays.remove(it) }; numberMarkers.clear()
            route.setPoints(mission?.waypoints?.map { GeoPoint(it.pos.lat, it.pos.lon) } ?: emptyList())
            mission?.waypoints?.forEachIndexed { i, w ->
                val m = Marker(map).apply {
                    position = GeoPoint(w.pos.lat, w.pos.lon)
                    icon = numberIcon(i + 1, w.takePhoto)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setInfoWindow(null)
                }
                numberMarkers += m; map.overlays.add(m)
            }
            // Drohne und Start über den Nummern halten
            map.overlays.remove(homeMarker); map.overlays.add(homeMarker)
            map.overlays.remove(droneMarker); map.overlays.add(droneMarker)
            fitted = false; changed = true
        }
        if (!fitted && (loc != null || home != null || mission != null)) { fit(); fitted = true }
        if (changed || loc != null) map.invalidate()
    }

    /** Karte so zoomen, dass Drohne, Startpunkt und Plan sichtbar sind. */
    fun fit() {
        val pts = mutableListOf<GeoPoint>()
        if (droneMarker.isEnabled) pts += droneMarker.position
        if (homeMarker.isEnabled) pts += homeMarker.position
        pts += route.actualPoints
        if (pts.isEmpty()) return
        map.post {
            try {
                if (pts.size == 1) { map.controller.setZoom(17.5); map.controller.setCenter(pts[0]) }
                else map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.3f), false, 24)
            } catch (_: Throwable) {}
        }
    }

    private fun dp(v: Float) = v * ctx.resources.displayMetrics.density

    private fun toBitmap(d: Drawable, rotateDeg: Float): Bitmap {
        val w = d.intrinsicWidth.coerceAtLeast(1); val h = d.intrinsicHeight.coerceAtLeast(1)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.rotate(rotateDeg, w / 2f, h / 2f)
        d.setBounds(0, 0, w, h); d.draw(c)
        return b
    }

    /** Kreis mit Nummer; Foto-Punkte weiß, reine Durchflugpunkte grau. */
    private fun numberIcon(n: Int, photo: Boolean): Drawable {
        val size = dp(22f).toInt()
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (photo) Color.WHITE else 0xFFB0BEC5.toInt() }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2F80ED.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2f) }
        c.drawCircle(size / 2f, size / 2f, size / 2f - dp(1.5f), fill)
        c.drawCircle(size / 2f, size / 2f, size / 2f - dp(1.5f), stroke)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = dp(if (n >= 100) 9f else 11f); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        val y = size / 2f - (text.descent() + text.ascent()) / 2
        c.drawText(n.toString(), size / 2f, y, text)
        return BitmapDrawable(ctx.resources, b)
    }
}
