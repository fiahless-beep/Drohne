package com.felixos.drohne.plan

/**
 * Mehrere Akkus sind keine eigene Mission (Felix 23.11.2026: „jede Mission kann theoretisch mehrere Batterien brauchen …
 * dass die Planung dann sagt, die Mission braucht 2 oder 3 Batterien“). Teilt einen Plan in Abschnitte, von denen jeder
 * mit An- und Rückflug in einen vollen Akku passt (Budget samt Reserve) und unter der Wegpunktgrenze bleibt.
 * Reine Rechnung; jeder Abschnitt geht einzeln durch die Starttür.
 */
object Akkus {
    /** So viel Prozent darf ein Abschnitt nach Budget.estimate höchstens brauchen (voller Akku, etwas Luft für Wind). */
    const val VOLL_PROZENT = 92

    class Teilung(val abschnitte: List<Mission>, val prozentJeAbschnitt: List<Int>) {
        val akkus: Int get() = abschnitte.size
    }

    /**
     * Abschnitte in Flugreihenfolge. Passt der ganze Plan in einen Akku, kommt er unverändert als einziger Abschnitt zurück.
     * Ein Abschnitt hat mindestens zwei Punkte; passen selbst zwei Punkte nicht (Ziel zu weit weg), bleibt es bei einem
     * Abschnitt und [zuWeit] sagt es.
     */
    fun teilen(m: Mission, home: LatLon, maxPunkte: Int = Missions.MAX_PLAN_WAYPOINTS): Teilung {
        fun braucht(w: List<Waypoint>) = Budget.estimate(m.copy(waypoints = w), home).neededPercent
        if (m.waypoints.size <= maxPunkte && braucht(m.waypoints) <= VOLL_PROZENT) return Teilung(listOf(m), listOf(braucht(m.waypoints)))
        val teile = mutableListOf<List<Waypoint>>()
        val anfaenge = mutableListOf<Int>()
        var von = 0
        val w = m.waypoints
        while (von < w.size) {
            var bis = minOf(von + 2, w.size)
            while (bis < w.size && bis - von < maxPunkte && braucht(w.subList(von, bis + 1)) <= VOLL_PROZENT) bis++
            // Ein einzelner Restpunkt ergibt keinen Plan: den letzten Abschnitt um einen Punkt kürzen.
            if (w.size - bis == 1) { if (bis - von > 2) bis-- else bis = w.size }
            teile += w.subList(von, bis).toList(); anfaenge += von
            von = bis
        }
        val n = teile.size
        val abschnitte = teile.mapIndexed { i, t ->
            // Offenes Video am Ende eines Abschnitts schließen, im nächsten wieder öffnen.
            var laeuft = false
            w.subList(0, anfaenge[i]).forEach { if (it.video == 1) laeuft = true else if (it.video == 2) laeuft = false }
            val t2 = t.toMutableList()
            if (laeuft && t2.first().video == 0) t2[0] = t2[0].copy(video = 1)
            // Läuft am Ende des Abschnitts noch Video, beendet es die App am ersten Rückweg-Punkt (MissionController.prepare).
            m.copy(name = if (n > 1) "${m.name}-leg${i + 1}of$n" else m.name, waypoints = t2)
        }
        return Teilung(abschnitte, abschnitte.map { braucht(it.waypoints) })
    }

    /** Der Plan passt auch mit nur zwei Punkten nicht in einen Akku: das Ziel liegt zu weit weg. */
    fun zuWeit(t: Teilung): Boolean = t.prozentJeAbschnitt.any { it > 100 }

    /** Satz für den Plan-Check. */
    fun satz(t: Teilung): String = when {
        zuWeit(t) -> "Too far for one battery: even a single leg needs ${t.prozentJeAbschnitt.max()} %."
        t.akkus == 1 -> "1 battery · needs about ${t.prozentJeAbschnitt[0]} %, reserve included."
        else -> "${t.akkus} batteries · legs " + t.prozentJeAbschnitt.joinToString(" / ") + " % · land, swap, the rest loads by itself."
    }

    /**
     * Restplan nach einer vorzeitigen Umkehr (Felix 22.11.2026: „dass dann einfach automatisch ein zweiter Restplan erzeugt wird“):
     * die Punkte ab [naechster] (0-basiert im geladenen Plan). null, wenn weniger als zwei Punkte fehlen.
     */
    fun rest(m: Mission, naechster: Int): Mission? {
        val von = naechster.coerceAtLeast(0)
        if (m.waypoints.size - von < 2) return null
        return m.copy(name = m.name.removeSuffix("-rest") + "-rest", waypoints = m.waypoints.drop(von), startFromCurrent = false, hoverAtEnd = false)
    }
}
