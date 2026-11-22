package com.felixos.drohne.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Je Regel der Startprüfung ein Test: genau diese Regel schlägt an, sonst keine. */
class PreflightTest {
    private val home = LatLon(10.0, 20.0)   // erfundener Ort, kein echter Startplatz

    private fun utc(hour: Int, minute: Int = 0): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(2026, Calendar.JUNE, 21, hour, minute)
    }.timeInMillis
    /** Bei 20° Ost steht die Sonne gegen 10:40 UTC am höchsten. */
    private val noon = utc(10, 40)
    private val night = utc(22, 40)

    private fun plan(heightM: Double = 40.0, distM: Double = 100.0, openArea: Boolean = false, plannedStart: LatLon? = null) = Mission(
        name = "test", openArea = openArea, plannedStart = plannedStart,
        waypoints = listOf(Waypoint(Geo.offset(home, distM, 0.0), heightM), Waypoint(Geo.offset(home, distM, 90.0), heightM)))

    /** Alles in Ordnung: Plan auf der Drohne, voller Akku, Tag, Sensoren an. */
    private fun ok(m: Mission? = plan()) = Preflight.Lage(
        registered = true, connected = true, mission = m, uploaded = true, home = home, position = home,
        batteryPercent = 100, satellites = 17, heightLimitM = 120, windWarning = 0, sensorsOk = true, sensorsText = "on",
        rthHeightM = 30.0, transitHeightM = 30.0, terrainCovered = true, preparedHome = home, nowMs = noon)

    private fun only(l: Preflight.Lage): String {
        val p = Preflight.problems(l)
        assertEquals("genau ein Problem erwartet: $p", 1, p.size)
        return p[0]
    }

    @Test fun readyStateHasNoProblems() = assertEquals(emptyList<String>(), Preflight.problems(ok()))

    @Test fun sdkNotRegistered() = assertTrue(only(ok().copy(registered = false)).startsWith("SDK not registered"))

    @Test fun droneNotConnected() = assertEquals("Drone not connected", only(ok().copy(connected = false)))

    @Test fun noPlanAtAll() = assertTrue(only(ok(null)).startsWith("No plan on the drone"))

    @Test fun planNotUploaded() = assertTrue(only(ok().copy(uploaded = false)).startsWith("No plan on the drone"))

    @Test fun batteryBelowNeedIncludesReserve() {
        val need = Budget.estimate(plan(), home).neededPercent
        assertTrue(need > Budget.RESERVE_PERCENT)
        assertEquals(emptyList<String>(), Preflight.problems(ok().copy(batteryPercent = need)))
        assertEquals("Battery ${need - 1} %, the plan needs about $need % (incl. ${Budget.RESERVE_PERCENT} % reserve)", only(ok().copy(batteryPercent = need - 1)))
    }

    @Test fun unknownBatteryCountsAsEmpty() = assertTrue(only(ok().copy(batteryPercent = null)).startsWith("Battery 0 %"))

    @Test fun pointFartherThan1000m() {
        val p = Preflight.problems(ok(plan(distM = 1100.0)))
        assertTrue(p.toString(), p.any { it.contains("from the home point (max. 1000 m)") })
        assertTrue(Preflight.problems(ok(plan(distM = 990.0))).none { it.contains("from the home point") })
    }

    @Test fun tooFewSatellites() {
        assertEquals("GPS too weak (at least 10 satellites)", only(ok().copy(satellites = 9)))
        assertEquals("GPS too weak (at least 10 satellites)", only(ok().copy(satellites = null)))
        assertEquals(emptyList<String>(), Preflight.problems(ok().copy(satellites = 10)))
    }

    @Test fun guardConflictsBlockTheStart() {
        assertEquals("Building guard: leg 1→2 hits a house", only(ok().copy(buildingConflicts = listOf("leg 1→2 hits a house"))))
        assertEquals("Surface guard: leg 2→3 too low", only(ok().copy(surfaceConflicts = listOf("leg 2→3 too low"))))
        assertEquals("Return guard: too high", only(ok().copy(returnProblems = listOf("Return guard: too high"))))
    }

    @Test fun heightLimitBelowFlightHeight() {
        assertTrue(only(ok(plan(heightM = 60.0)).copy(heightLimitM = 50)).startsWith("DJI height limit 50 m is below the flight height 60 m"))
        assertEquals(emptyList<String>(), Preflight.problems(ok(plan(heightM = 60.0)).copy(heightLimitM = 60)))
    }

    @Test fun heightLimitAlsoCoversTheReturnHeight() =
        assertTrue(only(ok().copy(heightLimitM = 50, rthHeightM = 70.0)).startsWith("DJI height limit 50 m is below the flight height 70 m"))

    @Test fun heightLimitNotReadYet() = assertEquals("DJI height limit not read yet (wait a moment)", only(ok().copy(heightLimitM = null)))

    @Test fun strongWindWarning() = assertEquals("Strong wind warning from the drone: no takeoff", only(ok().copy(windWarning = 2)))

    @Test fun moderateWindOnlyBlocksPointsBelowTransit() {
        assertEquals(emptyList<String>(), Preflight.problems(ok().copy(windWarning = 1)))
        assertEquals("Wind warning from the drone: no points below 30 m today", only(ok(plan(heightM = 28.0)).copy(windWarning = 1)))
    }

    @Test fun pointsBelowTransitNeedBuildingDataOpenAreaOrTerrain() {
        val low = plan(heightM = 28.0)
        assertTrue(only(ok(low).copy(terrainCovered = false)).startsWith("2 points below 30 m, but the plan has no building data"))
        assertEquals(emptyList<String>(), Preflight.problems(ok(low)))
        assertEquals(emptyList<String>(), Preflight.problems(ok(plan(heightM = 28.0, openArea = true)).copy(terrainCovered = false)))
        val withBuilding = low.copy(obstacles = listOf(Obstacle("Haus", 10.0, listOf(Geo.offset(home, 500.0, 180.0), Geo.offset(home, 510.0, 180.0), Geo.offset(home, 510.0, 181.0)))))
        assertEquals(emptyList<String>(), Preflight.problems(ok(withBuilding).copy(terrainCovered = false)))
    }

    @Test fun lowPointsOnlyInDaylight() {
        assertTrue(only(ok(plan(heightM = 20.0)).copy(nowMs = night)).startsWith("2 points below 25 m, but it is dark"))
        assertEquals(emptyList<String>(), Preflight.problems(ok(plan(heightM = 20.0))))
        assertEquals("über 25 m gilt die Nachtsperre nicht", emptyList<String>(), Preflight.problems(ok(plan(heightM = 26.0)).copy(nowMs = night)))
    }

    @Test fun lowPointsOnlyWithObstacleSensing() {
        assertEquals("2 points below 25 m, obstacle sensing OFF. Only once it reports on.", only(ok(plan(heightM = 20.0)).copy(sensorsOk = false, sensorsText = "OFF")))
        assertEquals(emptyList<String>(), Preflight.problems(ok(plan(heightM = 26.0)).copy(sensorsOk = false, sensorsText = "OFF")))
    }

    @Test fun homePointNotSet() {
        assertEquals("Home point not set yet", only(ok().copy(home = null)))
    }

    @Test fun planPreparedForAnotherHomePoint() {
        assertTrue(only(ok().copy(preparedHome = null)).startsWith("Plan was prepared without this home point"))
        assertTrue(only(ok().copy(preparedHome = Geo.offset(home, 31.0, 0.0))).startsWith("Plan was prepared without this home point"))
        assertEquals(emptyList<String>(), Preflight.problems(ok().copy(preparedHome = Geo.offset(home, 29.0, 0.0))))
    }

    @Test fun plannedStartFartherThan50m() {
        assertTrue(only(ok(plan(plannedStart = Geo.offset(home, 60.0, 90.0)))).startsWith("Plan was checked for a start point "))
        assertEquals(emptyList<String>(), Preflight.problems(ok(plan(plannedStart = Geo.offset(home, 40.0, 90.0)))))
    }

    /** Die Reihenfolge der Meldungen ist Teil der Anzeige (Checkliste) und bleibt wie vor dem Umzug. */
    @Test fun orderOfProblemsIsStable() {
        val p = Preflight.problems(ok(plan(heightM = 20.0)).copy(registered = false, connected = false, uploaded = false, satellites = 3,
            buildingConflicts = listOf("b"), surfaceConflicts = listOf("s"), returnProblems = listOf("Return guard: r"), windWarning = 2,
            sensorsOk = false, sensorsText = "OFF", batteryPercent = 1))
        assertEquals(listOf("SDK not", "Drone not", "No plan", "Battery 1", "GPS too weak", "Building guard: b", "Surface guard: s", "Return guard: r", "Strong wind", "2 points below 25 m, obstacle"),
            p.map { full -> listOf("SDK not", "Drone not", "No plan", "Battery 1", "GPS too weak", "Building guard: b", "Surface guard: s", "Return guard: r", "Strong wind", "2 points below 25 m, obstacle").first { full.startsWith(it) } })
    }

    @Test fun followNeedsAPosition() = assertEquals("No drone position", Preflight.followProblem(null, 20.0, true, "on", noon))

    @Test fun followBelow25mOnlyInDaylightAndWithSensing() {
        assertNull(Preflight.followProblem(home, 20.0, true, "on", noon))
        assertTrue(Preflight.followProblem(home, 20.0, true, "on", night)!!.contains("only in daylight"))
        assertTrue(Preflight.followProblem(home, 20.0, false, "OFF", noon)!!.contains("only with obstacle sensing on (now: OFF)"))
        assertNull(Preflight.followProblem(home, 25.0, false, "OFF", night))
    }
}
