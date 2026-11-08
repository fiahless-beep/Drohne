package com.felixos.drohne

import android.content.Context
import com.felixos.drohne.plan.Geo
import com.felixos.drohne.plan.LatLon
import org.json.JSONArray
import org.json.JSONObject

/**
 * Erhöhte Startplätze (Felix 8.11.: „vom dritten Stock starten“). Die Pläne rechnen alle Höhen
 * relativ zum Boden am Startpunkt (Höhenmodell). Startet die Drohne vom Balkon, liegt ihr Nullpunkt
 * [offsetM] über diesem Boden → die App zieht den Wert von allen Planhöhen ab und prüft mit dem erhöhten Start.
 * Einmal einmessen: vom Gehweg starten, vor den Balkon schweben, Mehr … → „Startplatz hier merken“.
 */
object StartPlaces {
    data class Place(val name: String, val pos: LatLon, val offsetM: Double)

    const val MATCH_M = 25.0
    private const val PREFS = "drohne"
    private const val KEY = "startplaetze"

    /** Fest eingebaute Plätze (Felix 8.11.): Balkon 3.º Esq., Rua Rodrigo da Fonseca 190 – Haus 28 m (Stadtdaten), 3. Stock ≈ 10 m über der Straße (bewusst eher niedrig geschätzt = sicherer). */
    private val DEFAULTS = listOf(Place("Balkon RdF 190 (3. Stock)", LatLon(38.72783, -9.15727), 10.0))
    private const val KEY_DEL = "startplaetze_geloescht"

    fun all(ctx: Context): List<Place> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = try {
            val a = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
            (0 until a.length()).map { i -> val o = a.getJSONObject(i); Place(o.getString("name"), LatLon(o.getDouble("lat"), o.getDouble("lon")), o.getDouble("offset")) }
        } catch (_: Throwable) { emptyList() }
        val deleted = prefs.getStringSet(KEY_DEL, emptySet()) ?: emptySet()
        return saved + DEFAULTS.filter { d -> d.name !in deleted && saved.none { Geo.distance(it.pos, d.pos) <= MATCH_M } }
    }

    fun save(ctx: Context, p: Place) {
        val list = all(ctx).filter { Geo.distance(it.pos, p.pos) > MATCH_M } + p  // gleicher Ort ersetzt den alten Eintrag
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("name", it.name).put("lat", it.pos.lat).put("lon", it.pos.lon).put("offset", it.offsetM)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    fun remove(ctx: Context, p: Place) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val a = JSONArray(); all(ctx).filter { it.name != p.name && it !in DEFAULTS }.forEach { a.put(JSONObject().put("name", it.name).put("lat", it.pos.lat).put("lon", it.pos.lon).put("offset", it.offsetM)) }
        val del = (prefs.getStringSet(KEY_DEL, emptySet()) ?: emptySet()).toMutableSet(); if (DEFAULTS.any { it.name == p.name }) del += p.name
        prefs.edit().putString(KEY, a.toString()).putStringSet(KEY_DEL, del).apply()
    }

    /** Gemerkter Startplatz in der Nähe von [home] (≤ 25 m), sonst null. */
    fun near(ctx: Context, home: LatLon?): Place? {
        home ?: return null
        return all(ctx).filter { Geo.distance(it.pos, home) <= MATCH_M }.minByOrNull { Geo.distance(it.pos, home) }
    }
}
