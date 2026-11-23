package com.felixos.drohne.plan

/**
 * Der Brief einer Mission: die wenigen Angaben, aus denen die App selbst einen Plan baut (Felix 23.11.2026: sieben
 * Missionen, jede läuft Brief → Plan-Check → Flug → Ergebnis). Reine Daten und Regeln ohne Android und ohne SDK.
 */
enum class Art(val nr: Int, val titel: String, val ort: OrtArt) {
    PLAN(1, "Fly a plan", OrtArt.KEINER),
    MOTIV(2, "Subject on the way", OrtArt.KEINER),
    NACHSEHEN(3, "Check a place", OrtArt.PUNKT),
    SUCHEN(4, "Find a spot", OrtArt.PUNKT),
    FOLGEN(5, "Follow", OrtArt.KEINER),
    VERMESSEN(6, "Survey a property", OrtArt.FLAECHE),
    STIMMUNG(7, "Atmosphere", OrtArt.PUNKT);

    companion object { fun von(nr: Int): Art? = values().firstOrNull { it.nr == nr } }
}

enum class OrtArt { KEINER, PUNKT, FLAECHE }

/** Ein Feld des Briefs mit festen Werten zur Wahl (kein Tippen im Feld, Daumen an den Sticks). */
class Feld(val id: String, val name: String, val werte: List<Int>, val einheit: String, var gewaehlt: Int) {
    val wert: Int get() = werte[gewaehlt.coerceIn(0, werte.size - 1)]
}

class Brief(val art: Art) {
    val felder: List<Feld> = Auftragsbau.felder(art)
    /** Der gewählte Ort (03, 04, 07). */
    var ort: LatLon? = null
    /** Die Ecken der Fläche in der Reihenfolge des Antippens (06). */
    val ecken: MutableList<LatLon> = mutableListOf()
    /** Frage (03) oder was gesucht wird (04), gesprochen. */
    var text: String = ""
    fun wert(id: String): Double = felder.first { it.id == id }.wert.toDouble()
}

object Auftragsbau {
    const val MAX_ECKEN = 12

    fun felder(art: Art): List<Feld> = when (art) {
        Art.NACHSEHEN -> listOf(Feld("h", "Height", listOf(40, 60, 80), "m", 0))
        Art.SUCHEN -> listOf(Feld("r", "Search radius", listOf(50, 80, 120), "m", 1), Feld("h", "Height", listOf(45, 60), "m", 0))
        Art.FOLGEN -> listOf(Feld("h", "Height", listOf(30, 40, 50), "m", 0), Feld("d", "Distance to you", listOf(15, 25, 40), "m", 1))
        Art.VERMESSEN -> listOf(Feld("h", "Height", listOf(50, 60, 80), "m", 1))
        Art.STIMMUNG -> listOf(Feld("r", "Keep-out radius around the crowd", listOf(30, 50, 80), "m", 1), Feld("d", "Distance outside the zone", listOf(30, 50), "m", 0), Feld("h", "Height", listOf(35, 50), "m", 0))
        else -> emptyList()
    }

    /** Was beim Tippen auf die Karte gilt; null = angenommen, sonst der Grund. */
    fun tippen(b: Brief, p: LatLon, home: LatLon?): String? {
        if (home != null && Geo.distance(home, p) > Preflight.MAX_DISTANCE_M) return "That point is ${Geo.distance(home, p).toInt()} m from the start point (max. ${Preflight.MAX_DISTANCE_M} m)."
        when (b.art.ort) {
            OrtArt.PUNKT -> b.ort = p
            OrtArt.FLAECHE -> { if (b.ecken.size >= MAX_ECKEN) return "At most $MAX_ECKEN corners."; b.ecken += p }
            OrtArt.KEINER -> return "This mission needs no place."
        }
        return null
    }

    /** Was im Brief noch fehlt; null = es kann geplant werden. */
    fun fehlt(b: Brief): String? = when (b.art) {
        Art.NACHSEHEN -> if (b.ort == null) "Choose the place on the map." else if (b.text.isBlank()) "Say what you want to know about the place." else null
        Art.SUCHEN -> if (b.ort == null) "Choose the middle of the search area on the map." else if (b.text.isBlank()) "Say what you are looking for." else null
        Art.VERMESSEN -> when { b.ecken.size < 3 -> "Tap at least three corners of the property on the map."; Missionsbau.ueberschlagen(b.ecken) -> "The outline crosses itself. Tap the corners in order around the property."; else -> null }
        Art.STIMMUNG -> if (b.ort == null) "Choose the middle of the crowd on the map." else null
        else -> null
    }

    /** „140 m north-east of the start point“: Ort in Worten, damit die Oberfläche keine Koordinaten braucht. */
    fun ortText(home: LatLon?, p: LatLon): String {
        if (home == null) return "chosen (distance unknown until the home point is set)"
        val d = Geo.distance(home, p)
        if (d < 5) return "at the start point"
        val r = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[(((Geo.bearing(home, p) + 22.5) % 360) / 45).toInt()]
        return "${Math.round(d / 5) * 5} m $r of the start point"
    }

    class Bau(val mission: Mission, val beschreibung: String)

    /** Plan zum Brief; wirft [Missionsbau.Abgelehnt] mit dem Grund für die Anzeige. */
    fun bauen(b: Brief, home: LatLon): Bau {
        fehlt(b)?.let { throw Missionsbau.Abgelehnt(it) }
        val h = if (b.felder.any { it.id == "h" }) b.wert("h") else 0.0
        val bau = when (b.art) {
            Art.NACHSEHEN -> {
                val p = b.ort!!
                Bau(Missionsbau.nahsicht(p, h), "Check a place: flies to the place ${ortText(home, p)}, hovers 12 s at ${h.toInt()} m with the camera straight down, takes two photos and comes home. Central Command answers from a still: “${b.text}”")
            }
            Art.SUCHEN -> {
                val p = b.ort!!; val r = b.wert("r")
                val m = Missionsbau.suche(Missionsbau.kreis(p, r), h)
                Bau(m, "Find a spot: sweeps a circle of ${r.toInt()} m radius ${ortText(home, p)} at ${h.toInt()} m, camera straight down, ${m.waypoints.size} photo points, then comes home. Central Command looks through the stills for: “${b.text}”")
            }
            Art.VERMESSEN -> {
                val m = Missionsbau.vermessung(b.ecken.toList(), h)
                val ha = Missionsbau.flaecheM2(b.ecken) / 10_000
                Bau(m, "Survey a property: grid from above and an oblique ring around ${String.format(java.util.Locale.US, "%.1f", ha)} ha at ${h.toInt()} m, ${m.waypoints.count { it.takePhoto }} photos. The photos are the basis for a height model and a 3D model.")
            }
            Art.STIMMUNG -> {
                val p = b.ort!!; val r = b.wert("r"); val d = b.wert("d")
                if (Geo.distance(home, p) < r + Sperrzone.PUFFER_M) throw Missionsbau.Abgelehnt("The start point is inside the keep-out zone. Take off at least ${(r + Sperrzone.PUFFER_M).toInt()} m from the middle of the crowd.")
                val spann = Missionsbau.groessterBogen(p, home, r, d)
                val m = Missionsbau.bogen(p, r, d, h, Missionsbau.seiteZumStart(p, home), spann)
                Missionsbau.sperrzoneProblem(m, home, p, r)?.let { throw Missionsbau.Abgelehnt("Keep-out zone: $it") }
                Bau(m, "Atmosphere: an arc of ${spann.toInt()}° on your side of the crowd, ${(r + d).toInt()} m from its middle at ${h.toInt()} m, camera on the crowd, ${m.waypoints.size} photos and one video. Keep-out zone ${r.toInt()} m: the drone never flies over the crowd, not on the way there and not on any way home.")
            }
            else -> throw Missionsbau.Abgelehnt("This mission is not built from a brief.")
        }
        val m = bau.mission
        if (m.waypoints.size > Missions.MAX_PLAN_WAYPOINTS) throw Missionsbau.Abgelehnt("${m.waypoints.size} points are too many for one plan (max. ${Missions.MAX_PLAN_WAYPOINTS}). Choose a greater height or a smaller area.")
        val weit = m.waypoints.maxOf { Geo.distance(home, it.pos) }
        if (weit > Preflight.MAX_DISTANCE_M) throw Missionsbau.Abgelehnt("One point would be ${weit.toInt()} m from the start point (max. ${Preflight.MAX_DISTANCE_M} m). Start closer.")
        return Bau(m.copy(plannedStart = home), bau.beschreibung)
    }

    /**
     * Ort am Boden zu einer Stelle in einem Bild, das senkrecht nach unten aufgenommen wurde: [x], [y] = Bildanteile
     * (0…1, oben links = 0/0), [kursGrad] = Blickrichtung der Drohne (Bild oben), [hoeheM] = Höhe über dem Boden dort.
     * Bildwinkel des Livebilds wie in FlugSitzung.groundPoint (73° quer, 46° hoch). Näherung auf einige Meter.
     */
    fun bildOrt(hier: LatLon, hoeheM: Double, kursGrad: Double, x: Double, y: Double): LatLon {
        val rechts = (x.coerceIn(0.0, 1.0) - 0.5) * 2 * Math.tan(Math.toRadians(73.0 / 2)) * hoeheM
        val vor = (0.5 - y.coerceIn(0.0, 1.0)) * 2 * Math.tan(Math.toRadians(46.0 / 2)) * hoeheM
        return Geo.offset(Geo.offset(hier, vor, kursGrad), rechts, kursGrad + 90.0)
    }
}
