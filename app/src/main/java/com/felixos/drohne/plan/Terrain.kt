package com.felixos.drohne.plan

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Höhenmodell (DGT-LiDAR 2024, gepackt von tools/hoehen_pack.py nach assets/hoehen.bin):
 * Gelände (MDT) und Oberfläche (MDS, also Dächer, Bäume, Kräne zum Flugdatum) als Raster.
 * Alle Flughöhen der Drohne gelten relativ zum Startpunkt; die App rechnet deshalb jede Strecke
 * gegen die Oberfläche relativ zum Gelände am Startpunkt.
 */
object Terrain {
    const val NODATA = -32768

    @Volatile private var mdt: ShortArray? = null
    @Volatile private var mds: ShortArray? = null
    private var lat0 = 0.0; private var lon0 = 0.0; private var dLat = 0.0; private var dLon = 0.0; private var rows = 0; private var cols = 0
    val loaded: Boolean get() = mdt != null

    fun load(input: InputStream): Int {
        val bytes = input.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.get() == 'H'.code.toByte() && b.get() == 'O'.code.toByte() && b.get() == 'E'.code.toByte() && b.get() == '1'.code.toByte()) { "kein HOE1" }
        lat0 = b.double; lon0 = b.double; dLat = b.double; dLon = b.double; rows = b.int; cols = b.int
        val n = rows * cols
        val g = ShortArray(n); val s = ShortArray(n)
        b.asShortBuffer().get(g); b.position(b.position() + n * 2); b.asShortBuffer().get(s)
        mdt = g; mds = s
        return n
    }

    /** Für Tests: kleines Raster direkt setzen (Meter). */
    fun loadForTest(lat0: Double, lon0: Double, dLat: Double, dLon: Double, groundM: Array<DoubleArray>, surfaceM: Array<DoubleArray>) {
        this.lat0 = lat0; this.lon0 = lon0; this.dLat = dLat; this.dLon = dLon; rows = groundM.size; cols = groundM[0].size
        mdt = ShortArray(rows * cols) { (groundM[it / cols][it % cols] * 10).toInt().toShort() }
        mds = ShortArray(rows * cols) { (surfaceM[it / cols][it % cols] * 10).toInt().toShort() }
    }

    private fun sample(arr: ShortArray?, p: LatLon): Double? {
        val a = arr ?: return null
        val r = ((lat0 - p.lat) / dLat).toInt(); val c = ((p.lon - lon0) / dLon).toInt()
        if (r < 0 || c < 0 || r >= rows || c >= cols) return null
        val v = a[r * cols + c].toInt()
        return if (v == NODATA) null else v / 10.0
    }

    /** Geländehöhe über Meer in m, null = außerhalb/unbekannt. */
    fun groundM(p: LatLon): Double? = sample(mdt, p)
    /** Oberflächenhöhe über Meer in m (Dach, Baum, Kran), null = außerhalb/unbekannt. */
    fun surfaceM(p: LatLon): Double? = sample(mds, p)

    data class Conflict(val from: Int, val to: Int, val flightM: Double, val surfaceRelM: Double, val at: LatLon) {
        override fun toString() = "Strecke ${from + 1}→${to + 1}: Flughöhe ${flightM.toInt()} m, Oberfläche dort ${surfaceRelM.toInt()} m über dem Startpunkt (Gelände/Baum/Dach)"
    }

    /**
     * Prüft alle Strecken (inkl. Start→erster Punkt) gegen die Oberfläche: Konflikt, wenn die Flughöhe
     * (relativ zum Start) tiefer als Oberfläche + [clearanceM] ist. Abtastung alle 3 m, Höhe linear interpoliert.
     */
    fun check(m: Mission, home: LatLon?, clearanceM: Double = Obstacles.CLEARANCE_M): List<Conflict> {
        val g0 = (home ?: m.waypoints.first().pos).let { groundM(it) } ?: return emptyList()
        val pts = buildList { if (home != null) add(Waypoint(home, m.waypoints.first().heightM, takePhoto = false)); addAll(m.waypoints) }
        val off = if (home != null) -1 else 0
        val out = mutableListOf<Conflict>()
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            val n = max(1, (Geo.distance(a.pos, b.pos) / 3.0).toInt())
            var worst: Conflict? = null
            for (k in 0..n) {
                val t = k.toDouble() / n
                val p = LatLon(a.pos.lat + (b.pos.lat - a.pos.lat) * t, a.pos.lon + (b.pos.lon - a.pos.lon) * t)
                val h = a.heightM + (b.heightM - a.heightM) * t
                val surf = surfaceM(p) ?: continue
                val rel = surf - g0
                if (h < rel + clearanceM && (worst == null || rel - h > worst.surfaceRelM - worst.flightM)) worst = Conflict(i + off, i + 1 + off, h, rel, p)
            }
            worst?.let { out += it }
        }
        return out
    }

    /** Geländeunterschied Ziel minus Start in m (positiv = Ziel liegt höher), null wenn unbekannt. */
    fun riseM(home: LatLon, p: LatLon): Double? { val a = groundM(home) ?: return null; val b = groundM(p) ?: return null; return b - a }
}
