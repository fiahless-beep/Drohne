package com.felixos.drohne.cockpit

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.felixos.drohne.R

/**
 * Sprechen mit Central Command, gebunden an die Ansicht, die gerade zu sehen ist (Felix 23.11.2026: „so ein Speak-Button,
 * der immer funktioniert und aber dann schon immer an den Kontext gebunden ist … dass es immer nur für das geht, was ich
 * gerade sehe“; „der helle Knopf ist da komisch in der gleichen Hierarchie“ → runder Knopf über der Ansicht, nicht in der Leiste).
 * Der Knopf schwebt über jeder Ansicht und jeder Fläche; der Streifen oben nennt den Kontext und zeigt Gehörtes und Antwort.
 * [lokal] erledigt, was ohne Netz gehen muss (Anhalten im Flug); alles andere geht mit dem Kontext an Central Command.
 * Abheben geht nie per Sprache. Ohne Druck auf einen Vorschlag passiert nichts Flugwirksames außer dem Anhalten.
 */
internal class SprechEbene(
    private val stil: Stil, host: FrameLayout, private val gast: Gastgeber,
    private val kontext: () -> Kontext,
    private val lokal: (String) -> Lokal?,
) {
    /** Was der Pilot gerade sieht: [name] für den Streifen und für Central Command, [beispiele] als Hinweis, was hier geht. */
    class Kontext(val name: String, val beispiele: String)
    /** Antwort ohne Central Command: [text] und höchstens ein Vorschlag (Name, Handlung). */
    class Lokal(val text: String, val vorschlag: Pair<String, () -> Unit>? = null)

    private val sprache = Sprache(stil.c)
    private val taste = Taste()
    private val streifen = stil.spalte()
    private val titel = stil.text(12f, stil.monoFett, Farbe.ACCENT, versalien = true, sperrung = 0.1f)
    private val wo = stil.text(11f, stil.monoMittel, Farbe.TEXT, versalien = true, sperrung = 0.06f)
    private val du = stil.text(14f, stil.sans, Farbe.TEXT, zeilen = 2)
    private val cc = stil.text(14f, stil.sans, Farbe.ACCENT, zeilen = 6)
    private val vorschlag = Knopf(stil, Knopf.Stilart.HAUPT) {
        val t = aktion; val alt = System.currentTimeMillis() - aktionSeitMs > 20_000
        if (alt) zeige("CC", null, "That suggestion is older than 20 seconds. Say it again.", true) else { schliessen(); t?.second?.invoke() }
    }
    private val sprachKnopf: LeistenKnopf = LeistenKnopf(stil, "Language for speaking: German or English") { sprachKnopf.setze(sprache.wechseln(), false) }
    private var aktion: Pair<String, () -> Unit>? = null
    private var aktionSeitMs = 0L
    private var wartet = false
    private val breiteFeld = stil.c.resources.getDimensionPixelSize(R.dimen.panelWidth)

    init {
        val kopf = stil.zeile()
        kopf.addView(titel)
        kopf.addView(wo, stil.lp(0, Stil.WRAP, 1f, links = 10))
        sprachKnopf.setze(sprache.kuerzel, false); sprachKnopf.setPadding(stil.dp(10), 0, stil.dp(10), 0)
        kopf.addView(sprachKnopf, stil.lp(Stil.WRAP, stil.dp(30)))
        val zu = LeistenKnopf(stil, "Close") { schliessen() }.apply { setze("×", false); setPadding(stil.dp(12), 0, stil.dp(12), 0) }
        kopf.addView(zu, stil.lp(Stil.WRAP, stil.dp(30), links = 6))
        streifen.background = stil.flaeche(Farbe.BG, Farbe.ACCENT)
        streifen.setPadding(stil.dp(10), stil.dp(6), stil.dp(10), stil.dp(8))
        streifen.isClickable = true
        streifen.addView(kopf, stil.lp(Stil.MATCH, stil.dp(32)))
        streifen.addView(View(stil.c).apply { setBackgroundColor(Farbe.ACCENT_DIM) }, stil.lp(Stil.MATCH, stil.dp(1), oben = 2))
        streifen.addView(du, stil.lp(Stil.MATCH, Stil.WRAP, oben = 6))
        streifen.addView(cc, stil.lp(Stil.MATCH, Stil.WRAP, oben = 4))
        streifen.addView(vorschlag, stil.lp(Stil.MATCH, stil.dp(46), oben = 6))
        vorschlag.visibility = View.GONE
        streifen.visibility = View.GONE
        host.addView(streifen, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = stil.dp(52); leftMargin = stil.dp(8); rightMargin = breiteFeld + stil.dp(14) })
        taste.contentDescription = "Speak to Central Command about this view"
        taste.setOnClickListener { druck() }
        host.addView(taste, FrameLayout.LayoutParams(stil.dp(64), stil.dp(64), Gravity.BOTTOM or Gravity.END).apply { rightMargin = breiteFeld + stil.dp(18); bottomMargin = stil.dp(12) })
    }

    /** Über der Fußzeile mit STOP bleiben, wenn eine Fläche im Flug offen ist; im Vollbild verschwindet der Knopf. */
    fun lage(fussHoehePx: Int, sichtbar: Boolean, flaecheOffen: Boolean = false) {
        val lp = taste.layoutParams as FrameLayout.LayoutParams
        val unten = stil.dp(12) + fussHoehePx
        // Eine offene Fläche (Msn, Sys, Log, Set) füllt die ganze Breite: dort sitzt der Knopf am rechten Rand, sonst links vom Aktionsfeld.
        val rechts = if (flaecheOffen) stil.dp(14) else breiteFeld + stil.dp(18)
        if (lp.bottomMargin != unten || lp.rightMargin != rechts) { lp.bottomMargin = unten; lp.rightMargin = rechts; taste.requestLayout() }
        taste.sichtbar(sichtbar)
        if (!sichtbar) schliessen()
    }

    private fun zeige(kopf: String, gehoert: String?, antwort: String?, warnung: Boolean = false, a: Pair<String, () -> Unit>? = null) {
        streifen.sichtbar(true)
        titel.zeige(kopf); wo.zeige("about: " + kontext().name)
        du.sichtbar(gehoert != null); if (gehoert != null) du.zeige(gehoert)
        cc.sichtbar(antwort != null); if (antwort != null) { cc.zeige(antwort); cc.farbe(if (warnung) Farbe.WARN_TEXT else Farbe.ACCENT) }
        aktion = a; aktionSeitMs = System.currentTimeMillis(); vorschlag.sichtbar(a != null)
        if (a != null) vorschlag.setze("Do it: ${a.first}", Zeichen.Art.PLAY, true)
        taste.an = sprache.hoert; taste.invalidate()
    }

    private fun druck() {
        if (wartet) return zeige("CC · looking …", null, "Still waiting for the last answer.")
        if (sprache.hoert) { sprache.genug(); return }
        if (!sprache.erkennungDa) return zeige("CC", null, "Speech recognition is not available on this phone.", true)
        if (!sprache.mikrofonErlaubt) { gast.mikrofonErlauben(); return zeige("CC", null, "Allow the microphone, then press Speak again.", true) }
        sprache.hoeren(
            teil = { zeige("Listening", "“$it”", null) },
            fertig = { t -> if (t == null) zeige("CC", null, "Nothing understood. Press Speak again.", true) else gehoert(t) },
            fehler = { f -> zeige("CC", null, f, true) })
        zeige("Listening", null, "Say it. Here for example: " + kontext().beispiele)
        cc.farbe(Farbe.TEXT_DIM)
    }

    private fun gehoert(t: String) {
        val satz = "“$t”"
        lokal(t)?.let { return zeige("CC", satz, it.text, false, it.vorschlag) }
        gast.ccGesperrt()?.let { return zeige("CC", satz, it, true) }
        wartet = true
        zeige("CC · looking …", satz, null)
        gast.ccFragen("[The pilot is looking at: ${kontext().name}] $t") { antwort, fehler, a ->
            wartet = false
            if (streifen.visibility == View.VISIBLE) zeige("CC", satz, antwort, fehler, a)
        }
    }

    /**
     * Etwas diktieren statt Central Command fragen (Frage zu einem Ort, das Gesuchte): derselbe Streifen, dasselbe Mikrofon,
     * aber der Text geht an [fertig] und nirgends sonst hin. Ein zweiter Druck auf den Sprechknopf beendet das Zuhören.
     */
    fun diktieren(was: String, fertig: (String) -> Unit) {
        if (wartet) return zeige("CC · looking …", null, "Still waiting for the last answer.")
        if (sprache.hoert) { sprache.genug(); return }
        if (!sprache.erkennungDa) return zeige(was, null, "Speech recognition is not available on this phone.", true)
        if (!sprache.mikrofonErlaubt) { gast.mikrofonErlauben(); return zeige(was, null, "Allow the microphone, then press again.", true) }
        sprache.hoeren(
            teil = { zeige("Listening · $was", "“$it”", null) },
            fertig = { t -> if (t == null) zeige(was, null, "Nothing understood. Press again.", true) else { schliessen(); fertig(t) } },
            fehler = { f -> zeige(was, null, f, true) })
        zeige("Listening · $was", null, "Say it now. It is written into the brief, nothing is sent yet.")
        cc.farbe(Farbe.TEXT_DIM)
    }

    fun schliessen() { sprache.schliessen(); aktion = null; streifen.sichtbar(false); taste.an = false; taste.invalidate() }
    /** Von außen anstoßen (Kachel „Anything else“). */
    fun sprechen() { if (!sprache.hoert) druck() }
    val offen: Boolean get() = streifen.visibility == View.VISIBLE

    /** Runder Knopf mit Mikrofon; gefüllt, solange zugehört wird. */
    private inner class Taste : View(stil.c) {
        var an = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val m = width / 2f; val rad = m - stil.dp(6f)
            p.style = Paint.Style.FILL; p.color = Farbe.ACCENT_GLOW; c.drawCircle(m, m, m, p)
            p.color = if (an) Farbe.ACCENT else Farbe.BG; c.drawCircle(m, m, rad, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = stil.dp(2f); p.color = Farbe.ACCENT; c.drawCircle(m, m, rad, p)
            val f = if (an) Farbe.BG else Farbe.ACCENT
            p.color = f; p.strokeWidth = stil.dp(2f)
            val w = stil.dp(4f); val oben = m - stil.dp(15f); val unten = m - stil.dp(1f)
            r.set(m - w, oben, m + w, unten); c.drawRoundRect(r, w, w, p)
            r.set(m - stil.dp(8f), oben + stil.dp(2f), m + stil.dp(8f), unten + stil.dp(5f)); c.drawArc(r, 0f, 180f, false, p)
            c.drawLine(m, unten + stil.dp(5f), m, unten + stil.dp(9f), p)
            p.style = Paint.Style.FILL; p.typeface = stil.monoFett; p.textSize = stil.px(9f); p.textAlign = Paint.Align.CENTER; p.letterSpacing = 0.1f
            c.drawText("SPEAK", m, m + stil.dp(20f), p)
        }
    }
}
