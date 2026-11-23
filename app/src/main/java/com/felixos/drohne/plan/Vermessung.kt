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
    }

    class Urteil(val lage: Lage, val satz: String)

    /**
     * [punkte] = Orte des Plans samt Start; [deckt] = hat das Höhenmodell dort einen Wert; [tiefsterPunktM] = tiefste Planhöhe
     * über dem Start.
     */
    fun urteil(punkte: List<LatLon>, tiefsterPunktM: Double, deckt: (LatLon) -> Boolean, sichereHoeheM: Double = Missionsbau.SICHERE_HOEHE_M): Urteil = when {
        punkte.isNotEmpty() && punkte.all(deckt) -> Urteil(Lage.GEDECKT, "Height model covers this area. No survey flight needed.")
        tiefsterPunktM >= sichereHoeheM -> Urteil(Lage.NICHT_NOETIG, "No height model here: the plan stays at ${tiefsterPunktM.toInt()} m or higher above the start point, but ground and trees on the route are not checked. Judge the line yourself. A survey flight pays off if you want to fly lower or closer here later.")
        else -> Urteil(Lage.EMPFOHLEN, "No height model here, and this plan goes down to ${tiefsterPunktM.toInt()} m: without measured ground and trees the app allows nothing below ${sichereHoeheM.toInt()} m. Fly a survey flight first; lower flights open once Claude has built the height model from its photos and it is in the app.")
    }
}
