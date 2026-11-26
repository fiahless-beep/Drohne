package com.felixos.drohne.sprache

import com.felixos.drohne.CockpitZustand
import com.felixos.drohne.CockpitZustand.Auftrag
import com.felixos.drohne.CockpitZustand.AuftragPhase
import com.felixos.drohne.CockpitZustand.Hauptaktion
import com.felixos.drohne.CockpitZustand.Phase
import com.felixos.drohne.CockpitZustand.Verbindung

/** Erfundene Schnappschüsse des Zustands für die Tests der Sprachbedienung (Entwurf Abschnitt 7.1 und 7.2). Ohne Gerät, ohne Koordinaten. */
object Lagen {
    private val plan = CockpitZustand.Plan(name = "Mühle Scan V5", zeile = "124 photos · 12 min", geladen = true, aufDrohne = true, punkte = 40,
        beiFunkabriss = "finish the plan, then return home", brauchtProzent = 73, reserveProzent = 15, transitHoeheM = 30)

    /** B: am Boden, verbunden, Plan auf der Drohne, nichts läuft. */
    val boden = CockpitZustand(verbindung = Verbindung.VERBUNDEN, geraet = "DJI Mini 4 Pro", phase = Phase.AM_BODEN, phaseText = "On the ground", statusText = "On the ground",
        hoeheM = 0.0, tempoMs = 0.0, akkuProzent = 86, akkuStufe = CockpitZustand.Stufe.OK, restMin = 26, distanzHeimM = 0, peilungHeimGrad = 0, satelliten = 19, gpsOk = true,
        heimGesetzt = true, sensoren = "on", sensorenOk = true, wind = CockpitZustand.Wind(warnung = 0), hoehenlimitM = 120, tageslicht = true, plan = plan,
        hauptaktion = Hauptaktion.START, abhebenMoeglich = true)

    /** Am Boden, verbunden, kein Plan geladen. */
    val bodenOhnePlan = boden.copy(plan = CockpitZustand.Plan(), hauptaktion = Hauptaktion.PLAN_HOLEN, startProbleme = listOf("No plan loaded"))

    /** P: der Plan läuft. */
    val planLaeuft = boden.copy(fliegt = true, motorenAn = true, phase = Phase.PUNKT, phaseText = "Waypoint 12/40", statusText = "Plan: flying to waypoint 12",
        hoeheM = 45.2, tempoMs = 5.0, akkuProzent = 71, restMin = 21, distanzHeimM = 140, peilungHeimGrad = 220, wind = CockpitZustand.Wind(warnung = 0, ms = 4.0, richtung = "SOUTH_WEST"),
        plan = plan.copy(laeuft = true, punktIndex = 11, fortschrittProzent = 30, fortschrittZeigen = true, naechstes = "Photo at waypoint 12, then on to 13"),
        hauptaktion = Hauptaktion.PAUSE, abhebenMoeglich = false, startpruefungZeigen = false)

    /** A: der Plan ist pausiert, die Drohne schwebt. */
    val pausiert = planLaeuft.copy(phase = Phase.PAUSIERT, phaseText = "Paused", statusText = "Paused. Hovering, you have control. Resume plan or fly by hand.",
        tempoMs = 0.0, plan = planLaeuft.plan.copy(laeuft = false, fortschrittZeigen = false), hauptaktion = Hauptaktion.FORTSETZEN)

    /** H: Handflug, kein Plan geladen. */
    val handflug = boden.copy(fliegt = true, motorenAn = true, phase = Phase.FLIEGT, phaseText = "Flying", statusText = "Hovering / manual flight. You have control.",
        hoeheM = 22.0, tempoMs = 1.5, akkuProzent = 64, restMin = 19, distanzHeimM = 60, peilungHeimGrad = 90, plan = CockpitZustand.Plan(), hauptaktion = Hauptaktion.PLAN_HOLEN, abhebenMoeglich = false)

    private fun feld(id: String, name: String, werte: List<Int>, gewaehlt: Int) = Auftrag.Feld(id, name, werte.map { "$it m" }, gewaehlt)
    private val brief05 = Auftrag(art = 5, titel = "Follow", phase = AuftragPhase.BRIEF, felder = listOf(feld("h", "Height", listOf(30, 40, 50), 0), feld("d", "Distance to you", listOf(15, 25, 40), 1)))

    /** F: die Drohne folgt dem Handy. */
    val folgen = handflug.copy(phase = Phase.FOLGT, phaseText = "Following", statusText = "Following the phone", folgenAktiv = true, auftrag = brief05)

    /** Getrennt: keine Drohne, keine Werte. */
    val getrennt = CockpitZustand()

    /** Funkabriss im Flug: der letzte Stand war „Plan läuft“, Werte gelten nicht mehr. */
    val funkabriss = planLaeuft.copy(verbindung = Verbindung.GETRENNT, funkabriss = true, warnung = CockpitZustand.Warnung("LINK LOST", "No connection to the drone. Values and live view are off."))

    /** M3: Mission 03 im Brief, Ort und Frage fehlen noch. */
    val m3 = boden.copy(auftrag = Auftrag(art = 3, titel = "Check a place", phase = AuftragPhase.BRIEF, felder = listOf(feld("h", "Height", listOf(40, 60, 80), 0)), ortArt = 1,
        textName = "Your question", fehlt = "Choose the place on the map."))

    /** M4: Mission 04 im Brief. */
    val m4 = boden.copy(auftrag = Auftrag(art = 4, titel = "Find a spot", phase = AuftragPhase.BRIEF, felder = listOf(feld("r", "Search radius", listOf(50, 80, 120), 1), feld("h", "Height", listOf(45, 60), 0)),
        ortArt = 1, textName = "What you are looking for", fehlt = "Choose the middle of the search area on the map."))

    /** M5: Mission 05 im Brief, die Drohne schwebt, nichts sperrt das Folgen. */
    val m5 = handflug.copy(auftrag = brief05)

    /** M6: Mission 06 im Brief, vollständig (Ecken getippt). */
    val m6 = boden.copy(auftrag = Auftrag(art = 6, titel = "Survey a property", phase = AuftragPhase.BRIEF, felder = listOf(feld("h", "Height", listOf(50, 60, 80), 1)), ortArt = 2,
        ortText = "4 corners · 0.8 ha", ecken = 4))

    /** M7: Mission 07 im Brief. */
    val m7 = boden.copy(auftrag = Auftrag(art = 7, titel = "Atmosphere", phase = AuftragPhase.BRIEF, ortArt = 1, fehlt = "Choose the middle of the crowd on the map.",
        felder = listOf(feld("r", "Keep-out radius around the crowd", listOf(30, 50, 80), 1), feld("d", "Distance outside the zone", listOf(30, 50), 0), feld("h", "Height", listOf(35, 50), 0))))

    /** Ortswahl zu Mission 06 läuft. */
    val ortswahl = m6.copy(auftrag = m6.auftrag.copy(phase = AuftragPhase.ORT))

    /** Mission 04 hat geantwortet: zwei Kandidaten. */
    val ergebnis04 = boden.copy(auftrag = m4.auftrag.copy(phase = AuftragPhase.ERGEBNIS, fehlt = null, ergebnis = "Two candidates.",
        kandidaten = listOf(Auftrag.Kandidat(1, "Gravel area by the road", "80 m north of the start point"), Auftrag.Kandidat(2, "Meadow", "120 m east of the start point"))))

    /** Alle Lagen mit Namen, für Tests, die über jede Lage laufen. */
    val alle: Map<String, CockpitZustand> = linkedMapOf("boden" to boden, "bodenOhnePlan" to bodenOhnePlan, "planLaeuft" to planLaeuft, "pausiert" to pausiert, "handflug" to handflug,
        "folgen" to folgen, "getrennt" to getrennt, "funkabriss" to funkabriss, "m3" to m3, "m4" to m4, "m5" to m5, "m6" to m6, "m7" to m7, "ortswahl" to ortswahl, "ergebnis04" to ergebnis04)

    /** Je ein Befehl jeder Art des Katalogs, mit Werten, die es gibt, und solchen, die es nicht gibt. */
    val befehle: List<Befehl> = Sicht.values().flatMap { listOf(Befehl.AnsichtZeigen(it), Befehl.FlaecheOeffnen(it)) } +
        listOf(Befehl.FlaecheSchliessen, Befehl.Vollbild(true), Befehl.Vollbild(false), Befehl.Einpassen, Befehl.SprechSprache("de"), Befehl.SprechSprache("en"), Befehl.SprechSprache("fr")) +
        Auskunft.values().map { Befehl.Frage(it) } + (-1..8).map { Befehl.MissionWaehlen(it) } +
        listOf("h", "r", "d", "x").flatMap { id -> listOf(30, 50, 60, 500).map { Befehl.FeldSetzen(id, it) } } +
        listOf(Befehl.BriefText("Ist der Parkplatz frei?"), Befehl.BriefText(""), Befehl.OrtWaehlen, Befehl.OrtZurueck, Befehl.OrtFertig, Befehl.KandidatWaehlen(1), Befehl.KandidatWaehlen(9),
            Befehl.BriefAendern, Befehl.VermessungVorbereiten, Befehl.Anhalten, Befehl.Heim, Befehl.Fortsetzen, Befehl.PlanHolen, Befehl.Hochladen, Befehl.BriefPlanen, Befehl.FolgenBeginnen,
            Befehl.NochmalFragen, Befehl.AnClaude("What do you see?"), Befehl.Unklar) + Verbot.values().map { Befehl.Abgelehnt(it) }

    /** Der ganze Weg eines Satzes mit Stufe 0: Regeln, dann Prüfschicht. */
    fun entscheide(satz: String, z: CockpitZustand, sicht: Sicht, modell: Verstehen? = null): Entscheidung {
        var e: Entscheidung? = null
        SprachLeitung(RegelVerstehen(), modell).gehoert(satz, "de", sicht, z) { e = it }
        return e ?: throw AssertionError("keine Entscheidung für „$satz“")
    }

    fun text(e: Entscheidung): String = when (e) {
        is Entscheidung.Ausfuehren -> "Ausfuehren(${e.befehl}" + (e.dazu?.let { " + Vorschlag ${it.befehl}" } ?: "") + "): ${e.sagt}"
        is Entscheidung.Vorschlag -> "Vorschlag(${e.befehl}, „${e.knopf}“): ${e.sagt}"
        is Entscheidung.Antwort -> "Antwort: ${e.text}"
        is Entscheidung.Nichts -> "Nichts: ${e.sagt}"
        is Entscheidung.AnClaude -> "AnClaude: ${e.frage}"
    }
}
