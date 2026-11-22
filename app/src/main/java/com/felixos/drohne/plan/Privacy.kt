package com.felixos.drohne.plan

/**
 * Öffentliches Repo: rund um gemerkte Startplätze (Zuhause) verlässt nichts die App.
 * Reine Entscheidung ohne Android, damit sie testbar ist.
 */
object Privacy {
    /**
     * Liegt [here] im Umkreis [radiusM] eines der Plätze? Im Zweifel ja (fail-closed):
     * Lässt sich die Liste nicht lesen oder der Abstand nicht berechnen, gilt der Ort als privat.
     */
    fun isPrivate(here: LatLon, radiusM: Double, places: () -> List<LatLon>): Boolean = try {
        places().any { !(Geo.distance(it, here) > radiusM) }   // NaN-Abstand zählt als „im Umkreis“
    } catch (_: Throwable) { true }
}
