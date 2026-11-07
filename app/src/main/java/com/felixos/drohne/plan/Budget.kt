package com.felixos.drohne.plan

import kotlin.math.ceil

/**
 * Akku-Budget (Felix 6.11.): sicher mit mindestens 20 % zurück.
 * Mini 4 Pro: DJI nennt 34 min, realistisch rechnen wir mit 25 min für 100 % (Wind, Kälte, Steigen).
 */
object Budget {
    const val MINUTES_PER_FULL_BATTERY = 25.0
    const val RESERVE_PERCENT = 20
    private const val CLIMB_MS = 3.0
    private const val CRUISE_MS = 8.0

    data class Estimate(
        val missionMin: Double,
        val transitMin: Double,
        val totalMin: Double,
        val neededPercent: Int,
        val maxDistanceFromHomeM: Double,
    )

    /** Schätzung ab Startpunkt [home]: Steigen, Anflug, Mission, Rückflug, Landen. */
    fun estimate(m: Mission, home: LatLon): Estimate {
        val first = m.waypoints.first()
        val last = m.waypoints.last()
        val maxH = m.waypoints.maxOf { it.heightM }
        val transitSec = Geo.distance(home, first.pos) / CRUISE_MS + Geo.distance(last.pos, home) / CRUISE_MS +
            2 * maxH / CLIMB_MS + 30 // Start + Landung
        val total = (m.durationSec + transitSec) / 60
        val needed = ceil(total / MINUTES_PER_FULL_BATTERY * 100).toInt() + RESERVE_PERCENT
        return Estimate(
            missionMin = m.durationSec / 60,
            transitMin = transitSec / 60,
            totalMin = total,
            neededPercent = needed,
            maxDistanceFromHomeM = m.waypoints.maxOf { Geo.distance(home, it.pos) },
        )
    }
}
