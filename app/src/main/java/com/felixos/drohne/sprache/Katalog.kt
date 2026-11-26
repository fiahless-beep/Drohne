package com.felixos.drohne.sprache

import com.felixos.drohne.CockpitZustand

/**
 * Der feste Befehlskatalog der Sprachbedienung (Felix 26.11.2026: „Lokales Modell für Sprachbefehle ja“; Entwurf
 * drohne/architektur/lokales-sprachmodell.md, Abschnitte 5.2 und 6). Reines Kotlin ohne Android und ohne SDK; das Paket
 * kennt vom Rest der App nur [CockpitZustand], und den nur lesend. Was hier nicht als [Befehl] steht, kann per Sprache
 * nicht geschehen: Abheben und Plan-Start haben keinen Befehl, nur eine Absage ([Befehl.Abgelehnt]).
 */

/** Wie gefährlich ein Befehl ist; daran hängt, was die Prüfschicht höchstens erlaubt. */
enum class Klasse { ANSEHEN, BRIEF, HALT, VORSCHLAG, NIE, CLAUDE }

/** Was der Pilot gerade sieht (eigener Typ, damit sprache/ das Paket cockpit/ nicht kennen muss). */
enum class Sicht { KARTE, LIVE, DREI_D, SYS, MSN, LOG, SET, ORTSWAHL;
    /** Eine der drei Ansichten der Bildfläche (keine Fläche darüber). */
    val istAnsicht: Boolean get() = this == KARTE || this == LIVE || this == DREI_D
    /** Eine der vier Flächen der Leiste. */
    val istFlaeche: Boolean get() = this == SYS || this == MSN || this == LOG || this == SET
}

/** Der feste Katalog. Es gibt keinen Befehl außerhalb dieser Klasse. */
sealed class Befehl(val klasse: Klasse) {
    // ANSEHEN: sofort, ändert nichts an Flug und Plan
    data class AnsichtZeigen(val sicht: Sicht) : Befehl(Klasse.ANSEHEN)     // KARTE, LIVE, DREI_D
    data class FlaecheOeffnen(val sicht: Sicht) : Befehl(Klasse.ANSEHEN)    // SYS, MSN, LOG, SET
    data object FlaecheSchliessen : Befehl(Klasse.ANSEHEN)
    data class Vollbild(val an: Boolean) : Befehl(Klasse.ANSEHEN)
    data object Einpassen : Befehl(Klasse.ANSEHEN)
    data class SprechSprache(val kuerzel: String) : Befehl(Klasse.ANSEHEN)  // "de" | "en"
    data class Frage(val was: Auskunft) : Befehl(Klasse.ANSEHEN)            // Antwort aus CockpitZustand
    // BRIEF: sofort, ändert nur den Brief am Boden; die App sagt zurück, was sie gesetzt hat
    data class MissionWaehlen(val art: Int) : Befehl(Klasse.BRIEF)          // 1…7, 0 = keine
    data class FeldSetzen(val id: String, val wert: Int) : Befehl(Klasse.BRIEF) // id h|r|d, Wert in Metern
    data class BriefText(val text: String) : Befehl(Klasse.BRIEF)           // Frage (03) / Gesuchtes (04)
    data object OrtWaehlen : Befehl(Klasse.BRIEF)                           // öffnet die Karte; getippt wird mit dem Finger
    data object OrtZurueck : Befehl(Klasse.BRIEF)
    data object OrtFertig : Befehl(Klasse.BRIEF)
    data class KandidatWaehlen(val nr: Int) : Befehl(Klasse.BRIEF)
    data object BriefAendern : Befehl(Klasse.BRIEF)
    data object VermessungVorbereiten : Befehl(Klasse.BRIEF)
    // HALT: wirkt sofort
    data object Anhalten : Befehl(Klasse.HALT)
    // VORSCHLAG: nur als Knopf, Druck nötig
    data object Heim : Befehl(Klasse.VORSCHLAG)
    data object Fortsetzen : Befehl(Klasse.VORSCHLAG)
    data object PlanHolen : Befehl(Klasse.VORSCHLAG)
    data object Hochladen : Befehl(Klasse.VORSCHLAG)
    data object BriefPlanen : Befehl(Klasse.VORSCHLAG)
    data object FolgenBeginnen : Befehl(Klasse.VORSCHLAG)
    data object NochmalFragen : Befehl(Klasse.VORSCHLAG)                    // Claude-Anfrage wiederholen (kostet)
    // NIE: wird erkannt, um freundlich abzulehnen
    data class Abgelehnt(val was: Verbot) : Befehl(Klasse.NIE)
    // CLAUDE: Bild, Protokoll, Erklärung
    data class AnClaude(val frage: String) : Befehl(Klasse.CLAUDE)
    data object Unklar : Befehl(Klasse.ANSEHEN)
}

enum class Auskunft { AKKU, RESTZEIT, HOEHE, TEMPO, ENTFERNUNG_HEIM, WIND, GPS, VERBINDUNG, SENSOREN,
    STARTKLAR, PLAN, FORTSCHRITT, NAECHSTES, WARNUNG, BRIEF_FEHLT, FUNKABRISS, HOEHENLIMIT, TAGESLICHT, STATUS }

/** Was nie per Sprache geht. [ORT] ist gegenüber dem Entwurf ergänzt: Orte tippt man auf der Karte (Entwurf Abschnitt 6, Liste „nicht per Sprache“). */
enum class Verbot { ABHEBEN, STARTEN, LANDEN, HINDERNISERKENNUNG, WAECHTER, STEUERN, BESTAETIGEN, EINSTELLUNG, ORT }

enum class Quelle { REGEL, MODELL }

/**
 * Was eine Stufe verstanden hat: genau ein [befehl]. [dazu] gibt es nur aus den Regeln und nur als „Anhalten, dazu Heim“
 * („Stopp und komm heim“): das Anhalten wirkt sofort, der Heimflug bleibt ein Vorschlag mit Druck. [roh] = die
 * Roh-Ausgabe eines Modells, nur für das Protokoll.
 */
class Deutung(val befehl: Befehl, val quelle: Quelle, val roh: String? = null, val dazu: Befehl? = null) {
    override fun toString() = "Deutung($befehl" + (dazu?.let { " + $it" } ?: "") + ", $quelle)"
}

/** Eine Stufe des Verstehens. Darf nichts ausführen und nichts am Zustand ändern. */
interface Verstehen {
    val bereit: Boolean
    /** [erlaubt] = die Befehle, die in dieser Sicht und Phase überhaupt in Frage kommen. Antwort auf dem UI-Thread; null = keine Deutung. */
    fun verstehe(text: String, sprache: String, erlaubt: Set<Class<out Befehl>>, z: CockpitZustand, fertig: (Deutung?) -> Unit)
    fun abbrechen()
}

/** Was die Prüfschicht aus einer Deutung macht. Nur das hier erreicht die Oberfläche. */
sealed class Entscheidung {
    /** Sofort ausführen und [sagt] zeigen; [dazu] = höchstens ein Vorschlag daneben (Heim nach dem Anhalten). */
    class Ausfuehren(val befehl: Befehl, val sagt: String, val dazu: Vorschlag? = null) : Entscheidung()
    /** Knopf „Do it: [knopf]“, 20 s gültig; [halteMs] > 0 = der Knopf muss so lange gehalten werden (Folgen, wie in der Missions-Fläche). */
    class Vorschlag(val befehl: Befehl, val knopf: String, val sagt: String, val halteMs: Long = 0) : Entscheidung()
    /** Verstanden, aber es gibt nichts zu tun: Auskunft aus dem Zustand oder eine Absage. */
    class Antwort(val text: String) : Entscheidung()
    /** Nichts tun: nicht verstanden, Zweifel, oder der Zustand lässt es nicht zu ([sagt] nennt den Grund). */
    class Nichts(val sagt: String) : Entscheidung()
    class AnClaude(val frage: String) : Entscheidung()
}

/** Ein Funktionsaufruf, wie ihn ein Modell liefert: Name und Werte als Text. */
class Aufruf(val name: String, val werte: Map<String, String> = emptyMap())

object Katalog {
    /** Woran eine Mission im gesprochenen Text hängen darf, außer an ihrer Nummer (Text in der Form von [Worte.norm]). */
    val TITELWORT: Map<Int, Regex> = mapOf(
        1 to Regex("\\b(fly a plan|plan fliegen|einen plan fliegen)\\b"),
        2 to Regex("\\b(subject on the way|motiv|subject)\\b"),
        3 to Regex("\\b(check (a|the|this|that) (place|spot|location)|(ort|platz|stelle) (pruefen|nachsehen|ansehen|anschauen|checken)|nachsehen|nachschauen)\\b"),
        4 to Regex("\\b(find (me )?(a|the) (spot|place)|(platz|stelle|ort) (suchen|finden)|(such|suche|finde|find) (mir )?(einen|eine) (platz|stelle|ort))\\b"),
        5 to Regex("\\b(follow|follow me|folge mir|folg mir|folge|folgen|mir folgen)\\b"),
        6 to Regex("\\b(survey|vermess\\w*|grundstueck\\w*|property)\\b"),
        7 to Regex("\\b(atmosphere|atmosphaere|stimmung|stimmungsbilder)\\b"),
    )

    /**
     * Übersetzt die Ausgabe eines Modells in genau einen [Befehl] (Sicherheitsregel 1). Unbekannter Name, fehlender oder
     * falscher Wert, kein oder mehr als ein Aufruf: [Befehl.Unklar]. Für Abheben, Starten, Landen und Steuern gibt es
     * hier keinen Namen, der etwas anderes ergäbe als eine Absage.
     */
    fun ausAufrufen(aufrufe: List<Aufruf>): Befehl {
        val a = aufrufe.singleOrNull() ?: return Befehl.Unklar
        fun w(k: String): String? = a.werte[k]?.trim()?.ifEmpty { null }
        fun zahl(k: String): Int? = w(k)?.toIntOrNull()
        fun <E : Enum<E>> aus(werte: Array<E>, k: String): E? = w(k)?.let { n -> werte.firstOrNull { it.name.equals(n, ignoreCase = true) } }
        return when (a.name.trim().lowercase(java.util.Locale.ROOT)) {
            "ansicht_zeigen" -> aus(Sicht.values(), "sicht")?.takeIf { it.istAnsicht }?.let { Befehl.AnsichtZeigen(it) }
            "flaeche_oeffnen" -> aus(Sicht.values(), "sicht")?.takeIf { it.istFlaeche }?.let { Befehl.FlaecheOeffnen(it) }
            "flaeche_schliessen" -> Befehl.FlaecheSchliessen
            "vollbild" -> when (w("an")?.lowercase(java.util.Locale.ROOT)) { "true" -> Befehl.Vollbild(true); "false" -> Befehl.Vollbild(false); else -> null }
            "einpassen" -> Befehl.Einpassen
            "sprech_sprache" -> w("kuerzel")?.lowercase(java.util.Locale.ROOT)?.takeIf { it == "de" || it == "en" }?.let { Befehl.SprechSprache(it) }
            "frage" -> aus(Auskunft.values(), "was")?.let { Befehl.Frage(it) }
            "mission_waehlen" -> zahl("art")?.takeIf { it in 0..7 }?.let { Befehl.MissionWaehlen(it) }
            "feld_setzen" -> { val id = w("id")?.lowercase(java.util.Locale.ROOT); val wert = zahl("wert"); if (id != null && id in FELDER && wert != null) Befehl.FeldSetzen(id, wert) else null }
            "brief_text" -> w("text")?.let { Befehl.BriefText(it) }
            "ort_waehlen" -> Befehl.OrtWaehlen
            "ort_zurueck" -> Befehl.OrtZurueck
            "ort_fertig" -> Befehl.OrtFertig
            "kandidat_waehlen" -> zahl("nr")?.takeIf { it > 0 }?.let { Befehl.KandidatWaehlen(it) }
            "brief_aendern" -> Befehl.BriefAendern
            "vermessung_vorbereiten" -> Befehl.VermessungVorbereiten
            "anhalten" -> Befehl.Anhalten
            "heim" -> Befehl.Heim
            "fortsetzen" -> Befehl.Fortsetzen
            "plan_holen" -> Befehl.PlanHolen
            "hochladen" -> Befehl.Hochladen
            "brief_planen" -> Befehl.BriefPlanen
            "folgen_beginnen" -> Befehl.FolgenBeginnen
            "nochmal_fragen" -> Befehl.NochmalFragen
            "abgelehnt" -> aus(Verbot.values(), "was")?.let { Befehl.Abgelehnt(it) }
            "an_claude" -> w("frage")?.let { Befehl.AnClaude(it) }
            else -> null
        } ?: Befehl.Unklar
    }

    /** Die Kennungen der Brief-Felder (plan/Auftrag.kt): Höhe, Radius, Abstand. */
    val FELDER = setOf("h", "r", "d")
}
