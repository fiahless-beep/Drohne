package com.felixos.drohne.plan

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gebäudedatenbank (Stadt Lissabon, alle Gebäude mit gemessener Höhe), gepackt von tools/gebaeude_pack.py
 * nach assets/gebaeude.bin. Die App holt für jeden Plan automatisch alle Gebäude im Umkreis der Strecke
 * und gibt sie dem Gebäude-Wächter dazu, ohne dass im Plan etwas eingetragen werden muss.
 */
object Buildings {
    private class Entry(val minLat: Int, val minLon: Int, val maxLat: Int, val maxLon: Int, val heightDm: Int, val pts: IntArray)

    @Volatile private var entries: List<Entry> = emptyList()
    @Volatile var loaded = false; private set
    val count: Int get() = entries.size

    fun load(input: InputStream): Int {
        val bytes = input.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.get() == 'G'.code.toByte() && b.get() == 'E'.code.toByte() && b.get() == 'B'.code.toByte() && b.get() == '1'.code.toByte()) { "kein GEB1" }
        val n = b.int
        val list = ArrayList<Entry>(n)
        repeat(n) {
            val minLat = b.int; val minLon = b.int; val maxLat = b.int; val maxLon = b.int
            val h = b.short.toInt() and 0xffff; val k = b.short.toInt() and 0xffff
            val pts = IntArray(k * 2) { b.int }
            list += Entry(minLat, minLon, maxLat, maxLon, h, pts)
        }
        entries = list; loaded = true
        return n
    }

    /** Alle Gebäude, deren Umriss-Rechteck näher als [radiusM] an einem der Punkte liegt. */
    fun near(points: List<LatLon>, radiusM: Double): List<Obstacle> {
        if (entries.isEmpty() || points.isEmpty()) return emptyList()
        val dLat = (radiusM / 110_540.0 * 1e6).toInt()
        val cosLat = Math.cos(Math.toRadians(points.first().lat))
        val dLon = (radiusM / (111_320.0 * cosLat) * 1e6).toInt()
        val boxes = points.map { p -> val la = (p.lat * 1e6).toInt(); val lo = (p.lon * 1e6).toInt(); intArrayOf(la - dLat, lo - dLon, la + dLat, lo + dLon) }
        val out = ArrayList<Obstacle>()
        for (e in entries) {
            val hit = boxes.any { bx -> e.maxLat >= bx[0] && e.minLat <= bx[2] && e.maxLon >= bx[1] && e.minLon <= bx[3] }
            if (!hit) continue
            val poly = (0 until e.pts.size / 2).map { i -> LatLon(e.pts[2 * i] / 1e6, e.pts[2 * i + 1] / 1e6) }
            out += Obstacle("Gebäude ${e.heightDm / 10.0} m", e.heightDm / 10.0, poly)
        }
        return out
    }

    /** Für Tests: aus einem Stream der Textform "lat,lon;lat,lon;…|h" laden. */
    fun loadForTest(buildings: List<Pair<List<LatLon>, Double>>) {
        entries = buildings.map { (poly, h) ->
            val lats = poly.map { (it.lat * 1e6).toInt() }; val lons = poly.map { (it.lon * 1e6).toInt() }
            Entry(lats.min(), lons.min(), lats.max(), lons.max(), (h * 10).toInt(), IntArray(poly.size * 2) { i -> if (i % 2 == 0) lats[i / 2] else lons[i / 2] })
        }
        loaded = true
    }
}
