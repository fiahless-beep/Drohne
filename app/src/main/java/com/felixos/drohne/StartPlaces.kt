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

    fun all(ctx: Context): List<Place> = read(ctx, strict = false)

    /**
     * Wie [all], wirft aber, wenn die gespeicherte Liste nicht lesbar ist (statt dann nur die festen Plätze zu liefern).
     * Für alles, was Orte verbirgt (Standbilder, Live-Draht): dort heißt „unbekannt“ privat.
     */
    fun allStrict(ctx: Context): List<Place> = read(ctx, strict = true)

    /** Gespeicherte Liste lesen; wirft bei beschädigtem Inhalt. */
    internal fun parse(json: String?): List<Place> {
        val a = JSONArray(json ?: "[]")
        return (0 until a.length()).map { i -> val o = a.getJSONObject(i); Place(o.getString("name"), LatLon(o.getDouble("lat"), o.getDouble("lon")), o.getDouble("offset")) }
    }

    private fun read(ctx: Context, strict: Boolean): List<Place> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = try { parse(prefs.getString(KEY, "[]")) } catch (t: Throwable) { if (strict) throw t; emptyList() }
        val deleted = prefs.getStringSet(KEY_DEL, emptySet()) ?: emptySet()
        return saved + DEFAULTS.filter { d -> d.name !in deleted && saved.none { Geo.distance(it.pos, d.pos) <= MATCH_M } }
    }

    // ---- Kopie außerhalb der App (Felix 22.11.2026: Startplätze dürfen mit der App nicht verloren gehen) ----
    // Datei Documents/FelixDrohne/startplaetze.json im Handyspeicher: bleibt bei Deinstallation liegen. Braucht „Zugriff auf alle Dateien“
    // (einmal erlauben). Die Datei enthält Orte, aber keinen Token; sie verlässt das Handy nicht.
    private const val KEY_ASKED = "spiegel_gefragt"

    internal fun toMirror(saved: List<Place>, deleted: Set<String>): String {
        val a = JSONArray(); saved.forEach { a.put(JSONObject().put("name", it.name).put("lat", it.pos.lat).put("lon", it.pos.lon).put("offset", it.offsetM)) }
        return JSONObject().put("plaetze", a).put("geloescht", JSONArray(deleted.sorted())).toString(1)
    }

    /** Wirft bei beschädigter Datei (dann wird nichts übernommen). */
    internal fun fromMirror(text: String): Pair<List<Place>, Set<String>> {
        val o = JSONObject(text); val d = o.optJSONArray("geloescht") ?: JSONArray()
        return parse(o.getJSONArray("plaetze").toString()) to (0 until d.length()).map { d.getString(it) }.toSet()
    }

    private fun mirrorFile() = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "FelixDrohne/startplaetze.json")
    fun canMirror(): Boolean = android.os.Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()
    fun askedForMirror(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)
    fun markAsked(ctx: Context) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply() }

    private fun writeMirror(ctx: Context) {
        if (!canMirror()) return
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = parse(prefs.getString(KEY, "[]"))   // beschädigte Liste nicht über die gute Kopie schreiben
            val f = mirrorFile(); f.parentFile?.mkdirs()
            val tmp = java.io.File(f.parentFile, f.name + ".neu"); tmp.writeText(toMirror(saved, prefs.getStringSet(KEY_DEL, emptySet()) ?: emptySet()))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (t: Throwable) { DroneManager.log("Startplätze: Kopie im Handyspeicher nicht geschrieben (${t.javaClass.simpleName})") }
    }

    private const val KEY_MERGED = "spiegel_abgeglichen"

    /** Plätze aus der Kopie, die die App noch nicht kennt (kein eigener Platz im Umkreis [MATCH_M]), hinter die eigenen. */
    internal fun merge(own: List<Place>, mirror: List<Place>): List<Place> =
        own + mirror.filter { m -> own.none { Geo.distance(it.pos, m.pos) <= MATCH_M } }

    /**
     * Beim App-Start und nach dem Erlauben: Beim ersten Abgleich einer Installation werden die Plätze aus der Kopie im Handyspeicher
     * zu den eigenen dazugenommen (frische Installation: alle kommen zurück; schon neu gemerkte bleiben). Danach folgt die Kopie der App.
     * Rückgabe: Zahl der zurückgeholten Plätze beim ersten Abgleich, sonst null.
     */
    fun syncMirror(ctx: Context): Int? {
        if (!canMirror()) return null
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var restored: Int? = null
        if (!prefs.getBoolean(KEY_MERGED, false)) {
            val f = mirrorFile()
            if (f.exists()) try {
                val own = parse(prefs.getString(KEY, "[]"))
                val (places, deleted) = fromMirror(f.readText())
                val all = merge(own, places)
                val a = JSONArray(); all.forEach { a.put(JSONObject().put("name", it.name).put("lat", it.pos.lat).put("lon", it.pos.lon).put("offset", it.offsetM)) }
                prefs.edit().putString(KEY, a.toString()).putStringSet(KEY_DEL, (prefs.getStringSet(KEY_DEL, emptySet()) ?: emptySet()) + deleted).commit()
                restored = all.size - own.size
                DroneManager.log("Startplätze: $restored aus der Kopie im Handyspeicher zurückgeholt")
            } catch (t: Throwable) {
                // Kopie oder eigene Liste nicht lesbar: nichts übernehmen und die Kopie NICHT überschreiben; beim nächsten Start neu versuchen.
                DroneManager.log("Startplätze: Abgleich mit der Kopie im Handyspeicher gescheitert (${t.javaClass.simpleName}), nichts geändert"); return null
            }
            prefs.edit().putBoolean(KEY_MERGED, true).apply()
        }
        writeMirror(ctx)
        return restored
    }

    fun save(ctx: Context, p: Place) {
        val list = all(ctx).filter { Geo.distance(it.pos, p.pos) > MATCH_M } + p  // gleicher Ort ersetzt den alten Eintrag
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("name", it.name).put("lat", it.pos.lat).put("lon", it.pos.lon).put("offset", it.offsetM)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).commit()
        writeMirror(ctx)
    }

    fun remove(ctx: Context, p: Place) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val a = JSONArray(); all(ctx).filter { it.name != p.name && it !in DEFAULTS }.forEach { a.put(JSONObject().put("name", it.name).put("lat", it.pos.lat).put("lon", it.pos.lon).put("offset", it.offsetM)) }
        val del = (prefs.getStringSet(KEY_DEL, emptySet()) ?: emptySet()).toMutableSet(); if (DEFAULTS.any { it.name == p.name }) del += p.name
        prefs.edit().putString(KEY, a.toString()).putStringSet(KEY_DEL, del).commit()
        writeMirror(ctx)
    }

    /** Gemerkter Startplatz in der Nähe von [home] (≤ 25 m), sonst null. */
    fun near(ctx: Context, home: LatLon?): Place? {
        home ?: return null
        return all(ctx).filter { Geo.distance(it.pos, home) <= MATCH_M }.minByOrNull { Geo.distance(it.pos, home) }
    }
}
