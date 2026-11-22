package com.felixos.drohne.plan

/**
 * Abheben per Knopf ohne Plan (Felix 22.11.2026). Reine Regel, damit sie ohne Gerät testbar ist.
 * Die Drohne muss am Boden stehen und so viel wissen, dass sie allein heimfinden kann (GPS, Startpunkt).
 */
object Takeoff {
    /** So lange muss der Knopf gehalten werden. */
    const val HOLD_MS = 1500L
    const val MIN_SATELLITES = 10
    const val MIN_BATTERY = 25

    /** null = Abheben erlaubt, sonst der Grund (englisch, für die Anzeige). */
    fun problem(connected: Boolean, flying: Boolean, motorsOn: Boolean, satellites: Int?, homeSet: Boolean, batteryPercent: Int?, windWarning: Int?): String? = when {
        !connected -> "Drone not connected."
        flying || motorsOn -> "The drone is not on the ground with motors off."
        (satellites ?: 0) < MIN_SATELLITES -> "GPS too weak (at least $MIN_SATELLITES satellites)."
        !homeSet -> "Home point not set yet. Wait a moment."
        batteryPercent == null -> "Battery level unknown."
        batteryPercent < MIN_BATTERY -> "Battery below $MIN_BATTERY %."
        windWarning == 2 -> "Strong wind warning from the drone."
        else -> null
    }
}
