package com.felixos.drohne.plan

/**
 * Sperrzone um eine Menschenmenge (Projektregel: keine Flüge über Menschenansammlungen). Kreis um [mitte] mit [radiusM].
 * Geprüft wird der vorbereitete Plan samt Steigflug, Anflug und Rückweg, dazu der gerade Heimflug von JEDEM Punkt
 * (Akku knapp, Funkabriss, STOP). Reine Rechnung.
 */
data class Sperrzone(val mitte: LatLon, val radiusM: Double) {
    companion object {
        /** Positionsfehler der Drohne (Felix 22.11.2026: mit höchstens ±1,5 m rechnen) plus Luft. */
        const val PUFFER_M = 5.0

        /** Alle Verstöße als Sätze für die Prüfliste; leer = frei. [m] = Plan wie er geflogen wird (mit Zusatzpunkten). */
        fun konflikte(m: Mission, home: LatLon?, heimwegPruefen: Boolean = true): List<String> {
            if (m.sperrzonen.isEmpty()) return emptyList()
            val out = mutableListOf<String>()
            val w = m.waypoints
            for (z in m.sperrzonen) {
                val r = z.radiusM + PUFFER_M
                if (home != null && Geo.distance(home, z.mitte) < r) { out += "the start point is inside the keep-out zone. Take off outside it."; continue }
                val quer = (0 until w.size - 1).firstOrNull { Missionsbau.abstandStrecke(z.mitte, w[it].pos, w[it + 1].pos) < r }
                if (quer != null) { out += "leg ${quer + 1}→${quer + 2} crosses the keep-out zone over the crowd."; continue }
                if (home == null) { out += "no home point yet, the way to and from the crowd cannot be checked."; continue }
                if (heimwegPruefen) {
                    val heim = w.indices.firstOrNull { Missionsbau.abstandStrecke(z.mitte, w[it].pos, home) < r }
                    if (heim != null) out += "the straight way home from point ${heim + 1} crosses the keep-out zone. Use the side of the crowd that faces the start point."
                }
            }
            return out
        }
    }
}
