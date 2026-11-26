package com.felixos.drohne.plan

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Baut aus dem Brief einer Mission einen Flugplan (Felix 23.11.2026: „bau auch mal die Missionen richtig sauber“).
 * Reine Rechnung ohne Android und ohne SDK. Jeder Plan von hier geht durch dieselbe Starttür wie ein Plan aus dem Chat
 * (MissionController.prepare, Gebäude- und Oberflächenwächter, Preflight.startGate); hier wird nichts freigegeben.
 * Höhen sind Meter über dem Startpunkt. Ohne Höhenmodell plant nichts hier unter [SICHERE_HOEHE_M].
 */
object Missionsbau {
    /** Tiefste Höhe, die ein Plan von hier ohne Höhen- und Gebäudedaten bekommt (gleich der Transithöhe der App). */
    const val SICHERE_HOEHE_M = 30.0
    /** Bildwinkel der Hauptkamera der Mini 4 Pro (24 mm KB, 4:3): quer und längs zur Flugrichtung, Kamera senkrecht nach unten. */
    private const val BILD_QUER = 2 * 0.750   // 2·tan(36,9°): Bildbreite am Boden je Meter Höhe
    private const val BILD_LAENGS = 2 * 0.5625 // 2·tan(29,4°)

    class Abgelehnt(val grund: String) : Exception(grund)

    // ---------------------------------------------------------------- Ebene Geometrie um einen Bezugspunkt

    /** Meter Ost/Nord um [o]; für Flächen bis wenige Kilometer genau genug. */
    class Ebene(private val o: LatLon) {
        private val kx = 111_320.0 * cos(Math.toRadians(o.lat))
        private val ky = 110_540.0
        fun x(p: LatLon) = (p.lon - o.lon) * kx
        fun y(p: LatLon) = (p.lat - o.lat) * ky
        fun ort(x: Double, y: Double) = LatLon(o.lat + y / ky, o.lon + x / kx)
    }

    /** Mittelpunkt des umschließenden Rechtecks. */
    fun mitte(poly: List<LatLon>): LatLon = LatLon((poly.minOf { it.lat } + poly.maxOf { it.lat }) / 2, (poly.minOf { it.lon } + poly.maxOf { it.lon }) / 2)

    /** Fläche in Quadratmetern (Schuhband-Formel). */
    fun flaecheM2(poly: List<LatLon>): Double {
        if (poly.size < 3) return 0.0
        val e = Ebene(mitte(poly))
        var s = 0.0
        for (i in poly.indices) { val a = poly[i]; val b = poly[(i + 1) % poly.size]; s += e.x(a) * e.y(b) - e.x(b) * e.y(a) }
        return abs(s) / 2
    }

    /** Größter Abstand einer Ecke vom Mittelpunkt. */
    fun umkreisM(poly: List<LatLon>): Double { val c = mitte(poly); return poly.maxOf { Geo.distance(c, it) } }

    /** Kreis als Vieleck (Suchgebiet aus Mittelpunkt und Radius). */
    fun kreis(c: LatLon, radiusM: Double, ecken: Int = 16): List<LatLon> = (0 until ecken).map { Geo.offset(c, radiusM, 360.0 * it / ecken) }

    /** Kreuzen sich zwei Kanten des Vielecks? Dann ist die Reihenfolge der Ecken falsch getippt. */
    fun ueberschlagen(poly: List<LatLon>): Boolean {
        if (poly.size < 4) return false
        val e = Ebene(mitte(poly))
        val p = poly.map { e.x(it) to e.y(it) }
        fun d(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>) = (b.first - a.first) * (c.second - a.second) - (b.second - a.second) * (c.first - a.first)
        fun schneiden(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>, dd: Pair<Double, Double>) =
            d(a, b, c) * d(a, b, dd) < 0 && d(c, dd, a) * d(c, dd, b) < 0
        for (i in p.indices) for (j in i + 1 until p.size) {
            if (j == i + 1 || (i == 0 && j == p.size - 1)) continue
            if (schneiden(p[i], p[(i + 1) % p.size], p[j], p[(j + 1) % p.size])) return true
        }
        return false
    }

    // ---------------------------------------------------------------- Raster (Vermessung, Suche)

    /** Bahn- und Fotoabstand für eine Höhe über Grund und die gewünschte Überlappung (0…0,9). */
    fun abstaende(hoeheUeberGrundM: Double, ueberlappQuer: Double, ueberlappLaengs: Double): Pair<Double, Double> =
        max(5.0, hoeheUeberGrundM * BILD_QUER * (1 - ueberlappQuer)) to max(5.0, hoeheUeberGrundM * BILD_LAENGS * (1 - ueberlappLaengs))

    /**
     * Mäander über das Vieleck: parallele Bahnen im Abstand [bahnM] längs der längeren Seite, auf jeder Bahn Fotopunkte
     * alle [fotoM], Kamera senkrecht nach unten, Nase in Flugrichtung der ersten Bahn (alle Bilder gleich ausgerichtet).
     * Die Bahnen enden am Rand des Vielecks; bei einspringenden Ecken fliegt eine Bahn über die Lücke hinweg.
     */
    fun raster(poly: List<LatLon>, hoeheM: Double, bahnM: Double, fotoM: Double, name: String, tempoMs: Double = 5.0, schwebenS: Int = 0): Mission {
        if (poly.size < 3) throw Abgelehnt("An area needs at least three corners.")
        if (ueberschlagen(poly)) throw Abgelehnt("The outline crosses itself. Tap the corners in order around the area.")
        if (hoeheM < SICHERE_HOEHE_M) throw Abgelehnt("Height ${hoeheM.toInt()} m is below the safe height of ${SICHERE_HOEHE_M.toInt()} m.")
        val e = Ebene(mitte(poly))
        val pts = poly.map { e.x(it) to e.y(it) }
        // Bahnrichtung: die Kantenrichtung, bei der das umschließende Rechteck am schmalsten ist (wenig Wenden).
        var best = 0.0; var bestBreite = Double.MAX_VALUE
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            val w = atan2(b.second - a.second, b.first - a.first)
            val vs = pts.map { -it.first * sin(w) + it.second * cos(w) }
            val breite = vs.max() - vs.min()
            if (breite < bestBreite - 0.01) { bestBreite = breite; best = w }
        }
        val c = cos(best); val s = sin(best)
        val rot = pts.map { (it.first * c + it.second * s) to (-it.first * s + it.second * c) }  // u längs der Bahn, v quer
        val vMin = rot.minOf { it.second }; val vMax = rot.maxOf { it.second }
        val bahnen = max(1, ceil((vMax - vMin) / bahnM).toInt())
        val schritt = (vMax - vMin) / bahnen
        val kurs = (90.0 - Math.toDegrees(best) + 360.0) % 360.0
        val out = mutableListOf<Waypoint>()
        for (k in 0 until bahnen) {
            val v = vMin + schritt * (k + 0.5)
            val us = mutableListOf<Double>()
            for (i in rot.indices) {
                val a = rot[i]; val b = rot[(i + 1) % rot.size]
                if ((a.second > v) != (b.second > v)) us += a.first + (b.first - a.first) * (v - a.second) / (b.second - a.second)
            }
            if (us.size < 2) continue
            val u0 = us.min(); val u1 = us.max()
            val n = max(1, ceil((u1 - u0) / fotoM).toInt())
            val reihe = (0..n).map { u0 + (u1 - u0) * it / n }.let { if (k % 2 == 1) it.reversed() else it }
            reihe.forEach { u -> out += Waypoint(e.ort(u * c - v * s, u * s + v * c), hoeheM, gimbalPitchDeg = -90.0, takePhoto = true, hoverSec = schwebenS, headingDeg = kurs) }
        }
        if (out.size < 2) throw Abgelehnt("The area is too small for a grid. Make it larger or use “Check a place”.")
        return Mission(name = name, waypoints = out, speedMs = tempoMs, target = mitte(poly))
    }

    /**
     * 06 Survey a property und der Vermessungsflug: Raster von oben (70 % Überlappung quer und längs) und ein schräger
     * Ring außen herum (Felix 22.11.2026: „Raster von oben plus schräger Ring, sichere Höhe“).
     */
    fun vermessung(grenze: List<LatLon>, hoeheM: Double = 60.0, name: String = "survey"): Mission {
        val (bahn, foto) = abstaende(hoeheM, 0.70, 0.70)
        val r = raster(grenze, hoeheM, bahn, foto, name, tempoMs = 5.0)
        val c = mitte(grenze)
        // Der Ring liegt außen um das Grundstück. Wäre er dafür größer als 200 m, läge er gekappt mitten darin: dann ablehnen.
        if (umkreisM(grenze) + hoeheM * 0.6 > 200.0) throw Abgelehnt("The property is too large for one survey. Tap a smaller part and survey it in pieces.")
        val radius = (umkreisM(grenze) + hoeheM * 0.6).coerceAtLeast(15.0)
        val punkte = (2 * Math.PI * radius / 25.0).toInt().coerceIn(8, 24)
        val vom = Geo.bearing(c, r.waypoints.last().pos)   // Ring dort beginnen, wo das Raster endet
        val ring = Missions.orbit(c, radius, hoeheM, points = punkte, startBearingDeg = vom, speedMs = 4.0, name = name).waypoints.dropLast(1)
        return r.copy(waypoints = r.waypoints + ring)
    }

    /** 04 Find a spot: Raster nur zum Abdecken (20 % Überlappung), an jedem Punkt [schwebenS] Sekunden für ein scharfes Bild. */
    fun suche(gebiet: List<LatLon>, hoeheM: Double = 45.0, name: String = "find-a-spot"): Mission {
        // Ausgewertet wird das Standbild des Livebilds (16:9, längs schmaler als das Foto): längs deshalb 35 % statt 20 %.
        val (bahn, foto) = abstaende(hoeheM, 0.20, 0.35)
        return raster(gebiet, hoeheM, bahn, foto, name, tempoMs = 5.0, schwebenS = 2)
    }

    // ---------------------------------------------------------------- 03 Check a place

    /**
     * Hinfliegen, [schwebenS] Sekunden senkrecht über dem Ort stehen, ein Foto, heim. Zwei Punkte, weil ein Plan mindestens
     * zwei braucht; der zweite liegt 3 m daneben und macht ein zweites Bild.
     */
    fun nahsicht(ort: LatLon, hoeheM: Double = 40.0, schwebenS: Int = 12, name: String = "check-a-place"): Mission {
        if (hoeheM < SICHERE_HOEHE_M) throw Abgelehnt("Height ${hoeheM.toInt()} m is below the safe height of ${SICHERE_HOEHE_M.toInt()} m.")
        return Mission(name = name, speedMs = 5.0, target = ort, waypoints = listOf(
            Waypoint(ort, hoeheM, gimbalPitchDeg = -90.0, takePhoto = true, hoverSec = schwebenS, headingDeg = 0.0),
            Waypoint(Geo.offset(ort, 3.0, 0.0), hoeheM, gimbalPitchDeg = -90.0, takePhoto = true, hoverSec = 2, headingDeg = 0.0)))
    }

    // ---------------------------------------------------------------- 07 Atmosphere

    /** Mindestabstand, den Kamerastandpunkte zusätzlich zum Sperrradius von der Menge halten. */
    const val SPERR_PUFFER_M = 15.0

    /**
     * Bogen außerhalb der Sperrzone um die Menge: [punkte] Standpunkte im Abstand [sperrRadiusM] + [abstandM] vom Zentrum,
     * von [mitteGrad] − [spannGrad]/2 bis + [spannGrad]/2 (Richtung vom Zentrum aus gesehen), Kamera zum Zentrum, je ein Foto;
     * Video läuft über den ganzen Bogen. Die Drohne fliegt den Bogen außen entlang, nie über die Menge.
     */
    fun bogen(zentrum: LatLon, sperrRadiusM: Double, abstandM: Double, hoeheM: Double, mitteGrad: Double, spannGrad: Double = 120.0,
              punkte: Int = 7, name: String = "atmosphere"): Mission {
        if (sperrRadiusM < 10.0) throw Abgelehnt("The keep-out radius must be at least 10 m.")
        if (abstandM < SPERR_PUFFER_M) throw Abgelehnt("Camera points stay at least ${SPERR_PUFFER_M.toInt()} m outside the keep-out zone.")
        if (hoeheM < SICHERE_HOEHE_M) throw Abgelehnt("Height ${hoeheM.toInt()} m is below the safe height of ${SICHERE_HOEHE_M.toInt()} m.")
        val r = sperrRadiusM + abstandM
        // Zwischen zwei Standpunkten fliegt die Drohne die Sehne: sie muss außerhalb der Sperrzone samt Puffer bleiben.
        val maxSchritt = Math.toDegrees(2 * Math.acos(((sperrRadiusM + SPERR_PUFFER_M * 0.6) / r).coerceIn(0.0, 1.0)))
        val n = max(punkte, ceil(spannGrad / max(5.0, maxSchritt * 0.9)).toInt() + 1).coerceIn(2, 36)
        val pitch = Missions.pitchTo(r, hoeheM)
        val wps = (0 until n).map { i ->
            val b = mitteGrad - spannGrad / 2 + spannGrad * i / (n - 1)
            val p = Geo.offset(zentrum, r, b)
            Waypoint(p, hoeheM, gimbalPitchDeg = pitch, takePhoto = true, headingDeg = (b + 180) % 360, speedMs = 3.0,
                video = when (i) { 0 -> 1; n - 1 -> 2; else -> 0 })
        }
        return Mission(name = name, waypoints = wps, speedMs = 3.0, target = zentrum, sperrzonen = listOf(Sperrzone(zentrum, sperrRadiusM)))
    }

    /** Kürzester Abstand der Strecke a→b vom Punkt [c] in Metern. */
    fun abstandStrecke(c: LatLon, a: LatLon, b: LatLon): Double {
        val e = Ebene(c)
        val ax = e.x(a); val ay = e.y(a); val bx = e.x(b); val by = e.y(b)
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / l2).coerceIn(0.0, 1.0)
        val px = ax + t * dx; val py = ay + t * dy
        return sqrt(px * px + py * py)
    }

    /** Erster Verstoß gegen die Sperrzone als Satz, null = frei (Regeln in [Sperrzone.konflikte]). */
    fun sperrzoneProblem(m: Mission, home: LatLon?, zentrum: LatLon, sperrRadiusM: Double): String? =
        Sperrzone.konflikte(m.copy(sperrzonen = listOf(Sperrzone(zentrum, sperrRadiusM))), home).firstOrNull()

    /** Richtung vom Zentrum zum Start: von dieser Seite aus kreuzt kein Hin- oder Rückweg die Menge. */
    fun seiteZumStart(zentrum: LatLon, home: LatLon): Double = Geo.bearing(zentrum, home)

    /** Größter Bogen (Grad) um [mitteGrad], bei dem der Heimweg von jedem Punkt außerhalb der Sperrzone bleibt; mindestens 40°. */
    fun groessterBogen(zentrum: LatLon, home: LatLon, sperrRadiusM: Double, abstandM: Double, wunschGrad: Double = 120.0): Double {
        var spann = wunschGrad
        val mitteGrad = seiteZumStart(zentrum, home)
        while (spann > 40.0) {
            val ecken = listOf(-spann / 2, spann / 2).map { Geo.offset(zentrum, sperrRadiusM + abstandM, mitteGrad + it) }
            if (ecken.all { abstandStrecke(zentrum, it, home) >= sperrRadiusM + Sperrzone.PUFFER_M + 1.0 }) return spann
            spann -= 10.0
        }
        return min(spann, 40.0)
    }
}
