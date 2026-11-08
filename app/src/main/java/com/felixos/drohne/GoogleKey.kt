package com.felixos.drohne

import android.content.Context

/**
 * Google-Maps-Schlüssel (Maps JavaScript API + Map Tiles API) für die fotorealistische 3D-Ansicht (assets/google3d.html).
 * Bleibt nur auf dem Handy (SharedPreferences), nie im Repo. Eintragen: Mehr … → „Google-Schlüssel eintragen“.
 */
object GoogleKey {
    private const val PREFS = "drohne"
    private const val KEY = "google_key"

    fun get(c: Context): String? = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.trim()?.ifBlank { null }
    fun save(c: Context, key: String) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, key.trim()).apply() }
}
