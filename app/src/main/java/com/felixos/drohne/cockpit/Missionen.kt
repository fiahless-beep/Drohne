package com.felixos.drohne.cockpit

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Missions-Übersicht (Felix 23.11.2026, Prototyp „Felix Drohne Missions“): ein Cockpit, sieben Aufgaben, dazu „Anything else“.
 * Jede Kachel sagt ehrlich, was davon gebaut und was geflogen ist. Mehrere Akkus und der Vermessungsflug sind keine eigenen
 * Missionen, sondern gehören zu jedem Plan (Felix 23.11.2026); beides steht hier nur als Text, gebaut ist es noch nicht.
 */
internal class MissionenTafel(private val stil: Stil, wurzel: FrameLayout, private val planHolen: () -> Unit, private val sprechen: () -> Unit) {
    val rahmen = TafelRahmen(stil, "Missions")
    private enum class Stand(val farbe: Int) { FERTIG(Farbe.ACCENT), UNGETESTET(Farbe.WARN_TEXT), KONZEPT(Farbe.TEXT_DIM) }
    private class Mission(val nr: String, val name: String, val kurz: String, val stand: Stand, val standText: String, val lang: String, val knopf: String? = null)

    private val liste = listOf(
        Mission("01", "Fly a plan", "A plan built with Claude in the chat.", Stand.FERTIG, "Ready · flown",
            "Get the plan, read the check list, hold Start. Pause with the button, any stick or the pause button on the controller; Resume continues. A plan that is too long for one battery comes back at the reserve and the rest is kept as a rest plan. Saying in the plan check how many batteries a plan needs is not built yet.", "Get plan"),
        Mission("02", "Subject on the way", "During a plan something catches your eye.", Stand.UNGETESTET, "Built · untested in flight",
            "Pause the plan. Tap the subject on the map or in the live view and choose a photo circle. When it is done the drone hovers; Resume plan continues from the point where you paused."),
        Mission("03", "Check a place", "A known place and one question.", Stand.UNGETESTET, "Built · untested in flight",
            "Hover at 10 m or higher, tap the place on the map or in the live view and choose Look closer: the drone flies above it, camera straight down, hovers 6 s and takes a photo. Then press Speak and ask your question; Central Command answers from the still."),
        Mission("04", "Find a spot", "You name what you need.", Stand.KONZEPT, "Concept · not built",
            "Parking, camping or picnic spot: Central Command proposes a search area, you confirm it, the drone sweeps it and marks candidates. Not in this build."),
        Mission("05", "Follow", "The drone keeps you or the car in frame.", Stand.UNGETESTET, "Tracking test first",
            "Needs the tracking test in flight first: Set → Device & test → Tracking test. Its result decides how following is built."),
        Mission("06", "Survey a property", "Boundary from the listing or the cadastre.", Stand.UNGETESTET, "As a plan from Claude",
            "Claude builds grid and rings from the boundary in the chat; you fly it here as a plan (01). A survey flight that first builds a height model, and the hint when an area is already surveyed, are not built yet.", "Get plan"),
        Mission("07", "Atmosphere", "Pictures of an event from the side.", Stand.KONZEPT, "Concept · not built",
            "The crowd gets a keep-out zone; the drone never flies above people. Not in this build."),
    )
    private val gitter = stil.spalte()
    private val einzel = stil.spalte()
    private val eNr = stil.text(22f, stil.monoFett, Farbe.ACCENT)
    private val eName = stil.text(17f, stil.sans, Farbe.TEXT)
    private val eStand = stil.text(11f, stil.monoMittel, Farbe.ACCENT, versalien = true, sperrung = 0.06f)
    private val eText = stil.text(14f, stil.sans, Farbe.TEXT, zeilen = Int.MAX_VALUE)
    private var eAktion: (() -> Unit)? = null
    private val eKnopf = Knopf(stil, Knopf.Stilart.HAUPT) { eAktion?.invoke() }
    private val zurueckKnopf: LeistenKnopf = rahmen.kopfKnopf("Back", "Back to all missions") { zurueck() }

    init {
        rahmen.zusatz.text = "one cockpit · seven jobs · Speak works in every view"
        val alle = liste.map { m -> kachel(m.nr, m.name, m.kurz, m.standText, m.stand.farbe) { zeige(m) } } +
            kachel("+", "Anything else", "Tap and say it.", "Speak", Farbe.TEXT_DIM) { sprechen() }
        alle.chunked(4).forEachIndexed { z, reihe ->
            val zeile = LinearLayout(stil.c).apply { orientation = LinearLayout.HORIZONTAL }
            reihe.forEachIndexed { i, k -> zeile.addView(k, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) leftMargin = stil.dp(6) }) }
            gitter.addView(zeile, stil.lp(Stil.MATCH, 0, 1f, oben = if (z > 0) 6 else 8))
        }
        rahmen.addView(gitter, stil.lp(Stil.MATCH, 0, 1f))
        val kopf = stil.zeile()
        kopf.addView(eNr); kopf.addView(eName, stil.lp(Stil.WRAP, Stil.WRAP, links = 10)); kopf.addView(eStand, stil.lp(0, Stil.WRAP, 1f, links = 12))
        einzel.addView(kopf, stil.lp(Stil.MATCH, Stil.WRAP, oben = 10))
        eText.setLineSpacing(0f, 1.3f); eText.ellipsize = null
        val rolle = ScrollView(stil.c); rolle.addView(eText, FrameLayout.LayoutParams(-1, -2))
        einzel.addView(rolle, stil.lp(Stil.MATCH, 0, 1f, oben = 8))
        einzel.addView(eKnopf, stil.lp(stil.dp(240), stil.dp(52), oben = 8))
        rahmen.addView(einzel, stil.lp(Stil.MATCH, 0, 1f))
        einzel.visibility = View.GONE; zurueckKnopf.visibility = View.GONE
        rahmen.visibility = View.GONE
        wurzel.addView(rahmen, FrameLayout.LayoutParams(-1, -1))
    }

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

    private fun zeige(m: Mission) {
        eNr.text = m.nr; eName.text = m.name; eStand.text = m.standText; eStand.setTextColor(m.stand.farbe); eText.text = m.lang
        eKnopf.sichtbar(m.knopf != null)
        if (m.knopf != null) { eKnopf.setze(m.knopf, null, true); eAktion = planHolen }
        gitter.sichtbar(false); einzel.sichtbar(true); zurueckKnopf.sichtbar(true)
    }

    /** Eine Ebene zurück; false = die Übersicht war schon zu sehen. */
    fun zurueck(): Boolean {
        if (einzel.visibility != View.VISIBLE) return false
        einzel.sichtbar(false); gitter.sichtbar(true); zurueckKnopf.sichtbar(false); return true
    }

    /** Was gerade zu sehen ist, für den Kontext von Central Command. */
    val kontext: String get() = if (einzel.visibility == View.VISIBLE) "Missions, mission ${eNr.text} ${eName.text}" else "Missions overview"
}
