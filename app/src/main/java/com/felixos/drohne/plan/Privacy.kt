package com.felixos.drohne.plan

/**
 * Öffentliches Repo: rund um gemerkte Startplätze (Zuhause) verlässt nichts die App.
 * Reine Entscheidung ohne Android, damit sie testbar ist.
 */
object Privacy {
    /**
     * Liegt [here] im Umkreis [radiusM] eines der Plätze? Im Zweifel ja (fail-closed):
     * Lässt sich die Liste nicht lesen oder der Abstand nicht berechnen, gilt der Ort als privat; ebenso eine Position,
     * die keine ist (0/0 = kein GPS, nicht endlich, außerhalb des Wertebereichs).
     */
    fun isPrivate(here: LatLon, radiusM: Double, places: () -> List<LatLon>): Boolean = try {
        !plausible(here) || places().any { !(Geo.distance(it, here) > radiusM) }   // NaN-Abstand zählt als „im Umkreis“
    } catch (_: Throwable) { true }

    /** Echte Position? 0/0 meldet die Drohne ohne GPS. */
    fun plausible(p: LatLon): Boolean =
        p.lat.isFinite() && p.lon.isFinite() && p.lat in -90.0..90.0 && p.lon in -180.0..180.0 && !(p.lat == 0.0 && p.lon == 0.0)
}
