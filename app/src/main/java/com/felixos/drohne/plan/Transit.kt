package com.felixos.drohne.plan

/**
 * Sicherheits-Rahmen um einen Plan (Absturz 7.11.): senkrecht über dem Start auf Transithöhe, oben zum ersten Punkt,
 * am Ende wieder hoch und zurück. Reine Rechnung ohne SDK, damit Anzahl und Lage der Zusatzpunkte testbar sind.
 */
object Transit {
    /** Mehr Punkte setzt [frame] in keinem Zweig dazu: 2 davor, 3 dahinter (Balkonstart im Flug mit Rückweg zum Anker). */
    const val MAX_EXTRA = 5

    class Frame(val pre: List<Waypoint>, val post: List<Waypoint>, val landingTail: Boolean)

    /** Übereinanderliegende Punkte 1,5 m versetzen (DJI mag keine Punkte ohne waagerechten Abstand). */
    private fun beside(p: LatLon, towards: LatLon) = Geo.offset(p, 1.5, Geo.bearing(p, towards))

    /**
     * [startAt] = jetzige Position, wenn der Plan im Flug beginnt (sonst [home]); [anchor] = Schwebeplatz vor dem Balkon,
     * [anchorH] = Höhe, auf der die Drohne dort am Ende wartet; [hoverAtEnd] = kein Rückweg.
     */
    fun frame(first: Waypoint, last: Waypoint, transit: Double, home: LatLon?, startAt: LatLon?, anchor: LatLon?, anchorH: Double?, hoverAtEnd: Boolean): Frame {
        val pre = buildList {
            if (startAt != null) add(first.copy(pos = startAt, heightM = transit, takePhoto = false, hoverSec = 0, headingDeg = null, video = 0))
            else if (home != null) add(first.copy(pos = home, heightM = transit, takePhoto = false, hoverSec = 0, headingDeg = null, video = 0))
            if (first.heightM < transit - 1) add(first.copy(pos = beside(first.pos, home ?: last.pos), heightM = transit, takePhoto = false, hoverSec = 0, video = 0))
        }
        var tail = false
        val post = if (hoverAtEnd) emptyList() else buildList {
            if (last.heightM < transit - 1) add(last.copy(pos = beside(last.pos, anchor ?: home ?: first.pos), heightM = transit, takePhoto = false, hoverSec = 0, headingDeg = null, video = 0))
            if (anchor != null) {
                add(last.copy(pos = anchor, heightM = transit, takePhoto = false, hoverSec = 0, headingDeg = null, video = 0))
                if (anchorH != null && anchorH < transit - 1) {
                    tail = true
                    add(last.copy(pos = beside(anchor, last.pos), heightM = anchorH, takePhoto = false, hoverSec = 0, headingDeg = null, video = 0))
                }
            } else if (home != null) add(last.copy(pos = home, heightM = transit, takePhoto = false, hoverSec = 0, headingDeg = null, video = 0))
        }
        return Frame(pre, post, tail)
    }
}
