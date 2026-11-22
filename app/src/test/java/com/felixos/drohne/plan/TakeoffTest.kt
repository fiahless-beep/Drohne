package com.felixos.drohne.plan

import org.junit.Assert.*
import org.junit.Test

class TakeoffTest {
    private fun p(connected: Boolean = true, flying: Boolean = false, motors: Boolean = false, sat: Int? = 14, home: Boolean = true, batt: Int? = 80, wind: Int? = 0) =
        Takeoff.problem(connected, flying, motors, sat, home, batt, wind)

    @Test fun allowedOnlyWhenEverythingIsKnownAndGood() {
        assertNull(p()); assertNull(p(wind = null)); assertNull(p(wind = 1)); assertNull(p(batt = 25)); assertNull(p(sat = 10))
    }

    @Test fun refusesEachMissingPrecondition() {
        assertNotNull(p(connected = false)); assertNotNull(p(flying = true)); assertNotNull(p(motors = true))
        assertNotNull(p(sat = 9)); assertNotNull(p(sat = null)); assertNotNull(p(home = false))
        assertNotNull(p(batt = 24)); assertNotNull(p(batt = null)); assertNotNull(p(wind = 2))
    }
}
