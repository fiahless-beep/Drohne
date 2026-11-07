package com.felixos.drohne.plan

/** Ein Wegpunkt: Höhe relativ zum Startpunkt, Kamera-Neigung, Foto ja/nein. */
data class Waypoint(
    val pos: LatLon,
    val heightM: Double,
    val gimbalPitchDeg: Double = -90.0,
    val takePhoto: Boolean = true,
    val hoverSec: Int = 0,
)

/** Was tut die Drohne, wenn das Funksignal zum Controller abreißt? */
enum class RcLost(val wpml: String) {
    /** Mission ohne Controller zu Ende fliegen (Autonomie-Wunsch). */
    CONTINUE("goContinue"),
    /** Sofort zum Startpunkt zurück. */
    GO_HOME("executeLostAction"),
}

data class Mission(
    val name: String,
    val waypoints: List<Waypoint>,
    val speedMs: Double = 5.0,
    val takeOffSecurityHeightM: Double = 20.0,
    // Felix 30.10.: Missionen fliegen bei Funkabriss zu Ende, danach Heimflug (finishAction goHome).
    val rcLost: RcLost = RcLost.CONTINUE,
    /** DJI-Gerätekennung im WPML. Mini 4 Pro = 68 laut DJI-Doku; am Gerät mit checkValidation prüfen. */
    val droneEnum: Int = 68,
    val droneSubEnum: Int = 0,
) {
    init {
        require(waypoints.size >= 2) { "Mindestens 2 Wegpunkte" }
        require(waypoints.size <= 200) { "Höchstens 200 Wegpunkte" }
        require(speedMs in 1.0..15.0) { "Geschwindigkeit 1–15 m/s" }
        waypoints.forEach { require(it.heightM in 2.0..120.0) { "Höhe 2–120 m" } }
    }

    /** Streckenlänge in Metern, ohne An- und Rückflug. */
    val lengthM: Double get() = waypoints.zipWithNext { a, b -> Geo.distance(a.pos, b.pos) }.sum()

    /** Grobe Flugzeit der Strecke in Sekunden, inklusive Stopp und Foto je Punkt. */
    val durationSec: Double get() = lengthM / speedMs + waypoints.sumOf { it.hoverSec + if (it.takePhoto) 3 else 0 }
}

object Missions {
    /** Kleinster Testflug: 10 m hoch, 5 m nach Norden, 1 Foto, zurück. Für den ersten Flug. */
    fun hoverTest(center: LatLon): Mission {
        val a = Waypoint(center, 10.0, gimbalPitchDeg = 0.0, takePhoto = false, hoverSec = 3)
        val b = Waypoint(Geo.offset(center, 5.0, 0.0), 10.0, gimbalPitchDeg = 0.0, takePhoto = true)
        return Mission(name = "schwebetest", waypoints = listOf(a, b), speedMs = 2.0, takeOffSecurityHeightM = 10.0)
    }

    /** Testflug: Quadrat mit [sideM] Metern Kantenlänge um [center], Foto an jeder Ecke. */
    fun testSquare(center: LatLon, sideM: Double = 30.0, heightM: Double = 30.0): Mission {
        val half = sideM / 2
        val diag = half * Math.sqrt(2.0)
        val corners = listOf(315.0, 45.0, 135.0, 225.0).map { Geo.offset(center, diag, it) }
        val pts = (corners + corners.first()).mapIndexed { i, p ->
            Waypoint(p, heightM, gimbalPitchDeg = -90.0, takePhoto = i < corners.size)
        }
        return Mission(name = "testquadrat", waypoints = pts, speedMs = 4.0)
    }
}
