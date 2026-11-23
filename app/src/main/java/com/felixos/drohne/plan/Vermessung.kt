package com.felixos.drohne.plan

/**
 * Der Vermessungsflug gehört zum Plan, nicht in eine eigene Mission (Felix 23.11.2026: „dem User vermitteln, ob sich
 * der Vermessungsflug lohnt … wenn eine Area schon vermessen wurde, dass kein Vermessungsflug mehr notwendig ist“).
 * Reine Rechnung: Wie steht ein Gebiet zum Höhenmodell der App, und was heißt das für diesen Plan?
 * Ehrlich bleiben: Das Höhenmodell entsteht aus den Fotos außerhalb der App; ein geflogener Vermessungsflug allein
 * erlaubt noch keine tieferen Flüge.
 */
object Vermessung {
    enum class Lage {
        /** Das Höhenmodell deckt das Gebiet: kein Vermessungsflug nötig. */
        GEDECKT,
        /** Kein Höhenmodell, aber der Plan bleibt ohnehin auf sicherer Höhe: Vermessung wäre ein Gewinn für später. */
        NICHT_NOETIG,
        /** Kein Höhenmodell und der Plan will tiefer: erst vermessen. */
        EMPFOHLEN,
        /** Hier wurde schon vermessen, das Modell ist aber noch nicht in der App. */
        GEFLOGEN,
    }

    class Urteil(val lage: Lage, val satz: String)

    /**
     * [punkte] = Orte des Plans samt Start; [deckt] = hat das Höhenmodell dort einen Wert; [tiefsterPunktM] = tiefste Planhöhe
     * über dem Start; [geflogenAm] = Datum eines Vermessungsflugs über diesem Gebiet oder null.
     */
    fun urteil(punkte: List<LatLon>, tiefsterPunktM: Double, deckt: (LatLon) -> Boolean, geflogenAm: String?, sichereHoeheM: Double = Missionsbau.SICHERE_HOEHE_M): Urteil = when {
        punkte.isNotEmpty() && punkte.all(deckt) -> Urteil(Lage.GEDECKT, "Height model covers this area. No survey flight needed.")
        geflogenAm != null -> Urteil(Lage.GEFLOGEN, "Survey flown here on $geflogenAm. The height model is built from those photos outside the app; until it is in the app, plans stay at ${sichereHoeheM.toInt()} m or higher.")
        tiefsterPunktM >= sichereHoeheM -> Urteil(Lage.NICHT_NOETIG, "No height model here. This plan stays at ${tiefsterPunktM.toInt()} m or higher and does not need one. A survey flight pays off if you want to fly lower or closer here later.")
        else -> Urteil(Lage.EMPFOHLEN, "No height model here, and this plan goes down to ${tiefsterPunktM.toInt()} m: without measured ground and trees the app allows nothing below ${sichereHoeheM.toInt()} m. Fly a survey first.")
    }

    /** Ein vermessenes Gebiet: Mittelpunkt, Radius, Datum (für „schon vermessen“). */
    data class Gebiet(val mitte: LatLon, val radiusM: Double, val datum: String)

    /** Datum des Vermessungsflugs, der alle [punkte] einschließt; null = keiner. */
    fun geflogen(punkte: List<LatLon>, gebiete: List<Gebiet>): String? =
        gebiete.lastOrNull { g -> punkte.isNotEmpty() && punkte.all { Geo.distance(it, g.mitte) <= g.radiusM + 20.0 } }?.datum
}
