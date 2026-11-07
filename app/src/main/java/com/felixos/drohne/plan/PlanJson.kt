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
 *   "waypoints": [ {"lat": 39.05, "lon": -8.17, "h": 40, "pitch": -45, "photo": true, "hover": 0}, ... ]
 * }
 */
object PlanJson {
    fun parse(text: String): Pair<Mission, String> {
        val o = JSONObject(text)
        val arr: JSONArray = o.getJSONArray("waypoints")
        val wps = (0 until arr.length()).map { i ->
            val w = arr.getJSONObject(i)
            Waypoint(
                pos = LatLon(w.getDouble("lat"), w.getDouble("lon")),
                heightM = w.getDouble("h"),
                gimbalPitchDeg = w.optDouble("pitch", -90.0),
                takePhoto = w.optBoolean("photo", true),
                hoverSec = w.optInt("hover", 0),
            )
        }
        val name = o.optString("name", "plan").lowercase().replace(Regex("[^a-z0-9-]"), "-").take(40).ifBlank { "plan" }
        val m = Mission(
            name = name,
            waypoints = wps,
            speedMs = o.optDouble("speed", 5.0),
            rcLost = if (o.optString("rcLost", "continue") == "goHome") RcLost.GO_HOME else RcLost.CONTINUE,
        )
        return m to o.optString("beschreibung", "")
    }

    fun toJson(m: Mission, beschreibung: String = ""): String {
        val o = JSONObject()
        o.put("name", m.name)
        o.put("beschreibung", beschreibung)
        o.put("speed", m.speedMs)
        o.put("rcLost", if (m.rcLost == RcLost.GO_HOME) "goHome" else "continue")
        val arr = JSONArray()
        m.waypoints.forEach { w ->
            arr.put(JSONObject().put("lat", w.pos.lat).put("lon", w.pos.lon).put("h", w.heightM)
                .put("pitch", w.gimbalPitchDeg).put("photo", w.takePhoto).put("hover", w.hoverSec))
        }
        o.put("waypoints", arr)
        return o.toString(2)
    }
}
