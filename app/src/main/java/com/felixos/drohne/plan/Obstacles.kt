package com.felixos.drohne.plan

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Ein Hindernis aus den Kartendaten: Gebäude-Umriss (Polygon) mit Höhe über Grund. */
data class Obstacle(val name: String, val heightM: Double, val polygon: List<LatLon>)

/**
 * Geometrie-Wächter (Absturz 7.11.2026): prüft jede Flugstrecke gegen bekannte Gebäude.
 * Eine Strecke gilt als Konflikt, wenn sie waagerecht näher als [MARGIN_M] an ein Gebäude kommt
 * und dabei tiefer als Gebäudehöhe + [CLEARANCE_M] ist.
 */
object Obstacles {
    const val MARGIN_M = 8.0   // Kartenkoordinaten und GPS sind je einige Meter ungenau
    const val CLEARANCE_M = 8.0

    data class Conflict(val from: Int, val to: Int, val obstacle: Obstacle, val heightM: Double) {
        override fun toString() = "Strecke ${from + 1}→${to + 1} auf ${heightM.toInt()} m trifft „${obstacle.name}“ (${obstacle.heightM.toInt()} m hoch)"
    }

    /** Alle Konflikte der Mission inkl. Start (Steigflug am Startpunkt, dann gerade zum ersten Punkt). */
    fun check(m: Mission, home: LatLon?, obstacles: List<Obstacle> = m.obstacles): List<Conflict> {
        if (obstacles.isEmpty()) return emptyList()
        val pts = buildList {
            if (home != null) add(Waypoint(home, m.waypoints.first().heightM, takePhoto = false))
            addAll(m.waypoints)
        }
        val offset = if (home != null) -1 else 0
        val out = mutableListOf<Conflict>()
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            val hMin = min(a.heightM, b.heightM)
            for (o in obstacles) {
                if (hMin >= o.heightM + CLEARANCE_M) continue
                if (segmentNear(a.pos, b.pos, o.polygon, MARGIN_M)) out += Conflict(i + offset, i + 1 + offset, o, hMin)
            }
        }
        return out
    }

    /** Kommt die Strecke a→b dem Polygon näher als [marginM]? (Abtastung alle 1 m) */
    fun segmentNear(a: LatLon, b: LatLon, poly: List<LatLon>, marginM: Double): Boolean {
        val n = max(1, (Geo.distance(a, b) / 1.0).toInt())
        for (k in 0..n) {
            val t = k.toDouble() / n
            val p = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
            if (inside(p, poly) || distanceToPolygon(p, poly) < marginM) return true
        }
        return false
    }

    /** Punkt im Polygon (Strahl-Test auf Breiten-/Längengraden, für kleine Flächen ausreichend). */
    fun inside(p: LatLon, poly: List<LatLon>): Boolean {
        var c = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val pi = poly[i]; val pj = poly[j]
            if ((pi.lon > p.lon) != (pj.lon > p.lon) &&
                p.lat < (pj.lat - pi.lat) * (p.lon - pi.lon) / (pj.lon - pi.lon) + pi.lat) c = !c
            j = i
        }
        return c
    }

    /** Kürzester Abstand in Metern vom Punkt zu den Polygonkanten. */
    fun distanceToPolygon(p: LatLon, poly: List<LatLon>): Double {
        val kx = 111_320.0 * cos(Math.toRadians(p.lat)); val ky = 110_540.0
        fun x(q: LatLon) = (q.lon - p.lon) * kx
        fun y(q: LatLon) = (q.lat - p.lat) * ky
        var best = Double.MAX_VALUE
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            val ax = x(a); val ay = y(a); val bx = x(b); val by = y(b)
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((0 - ax) * dx + (0 - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx; val cy = ay + t * dy
            best = min(best, sqrt(cx * cx + cy * cy))
        }
        return best
    }
}
