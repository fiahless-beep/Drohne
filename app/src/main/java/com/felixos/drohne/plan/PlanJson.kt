package com.felixos.drohne.plan

import org.json.JSONArray
import org.json.JSONObject

/**
 * Flugplan-Format, das die Cloud-KI erzeugt (Datei plaene/aktuell.json im Repo).
 *
 * {
 *   "name": "grundstueck-montargil",
 *   "beschreibung": "Fotos vom Haupthaus, 4 Seiten",
 *   "speed": 5,                       // m/s
 *   "rcLost": "continue" | "goHome",
 *   "umgebung": "frei",               // nur wenn im Umkreis von 50 m wirklich nichts höher als 2 m steht; sonst braucht jeder Punkt unter 30 m "hindernisse"
 *   "start": {"lat": 39.05, "lon": -8.17},   // Startpunkt, für den die Strecke geprüft wurde; steht die Drohne > 50 m weg, verweigert die App den Start
 *   "licht": true,                    // optional: Landelampe unten während der Mission an (kleine LED, hilft nur aus wenigen Metern)
 *   "hindernisse": [ {"name": "LMSI", "h": 15, "umriss": [[lat, lon], [lat, lon], ...]} ],  // Gebäude aus den Kartendaten; die App prüft die Strecke dagegen
 *   "ziel": {"lat": 39.05, "lon": -8.17, "h": 8},   // optional: Gebäude/Platz; Kamera zeigt darauf (h = Zielhöhe über Boden)
 *   "kreis": {"radius": 30, "h": 40, "punkte": 8, "fotos": 4, "start": 180, "uhrzeigersinn": true}, // optional: Kreisflug ums Ziel, ersetzt waypoints; fotos = Anzahl Fotos (an weniger Punkten = flüssiger)
 *   "relativ": [ {"richtung": 200, "abstand": 30, "h": 15} ],   // optional, mit ziel: Punkte als Richtung (vom Ziel aus gesehen) und Abstand; Kamera aufs Ziel
 *   "waypoints": [ {"lat": 39.05, "lon": -8.17, "h": 40, "pitch": -45, "photo": true, "hover": 0, "yaw": 90}, ... ]
 * }
 * Mit "ziel": Wegpunkte ohne "yaw" blicken zum Ziel, ohne "pitch" neigt die Kamera aufs Ziel.
 * "start" beim Kreis = Richtung vom Ziel zum ersten Punkt (Sonne im Rücken: Richtung der Sonne wählen).
 */
object PlanJson {
    fun parse(text: String): Pair<Mission, String> {
        val o = JSONObject(text)
        val name = o.optString("name", "plan").lowercase().replace(Regex("[^a-z0-9-]"), "-").take(40).ifBlank { "plan" }
        val speed = o.optDouble("speed", 5.0)
        val rcLost = if (o.optString("rcLost", "continue") == "goHome") RcLost.GO_HOME else RcLost.CONTINUE
        val target = o.optJSONObject("ziel")?.let { LatLon(it.getDouble("lat"), it.getDouble("lon")) }
        val obstacles = o.optJSONArray("hindernisse")?.let { arr ->
            (0 until arr.length()).map { i ->
                val h = arr.getJSONObject(i)
                val u = h.getJSONArray("umriss")
                Obstacle(h.optString("name", "Gebäude ${i + 1}"), h.getDouble("h"),
                    (0 until u.length()).map { k -> val c = u.getJSONArray(k); LatLon(c.getDouble(0), c.getDouble(1)) })
            }
        } ?: emptyList()
        val targetH = o.optJSONObject("ziel")?.optDouble("h", 0.0) ?: 0.0
        val openArea = o.optString("umgebung", "") == "frei"
        val plannedStart = o.optJSONObject("start")?.let { LatLon(it.getDouble("lat"), it.getDouble("lon")) }
        val kreis = o.optJSONObject("kreis")
        if (kreis != null && !o.has("waypoints")) {
            requireNotNull(target) { "kreis braucht ziel" }
            val m = Missions.orbit(target, kreis.getDouble("radius"), kreis.getDouble("h"), kreis.optInt("punkte", 8),
                kreis.optDouble("start", 0.0), targetH, speed, name, kreis.optBoolean("uhrzeigersinn", true), kreis.optInt("fotos", kreis.optInt("punkte", 8)))
            return m.copy(rcLost = rcLost, light = o.optBoolean("licht", false), obstacles = obstacles, openArea = openArea, plannedStart = plannedStart) to o.optString("beschreibung", "")
        }
        // "relativ": Punkte als Richtung/Abstand vom Ziel (z. B. Fassadenfoto: richtung = von wo aus man schaut).
        val rel = o.optJSONArray("relativ")
        if (rel != null && !o.has("waypoints")) {
            requireNotNull(target) { "relativ braucht ziel" }
            val wps = (0 until rel.length()).map { i ->
                val w = rel.getJSONObject(i)
                val pos = Geo.offset(target, w.getDouble("abstand"), w.getDouble("richtung"))
                val h = w.getDouble("h")
                Waypoint(pos, h,
                    gimbalPitchDeg = if (w.has("pitch")) w.getDouble("pitch") else Missions.pitchTo(w.getDouble("abstand"), h - targetH),
                    takePhoto = w.optBoolean("photo", true), hoverSec = w.optInt("hover", 0),
                    headingDeg = if (w.has("yaw")) w.getDouble("yaw") else Geo.bearing(pos, target))
            }
            val wps2 = if (wps.size == 1) wps + wps.first().copy(pos = Geo.offset(wps.first().pos, 3.0, 0.0), takePhoto = false) else wps
            return Mission(name = name, waypoints = wps2, speedMs = speed, rcLost = rcLost, target = target, light = o.optBoolean("licht", false), obstacles = obstacles, openArea = openArea, plannedStart = plannedStart) to o.optString("beschreibung", "")
        }
        val arr: JSONArray = o.getJSONArray("waypoints")
        val wps = (0 until arr.length()).map { i ->
            val w = arr.getJSONObject(i)
            val pos = LatLon(w.getDouble("lat"), w.getDouble("lon"))
            val h = w.getDouble("h")
            val autoPitch = target?.let { Missions.pitchTo(Geo.distance(pos, it), h - targetH) } ?: -90.0
            Waypoint(
                pos = pos,
                heightM = h,
                gimbalPitchDeg = if (w.has("pitch")) w.getDouble("pitch") else autoPitch,
                takePhoto = w.optBoolean("photo", true),
                hoverSec = w.optInt("hover", 0),
                headingDeg = if (w.has("yaw")) w.getDouble("yaw") else target?.let { Geo.bearing(pos, it) },
            )
        }
        val m = Mission(name = name, waypoints = wps, speedMs = speed, rcLost = rcLost, target = target, light = o.optBoolean("licht", false), obstacles = obstacles, openArea = openArea, plannedStart = plannedStart)
        return m to o.optString("beschreibung", "")
    }

    fun toJson(m: Mission, beschreibung: String = ""): String {
        val o = JSONObject()
        o.put("name", m.name)
        o.put("beschreibung", beschreibung)
        o.put("speed", m.speedMs)
        o.put("rcLost", if (m.rcLost == RcLost.GO_HOME) "goHome" else "continue")
        m.target?.let { o.put("ziel", JSONObject().put("lat", it.lat).put("lon", it.lon)) }
        if (m.light) o.put("licht", true)
        if (m.openArea) o.put("umgebung", "frei")
        m.plannedStart?.let { o.put("start", JSONObject().put("lat", it.lat).put("lon", it.lon)) }
        val arr = JSONArray()
        m.waypoints.forEach { w ->
            val j = JSONObject().put("lat", w.pos.lat).put("lon", w.pos.lon).put("h", w.heightM)
                .put("pitch", w.gimbalPitchDeg).put("photo", w.takePhoto).put("hover", w.hoverSec)
            w.headingDeg?.let { j.put("yaw", it) }
            arr.put(j)
        }
        o.put("waypoints", arr)
        return o.toString(2)
    }
}
