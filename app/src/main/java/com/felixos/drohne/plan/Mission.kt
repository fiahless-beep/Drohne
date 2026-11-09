package com.felixos.drohne.plan

/** Ein Wegpunkt: Höhe relativ zum Startpunkt, Kamera-Neigung, Foto ja/nein. */
data class Waypoint(
    val pos: LatLon,
    val heightM: Double,
    val gimbalPitchDeg: Double = -90.0,
    val takePhoto: Boolean = true,
    val hoverSec: Int = 0,
    /** Blickrichtung der Drohne in Grad (0 = Nord, 90 = Ost); null = in Flugrichtung. */
    val headingDeg: Double? = null,
    /** Geschwindigkeit ab diesem Punkt bis zum nächsten in m/s; null = Missionsgeschwindigkeit. */
    val speedMs: Double? = null,
    /** Video an diesem Punkt: 1 = Aufnahme starten, 2 = Aufnahme beenden, 0 = nichts (Planfeld "video": "start"/"stop"). */
    val video: Int = 0,
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
    /** Zielpunkt (Gebäude, Platz), auf den die Kamera zeigt; nur zur Beschreibung, die Wegpunkte tragen Blickrichtung und Neigung schon. */
    val target: LatLon? = null,
    /** Landelampe (LED unten) während der Mission einschalten, z. B. zum Ausleuchten beim Schweben. */
    val light: Boolean = false,
    /** Bekannte Gebäude aus den Kartendaten; die App prüft die Strecke dagegen (Obstacles.check). */
    val obstacles: List<Obstacle> = emptyList(),
    /** Umgebung ausdrücklich frei (keine Gebäude/Bäume im Umkreis von 50 m, z. B. Feld, Hafenbecken). Ohne das und ohne [obstacles] verweigert die App Punkte unter 30 m. */
    val openArea: Boolean = false,
    /** Startpunkt, für den der Plan geprüft wurde; weicht der echte Startpunkt mehr als 50 m ab, verweigert die App den Start. */
    val plannedStart: LatLon? = null,
    /** Transithöhe aus dem Plan (Pläne mit korridorgeprüften Höhen, z. B. plan_objekt.py): sonst steigt der Transit auf die höchste Planhöhe. */
    val transitHeightM: Double? = null,
    /** Startplatz liegt so viele Meter über dem Boden des Höhenmodells (Balkon, Dach, Mauer). Die App zieht den Wert von allen Planhöhen ab und prüft mit dem erhöhten Start. */
    val startAboveGroundM: Double? = null,
) {
    init {
        require(waypoints.size >= 2) { "Mindestens 2 Wegpunkte" }
        require(waypoints.size <= 200) { "Höchstens 200 Wegpunkte" }
        require(speedMs in 1.0..15.0) { "Geschwindigkeit 1–15 m/s" }
        waypoints.forEach { require(it.heightM in -100.0..120.0) { "Höhe −100…120 m relativ zum Start" } } // negativ = bergab (Lissabon), Oberflächen-Wächter prüft den Bodenabstand
    }

    /** Streckenlänge in Metern, ohne An- und Rückflug. */
    val lengthM: Double get() = waypoints.zipWithNext { a, b -> Geo.distance(a.pos, b.pos) }.sum()

    /** Grobe Flugzeit der Strecke in Sekunden, inklusive Stopp und Foto je Punkt. */
    val durationSec: Double get() = lengthM / speedMs + waypoints.sumOf { it.hoverSec + if (it.takePhoto) 3 else 0 }
}

object Missions {
    /** Blickrichtung von [from] zum Ziel [to] in Grad. */
    fun bearingTo(from: LatLon, to: LatLon): Double = Geo.bearing(from, to)

    /** Kameraneigung, damit ein Ziel in [distM] Entfernung und [heightM] Tiefe mittig im Bild liegt. */
    fun pitchTo(distM: Double, heightM: Double): Double =
        (-Math.toDegrees(Math.atan2(heightM, distM))).coerceIn(-90.0, 0.0)

    /**
     * Kreisflug um ein Ziel: [points] Wegpunkte im Abstand [radiusM], Höhe [heightM], Kamera aufs Ziel.
     * [startBearingDeg] = Richtung vom Ziel zum ersten Punkt (z. B. Sonne im Rücken: Sonnenrichtung wählen).
     */
    fun orbit(target: LatLon, radiusM: Double, heightM: Double, points: Int = 8, startBearingDeg: Double = 0.0,
              targetHeightM: Double = 0.0, speedMs: Double = 4.0, name: String = "kreisflug", clockwise: Boolean = true,
              photos: Int = points): Mission {
        require(points in 3..36) { "3–36 Punkte" }
        require(radiusM in 5.0..200.0) { "Radius 5–200 m" }
        val pitch = pitchTo(radiusM, heightM - targetHeightM)
        val wps = (0..points).map { i ->
            val b = startBearingDeg + (if (clockwise) 1 else -1) * 360.0 * (i % points) / points
            val p = Geo.offset(target, radiusM, b)
            val every = if (photos <= 0) Int.MAX_VALUE else maxOf(1, points / photos)
            Waypoint(p, heightM, gimbalPitchDeg = pitch, takePhoto = i < points && i % every == 0, headingDeg = (b + 180) % 360)
        }
        return Mission(name = name, waypoints = wps, speedMs = speedMs, target = target)
    }

    /** Kleinster Testflug: 10 m hoch, 5 m nach Norden, 1 Foto, zurück. Für den ersten Flug. */
    fun hoverTest(center: LatLon): Mission {
        val a = Waypoint(center, 10.0, gimbalPitchDeg = 0.0, takePhoto = false, hoverSec = 3)
        val b = Waypoint(Geo.offset(center, 5.0, 0.0), 10.0, gimbalPitchDeg = 0.0, takePhoto = true)
        return Mission(name = "schwebetest", waypoints = listOf(a, b), speedMs = 2.0, takeOffSecurityHeightM = 10.0, openArea = true)
    }

    /** Testflug: Quadrat mit [sideM] Metern Kantenlänge um [center], Foto an jeder Ecke. */
    fun testSquare(center: LatLon, sideM: Double = 30.0, heightM: Double = 30.0): Mission {
        val half = sideM / 2
        val diag = half * Math.sqrt(2.0)
        val corners = listOf(315.0, 45.0, 135.0, 225.0).map { Geo.offset(center, diag, it) }
        val pts = (corners + corners.first()).mapIndexed { i, p ->
            Waypoint(p, heightM, gimbalPitchDeg = -90.0, takePhoto = i < corners.size)
        }
        return Mission(name = "testquadrat", waypoints = pts, speedMs = 4.0, openArea = true)
    }
}
