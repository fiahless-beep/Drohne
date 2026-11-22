package com.felixos.drohne

/**
 * Vertrag Flugkern → Oberfläche, lesende Seite: alles, was das Cockpit anzeigt, als unveränderlicher Schnappschuss.
 * Bewusst ohne SDK-Typen und ohne Koordinaten: die Oberfläche (Paket cockpit) darf nur diese Datei, [Aktionen] und
 * [FlugSitzung] kennen. Texte sind englisch wie die übrige Bedienung der App.
 */
data class CockpitZustand(
    val verbindung: Verbindung = Verbindung.GETRENNT,
    val geraet: String? = null,
    val simulation: Boolean = false,
    /** Verbindung zur Drohne ist abgerissen, während sie in der Luft war oder die Motoren liefen. */
    val funkabriss: Boolean = false,
    /** Verbunden, aber seit so vielen Sekunden kein Wert mehr von der Drohne; null = Daten laufen. */
    val keineDatenSeitS: Long? = null,
    /** Alter des letzten Werts in Sekunden; null = noch nie einer. */
    val letzteDatenS: Double? = null,
    val fliegt: Boolean = false,
    val motorenAn: Boolean = false,
    val phase: Phase = Phase.GETRENNT,
    val phaseText: String = "Disconnected",
    /** Ein Satz: was gerade geschieht und wer die Kontrolle hat. */
    val statusText: String = "Not connected",
    val statusSeitS: Long = 0,
    /** Wichtigste Warnung; null = keine. */
    val warnung: Warnung? = null,
    val hoeheM: Double? = null,
    val tempoMs: Double? = null,
    val akkuProzent: Int? = null,
    val akkuStufe: Stufe = Stufe.UNBEKANNT,
    /** Grobe Restflugzeit nach dem Budget-Modell der App (voller Akku = Budget.MINUTES_PER_FULL_BATTERY). */
    val restMin: Int? = null,
    val kursGrad: Double? = null,
    val distanzHeimM: Int? = null,
    val peilungHeimGrad: Int? = null,
    val satelliten: Int? = null,
    val gpsOk: Boolean = false,
    val heimGesetzt: Boolean = false,
    /** Hinderniserkennung als Anzeigetext (on, OFF, blind …); null = unbekannt oder nicht verbunden. */
    val sensoren: String? = null,
    val sensorenOk: Boolean = false,
    val wind: Wind = Wind(),
    val hoehenlimitM: Int? = null,
    val tageslicht: Boolean? = null,
    val plan: Plan = Plan(),
    /** Was der Hauptknopf jetzt anbietet. */
    val hauptaktion: Hauptaktion = Hauptaktion.PLAN_HOLEN,
    val hauptaktionFrei: Boolean = true,
    /** Startprüfung: nur die Probleme; leer und [Plan.geladen] = startklar. */
    val startProbleme: List<String> = emptyList(),
    /** Im Flug ist die Startprüfung erledigt und wird nicht gezeigt. */
    val startpruefungZeigen: Boolean = true,
    val folgenAktiv: Boolean = false,
    /** Abheben per Knopf wird angeboten: am Boden, verbunden, kein Plan läuft oder startet gerade. */
    val abhebenMoeglich: Boolean = false,
    /** So lange muss der Abheben-Knopf gehalten werden. */
    val abhebenHalteMs: Long = 1500,
    /** Startprüfung als Liste für die Anzeige: nur Probleme, oder „Ready for takeoff“. */
    val pruefliste: List<Pruefzeile> = emptyList(),
    val waechter: Waechter = Waechter(),
    val bahn: Bahn? = null,
) {
    /** Werte der Drohne sind frisch genug zum Anzeigen. */
    val datenDa: Boolean get() = verbindung == Verbindung.VERBUNDEN && keineDatenSeitS == null

    enum class Verbindung { SDK_FEHLER, SDK_STARTET, VERBUNDEN, NUR_FERNSTEUERUNG, GETRENNT }
    enum class Phase { GETRENNT, AM_BODEN, MOTOREN_AN, STEIGT, PUNKT, PAUSIERT, FLIEGT, HEIMFLUG, LANDET, FOLGT }
    enum class Stufe { UNBEKANNT, OK, WARNUNG, NIEDRIG }

    /** [titel] kurz in Versalien (LINK LOST, BATTERY 23 %), [text] ein Satz dazu. */
    data class Warnung(val titel: String, val text: String)

    data class Wind(
        /** Warnstufe der Drohne: 0 keine, 1 mäßig, 2 stark; null = nicht gemeldet. */
        val warnung: Int? = null,
        /** Von der Drohne im Flug geschätzte Windstärke; null = kein Wert. */
        val ms: Double? = null,
        /** Richtung als DJI-Name (NORTH, SOUTH_WEST …); ob „aus“ oder „nach“, ist am Gerät noch nicht gegengeprüft. */
        val richtung: String? = null,
        /** Vorhersagezeile zum zuletzt geladenen Plan; null = nicht geholt. */
        val vorhersage: String? = null,
    )

    data class Plan(
        val name: String = "No plan",
        val zeile: String = "Get a plan",
        /** Langer Text (Strecke, Höhe, Akku, Funkabriss) für die Detailansicht. */
        val text: String = "",
        val geladen: Boolean = false,
        val aufDrohne: Boolean = false,
        val laeuft: Boolean = false,
        /** Aktueller Wegpunkt, 0-basiert, -1 = keiner. */
        val punktIndex: Int = -1,
        val punkte: Int = 0,
        val fortschrittProzent: Int = 0,
        val fortschrittZeigen: Boolean = false,
        /** Was die Drohne als Nächstes tut. */
        val naechstes: String = "",
        /** Verhalten bei Funkabriss laut Plan; null = kein Plan. */
        val beiFunkabriss: String? = null,
        val brauchtProzent: Int? = null,
        val reserveProzent: Int = 0,
        val transitHoeheM: Int = 0,
        val rueckkehrHoeheM: Int? = null,
    )

    enum class Hauptaktion(val text: String) {
        ARBEITET("Working …"), PLAN_WIRD_GEHOLT("Getting plan …"), LAEDT_HOCH("Uploading …"),
        PAUSE("Pause"), FORTSETZEN("Resume plan"), PLAN_HOLEN("Get plan"), HOCHLADEN("Upload again"), START("Start")
    }

    data class Waechter(
        val flugwaechter: Boolean = true,
        val duerfenPausieren: Boolean = false,
        val bildwaechter: Boolean = true,
        val bildwaechterDarfPausieren: Boolean = false,
        val daemmerung: Boolean = false,
    )

    /** Flugbahn für die kleine Karte: Meter Ost/Nord relativ zum ersten Punkt, Norden oben. Keine Koordinaten. */
    class Bahn(val punkte: List<Punkt>, val erledigtBis: Int, val heim: Punkt?, val drohne: Punkt?) {
        class Punkt(val ostM: Float, val nordM: Float)
    }
}
