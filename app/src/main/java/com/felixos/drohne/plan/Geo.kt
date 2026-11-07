package com.felixos.drohne.plan

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

object Geo {
    private const val R = 6_371_000.0

    /** Punkt in [distM] Metern Entfernung und Richtung [bearingDeg] (0 = Nord, 90 = Ost). */
    fun offset(p: LatLon, distM: Double, bearingDeg: Double): LatLon {
        val d = distM / R
        val b = bearingDeg * PI / 180
        val la1 = p.lat * PI / 180
        val lo1 = p.lon * PI / 180
        val la2 = asin(sin(la1) * cos(d) + cos(la1) * sin(d) * cos(b))
        val lo2 = lo1 + atan2(sin(b) * sin(d) * cos(la1), cos(d) - sin(la1) * sin(la2))
        return LatLon(la2 * 180 / PI, lo2 * 180 / PI)
    }

    /** Richtung von [a] nach [b] in Grad, 0 = Nord, 90 = Ost. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val la1 = a.lat * PI / 180; val la2 = b.lat * PI / 180
        val dLo = (b.lon - a.lon) * PI / 180
        val y = sin(dLo) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLo)
        return ((atan2(y, x) * 180 / PI) + 360) % 360
    }

    /** Sonnenhöhe über dem Horizont in Grad (NOAA-Näherung, ±1°). Unter etwa -6° ist es dunkel. */
    fun sunElevationDeg(p: LatLon, timeMs: Long): Double {
        val days = timeMs / 86_400_000.0 - 10957.5 // Tage seit J2000 (1.1.2000 12:00 UTC)
        val g = Math.toRadians((357.529 + 0.98560028 * days) % 360)
        val q = (280.459 + 0.98564736 * days) % 360
        val l = Math.toRadians((q + 1.915 * sin(g) + 0.020 * sin(2 * g)) % 360)
        val e = Math.toRadians(23.439 - 0.00000036 * days)
        val ra = Math.toDegrees(atan2(cos(e) * sin(l), cos(l)))
        val dec = asin(sin(e) * sin(l))
        val gmst = (18.697374558 + 24.06570982441908 * days) % 24
        val ha = Math.toRadians(((gmst * 15 + p.lon - ra) % 360 + 540) % 360 - 180)
        val lat = Math.toRadians(p.lat)
        return Math.toDegrees(asin(sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(ha)))
    }

    /** Entfernung in Metern (Haversine). */
    fun distance(a: LatLon, b: LatLon): Double {
        val dLa = (b.lat - a.lat) * PI / 180
        val dLo = (b.lon - a.lon) * PI / 180
        val h = sin(dLa / 2).let { it * it } +
            cos(a.lat * PI / 180) * cos(b.lat * PI / 180) * sin(dLo / 2).let { it * it }
        return 2 * R * asin(sqrt(h))
    }
}
