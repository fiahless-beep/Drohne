package com.felixos.drohne.plan

/**
 * Startprüfung als reine Rechnung: ein Zustand rein, die Liste der Hindernisse für den Start raus.
 * Kein Android, kein SDK, damit jede Regel einen Test hat. Die Regeln sind am 22.11.2026 unverändert aus
 * MainActivity.preflightProblems hierher gezogen (Reihenfolge und Texte gleich); der Flugkern (FlugSitzung)
 * füllt [Lage] und rechnet die Prüfung vor jedem Start selbst.
 */
object Preflight {
    /** Mindestzahl Satelliten vor dem Start. */
    const val MIN_SATELLITES = 10
    /** Punkte unter dieser Höhe nur bei Tageslicht und mit arbeitender Hinderniserkennung (Absturz 7.11.). */
    const val MIN_LOW_HEIGHT_M = 25.0
    /** Kein Punkt weiter vom Startpunkt entfernt. */
    const val MAX_DISTANCE_M = 1000

    /** Alles, was die Regeln lesen. Positionen sind null, solange die Drohne keine gültige meldet. */
    data class Lage(
        val registered: Boolean,
        val connected: Boolean,
        /** Plan, wie er geladen wurde (ohne Steigflug und Rückweg). */
        val mission: Mission?,
        val uploaded: Boolean,
        val home: LatLon?,
        val position: LatLon?,
        val batteryPercent: Int?,
        val satellites: Int?,
        val heightLimitM: Int?,
        /** Windwarnung der Drohne: 0 = keine, 1 = mäßig, 2 = stark. */
        val windWarning: Int?,
        val sensorsOk: Boolean,
        /** Zustand der Hinderniserkennung als Anzeigetext (on, OFF, blind: …). */
        val sensorsText: String,
        val buildingConflicts: List<String> = emptyList(),
        val surfaceConflicts: List<String> = emptyList(),
        val returnProblems: List<String> = emptyList(),
        val rthHeightM: Double,
        val transitHeightM: Double,
        /** Lag der vorbereitete Plan ganz im Höhenmodell? */
        val terrainCovered: Boolean,
        /** Startpunkt, mit dem der Plan vorbereitet wurde. */
        val preparedHome: LatLon?,
        val nowMs: Long,
    )

    fun problems(l: Lage): List<String> = buildList {
        if (!l.registered) add("SDK not registered (app key missing?)")
        if (!l.connected) add("Drone not connected")
        val m = l.mission
        if (m == null || !l.uploaded) add("No plan on the drone (get a plan, then upload)")
        val home = l.home
        if (m != null && home != null) {
            val est = Budget.estimate(m, home)
            val have = l.batteryPercent ?: 0
            if (have < est.neededPercent) add("Battery $have %, the plan needs about ${est.neededPercent} % (incl. ${Budget.RESERVE_PERCENT} % reserve)")
            if (est.maxDistanceFromHomeM > MAX_DISTANCE_M) add("One point is ${est.maxDistanceFromHomeM.toInt()} m from the home point (max. $MAX_DISTANCE_M m)")
        }
        if ((l.satellites ?: 0) < MIN_SATELLITES) add("GPS too weak (at least $MIN_SATELLITES satellites)")
        l.buildingConflicts.forEach { add("Building guard: $it") }
        l.surfaceConflicts.forEach { add("Surface guard: $it") }
        addAll(l.returnProblems)
        if (m != null) {
            val top = maxOf(m.waypoints.maxOf { it.heightM }, l.rthHeightM)
            l.heightLimitM?.let { if (it < top - 0.5) add("DJI height limit $it m is below the flight height ${top.toInt()} m. Settings → Safety → Height limit.") }
            if (l.heightLimitM == null) add("DJI height limit not read yet (wait a moment)")
            when (l.windWarning) {
                2 -> add("Strong wind warning from the drone: no takeoff")
                1 -> if (m.waypoints.any { it.heightM < l.transitHeightM }) add("Wind warning from the drone: no points below ${l.transitHeightM.toInt()} m today")
            }
        }
        // Absturz 7.11.: tiefe Punkte nur bei Tageslicht und mit arbeitenden Sensoren.
        if (m != null) {
            val low = m.waypoints.filter { it.heightM < MIN_LOW_HEIGHT_M }
            val pos = l.position ?: home
            val sun = Geo.sunElevationDeg(pos ?: m.waypoints.first().pos, l.nowMs)
            // Ohne Gebäudedaten, ohne Höhenmodell für die ganze Strecke und ohne ausdrücklich freie Umgebung: nichts unter Transithöhe.
            val underTransit = m.waypoints.filter { it.heightM < l.transitHeightM }
            if (underTransit.isNotEmpty() && m.obstacles.isEmpty() && !m.openArea && !l.terrainCovered) add("${underTransit.size} points below ${l.transitHeightM.toInt()} m, but the plan has no building data and is not marked as open area. Ask Claude to complete the plan.")
            if (low.isNotEmpty() && sun < -3) add("${low.size} points below ${MIN_LOW_HEIGHT_M.toInt()} m, but it is dark: the sensors are blind at night. Daylight only.")
            if (low.isNotEmpty() && !l.sensorsOk) add("${low.size} points below ${MIN_LOW_HEIGHT_M.toInt()} m, obstacle sensing ${l.sensorsText}. Only once it reports on.")
        }
        if (home == null) add("Home point not set yet")
        else if (m != null) {
            val ph = l.preparedHome
            if (ph == null || Geo.distance(home, ph) > 30) add("Plan was prepared without this home point (transit, building guard) → reload the plan")
            m.plannedStart?.let { if (Geo.distance(home, it) > 50) add("Plan was checked for a start point ${Geo.distance(home, it).toInt()} m from here (max. 50 m). Go to the planned start or have the plan checked again.") }
        }
    }

    /**
     * Die Starttür: was einen Start jetzt verhindert; leer = Start erlaubt.
     * [inFlight] = Start eines Umwegs oder Planrests aus dem Schweben: der Plan wird erst nach dieser Prüfung
     * hochgeladen, deshalb zählt dort nur „kein Plan auf der Drohne“ nicht. Alle anderen Regeln gelten gleich.
     */
    fun startGate(l: Lage, inFlight: Boolean = false): List<String> =
        if (inFlight) problems(l).filterNot { it.startsWith("No plan on the drone") } else problems(l)

    /**
     * Sperren für „Folgen“ (das Handy als Ziel): [pos] = Drohne oder Startpunkt, null = keine Position.
     * null = erlaubt, sonst der Grund.
     */
    fun followProblem(pos: LatLon?, heightM: Double, sensorsOk: Boolean, sensorsText: String, nowMs: Long): String? {
        val dark = pos != null && Geo.sunElevationDeg(pos, nowMs) < -3
        return when {
            pos == null -> "No drone position"
            heightM < MIN_LOW_HEIGHT_M && dark -> "Following below ${MIN_LOW_HEIGHT_M.toInt()} m only in daylight (sensors are blind at night)"
            heightM < MIN_LOW_HEIGHT_M && !sensorsOk -> "Following below ${MIN_LOW_HEIGHT_M.toInt()} m only with obstacle sensing on (now: $sensorsText)"
            else -> null
        }
    }
}
