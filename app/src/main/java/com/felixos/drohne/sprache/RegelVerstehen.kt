package com.felixos.drohne.sprache

import com.felixos.drohne.CockpitZustand

/**
 * Stufe 0: feste Muster in Deutsch und Englisch, sofort und ohne Modell (Entwurf Abschnitt 1 und 5). Löst die Muster aus
 * dem früheren Cockpit.lokal() ab; dessen Sätze stehen als Tests in BestandTest. Reihenfolge: Anhalten zuerst, dann Heim,
 * dann die Absagen, dann alles Harmlose. Hier wird nur gedeutet; ob etwas geschieht, entscheidet allein der [Pruefer].
 * Im Zweifel [Befehl.Unklar]: lieber nichts tun als das Falsche.
 */
class RegelVerstehen : Verstehen {
    override val bereit: Boolean get() = true
    override fun verstehe(text: String, sprache: String, erlaubt: Set<Class<out Befehl>>, z: CockpitZustand, fertig: (Deutung?) -> Unit) = fertig(deute(text, erlaubt, z))
    override fun abbrechen() {}

    /** Dieselbe Deutung ohne Umweg über den Rückruf (die Regeln rechnen sofort). Die Sprech-Sprache spielt keine Rolle: beide Sprachen gelten immer. */
    fun deute(text: String, erlaubt: Set<Class<out Befehl>>, z: CockpitZustand): Deutung {
        val s = Worte.norm(text)
        if (s.isEmpty()) return UNKLAR
        val n = s.split(' ').size
        val verneint = Worte.verneint(s)
        val fragt = Worte.fragt(text, s)
        fun r(b: Befehl, dazu: Befehl? = null) = Deutung(b, Quelle.REGEL, null, dazu)
        fun hat(m: Regex) = m.containsMatchIn(s)
        /** Flugwirksames (Vorschlag mit Druck): bei Verneinung nichts, bei einer Frage die Auskunft [frage], über sechs Wörter nichts. */
        fun vorschlag(b: Befehl, frage: Auskunft = Auskunft.STATUS): Deutung = when { verneint -> UNKLAR; fragt -> r(Befehl.Frage(frage)); n > MAX_WOERTER -> UNKLAR; else -> r(b) }

        // 1. Anhalten zuerst, dann Heim (Verhalten des früheren Cockpit.lokal(): Verneinung, Frage und lange Sätze lösen nichts aus).
        val halt = hat(HALT_ANFANG) || hat(HALT_ENDE) || hat(FOLGEN_ENDE)
        val heim = hat(HEIM)
        if (halt || heim) return when {
            verneint -> UNKLAR
            fragt -> r(Befehl.Frage(Auskunft.STATUS))
            n > MAX_WOERTER -> UNKLAR
            halt -> r(Befehl.Anhalten, if (heim) Befehl.Heim else null)
            else -> r(Befehl.Heim)
        }
        // Verneinung neben einem Wort für Anhalten oder Heim, das die strengen Muster oben nicht trifft („Flieg noch nicht nach Hause“): nichts.
        if (verneint && hat(FLUG_WORT)) return UNKLAR

        // 2. Absagen. Abheben und Starten wie bisher, auch als ganzer Satz erkannt; eine Frage danach ist eine Frage nach der Startprüfung.
        START_GENAU.matchEntire(s)?.let { return r(Befehl.Abgelehnt(if (hat(ABHEBEN)) Verbot.ABHEBEN else Verbot.STARTEN)) }
        if (hat(FOLGEN_BEGINN) || (hat(FOLGE_MIR) && z.auftrag.art == 5 && !fragt)) return vorschlag(Befehl.FolgenBeginnen)
        if (hat(SELBSTTEST) && !fragt) return r(Befehl.Abgelehnt(Verbot.EINSTELLUNG))
        if (BESTAETIGEN.matches(s)) return r(Befehl.Abgelehnt(Verbot.BESTAETIGEN))
        if (hat(ABHEBEN)) return if (fragt) r(Befehl.Frage(Auskunft.STARTKLAR)) else r(Befehl.Abgelehnt(Verbot.ABHEBEN))
        if (hat(STARTEN)) return if (fragt) r(Befehl.Frage(Auskunft.STARTKLAR)) else r(Befehl.Abgelehnt(Verbot.STARTEN))
        if (!fragt) {
            if (hat(SCHALTEN) && hat(HINDERNIS)) return r(Befehl.Abgelehnt(Verbot.HINDERNISERKENNUNG))
            if (hat(SCHALTEN) && hat(WAECHTER)) return r(Befehl.Abgelehnt(Verbot.WAECHTER))
            if (hat(GEHEIM) || (hat(GRENZWERT) && hat(AENDERN))) return r(Befehl.Abgelehnt(Verbot.EINSTELLUNG))
            if (hat(LANDEN)) return r(Befehl.Abgelehnt(Verbot.LANDEN))
            if (hat(HIMMELSRICHTUNG)) return r(Befehl.Abgelehnt(if (Worte.zahlen(s).isNotEmpty() || hat(ORT_WORT)) Verbot.ORT else Verbot.STEUERN))
            if (hat(STEUERN) || hat(KAMERA)) return r(Befehl.Abgelehnt(Verbot.STEUERN))
        }

        // 3. Echte Fragen an Central Command, die wie ein Befehl klingen könnten: Bild, Protokoll, Erklärung.
        if (hat(CC_BILD) || hat(CC_PROTOKOLL) || hat(CC_ERKLAERUNG)) return r(Befehl.AnClaude(text.trim()))

        // 4. Ortswahl: kurze Wörter, die nur dort etwas heißen.
        if (Befehl.OrtFertig::class.java in erlaubt) {
            if (ORT_FERTIG.matches(s)) return if (verneint) UNKLAR else r(Befehl.OrtFertig)
            if (ORT_ZURUECK.matches(s)) return if (verneint) UNKLAR else r(Befehl.OrtZurueck)
        }

        // Text des Briefs: der Wortlaut nach „Die Frage ist“ bzw. „Ich suche“, so wie er gesprochen wurde.
        if (Befehl.BriefText::class.java in erlaubt) {
            val m = when (z.auftrag.art) { 3 -> BRIEF_FRAGE.find(text.trim()); 4 -> BRIEF_SUCHE.find(text.trim()); else -> null }
            val inhalt = m?.groupValues?.last()?.trim()
            if (!inhalt.isNullOrEmpty()) return r(Befehl.BriefText(inhalt))
        }

        // 5. Flugwirksames mit Druck.
        if (hat(VERMESSUNG_VORBEREITEN)) return if (verneint) UNKLAR else r(Befehl.VermessungVorbereiten)
        if (hat(BRIEF_PLANEN)) return vorschlag(Befehl.BriefPlanen, Auskunft.BRIEF_FEHLT)
        if (hat(HOCHLADEN)) return vorschlag(Befehl.Hochladen, Auskunft.PLAN)
        if (hat(PLAN_HOLEN)) return vorschlag(Befehl.PlanHolen, Auskunft.PLAN)
        if (hat(FORTSETZEN) || FORTSETZEN_GENAU.matches(s)) return vorschlag(Befehl.Fortsetzen)
        if (hat(NOCHMAL)) return vorschlag(Befehl.NochmalFragen)

        // 6. Brief-Feld und Mission mit Zahl: vor den Ansichten, damit „Set the radius to 80“ nicht die Fläche Set öffnet.
        val zahlen = Worte.zahlen(s)
        if (!fragt && zahlen.isNotEmpty()) {
            val felder = listOfNotNull(if (hat(F_HOEHE)) "h" else null, if (hat(F_RADIUS)) "r" else null, if (hat(F_ABSTAND)) "d" else null)
            if (felder.isNotEmpty()) return if (verneint || felder.size != 1 || zahlen.size != 1) UNKLAR else r(Befehl.FeldSetzen(felder[0], zahlen[0]))
            if (hat(MISSION_NR)) return if (verneint || zahlen.size != 1 || zahlen[0] !in 1..7) UNKLAR else r(Befehl.MissionWaehlen(zahlen[0]))
        }

        // 7. Ansehen: ändert nichts an Flug und Plan.
        if (hat(VOLLBILD)) return r(Befehl.Vollbild(!hat(VOLLBILD_AUS)))
        val deutsch = hat(DEUTSCH); val englisch = hat(ENGLISCH)
        if ((deutsch || englisch) && n <= MAX_WOERTER) return if (deutsch && englisch) UNKLAR else r(Befehl.SprechSprache(if (deutsch) "de" else "en"))
        if (SCHLIESSEN.matches(s)) return r(Befehl.FlaecheSchliessen)
        if (!fragt && (n <= 3 || hat(ZEIGEN))) {
            // Flächen vor Ansichten: „Open the log“ meint die Fläche.
            val ziele = listOfNotNull(
                if (hat(N_MSN)) Befehl.FlaecheOeffnen(Sicht.MSN) else null, if (hat(N_LOG)) Befehl.FlaecheOeffnen(Sicht.LOG) else null,
                if (hat(N_SET)) Befehl.FlaecheOeffnen(Sicht.SET) else null, if (hat(N_SYS)) Befehl.FlaecheOeffnen(Sicht.SYS) else null,
                if (hat(N_KARTE)) Befehl.AnsichtZeigen(Sicht.KARTE) else null, if (hat(N_LIVE)) Befehl.AnsichtZeigen(Sicht.LIVE) else null,
                if (hat(N_3D)) Befehl.AnsichtZeigen(Sicht.DREI_D) else null)
            if (ziele.size == 1) return r(ziele[0])
            if (ziele.size > 1) return UNKLAR
        }
        if (EINPASSEN.matches(s)) return r(Befehl.Einpassen)

        // 8. Brief: Ort, Kandidat, Mission fallen lassen. Verneint: nichts.
        if (!fragt) {
            if (hat(BRIEF_AENDERN)) return if (verneint) UNKLAR else r(Befehl.BriefAendern)
            if (hat(ORT_WAEHLEN)) return if (verneint) UNKLAR else r(Befehl.OrtWaehlen)
            if (hat(MISSION_WEG)) return if (verneint) UNKLAR else r(Befehl.MissionWaehlen(0))
            if (hat(KANDIDAT)) return if (verneint || zahlen.size != 1) UNKLAR else r(Befehl.KandidatWaehlen(zahlen[0]))
        }

        // 9. Auskünfte aus dem Zustand, ohne Central Command. „Was bedeutet …“ ist eine Erklärung und geht an Central Command, außer es geht um die Warnung, die gerade ansteht.
        if (hat(CC_BEDEUTUNG) && !hat(A_WARNUNG)) return r(Befehl.AnClaude(text.trim()))
        auskunft(s, erlaubt)?.let { return r(Befehl.Frage(it)) }

        // 10. Mission über ein Titelwort („Ich will ein Grundstück vermessen“, „Check a place“, „Folge mir“).
        if (!fragt && n <= 8) {
            val arten = Katalog.TITELWORT.filterValues { hat(it) }.keys
            if (arten.size == 1) return if (verneint) UNKLAR else r(Befehl.MissionWaehlen(arten.first()))
        }

        return UNKLAR
    }

    /** Fragen, die der Zustand beantwortet. Reihenfolge: das Genauere vor dem Allgemeinen. */
    private fun auskunft(s: String, erlaubt: Set<Class<out Befehl>>): Auskunft? {
        fun hat(m: Regex) = m.containsMatchIn(s)
        return when {
            hat(A_FUNKABRISS) -> Auskunft.FUNKABRISS
            hat(A_HOEHENLIMIT) -> Auskunft.HOEHENLIMIT
            hat(A_FEHLT) -> if (hat(A_BRIEF) || (Befehl.FeldSetzen::class.java in erlaubt && !hat(A_START))) Auskunft.BRIEF_FEHLT else Auskunft.STARTKLAR
            hat(A_STARTKLAR) -> Auskunft.STARTKLAR
            hat(A_FORTSCHRITT) -> Auskunft.FORTSCHRITT
            hat(A_NAECHSTES) -> Auskunft.NAECHSTES
            hat(A_WARNUNG) -> Auskunft.WARNUNG
            hat(A_RESTZEIT) -> Auskunft.RESTZEIT
            hat(A_AKKU) -> Auskunft.AKKU
            hat(A_HOEHE) -> Auskunft.HOEHE
            hat(A_TEMPO) -> Auskunft.TEMPO
            hat(A_ENTFERNUNG) -> Auskunft.ENTFERNUNG_HEIM
            hat(A_WIND) -> Auskunft.WIND
            hat(A_GPS) -> Auskunft.GPS
            hat(A_SENSOREN) -> Auskunft.SENSOREN
            hat(A_VERBINDUNG) -> Auskunft.VERBINDUNG
            hat(A_TAGESLICHT) -> Auskunft.TAGESLICHT
            hat(A_PLAN) -> Auskunft.PLAN
            hat(A_BRIEF) -> Auskunft.BRIEF_FEHLT
            hat(A_STATUS) -> Auskunft.STATUS
            else -> null
        }
    }

    private companion object {
        val UNKLAR = Deutung(Befehl.Unklar, Quelle.REGEL)
        /** Wie im früheren Cockpit.lokal(): längere Sätze lösen nichts Flugwirksames aus. */
        const val MAX_WOERTER = 6

        // Alle Muster in der Form von Worte.norm: klein, ohne Satzzeichen, ae/oe/ue/ss.
        val HALT_ANFANG = Regex("^((bitte|please|drohne|drone|die drohne|the drone|ok|okay|jetzt|now|sofort) )*(stop+|stopp|stoppe|stoppen|halt|halte an|pause|pausieren|pausiere|anhalten|warte|wait|hold|hoer auf|aufhoeren)\\b")
        val HALT_ENDE = Regex("\\b(anhalten|pausieren|stoppen|stopp|stop)( bitte| please| jetzt| now| sofort)*$")
        val FOLGEN_ENDE = Regex("\\b(hoer auf (mir |mich )?(zu )?(folgen|verfolgen)|aufhoeren zu folgen|folgen (beenden|stoppen|aufhoeren|abbrechen)|stop following|stop the follow|quit following|end (the )?follow\\w*)\\b")
        val HEIM = Regex("\\b(come|return|fly|go) (back )?home\\b|\\b(komm|komme|flieg|fliege) (bitte )?(nach hause|zurueck|heim)\\b|^(nach hause|return home|come back|return to home|heimflug|heimfliegen|zurueck zum start|back to the start)$")
        val FLUG_WORT = Regex("\\b(anhalten|stoppen|stopp|stop|pause|pausieren|nach hause|heim|home|heimflug|zurueckfliegen|zurueckkommen)\\b")

        val START_GENAU = Regex("^(start|take ?off|abheben|starten|heb ab|los|go)( now| jetzt| bitte| please)*$")
        val ABHEBEN = Regex("\\b(abheben|abhebe|(heb|hebe) ((jetzt|bitte|mal|sie|die drohne) )*ab|take ?off|lift ?off)\\b")
        val STARTEN = Regex("\\b(starte|starten|start)\\b.*\\b(plan|mission|flug|flight|drohne|drone|motor|motoren|motors|engines)\\b|\\b(plan|mission|flug|flight|motoren|motors) (starten|start)\\b|" +
            "\\b(flieg|fliege) los\\b|\\blosfliegen\\b|\\blos geht'?s\\b|\\blos geht es\\b|\\blet'?s (go|fly)\\b|\\bfly the (plan|mission)\\b|\\b(flieg|fliege) (den plan|die mission)\\b|\\bstart flying\\b|\\bstarte$")
        val BESTAETIGEN = Regex("^((ja|jawohl|jo|yes|yeah|yep|ok|okay|klar|sure|genau|einverstanden|bestaetigt|bestaetigen|bestaetige|confirm|confirmed|go ahead|mach das|mach es|mach schon|mach|tu es|tu das|do it|do that|bitte|please)( |$))+")
        val SELBSTTEST = Regex("\\b(selbsttest|self ?test)\\b")
        val SCHALTEN = Regex("\\b(aus|an|ein|ab|off|on|schalte|schalt|ausschalten|einschalten|abschalten|anschalten|disable|enable|deaktivier\\w*|aktivier\\w*|turn|switch)\\b")
        val HINDERNIS = Regex("\\b(hindernis\\w*|obstacle\\w*|sensor|sensoren|sensors|sensing)\\b")
        val WAECHTER = Regex("\\b(waechter|flugwaechter|bildwaechter|guard|guards|korridor|geofence)\\b")
        val GEHEIM = Regex("\\b(schluessel|key|token)\\b")
        val GRENZWERT = Regex("\\b(hoehenlimit|height limit|akkureserve|reserve|funkabriss\\w*|rc lost|link loss)\\b")
        val AENDERN = Regex("\\b(aendern|aendere|setz|setze|stell|stelle|change|set|raise|lower|erhoeh\\w*|senk\\w*)\\b")
        val LANDEN = Regex("\\b(lande|landen|land|landung|landing|touch down|set it down|setz sie ab)\\b")
        val HIMMELSRICHTUNG = Regex("\\b(norden|sueden|osten|westen|north|south|east|west|noerdlich|suedlich|oestlich|westlich)\\b")
        val ORT_WORT = Regex("\\b(ort|place|punkt|point|ecke|corner|setz|setze|set|put)\\b")
        val STEUERN = Regex("\\b(hoeher|tiefer|higher|lower|schneller|langsamer|faster|slower|vorwaerts|rueckwaerts|forward|forwards|backward|backwards|steig|steige|steigen|sinke|sinken|climb|descend|" +
            "dreh|drehe|drehen|rotate|rauf|runter|nach oben|nach unten|go up|go down|fly up|fly down|speed up|slow down)\\b|" +
            "\\b(nach|to the|go|fly|flieg|fliege|turn|dreh|drehe|move|beweg\\w*|schwenk\\w*|pan) (links|rechts|left|right)\\b")
        val KAMERA = Regex("\\b(foto|fotos|photo|photos|picture|aufnahme|aufnehmen|record|recording|bild machen|ausloesen|shutter)\\b")

        val CC_BILD = Regex("\\b(siehst du|sehen sie|do you see|can you see|was siehst|schau\\w*|sieh dir|guck\\w*|look at|look closer|look close|take a look|im bild|auf dem bild|in the image|in the picture|on the image|describe|beschreib\\w*)\\b")
        val CC_PROTOKOLL = Regex("\\b(letzten flug|letzter flug|last flight|went wrong|schiefgelaufen|schief gelaufen|schiefgegangen|schief gegangen|im protokoll|laut protokoll|im log|in the log)\\b")
        val CC_ERKLAERUNG = Regex("\\b(erklaer\\w*|explain|wie funktioniert|how does|how do i|how can i|wie mache ich|wie kann ich|wie stelle ich|where do i|where can i|wo kann ich|wo stelle ich|wo finde ich|wo schalte ich|" +
            "which mission|welche mission (brauche|nehme|passt)|what is the difference|unterschied|difference between)\\b")
        val CC_BEDEUTUNG = Regex("\\b(was bedeutet|was heisst|what does|what is a|what is an|what are|was ist ein|was ist eine|was sind)\\b")

        val ORT_FERTIG = Regex("^(ok |okay )?(fertig|done|finished|das wars|das war's|bin fertig|i am done|i'm done)$")
        val ORT_ZURUECK = Regex("^(zurueck|undo|rueckgaengig|(die )?letzte ecke (zurueck|weg|loeschen)|(den )?letzten (punkt|tipp) (zurueck|weg|loeschen)|undo the last (tap|corner|point)|remove the last (tap|corner|point))$")

        val FOLGEN_BEGINN = Regex("\\b(fang an (mir )?zu folgen|beginn\\w* (mir )?zu folgen|folgen (starten|beginnen|anfangen)|starte das folgen|start following|begin following|start the follow\\w*|start follow)\\b")
        val FOLGE_MIR = Regex("\\b(folge mir|folg mir|follow me)\\b")
        val VERMESSUNG_VORBEREITEN = Regex("\\b(vermessung\\w* vorbereiten|bereite (die |den |eine |einen )?vermessung\\w* vor|prepare (the |a )?survey( flight)?)\\b")
        val BRIEF_PLANEN = Regex("\\b(bau|baue|bauen|erstell|erstelle|erstellen|build|make|create) (mir )?(den|einen|the|a) plan\\b|\\bplan (bauen|erstellen)\\b")
        val HOCHLADEN = Regex("\\b(hochladen|upload|lad (ihn|den plan|es) hoch|lade (ihn|den plan|es) hoch)\\b")
        val PLAN_HOLEN = Regex("\\b(plan holen|(hol|hole|lad|lade) (mir )?(den|einen) plan|(get|load|fetch) (the|a|my) plan)\\b")
        val FORTSETZEN = Regex("\\b(resume|fortsetzen|weiterfliegen|weitermachen|mach weiter|flieg weiter|fliege weiter|carry on|keep going|setz (den plan |die mission )?fort)\\b")
        val FORTSETZEN_GENAU = Regex("^(weiter|continue|go on)( bitte| please)?$|^continue (the )?(plan|mission|flight)$")
        val NOCHMAL = Regex("\\b(frag (noch ?mal|erneut)|noch ?mal fragen|ask again|ask (central command|claude) again)\\b")

        val VOLLBILD = Regex("\\b(vollbild|full ?screen)\\b")
        val VOLLBILD_AUS = Regex("\\b(aus|verlassen|beenden|raus|exit|leave|off|end)\\b")
        val DEUTSCH = Regex("\\b(deutsch|german)\\b")
        val ENGLISCH = Regex("\\b(englisch|english)\\b")
        val SCHLIESSEN = Regex("^(bitte |please )?(mach (das|es|die flaeche|das fenster) (wieder )?zu|zumachen|schliessen|schliess (das|es|die flaeche|das fenster)|schliesse (das|es|die flaeche|das fenster)|close|close (it|this|that|the panel)|" +
            "zurueck zum cockpit|back to the cockpit)( bitte| please)?$")
        val ZEIGEN = Regex("\\b(zeig|zeige|oeffne|oeffnen|open|show|geh zu|geh auf|go to|switch to|wechsle|wechsel|schalte auf|schalt auf)\\b")
        val N_KARTE = Regex("\\b(karte|map)\\b")
        val N_LIVE = Regex("\\b(livebild|live bild|live view|live|kamerabild|camera view|videobild)\\b")
        val N_3D = Regex("\\b(3d|3 d|drei d|three d)\\b")
        val N_MSN = Regex("\\b(missionen|missions|msn|missionsuebersicht)\\b")
        val N_LOG = Regex("\\b(log|logbuch|protokoll|flight log|flugprotokoll)\\b")
        val N_SET = Regex("\\b(einstellungen|settings|set)\\b")
        val N_SYS = Regex("\\b(system|systemstatus|system status|sys|lagebild)\\b")
        val EINPASSEN = Regex("^(bitte |please )?(einpassen|karte einpassen|fit|fit the map|fit the view|zoom|alles zeigen|show everything|show all)( bitte| please)?$")

        val BRIEF_AENDERN = Regex("\\b(brief (aendern|oeffnen|bearbeiten)|aendere den brief|change the brief|edit the brief|open the brief)\\b")
        val ORT_WAEHLEN = Regex("\\b(ort (waehlen|auswaehlen|tippen)|waehle (den|einen) ort|ecken (waehlen|tippen)|(choose|pick|select|tap) (the|a) (place|area|corners))\\b")
        val MISSION_WEG = Regex("\\b(mission (abwaehlen|verwerfen|schliessen|fallen lassen)|brief verwerfen|(discard|drop) the (mission|brief))\\b")
        val KANDIDAT = Regex("\\b(kandidat|kandidaten|candidate)\\b")
        val BRIEF_FRAGE = Regex("^(?:die |meine |the |my )?(?:frage|question) (?:ist|lautet|is)[:,]? (.+)$", RegexOption.IGNORE_CASE)
        val BRIEF_SUCHE = Regex("^(?:ich suche|gesucht (?:ist|wird)|i am looking for|i'm looking for|looking for) (.+)$", RegexOption.IGNORE_CASE)
        val F_HOEHE = Regex("\\b(hoehe|height|altitude|flughoehe)\\b")
        val F_RADIUS = Regex("\\b(radius|suchradius|sperrradius)\\b")
        val F_ABSTAND = Regex("\\b(abstand|distance|distanz)\\b")
        val MISSION_NR = Regex("\\b(mission|missions)\\b")

        val A_FUNKABRISS = Regex("\\b(funkabriss\\w*|funk abreisst|verbindung (abreisst|verloren|weg)|signal (loss|lost|weg)|link (loss|is lost|lost)|rc lost|lose (the )?(signal|connection|link))\\b")
        val A_HOEHENLIMIT = Regex("\\b(hoehenlimit|height limit|maximale hoehe|maximalhoehe|maximum (height|altitude)|max height|wie hoch darf)\\b")
        val A_FEHLT = Regex("\\b(fehlt|missing)\\b")
        val A_BRIEF = Regex("\\b(brief)\\b")
        val A_START = Regex("\\b(start|starten|abheben|take ?off|flug|fly)\\b")
        val A_STARTKLAR = Regex("\\b(startklar|startbereit|flugbereit|bereit|ready|kann ich (starten|abheben|fliegen|los)|can i (start|take off|fly))\\b")
        val A_FORTSCHRITT = Regex("\\b(fortschritt|progress|wie weit ist (der plan|die mission)|how far along|how far is the (plan|mission)|wie viel prozent)\\b")
        val A_NAECHSTES = Regex("\\b(als naechstes|naechstes|naechste|next|was kommt|what comes|danach)\\b")
        val A_WARNUNG = Regex("\\b(warnung|warnungen|warning|warnings|alarm|warnt)\\b")
        val A_RESTZEIT = Regex("\\b(wie lange|how long|restzeit|flugzeit|flight time|time left|minuten|minutes)\\b")
        val A_AKKU = Regex("\\b(akku|akkustand|batterie|battery|strom|saft)\\b")
        val A_HOEHE = Regex("\\b(wie hoch|how high|hoehe|flughoehe|altitude|height)\\b")
        val A_TEMPO = Regex("\\b(wie schnell|how fast|tempo|geschwindigkeit|speed)\\b")
        val A_ENTFERNUNG = Regex("\\b(wie weit|how far|entfernung|distance|distanz|wo ist die drohne|where is (the drone|it)|richtung|direction)\\b")
        val A_WIND = Regex("\\b(wind|windig|windy|boeen|gusts)\\b")
        val A_GPS = Regex("\\b(gps|satellit|satelliten|satellite|satellites)\\b")
        val A_SENSOREN = Regex("\\b(hindernis\\w*|obstacle\\w*|sensor|sensoren|sensors)\\b")
        val A_VERBINDUNG = Regex("\\b(verbindung|verbunden|connected|connection|signal|link|funk)\\b")
        val A_TAGESLICHT = Regex("\\b(tageslicht|daylight|hell genug|dunkel|dark|light enough|sonne|sun)\\b")
        val A_PLAN = Regex("\\b(welcher plan|which plan|what plan|name of the plan|plan name|planname|was ist geladen|what is loaded)\\b")
        val A_STATUS = Regex("\\b(status|was passiert|what is happening|what's happening|what is going on|was macht die drohne|what is the drone doing|was ist los|lage)\\b")
    }
}
