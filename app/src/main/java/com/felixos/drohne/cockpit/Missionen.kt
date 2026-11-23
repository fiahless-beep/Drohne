package com.felixos.drohne.cockpit

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.felixos.drohne.Aktionen
import com.felixos.drohne.CockpitZustand
import com.felixos.drohne.CockpitZustand.AuftragPhase

/**
 * Missionen (Felix 23.11.2026): ein Cockpit, sieben Aufgaben, dazu „Anything else“. Die Übersicht zeigt die Kacheln;
 * ein Druck öffnet die Seite der Mission. Jede Mission läuft gleich: Brief (wenige feste Werte, Ort auf der Karte, das
 * Gesprochene) → Plan-Check (dieselbe Prüfliste und derselbe Start-Knopf wie bei jedem Plan) → Flug → Ergebnis.
 * Auf der Seite steht oben, was zu tun ist, mit genau einem gefüllten Knopf; die Erklärung steht darunter.
 * Die Fläche zeigt nur, was im [CockpitZustand.Auftrag] steht, und ruft [Aktionen]; Orte sieht sie nur in Worten.
 * Mehrere Akkus und der Vermessungsflug sind keine eigenen Missionen: beides steht als Satz im Plan-Check jedes Plans.
 */
internal class MissionenTafel(
    private val stil: Stil, wurzel: FrameLayout, private val aktionen: Aktionen,
    private val planHolen: () -> Unit, private val sprechen: () -> Unit,
    /** Etwas diktieren: Name des Gesuchten für den Streifen, dann der verstandene Text. */
    private val diktieren: (String, (String) -> Unit) -> Unit,
    /** Die Fläche schließen und den Live-Bildschirm mit Plan-Check zeigen. */
    private val zumCockpit: () -> Unit,
    /** Zuhören beenden (beim Wechsel der Seite, damit Diktiertes nicht im falschen Brief landet). */
    private val still: () -> Unit,
) {
    val rahmen = TafelRahmen(stil, "Missions")
    private class Mission(val nr: Int, val name: String, val kurz: String, val geflogen: Boolean, val standText: String, val lang: String)

    private val liste = listOf(
        Mission(1, "Fly a plan", "A plan built with Claude in the chat.", true, "Flown · rest plan untested",
            "Get the plan, read the plan check, press Start and confirm. Pause with the button, any stick or the pause button on the controller; Resume continues. The plan check says how many batteries the plan needs and whether a survey flight pays off here. If the battery runs low the drone turns back at the reserve; after landing, the app loads the remaining points as a rest plan."),
        Mission(2, "Subject on the way", "During a plan something catches your eye.", false, "Untested in flight",
            "1  Pause the plan (button, any stick, or the pause button on the controller).\n2  Tap the subject on the map or in the live view.\n3  Choose a photo circle or Look closer. The drone flies it and then hovers.\n4  Resume: the plan continues where you paused."),
        Mission(3, "Check a place", "A known place and one question.", false, "Untested in flight",
            "The drone flies to the place, hovers with the camera straight down, takes two photos and comes home. While it flies back, Central Command answers your question from a still of the live view; the answer appears here."),
        Mission(4, "Find a spot", "You name what you need.", false, "Untested in flight",
            "For a place to park, to camp, for a picnic. The drone sweeps the area with the camera straight down and comes home. Central Command looks through the stills and lists candidates here. After landing, each candidate gets the key “Check this place” for mission 03."),
        Mission(5, "Follow", "The drone follows your phone.", false, "Never flown",
            "The drone follows the GPS of this phone at a fixed height and distance. It never takes off by itself: take off in the cockpit with “Hold: take off”, let it hover, then start following here. It first climbs straight up to the follow height, then moves. Never flown yet, and whether the sticks take over while following is not proven on this drone: first test over an open field with nobody around, and end it with Stop following or STOP."),
        Mission(6, "Survey a property", "Tap the corners, get grid and ring.", false, "Untested in flight",
            "The app builds a grid from straight above and an oblique ring around the property. The photos are the basis for a height model and a 3D model, which Claude builds from them in the chat. Where no height model exists yet, this is the survey flight."),
        Mission(7, "Atmosphere", "Pictures of an event from the side.", false, "Untested in flight",
            "The drone flies an arc outside the keep-out zone on your side, camera on the crowd, photos and one video. It never flies over the crowd: the app refuses any plan whose route, way there or way home crosses the zone."),
    )
    private val gitter = stil.spalte()
    private val einzel = stil.spalte()
    private val eNr = stil.text(22f, stil.monoFett, Farbe.ACCENT)
    private val eName = stil.text(17f, stil.sans, Farbe.TEXT)
    private val eStand = stil.text(11f, stil.monoMittel, Farbe.TEXT_DIM, versalien = true, sperrung = 0.06f)
    private val inhalt = stil.spalte()
    private val zurueckKnopf: LeistenKnopf = rahmen.kopfKnopf("Back", "Back to all missions") { zurueck() }
    private var offen: Mission? = null
    private var schluessel = ""
    private var meldung: String? = null

    init {
        rahmen.zusatz.text = "brief · plan check · flight · result"
        val alle = liste.map { m -> kachel(nr(m.nr), m.name, m.kurz, stand(m), if (m.geflogen) Farbe.ACCENT else Farbe.TEXT_DIM) { oeffnen(m) } } +
            kachel("+", "Anything else", "Tap and say it.", "Speak", Farbe.TEXT_DIM) { sprechen() }
        alle.chunked(4).forEachIndexed { z, reihe ->
            val zeile = LinearLayout(stil.c).apply { orientation = LinearLayout.HORIZONTAL }
            reihe.forEachIndexed { i, k -> zeile.addView(k, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) leftMargin = stil.dp(6) }) }
            gitter.addView(zeile, stil.lp(Stil.MATCH, 0, 1f, oben = if (z > 0) 6 else 8))
        }
        rahmen.addView(gitter, stil.lp(Stil.MATCH, 0, 1f))
        val kopf = stil.zeile()
        kopf.addView(eNr); kopf.addView(eName, stil.lp(Stil.WRAP, Stil.WRAP, links = 10)); kopf.addView(eStand, stil.lp(0, Stil.WRAP, 1f, links = 12))
        einzel.addView(kopf, stil.lp(Stil.MATCH, Stil.WRAP, oben = 8))
        val rolle = ScrollView(stil.c).apply { isVerticalScrollBarEnabled = true }
        // Unten Platz lassen: der runde Sprechknopf schwebt über der rechten unteren Ecke.
        inhalt.setPadding(0, 0, stil.dp(4), stil.dp(84))
        rolle.addView(inhalt, FrameLayout.LayoutParams(-1, -2))
        einzel.addView(rolle, stil.lp(Stil.MATCH, 0, 1f, oben = 2))
        rahmen.addView(einzel, stil.lp(Stil.MATCH, 0, 1f))
        einzel.visibility = View.GONE; zurueckKnopf.visibility = View.GONE
        rahmen.visibility = View.GONE
        wurzel.addView(rahmen, FrameLayout.LayoutParams(-1, -1))
    }

    private fun nr(n: Int) = if (n < 10) "0$n" else "$n"
    /** Stand mit Zeichen davor, damit er nicht nur an der Farbe hängt: Haken = geflogen, Ring = noch nicht. */
    private fun stand(m: Mission) = (if (m.geflogen) "✓ " else "○ ") + m.standText

    private fun kachel(nr: String, name: String, kurz: String, stand: String, standFarbe: Int, beiDruck: () -> Unit): View = stil.spalte().apply {
        background = stil.flaeche(Farbe.PANEL)
        setPadding(stil.dp(10), stil.dp(8), stil.dp(10), stil.dp(8))
        gravity = Gravity.TOP
        isClickable = true; isFocusable = true
        contentDescription = "$nr $name. $kurz $stand"
        setOnClickListener { beiDruck() }
        addView(stil.text(20f, stil.monoFett, Farbe.ACCENT).apply { text = nr })
        addView(stil.text(15f, stil.sans, Farbe.TEXT).apply { text = name }, stil.lp(Stil.MATCH, Stil.WRAP, oben = 2))
        addView(stil.text(12f, stil.sans, Farbe.TEXT_DIM, zeilen = 3).apply { text = kurz }, stil.lp(Stil.MATCH, 0, 1f, oben = 4))
        addView(stil.text(10f, stil.monoMittel, standFarbe, versalien = true, sperrung = 0.06f, zeilen = 2).apply { text = stand })
    }

    private fun kopfSetzen(m: Mission) { eNr.text = nr(m.nr); eName.text = m.name; eStand.text = stand(m); eStand.setTextColor(if (m.geflogen) Farbe.ACCENT else Farbe.TEXT_DIM) }

    private fun oeffnen(m: Mission) {
        // 01 und 02 haben keinen Brief: sie zu lesen darf den Brief einer anderen Mission nicht anfassen.
        still()
        meldung = if (m.nr >= 3) aktionen.auftragWaehlen(m.nr) else null
        offen = m; schluessel = ""
        kopfSetzen(m)
        gitter.sichtbar(false); einzel.sichtbar(true); zurueckKnopf.sichtbar(true)
        zeige(aktionen.zustand())
    }

    /** Eine Ebene zurück; false = die Übersicht war schon zu sehen. Der Brief bleibt im Kern erhalten. */
    fun zurueck(): Boolean {
        if (offen == null) return false
        still()
        offen = null; meldung = null
        einzel.sichtbar(false); gitter.sichtbar(true); zurueckKnopf.sichtbar(false); return true
    }

    /** Was gerade zu sehen ist, für den Kontext von Central Command. */
    val kontext: String get() = offen?.let { "Missions, mission ${nr(it.nr)} ${it.name}" } ?: "Missions overview"

    // ---------------------------------------------------------------- Brief, Plan, Flug, Ergebnis einer Mission

    /** Füllt die offene Mission aus dem Zustand; baut nur neu, wenn sich etwas geändert hat. */
    fun zeige(z: CockpitZustand) {
        val m = offen ?: return
        val a = z.auftrag
        val s = listOf(m.nr, a, meldung, z.plan.geladen, z.plan.name, z.plan.zeile, z.plan.akkuSatz, z.plan.akkuZuWeit, z.plan.vermessungSatz, z.plan.vermessungEmpfohlen, z.plan.laeuft, z.phase == CockpitZustand.Phase.PAUSIERT, z.fliegt, z.folgenAktiv, z.hauptaktion).joinToString("|")
        if (s == schluessel) return
        schluessel = s
        inhalt.removeAllViews()
        meldung?.let { hinweis(it, warnung = true) }
        when (m.nr) {
            1 -> planSeite(z)
            2 -> motivSeite(z)
            5 -> if (a.art == 5) folgenSeite(z, a) else andereOffen(m, a)
            else -> if (a.art == m.nr) briefSeite(z, a) else andereOffen(m, a)
        }
        trenner("How it works")
        absatz(m.lang, Farbe.TEXT_DIM)
    }

    /** Der Kern hält noch eine andere Mission (Plan geladen, Flug, Auswertung): die darf man fallen lassen, aber nur mit einem eigenen Druck. */
    private fun andereOffen(m: Mission, a: CockpitZustand.Auftrag) {
        if (a.art == 0) return
        val reihe = knopfReihe()
        knopf(reihe, "Open mission ${nr(a.art)}", null, haupt = true) { liste.firstOrNull { it.nr == a.art }?.let { oeffnen(it) } }
        if (a.phase != AuftragPhase.FLUG && a.arbeitet == null) knopf(reihe, "Discard ${nr(a.art)} · open this", null, haupt = false) { aktionen.auftragWaehlen(0); oeffnen(m) }
    }

    /** Der Kern hat die Mission gewechselt (Kandidat der Suche → 03, Vermessungsflug → 06): die Seite geht mit. */
    private fun mitgehen() {
        val art = aktionen.zustand().auftrag.art
        liste.firstOrNull { it.nr == art }?.let { n -> offen = n; schluessel = ""; kopfSetzen(n) }
        zeige(aktionen.zustand())
    }

    private fun planCheck(z: CockpitZustand) {
        absatz("${z.plan.name} · ${z.plan.zeile}", Farbe.TEXT)
        z.plan.akkuSatz?.let { zeichenZeile(if (z.plan.akkuZuWeit) false else true, it) }
        z.plan.vermessungSatz?.let { zeichenZeile(!z.plan.vermessungEmpfohlen, "Survey flight: $it") }
    }

    private fun planSeite(z: CockpitZustand) {
        z.auftrag.restSatz?.let { hinweis(it, warnung = false) }
        if (z.plan.geladen) {
            trenner("Plan check")
            planCheck(z)
            val reihe = knopfReihe()
            val erstVermessen = z.plan.vermessungEmpfohlen
            if (erstVermessen) knopf(reihe, "Prepare survey flight", null, haupt = true) { meldung = aktionen.vermessungVorbereiten(); if (meldung == null) mitgehen() else zeige(aktionen.zustand()) }
            knopf(reihe, "Go to the plan check", null, haupt = !erstVermessen) { zumCockpit() }
            knopf(reihe, "Get plan again", null, haupt = false) { planHolen() }
        } else {
            absatz("No plan is loaded.", Farbe.TEXT)
            knopf(knopfReihe(), "Get plan", null, haupt = true) { planHolen() }
        }
    }

    private fun motivSeite(z: CockpitZustand) {
        trenner("Now")
        val pausiert = z.phase == CockpitZustand.Phase.PAUSIERT || z.hauptaktion == CockpitZustand.Hauptaktion.FORTSETZEN
        when {
            pausiert -> zeichenZeile(true, "The plan is paused, the drone hovers. Tap the subject on the map or in the live view now.")
            z.plan.laeuft -> zeichenZeile(false, "A plan is running. Press Pause first.")
            z.fliegt -> zeichenZeile(true, "The drone hovers without a plan. Tap a subject on the map or in the live view (from 10 m height).")
            else -> zeichenZeile(null, "Starts in flight: pause a plan, then tap the subject.")
        }
        if (z.fliegt) knopf(knopfReihe(), "Go to map and live view", null, haupt = true) { zumCockpit() }
    }

    private fun folgenSeite(z: CockpitZustand, a: CockpitZustand.Auftrag) {
        trenner("Brief")
        felder(a, frei = !z.folgenAktiv)
        trenner(if (z.folgenAktiv) "Following" else "Before it can start")
        if (z.folgenAktiv) {
            zeichenZeile(true, "The drone follows this phone. End it with Stop following or STOP. A stick movement should end it too; that is untested.")
            knopf(knopfReihe(), "Stop following", Zeichen.Art.PAUSE, haupt = true) { aktionen.folgenAufhoeren() }
        } else {
            if (a.folgenSperren.isEmpty()) zeichenZeile(true, "Ready. The drone hovers; following starts from where it is.")
            a.folgenSperren.forEach { zeichenZeile(false, it) }
            if (!z.fliegt) knopf(knopfReihe(), "Go to the cockpit to take off", null, haupt = false) { zumCockpit() }
            // Flugwirksam: wie Abheben nur mit gehaltenem Knopf.
            knopf(knopfReihe(), "Hold: start following", null, haupt = true, frei = a.folgenSperren.isEmpty(), halteMs = 1500) {
                val gruende = aktionen.folgenBeginnen()
                meldung = if (gruende.isEmpty()) null else gruende.joinToString(" · ")
                zeige(aktionen.zustand())
            }
        }
    }

    private fun briefSeite(z: CockpitZustand, a: CockpitZustand.Auftrag) {
        val offenerBrief = a.phase == AuftragPhase.BRIEF || a.phase == AuftragPhase.ORT
        // Ergebnis zuerst, sobald es eins gibt: dafür wurde geflogen.
        val kandidatenVon04 = a.art == 3 && a.kandidaten.isNotEmpty()
        if (a.arbeitet != null || a.ergebnis != null || (a.kandidaten.isNotEmpty() && !kandidatenVon04)) {
            trenner("Result")
            a.arbeitet?.let { absatz(it, Farbe.ACCENT) }
            a.ergebnis?.let { if (a.ergebnisWarnung) zeichenZeile(false, it) else absatz(it, Farbe.TEXT) }
            if (a.nochmalMoeglich) knopf(knopfReihe(), "Ask Central Command again", null, haupt = true) { aktionen.auftragNochmal(); zeige(aktionen.zustand()) }
            if (!kandidatenVon04) kandidaten(a)
        }
        trenner("Brief")
        felder(a, frei = offenerBrief)
        if (a.ortArt != 0) {
            val name = when (a.art) { 4 -> "Middle of the search area"; 6 -> "Property"; 7 -> "Middle of the crowd"; else -> "Place" }
            wahlZeile(name, a.ortText ?: "not chosen yet", a.ortText != null, if (a.ortText == null) "Choose on the map" else "Change on the map", offenerBrief) {
                meldung = aktionen.ortWaehlen(); zeige(aktionen.zustand())
            }
        }
        a.textName?.let { n ->
            wahlZeile(n, if (a.text.isBlank()) "nothing said yet" else "“${a.text}”", a.text.isNotBlank(), if (a.text.isBlank()) "Say it" else "Say it again", offenerBrief) {
                diktieren(n) { t -> aktionen.auftragText(t) }
            }
            // Feste Sätze, falls die Spracherkennung am Feld nicht will: ein Druck statt Tippen.
            if (offenerBrief && a.vorschlaege.isNotEmpty()) {
                val z = stil.zeile()
                z.addView(stil.label("or", Farbe.TEXT_DIM))
                a.vorschlaege.forEach { v ->
                    val k = LeistenKnopf(stil, "Use: $v") { aktionen.auftragText(v); zeige(aktionen.zustand()) }
                    k.setze(v, false); k.isAllCaps = false; k.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f); k.setPadding(stil.dp(10), 0, stil.dp(10), 0)
                    z.addView(k, LinearLayout.LayoutParams(Stil.WRAP, stil.dp(48)).apply { leftMargin = stil.dp(8) })
                }
                inhalt.addView(z, stil.lp(Stil.MATCH, Stil.WRAP, oben = 6))
            }
        }
        when (a.phase) {
            AuftragPhase.BRIEF, AuftragPhase.ORT -> {
                a.abgelehnt?.let { hinweis(it, warnung = true) }
                a.fehlt?.let { zeichenZeile(false, it) }
                knopf(knopfReihe(), "Build the plan", null, haupt = true, frei = a.fehlt == null) {
                    meldung = null
                    if (aktionen.auftragPlanen() == null) zumCockpit() else zeige(aktionen.zustand())
                }
            }
            AuftragPhase.PLAN -> {
                trenner("Plan check")
                a.restSatz?.let { hinweis(it, warnung = false) }
                planCheck(z)
                absatz("Start stays with your thumb: go to the plan check, read the list, press Start and confirm.", Farbe.TEXT_DIM)
                val reihe = knopfReihe()
                knopf(reihe, "Go to the plan check", null, haupt = true) { zumCockpit() }
                knopf(reihe, "Change the brief", null, haupt = false) { aktionen.auftragAendern(); zeige(aktionen.zustand()) }
            }
            AuftragPhase.FLUG -> {
                trenner("Flight")
                zeichenZeile(true, "This mission is flying. Pause and STOP are in the cockpit.")
                knopf(knopfReihe(), "Go to map and live view", null, haupt = true) { zumCockpit() }
            }
            AuftragPhase.ERGEBNIS -> {
                val reihe = knopfReihe()
                if (a.arbeitet == null) knopf(reihe, "Change the brief", null, haupt = false) { aktionen.auftragAendern(); zeige(aktionen.zustand()) }
            }
            AuftragPhase.KEINE -> {}
        }
        if (kandidatenVon04) { trenner("Candidates from 04"); kandidaten(a) }
    }

    private fun kandidaten(a: CockpitZustand.Auftrag) {
        if (a.kandidaten.isEmpty()) return
        a.kandidaten.forEach { k ->
            wahlZeile("Candidate ${k.nr} · ${k.ortText}", k.text, true, "Check this place", a.phase != AuftragPhase.FLUG && a.arbeitet == null) { aktionen.kandidatWaehlen(k.nr); mitgehen() }
        }
        absatz("Positions are estimated from the stills and can be off by several metres. Check a candidate before you rely on it.", Farbe.TEXT_DIM)
    }

    // ---------------------------------------------------------------- Bausteine der Seite

    private fun absatz(t: String, farbe: Int) {
        inhalt.addView(stil.text(14f, stil.sans, farbe, zeilen = Int.MAX_VALUE).apply { text = t; setLineSpacing(0f, 1.3f); ellipsize = null }, stil.lp(Stil.MATCH, Stil.WRAP, oben = 6))
    }

    private fun trenner(t: String) {
        inhalt.addView(stil.label(t, Farbe.ACCENT), stil.lp(Stil.MATCH, Stil.WRAP, oben = 12))
        inhalt.addView(View(stil.c).apply { setBackgroundColor(Farbe.PANEL_LINE) }, stil.lp(Stil.MATCH, stil.dp(1), oben = 3))
    }

    private fun hinweis(t: String, warnung: Boolean) {
        val z = stil.zeile().apply { gravity = Gravity.TOP; background = stil.flaeche(if (warnung) Farbe.WARN_PANEL else Farbe.PANEL, if (warnung) Farbe.WARN else Farbe.ACCENT_DIM); setPadding(stil.dp(8), stil.dp(6), stil.dp(8), stil.dp(6)) }
        z.addView(ImageView(stil.c).apply { setImageDrawable(Zeichen(if (warnung) Zeichen.Art.WARN else Zeichen.Art.OK, if (warnung) Farbe.WARN else Farbe.ACCENT, aussparung = if (warnung) Farbe.WARN_PANEL else Farbe.PANEL, groessePx = stil.dp(15))) }, stil.lp(stil.dp(15), stil.dp(15), oben = 2))
        z.addView(stil.text(14f, stil.sans, Farbe.TEXT, zeilen = Int.MAX_VALUE).apply { text = t; ellipsize = null; setLineSpacing(0f, 1.25f) }, stil.lp(0, Stil.WRAP, 1f, links = 8))
        inhalt.addView(z, stil.lp(Stil.MATCH, Stil.WRAP, oben = 8))
    }

    /** Zeile mit Zeichenform: Haken = erfüllt, Dreieck = fehlt noch, Ring (null) = noch nicht dran. */
    private fun zeichenZeile(ok: Boolean?, t: String) {
        val z = stil.zeile().apply { gravity = Gravity.TOP }
        val (art, farbe, schrift) = when (ok) { true -> Triple(Zeichen.Art.OK, Farbe.ACCENT, Farbe.TEXT); false -> Triple(Zeichen.Art.WARN, Farbe.WARN, Farbe.WARN_TEXT); null -> Triple(Zeichen.Art.AUS, Farbe.TEXT_DIM, Farbe.TEXT) }
        z.addView(ImageView(stil.c).apply { setImageDrawable(Zeichen(art, farbe, groessePx = stil.dp(14))) }, stil.lp(stil.dp(14), stil.dp(14), oben = 2))
        z.addView(stil.text(14f, stil.sans, schrift, zeilen = Int.MAX_VALUE).apply { text = t; ellipsize = null; setLineSpacing(0f, 1.25f) }, stil.lp(0, Stil.WRAP, 1f, links = 8))
        inhalt.addView(z, stil.lp(Stil.MATCH, Stil.WRAP, oben = 6))
    }

    /** Die festen Werte des Briefs: je Feld eine Reihe Tasten; die gewählte ist voll gefüllt und trägt einen Punkt davor. */
    private fun felder(a: CockpitZustand.Auftrag, frei: Boolean) {
        a.felder.forEach { f ->
            val z = stil.zeile()
            z.addView(stil.label(f.name, Farbe.TEXT_DIM).apply { maxLines = 2 }, stil.lp(0, Stil.WRAP, 1f))
            f.werte.forEachIndexed { i, w ->
                val gewaehlt = i == f.gewaehlt
                val k = LeistenKnopf(stil, "${f.name}: $w" + if (gewaehlt) ", selected" else "") { if (frei) { aktionen.auftragFeld(f.id, i); zeige(aktionen.zustand()) } }
                k.setze(if (gewaehlt) "● $w" else w, gewaehlt); k.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                if (gewaehlt) { k.background = stil.flaeche(Farbe.ACCENT, Farbe.ACCENT); k.setTextColor(Farbe.BG) }
                k.alpha = if (frei || gewaehlt) 1f else 0.35f
                z.addView(k, LinearLayout.LayoutParams(stil.dp(88), stil.dp(48)).apply { leftMargin = stil.dp(6) })
            }
            inhalt.addView(z, stil.lp(Stil.MATCH, Stil.WRAP, oben = 8))
        }
    }

    /** Name und Wert links, rechts die Taste, die ihn setzt (Ort auf der Karte, das Gesprochene, ein Kandidat). */
    private fun wahlZeile(name: String, wert: String, gesetzt: Boolean, taste: String, frei: Boolean, beiDruck: () -> Unit) {
        val z = stil.zeile()
        val links = stil.spalte()
        links.addView(stil.label(name, Farbe.TEXT_DIM))
        links.addView(stil.text(14f, stil.sans, if (gesetzt) Farbe.TEXT else Farbe.TEXT_DIM, zeilen = 4).apply { text = wert; setLineSpacing(0f, 1.2f) }, stil.lp(Stil.MATCH, Stil.WRAP, oben = 2))
        z.addView(links, stil.lp(0, Stil.WRAP, 1f))
        if (frei) {
            val k = LeistenKnopf(stil, "$name: $taste") { beiDruck() }
            k.setze(taste, false); k.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f); k.setPadding(stil.dp(12), 0, stil.dp(12), 0)
            z.addView(k, LinearLayout.LayoutParams(Stil.WRAP, stil.dp(48)).apply { leftMargin = stil.dp(8) })
        }
        inhalt.addView(z, stil.lp(Stil.MATCH, Stil.WRAP, oben = 10))
    }

    private fun knopfReihe(): LinearLayout = stil.zeile().also { inhalt.addView(it, stil.lp(Stil.MATCH, Stil.WRAP, oben = 12)) }

    /** [haupt] = der eine gefüllte Knopf der Seite; alles andere ist ein Rahmenknopf. */
    private fun knopf(reihe: LinearLayout, text: String, zeichen: Zeichen.Art?, haupt: Boolean, frei: Boolean = true, halteMs: Long = 0, beiDruck: () -> Unit) {
        val lp = LinearLayout.LayoutParams(stil.dp(if (haupt) 230 else 200), stil.dp(50)).apply { if (reihe.childCount > 0) leftMargin = stil.dp(8) }
        if (haupt) reihe.addView(Knopf(stil, Knopf.Stilart.HAUPT, beiDruck).apply { this.halteMs = halteMs; if (halteMs > 0) halteText = "Keep holding …"; setze(text, zeichen, frei) }, lp)
        else reihe.addView(LeistenKnopf(stil, text) { if (frei) beiDruck() }.apply { setze(text, false); setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f); alpha = if (frei) 1f else 0.35f }, lp)
    }
}
