package com.felixos.drohne.sprache

import com.felixos.drohne.CockpitZustand
import com.felixos.drohne.CockpitZustand.AuftragPhase
import com.felixos.drohne.CockpitZustand.Hauptaktion

/**
 * Die Prüfschicht (Entwurf Abschnitt 5.1 und 7): fest programmiert, reine Funktionen. Sie allein entscheidet, was aus
 * einer [Deutung] wird, gleich ob die von den Regeln oder von einem Modell kommt:
 *  1. Nur der Katalog: alles andere ist [Befehl.Unklar] und tut nichts.
 *  2. Die [Klasse] begrenzt den Weg: ANSEHEN/BRIEF sofort, HALT sofort, VORSCHLAG nur als Knopf mit Druck, NIE nie.
 *  3. Verneinung und Zweifel entscheidet dieser Code, nicht die Deutung.
 *  4. Jeder Wert muss wörtlich im gesprochenen Text stehen.
 *  5. Zustand zuletzt: passt er nicht, wird der Grund gesagt und nichts getan. Der Flugkern prüft jede Aktion ohnehin selbst.
 * Feste Grenzen: Abheben und Plan-Start nie; Heim, Fortsetzen, Planbau, Hochladen, Folgen beginnen nur mit Druck;
 * Anhalten sofort; im Flug keine Mission, kein Brief, kein Planbau; Orte nie per Sprache.
 */
object Pruefer {
    const val NICHT_SICHER = "Not sure what you want. Nothing done."
    const val NICHTS_FLIEGT = "Nothing is flying."
    /** Folgen beginnt wie in der Missions-Fläche nur mit gehaltenem Knopf (Projekt-DNA Abschnitt 11). */
    const val FOLGEN_HALTE_MS = 1500L

    /** In der Luft, Motoren an, Plan oder Folgen läuft, oder die Verbindung riss im Flug ab: dann gibt es keinen Brief und keinen Planbau. */
    fun imFlug(z: CockpitZustand): Boolean = z.fliegt || z.motorenAn || z.plan.laeuft || z.folgenAktiv || z.funkabriss

    /** Dieselbe Regel wie cockpit.vollbildErlaubt (das Paket sprache kennt cockpit nicht); ein Test hält beide gleich. */
    fun vollbildFrei(z: CockpitZustand): Boolean = z.warnung == null && !z.funkabriss && z.keineDatenSeitS == null && z.phase != CockpitZustand.Phase.PAUSIERT

    /** Die Befehle, die in dieser Sicht und Phase überhaupt in Frage kommen; bekommt jede Stufe des Verstehens. */
    fun erlaubt(sicht: Sicht, z: CockpitZustand): Set<Class<out Befehl>> {
        val s = HashSet<Class<out Befehl>>()
        s += listOf(Befehl.AnsichtZeigen::class.java, Befehl.FlaecheOeffnen::class.java, Befehl.SprechSprache::class.java, Befehl.Frage::class.java,
            Befehl.Anhalten::class.java, Befehl.Abgelehnt::class.java, Befehl.AnClaude::class.java, Befehl.Unklar::class.java, Befehl.Vollbild::class.java)
        if (sicht.istFlaeche) s += Befehl.FlaecheSchliessen::class.java
        if (sicht == Sicht.KARTE || sicht == Sicht.DREI_D || sicht == Sicht.ORTSWAHL) s += Befehl.Einpassen::class.java
        if (z.fliegt || z.motorenAn) s += Befehl.Heim::class.java
        if (z.hauptaktion == Hauptaktion.FORTSETZEN) s += Befehl.Fortsetzen::class.java
        if (z.auftrag.art == 5 && !z.folgenAktiv) s += Befehl.FolgenBeginnen::class.java
        if (z.auftrag.nochmalMoeglich) s += Befehl.NochmalFragen::class.java
        if (!imFlug(z)) {
            s += listOf(Befehl.MissionWaehlen::class.java, Befehl.PlanHolen::class.java, Befehl.Hochladen::class.java, Befehl.VermessungVorbereiten::class.java)
            if (sicht == Sicht.MSN) s += listOf(Befehl.FeldSetzen::class.java, Befehl.BriefText::class.java, Befehl.OrtWaehlen::class.java, Befehl.BriefAendern::class.java,
                Befehl.BriefPlanen::class.java, Befehl.KandidatWaehlen::class.java)
            if (sicht == Sicht.ORTSWAHL) s += listOf(Befehl.OrtZurueck::class.java, Befehl.OrtFertig::class.java)
        }
        return s
    }

    /**
     * Zustand zuletzt: warum [b] jetzt, in dieser Sicht, nicht geht; null = es geht. Wird bei der Entscheidung gefragt und
     * noch einmal unmittelbar vor dem Ausführen (ein Vorschlag kann 20 Sekunden alt sein).
     */
    fun grund(b: Befehl, sicht: Sicht, z: CockpitZustand): String? {
        val a = z.auftrag
        val flug = imFlug(z)
        val brief = "Open the brief under Msn first."
        fun mission() = "mission ${Auskuenfte.nr(a.art)}"
        return when (b) {
            is Befehl.AnsichtZeigen -> if (b.sicht.istAnsicht) null else "There is no such view."
            is Befehl.FlaecheOeffnen -> if (b.sicht.istFlaeche) null else "There is no such panel."
            Befehl.FlaecheSchliessen -> if (sicht.istFlaeche) null else "No panel is open."
            is Befehl.Vollbild -> when {
                !b.an -> null
                !sicht.istAnsicht -> "Full screen shows the map, the live view or the 3D view. Close the panel first."
                z.phase == CockpitZustand.Phase.PAUSIERT -> "No full screen while the plan is paused: the buttons must stay in view."
                !vollbildFrei(z) -> "No full screen now: the warning line must stay in view."
                else -> null
            }
            Befehl.Einpassen -> when { sicht == Sicht.LIVE -> "The live view has nothing to fit."; sicht.istFlaeche -> "Close the panel first."; else -> null }
            is Befehl.SprechSprache -> if (b.kuerzel == "de" || b.kuerzel == "en") null else "Speaking works in German and English."
            is Befehl.Frage -> null
            is Befehl.MissionWaehlen -> when {
                b.art !in 0..7 -> "Unknown mission."
                flug -> "Missions are chosen on the ground. Nothing changed."
                b.art != 0 -> null
                a.art == 0 || a.phase == AuftragPhase.KEINE -> "No mission is open."
                a.phase == AuftragPhase.BRIEF || a.phase == AuftragPhase.ORT -> null
                else -> "${mission().replaceFirstChar { it.uppercase() }} has a plan or an answer. Drop it with the button under Msn."
            }
            is Befehl.FeldSetzen -> {
                val f = a.felder.firstOrNull { it.id == b.id }
                when {
                    flug -> "The brief is set on the ground. Nothing changed."
                    a.art == 0 -> "Choose a mission first."
                    sicht != Sicht.MSN -> brief
                    a.phase != AuftragPhase.BRIEF -> "The brief is closed. Say “change the brief” first."
                    f == null -> "${mission().replaceFirstChar { it.uppercase() }} has no such field. It has: ${a.felder.joinToString { it.name }.ifEmpty { "none" }}."
                    feldIndex(f, b.wert) == null -> "${f.name} can be ${werte(f)}."
                    else -> null
                }
            }
            is Befehl.BriefText -> when {
                flug -> "The brief is set on the ground. Nothing changed."
                a.art != 3 && a.art != 4 -> "Only missions 03 and 04 take a spoken text."
                sicht != Sicht.MSN -> brief
                a.phase != AuftragPhase.BRIEF -> "The brief is closed. Say “change the brief” first."
                b.text.isBlank() -> "No text heard."
                else -> null
            }
            Befehl.OrtWaehlen -> when {
                flug -> "Places are chosen on the ground."
                a.art == 0 -> "Choose a mission first."
                a.ortArt == 0 -> "This mission needs no place."
                sicht != Sicht.MSN -> brief
                a.phase != AuftragPhase.BRIEF -> "The brief is closed. Say “change the brief” first."
                else -> null
            }
            Befehl.OrtZurueck, Befehl.OrtFertig -> if (a.phase == AuftragPhase.ORT && sicht == Sicht.ORTSWAHL) null else "No place is being chosen right now."
            is Befehl.KandidatWaehlen -> when {
                flug -> "Candidates are prepared on the ground."
                a.kandidaten.none { it.nr == b.nr } -> if (a.kandidaten.isEmpty()) "There are no candidates." else "There is no candidate ${b.nr}. There are: ${a.kandidaten.joinToString { it.nr.toString() }}."
                a.phase == AuftragPhase.FLUG || a.arbeitet != null -> "Wait until Central Command has finished."
                sicht != Sicht.MSN -> "Open the candidates under Msn first."
                else -> null
            }
            Befehl.BriefAendern -> when {
                flug -> "The brief is changed on the ground."
                a.art == 0 -> "No mission is open."
                a.arbeitet != null -> "Central Command is still working. Wait for the answer."
                a.phase == AuftragPhase.BRIEF || a.phase == AuftragPhase.ORT -> "The brief is already open."
                a.phase != AuftragPhase.PLAN && a.phase != AuftragPhase.ERGEBNIS -> "The brief cannot be changed now."
                // Eine Antwort geht nicht still verloren (Felix 23.11.2026): das bleibt der Knopf mit Rückfrage in der Missions-Fläche.
                a.phase == AuftragPhase.ERGEBNIS && (a.ergebnis != null || a.kandidaten.isNotEmpty()) -> "Changing the brief removes the answer shown. Use the button “Change the brief” under Msn."
                sicht != Sicht.MSN -> brief
                else -> null
            }
            Befehl.VermessungVorbereiten -> when {
                flug -> "Prepare the survey flight on the ground."
                !z.plan.geladen -> "No plan is loaded."
                !z.plan.vermessungEmpfohlen -> "No survey flight is recommended for this plan."
                else -> null
            }
            Befehl.Anhalten -> null
            Befehl.Heim -> if (z.fliegt || z.motorenAn) null else NICHTS_FLIEGT
            Befehl.Fortsetzen -> if (z.hauptaktion == Hauptaktion.FORTSETZEN) null else "No plan is waiting to be resumed."
            Befehl.PlanHolen -> when {
                flug -> "Plans are fetched on the ground."
                !z.hauptaktionFrei || z.hauptaktion == Hauptaktion.ARBEITET || z.hauptaktion == Hauptaktion.PLAN_WIRD_GEHOLT || z.hauptaktion == Hauptaktion.LAEDT_HOCH -> "The app is busy. Try again in a moment."
                z.hauptaktion != Hauptaktion.PLAN_HOLEN -> "A plan is already loaded: “${z.plan.name}”."
                else -> null
            }
            Befehl.Hochladen -> when {
                flug -> "Plans are uploaded on the ground."
                !z.hauptaktionFrei || z.hauptaktion == Hauptaktion.ARBEITET || z.hauptaktion == Hauptaktion.PLAN_WIRD_GEHOLT || z.hauptaktion == Hauptaktion.LAEDT_HOCH -> "The app is busy. Try again in a moment."
                !z.plan.geladen -> "No plan is loaded."
                z.hauptaktion != Hauptaktion.HOCHLADEN -> "The plan is already on the drone."
                else -> null
            }
            Befehl.BriefPlanen -> when {
                flug -> "Plans from a brief are built on the ground. Land first."
                a.art == 0 -> "Choose a mission first."
                a.art == 1 || a.art == 2 || a.art == 5 -> "${mission().replaceFirstChar { it.uppercase() }} is not built from a brief."
                sicht != Sicht.MSN -> brief
                a.phase != AuftragPhase.BRIEF -> "The brief is closed. Say “change the brief” first."
                a.arbeitet != null -> "Central Command is still working. Wait for the answer."
                a.fehlt != null -> a.fehlt
                else -> null
            }
            Befehl.FolgenBeginnen -> when {
                a.art != 5 -> "Open mission 05 Follow on the ground first."
                z.folgenAktiv -> "Already following."
                a.folgenSperren.isNotEmpty() -> a.folgenSperren.joinToString(" · ")
                else -> null
            }
            Befehl.NochmalFragen -> if (a.nochmalMoeglich) null else "There is no failed request to repeat."
            is Befehl.Abgelehnt, is Befehl.AnClaude, Befehl.Unklar -> null
        }
    }

    /** Index des festen Werts [wert] in einem Brief-Feld (die Werte heißen dort „60 m“); null = nicht angeboten. */
    fun feldIndex(f: CockpitZustand.Auftrag.Feld, wert: Int): Int? = f.werte.indexOfFirst { it.substringBefore(' ').toIntOrNull() == wert }.takeIf { it >= 0 }

    private fun werte(f: CockpitZustand.Auftrag.Feld): String {
        val zahlen = f.werte.map { it.substringBefore(' ') }
        val einheit = f.werte.firstOrNull()?.substringAfter(' ', "").orEmpty()
        val liste = if (zahlen.size <= 1) zahlen.joinToString("") else zahlen.dropLast(1).joinToString(", ") + " or " + zahlen.last()
        return if (einheit.isEmpty()) liste else "$liste $einheit"
    }

    /** Verankerung: jeder Wert des Befehls steht wörtlich im gesprochenen Text ([s] in der Form von [Worte.norm]). */
    fun verankert(b: Befehl, text: String, s: String): Boolean = when (b) {
        is Befehl.FeldSetzen -> b.id in Katalog.FELDER && b.wert in Worte.zahlen(s)
        is Befehl.MissionWaehlen -> b.art == 0 || b.art in Worte.zahlen(s) || Katalog.TITELWORT[b.art]?.containsMatchIn(s) == true
        is Befehl.KandidatWaehlen -> b.nr in Worte.zahlen(s)
        is Befehl.BriefText -> Worte.norm(b.text).let { it.isNotEmpty() && s.contains(it) }
        is Befehl.SprechSprache -> Regex(if (b.kuerzel == "de") "\\b(deutsch|german)\\b" else "\\b(englisch|english)\\b").containsMatchIn(s)
        is Befehl.AnClaude -> b.frage.trim() == text.trim()
        else -> true
    }

    /** Die Entscheidung. [d] null oder unklar: nichts tun und zwei Beispiele aus der Sicht nennen. */
    fun pruefe(d: Deutung?, text: String, sicht: Sicht, z: CockpitZustand): Entscheidung {
        val b = d?.befehl ?: return nichtVerstanden(sicht, z)
        if (b is Befehl.Unklar) return nichtVerstanden(sicht, z)
        val s = Worte.norm(text)
        // Zwei Befehle auf einmal gibt es nur als „Anhalten, dazu Heim“ aus den Regeln; alles andere gilt als nicht verstanden.
        val dazuHeim = d.dazu == Befehl.Heim && b == Befehl.Anhalten && d.quelle == Quelle.REGEL
        if (d.dazu != null && !dazuHeim) return nichtVerstanden(sicht, z)
        if (b is Befehl.Abgelehnt) return Entscheidung.Antwort(absage(b.was))
        val flugwirksam = b.klasse == Klasse.HALT || b.klasse == Klasse.VORSCHLAG
        // Verneinung und Zweifel entscheidet dieser Code. Der Text des Briefs ist frei und darf ein „nicht“ enthalten.
        if ((flugwirksam || (b.klasse == Klasse.BRIEF && b !is Befehl.BriefText)) && Worte.verneint(s)) return Entscheidung.Nichts(NICHT_SICHER)
        if (flugwirksam && Worte.fragt(text, s)) return Entscheidung.Antwort(Auskuenfte.antwort(Auskunft.STATUS, z))
        if (!verankert(b, text, s)) return nichtVerstanden(sicht, z)
        if (b is Befehl.AnClaude) return Entscheidung.AnClaude(b.frage.trim())
        if (b is Befehl.Frage) return Entscheidung.Antwort(Auskuenfte.antwort(b.was, z))
        if (b == Befehl.Anhalten) return anhalten(dazuHeim, z)
        if (b == Befehl.Heim && !(z.fliegt || z.motorenAn)) return Entscheidung.Antwort(NICHTS_FLIEGT)
        if (b::class.java !in erlaubt(sicht, z)) return Entscheidung.Nichts(grund(b, sicht, z) ?: "That is not possible in this view right now. Nothing done.")
        grund(b, sicht, z)?.let { return Entscheidung.Nichts(it) }
        return when (b.klasse) {
            Klasse.ANSEHEN, Klasse.BRIEF -> Entscheidung.Ausfuehren(b, sagt(b, z))
            Klasse.VORSCHLAG -> vorschlag(b)
            // HALT ist oben entschieden, NIE und CLAUDE ebenso: was hier ankäme, wäre ein Fehler im Katalog. Im Zweifel nichts.
            Klasse.HALT, Klasse.NIE, Klasse.CLAUDE -> nichtVerstanden(sicht, z)
        }
    }

    /** Anhalten wirkt sofort: Folgen beenden bzw. Plan pausieren. Wortlaut und Fälle wie im früheren Cockpit.lokal(). */
    private fun anhalten(heim: Boolean, z: CockpitZustand): Entscheidung {
        val inDerLuft = z.fliegt || z.motorenAn
        val heimKnopf = if (heim && inDerLuft) vorschlag(Befehl.Heim) else null
        val dazu = if (heimKnopf != null) " Return home needs your press." else ""
        return when {
            z.folgenAktiv -> Entscheidung.Ausfuehren(Befehl.Anhalten, "Stop following sent. You have control.$dazu", heimKnopf)
            z.plan.laeuft || z.hauptaktion == Hauptaktion.PAUSE -> Entscheidung.Ausfuehren(Befehl.Anhalten, "Pausing. The drone hovers, you have control.$dazu", heimKnopf)
            heimKnopf != null -> Entscheidung.Vorschlag(Befehl.Heim, heimKnopf.knopf, "Return home flies back to the start point. Press to confirm.")
            z.phase == CockpitZustand.Phase.PAUSIERT -> Entscheidung.Antwort("Already paused. The drone hovers.")
            z.fliegt -> Entscheidung.Antwort("No plan is running that I could pause. You fly by hand; to end it press Stop · Return home.")
            else -> Entscheidung.Antwort(NICHTS_FLIEGT)
        }
    }

    private fun vorschlag(b: Befehl): Entscheidung.Vorschlag = when (b) {
        Befehl.Heim -> Entscheidung.Vorschlag(b, "Stop · Return home", "Return home stops the plan and flies back to the start point. Press to confirm.")
        Befehl.Fortsetzen -> Entscheidung.Vorschlag(b, "Resume plan", "Resume continues the plan from here. Press to confirm.")
        Befehl.PlanHolen -> Entscheidung.Vorschlag(b, "Get plan", "Get plan fetches the current plan, checks it and uploads it to the drone. It does not start. Press to confirm.")
        Befehl.Hochladen -> Entscheidung.Vorschlag(b, "Upload again", "Uploads the loaded plan to the drone again. It does not start. Press to confirm.")
        Befehl.BriefPlanen -> Entscheidung.Vorschlag(b, "Build the plan", "Builds the plan from this brief, checks it and uploads it. It does not start. Press to confirm.")
        Befehl.FolgenBeginnen -> Entscheidung.Vorschlag(b, "Start following", "Following starts from where the drone hovers. Hold the button to confirm.", FOLGEN_HALTE_MS)
        Befehl.NochmalFragen -> Entscheidung.Vorschlag(b, "Ask Central Command again", "Asks Central Command again with the same stills. Press to confirm.")
        else -> throw IllegalArgumentException("kein Vorschlag: $b")
    }

    /** Was die App zurücksagt, wenn sie etwas Harmloses sofort tut. */
    private fun sagt(b: Befehl, z: CockpitZustand): String = when (b) {
        is Befehl.AnsichtZeigen -> when (b.sicht) { Sicht.KARTE -> "Map."; Sicht.LIVE -> "Live view."; else -> "3D view." }
        is Befehl.FlaecheOeffnen -> when (b.sicht) { Sicht.SYS -> "System status."; Sicht.MSN -> "Missions."; Sicht.LOG -> "Flight log."; else -> "Settings." }
        Befehl.FlaecheSchliessen -> "Closed."
        is Befehl.Vollbild -> if (b.an) "Full screen." else "Full screen off."
        Befehl.Einpassen -> "Fitted."
        is Befehl.SprechSprache -> if (b.kuerzel == "de") "Listening in German now." else "Listening in English now."
        is Befehl.MissionWaehlen -> if (b.art == 0) "Brief dropped. No mission is open." else "Mission ${Auskuenfte.nr(b.art)}" + (TITEL[b.art]?.let { " $it" } ?: "") + (if (b.art >= 3) ". This is its brief." else ".")
        is Befehl.FeldSetzen -> (z.auftrag.felder.firstOrNull { it.id == b.id }?.name ?: "Value") + " set to ${b.wert} m."
        is Befehl.BriefText -> "Written into the brief: “${b.text}”"
        Befehl.OrtWaehlen -> "Tap the place on the map. Places are tapped, not spoken."
        Befehl.OrtZurueck -> "Last tap undone."
        Befehl.OrtFertig -> "Back in the brief."
        is Befehl.KandidatWaehlen -> "Candidate ${b.nr} is prepared as mission 03 Check a place."
        Befehl.BriefAendern -> "The brief is open again. The plan must be built again."
        Befehl.VermessungVorbereiten -> "Survey flight prepared as mission 06. Check the brief."
        else -> "Done."
    }

    /** So nennt man die Brief-Felder beim Sprechen. */
    private val FELDWORT = mapOf("h" to "Height", "r" to "Radius", "d" to "Distance")

    /** Titel der Missionen wie in plan/Auftrag.kt (das Paket sprache kennt plan/ nicht; ein Test hält beide gleich). */
    val TITEL: Map<Int, String> = mapOf(1 to "Fly a plan", 2 to "Subject on the way", 3 to "Check a place", 4 to "Find a spot", 5 to "Follow", 6 to "Survey a property", 7 to "Atmosphere")

    fun absage(v: Verbot): String = when (v) {
        // Wortlaut wie bisher, für Abheben und Starten derselbe.
        Verbot.ABHEBEN, Verbot.STARTEN -> "Take-off stays with your thumb: hold the start button. I never take off by voice."
        Verbot.LANDEN, Verbot.STEUERN -> "That stays on the sticks. I do not steer, land or trigger the camera by voice."
        Verbot.HINDERNISERKENNUNG, Verbot.WAECHTER -> "Obstacle sensing and the guards are not changed by voice. That stays under Set."
        Verbot.BESTAETIGEN -> "A suggestion needs your press on its button. Saying yes does not confirm it."
        Verbot.EINSTELLUNG -> "Settings, keys and the self test are not changed by voice. Open Set or Sys."
        Verbot.ORT -> "Places are tapped on the map, not spoken."
    }

    /** Zwei Beispiele dafür, was in dieser Sicht und Lage per Sprache geht. */
    fun beispiele(sicht: Sicht, z: CockpitZustand): Pair<String, String> = when {
        z.folgenAktiv -> "Stop following." to "How much battery is left?"
        z.plan.laeuft && z.phase != CockpitZustand.Phase.PAUSIERT -> "Stop here." to "How much battery is left?"
        z.hauptaktion == Hauptaktion.FORTSETZEN -> "Resume the plan." to "Come home."
        z.fliegt || z.motorenAn -> "Come home." to "How high is the drone?"
        sicht == Sicht.ORTSWAHL -> "Done." to "Undo."
        sicht == Sicht.MSN && z.auftrag.phase == AuftragPhase.BRIEF && z.auftrag.felder.isNotEmpty() ->
            z.auftrag.felder.first().let { f -> "${FELDWORT[f.id] ?: f.name} ${f.werte.first().substringBefore(' ')} meters." } to "What is missing in the brief?"
        sicht == Sicht.MSN -> "Mission three." to "Survey a property."
        sicht == Sicht.SYS -> "How much battery is left?" to "Do I have GPS?"
        sicht == Sicht.LOG -> "What went wrong in the last flight?" to "Close it."
        sicht == Sicht.SET -> "Speak German." to "Close it."
        else -> "Is the plan ready to fly?" to "Open the missions."
    }

    fun nichtVerstanden(sicht: Sicht, z: CockpitZustand): Entscheidung.Nichts =
        beispiele(sicht, z).let { (a, b) -> Entscheidung.Nichts("Not understood. Nothing done. Here for example: “$a” · “$b”") }
}
