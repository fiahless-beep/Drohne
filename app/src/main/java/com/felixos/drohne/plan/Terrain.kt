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

    /** Ein Raster; die App kann mehrere tragen (Lissabon, Mühle …), jedes aus einer eigenen assets/hoehen*.bin. */
    private class Grid(val lat0: Double, val lon0: Double, val dLat: Double, val dLon: Double, val rows: Int, val cols: Int, val mdt: ShortArray, val mds: ShortArray) {
        fun index(p: LatLon): Int {
            val r = ((lat0 - p.lat) / dLat).toInt(); val c = ((p.lon - lon0) / dLon).toInt()
            return if (p.lat > lat0 || p.lon < lon0 || r < 0 || c < 0 || r >= rows || c >= cols) -1 else r * cols + c
        }
    }
    @Volatile private var grids: List<Grid> = emptyList()
    val loaded: Boolean get() = grids.isNotEmpty()

    /** Lädt ein weiteres Raster dazu (bereits geladene bleiben). */
    fun load(input: InputStream): Int {
        val bytes = input.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.get() == 'H'.code.toByte() && b.get() == 'O'.code.toByte() && b.get() == 'E'.code.toByte() && b.get() == '1'.code.toByte()) { "kein HOE1" }
        val lat0 = b.double; val lon0 = b.double; val dLat = b.double; val dLon = b.double; val rows = b.int; val cols = b.int
        val n = rows * cols
        val g = ShortArray(n); val s = ShortArray(n)
        b.asShortBuffer().get(g); b.position(b.position() + n * 2); b.asShortBuffer().get(s)
        grids = grids + Grid(lat0, lon0, dLat, dLon, rows, cols, g, s)
        return n
    }

    /** Für Tests: kleines Raster direkt setzen (Meter); ersetzt alle geladenen. */
    fun loadForTest(lat0: Double, lon0: Double, dLat: Double, dLon: Double, groundM: Array<DoubleArray>, surfaceM: Array<DoubleArray>) {
        val rows = groundM.size; val cols = groundM[0].size
        grids = listOf(Grid(lat0, lon0, dLat, dLon, rows, cols,
            ShortArray(rows * cols) { (groundM[it / cols][it % cols] * 10).toInt().toShort() },
            ShortArray(rows * cols) { (surfaceM[it / cols][it % cols] * 10).toInt().toShort() }))
    }

    private fun sample(surface: Boolean, p: LatLon): Double? {
        for (g in grids) {
            val i = g.index(p); if (i < 0) continue
            val v = (if (surface) g.mds else g.mdt)[i].toInt()
            if (v != NODATA) return v / 10.0
        }
        return null
    }

    /** Geländehöhe über Meer in m, null = außerhalb/unbekannt. */
    fun groundM(p: LatLon): Double? = sample(false, p)
    /** Oberflächenhöhe über Meer in m (Dach, Baum, Kran), null = außerhalb/unbekannt. */
    fun surfaceM(p: LatLon): Double? = sample(true, p)

    data class Conflict(val from: Int, val to: Int, val flightM: Double, val surfaceRelM: Double, val at: LatLon) {
        override fun toString() = "leg ${from + 1}→${to + 1}: flight height ${flightM.toInt()} m, surface there ${surfaceRelM.toInt()} m above the start point (ground/tree/roof)"
    }

    /**
     * Prüft alle Strecken (inkl. Start→erster Punkt) gegen die Oberfläche: Konflikt, wenn die Flughöhe
     * (relativ zum Start) tiefer als Oberfläche + [clearanceM] ist. Abtastung alle 3 m, Höhe linear interpoliert.
     */
    fun check(m: Mission, home: LatLon?, clearanceM: Double = Obstacles.CLEARANCE_M, homeAboveGroundM: Double = 0.0, homeLeg: Boolean = true): List<Conflict> {
        val g0 = ((home ?: m.waypoints.first().pos).let { groundM(it) } ?: return emptyList()) + homeAboveGroundM  // Start auf Balkon/Dach: Nullpunkt der Drohne liegt höher
        val pts = buildList { if (home != null && homeLeg) add(Waypoint(home, m.waypoints.first().heightM, takePhoto = false)); addAll(m.waypoints) }
        val off = if (home != null && homeLeg) -1 else 0
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

    /** Deckt das Höhenmodell den Start und jeden Punkt des Plans ab? Sonst ist „keine Konflikte“ keine Aussage (14.11.: Mühle außerhalb der Daten meldete „Strecke frei“). */
    fun covers(m: Mission, home: LatLon?): Boolean =
        loaded && (home == null || groundM(home) != null) && m.waypoints.all { surfaceM(it.pos) != null }

    /** Ist die gerade Linie a→b in Höhe [hM] (relativ zum Nullpunkt [g0] über Meer) überall mindestens [clearanceM] über der gemessenen Oberfläche? Fehlende Daten = nein. */
    fun lineClear(g0: Double, a: LatLon, b: LatLon, hM: Double, clearanceM: Double): Boolean {
        val n = max(1, (Geo.distance(a, b) / 3.0).toInt())
        for (k in 0..n) {
            val t = k.toDouble() / n
            val surf = surfaceM(LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)) ?: return false
            if (hM < surf - g0 + clearanceM) return false
        }
        return true
    }

    /** Geländeunterschied Ziel minus Start in m (positiv = Ziel liegt höher), null wenn unbekannt. */
    fun riseM(home: LatLon, p: LatLon): Double? { val a = groundM(home) ?: return null; val b = groundM(p) ?: return null; return b - a }
}
