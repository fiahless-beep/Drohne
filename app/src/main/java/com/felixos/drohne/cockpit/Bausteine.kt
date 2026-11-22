package com.felixos.drohne.cockpit

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Statuszeichen als Form, damit ein Zustand auch ohne Farbe lesbar ist:
 * Kreis mit Haken = gut, Dreieck mit Ausrufezeichen = Warnung, Achteck mit Kreuz = Stopp, Ring = kein Wert.
 */
internal class Zeichen(val art: Art, private var farbe: Int, private val aussparung: Int = Farbe.BG, private val groessePx: Int) : Drawable() {
    enum class Art { OK, WARN, STOP, PAUSE, PLAY, AUS }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pfad = Path()
    var deckung = 255; private set

    fun farbe(f: Int) { if (f != farbe) { farbe = f; invalidateSelf() } }

    override fun getIntrinsicWidth() = groessePx
    override fun getIntrinsicHeight() = groessePx
    override fun setAlpha(alpha: Int) { if (alpha != deckung) { deckung = alpha; invalidateSelf() } }
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 12f
        c.save(); c.translate(b.left.toFloat(), b.top.toFloat()); c.scale(s, s)
        p.style = Paint.Style.FILL; p.color = farbe; p.alpha = deckung
        fun strich(breite: Float) { p.style = Paint.Style.STROKE; p.strokeWidth = breite; p.color = aussparung; p.alpha = deckung; p.strokeCap = Paint.Cap.BUTT }
        pfad.reset()
        when (art) {
            Art.OK -> {
                c.drawCircle(6f, 6f, 6f, p)
                strich(1.6f); pfad.moveTo(3.2f, 6.2f); pfad.lineTo(5.2f, 8.2f); pfad.lineTo(8.8f, 4.2f); c.drawPath(pfad, p)
            }
            Art.WARN -> {
                pfad.moveTo(6f, 0.6f); pfad.lineTo(11.8f, 11f); pfad.lineTo(0.2f, 11f); pfad.close(); c.drawPath(pfad, p)
                strich(1.5f); c.drawLine(6f, 4.3f, 6f, 7.6f, p); c.drawLine(6f, 8.7f, 6f, 9.9f, p)
            }
            Art.STOP -> {
                pfad.moveTo(3.5f, 0f); pfad.lineTo(8.5f, 0f); pfad.lineTo(12f, 3.5f); pfad.lineTo(12f, 8.5f); pfad.lineTo(8.5f, 12f); pfad.lineTo(3.5f, 12f); pfad.lineTo(0f, 8.5f); pfad.lineTo(0f, 3.5f); pfad.close()
                c.drawPath(pfad, p)
                strich(1.5f); c.drawLine(3.8f, 3.8f, 8.2f, 8.2f, p); c.drawLine(8.2f, 3.8f, 3.8f, 8.2f, p)
            }
            Art.PAUSE -> { c.drawRect(2f, 1f, 5f, 11f, p); c.drawRect(7f, 1f, 10f, 11f, p) }
            Art.PLAY -> { pfad.moveTo(2f, 1f); pfad.lineTo(11f, 6f); pfad.lineTo(2f, 11f); pfad.close(); c.drawPath(pfad, p) }
            Art.AUS -> { p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f; c.drawCircle(6f, 6f, 5f, p) }
        }
        c.restore()
    }
}

/** Status als Zeichen plus Klartext in Versalien (LINK OK, GPS 17, WARN …). */
@SuppressLint("ViewConstructor")
internal class Abzeichen(private val stil: Stil, private val neutral: Boolean = false, private val grad: Float = 12f) : LinearLayout(stil.c) {
    private val bild = ImageView(stil.c)
    private val tx = stil.text(grad, stil.monoMittel, Farbe.ACCENT, versalien = true, sperrung = 0.06f)
    private var art: Zeichen.Art? = null
    private var ton: Ton? = null

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(bild, LayoutParams(stil.dp(13), stil.dp(13)))
        addView(tx, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = stil.dp(4) })
    }

    fun setze(ton: Ton, text: String, art: Zeichen.Art = when (ton) { Ton.WARN -> Zeichen.Art.WARN; Ton.AUS -> Zeichen.Art.AUS; else -> Zeichen.Art.OK }) {
        tx.zeige(text)
        if (art == this.art && ton == this.ton) return
        this.art = art; this.ton = ton
        val schrift = when { ton == Ton.AUS -> Farbe.TEXT_DIM; neutral -> Farbe.TEXT; ton == Ton.WARN -> Farbe.WARN_TEXT; ton == Ton.NEUTRAL -> Farbe.TEXT; else -> Farbe.ACCENT }
        val zeichen = when { ton == Ton.WARN -> Farbe.WARN; else -> schrift }
        tx.setTextColor(schrift)
        bild.setImageDrawable(Zeichen(art, zeichen, groessePx = stil.dp(13)))
        contentDescription = null
    }
}

/** Reihe gleich breiter Zellen: Akku in Zehnteln, Fortschritt in Wegpunkten. */
@SuppressLint("ViewConstructor")
internal class Zellen(private val stil: Stil, private var n: Int) : View(stil.c) {
    private val p = Paint()
    private var an = 0
    private var jetzt = -1
    private var farbeAn = Farbe.ACCENT
    private var farbeJetzt = Farbe.ACCENT

    /** [an] Zellen von links gefüllt; [jetzt] (Index) hervorgehoben, -1 = keine. */
    fun setze(an: Int, farbeAn: Int = Farbe.ACCENT, jetzt: Int = -1, farbeJetzt: Int = Farbe.ACCENT, zellen: Int = n) {
        if (an == this.an && farbeAn == this.farbeAn && jetzt == this.jetzt && zellen == n) return
        this.an = an; this.farbeAn = farbeAn; this.jetzt = jetzt; this.farbeJetzt = farbeJetzt; n = zellen.coerceAtLeast(1)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val luecke = stil.dp(2f)
        val b = (width - luecke * (n - 1)) / n
        for (i in 0 until n) {
            p.color = when { i == jetzt -> farbeJetzt; i < an -> farbeAn; else -> Farbe.DECO }
            val x = i * (b + luecke)
            c.drawRect(x, 0f, x + b, height.toFloat(), p)
        }
    }
}

/** Beschriftung links, Wert rechts (DIST · HOME   318 m). */
@SuppressLint("ViewConstructor")
internal class WertZeile(stil: Stil, name: String, hell: Boolean = false) : LinearLayout(stil.c) {
    private val w = stil.text(12f, stil.monoMittel, Farbe.TEXT)

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = stil.dp(17)
        val k = stil.text(12f, stil.mono, if (hell) Farbe.TEXT else Farbe.TEXT_DIM, versalien = true, sperrung = 0.04f).apply { text = name }
        addView(k, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        w.gravity = Gravity.END
        addView(w, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = stil.dp(8) })
    }

    /** [ton] WARN färbt den Wert und stellt „!“ voran, damit die Warnung nicht nur an der Farbe hängt. */
    fun wert(t: String?, ton: Ton = Ton.NEUTRAL) {
        val leer = t == null
        w.zeige(if (leer) "–" else if (ton == Ton.WARN) "! $t" else t!!)
        w.farbe(when { leer || ton == Ton.AUS -> Farbe.TEXT_DIM; ton == Ton.WARN -> Farbe.WARN_TEXT; ton == Ton.OK -> Farbe.ACCENT; else -> Farbe.TEXT })
    }
}

/** Kachel des System-Bildschirms: Kopf mit Label und Status, darunter Zeilen. */
@SuppressLint("ViewConstructor")
internal class Kachel(private val stil: Stil, titel: String, mitStatus: Boolean = true) : LinearLayout(stil.c) {
    val status = Abzeichen(stil)
    private var warn: Boolean? = null

    init {
        orientation = VERTICAL
        setPadding(stil.dp(8), stil.dp(6), stil.dp(8), stil.dp(6))
        val kopf = stil.zeile()
        kopf.addView(stil.label(titel), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (mitStatus) kopf.addView(status)
        kopf.minimumHeight = stil.dp(18)
        addView(kopf, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = stil.dp(2) })
        warnung(false)
    }

    fun warnung(an: Boolean) {
        if (warn == an) return
        warn = an
        background = stil.flaeche(Farbe.PANEL, if (an) Farbe.WARN else Farbe.PANEL_LINE)
    }

    fun zeile(name: String): WertZeile = WertZeile(stil, name).also { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
}

/**
 * Großer Knopf des Aktionsfelds: Zeichen plus Versalien-Text. [halteMs] > 0 = erst nach so langem Halten auslösen
 * (ein Balken füllt sich); sonst ein Druck.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class Knopf(private val stil: Stil, private val art: Stilart, private val beiDruck: () -> Unit) : FrameLayout(stil.c) {
    enum class Stilart { HAUPT, STOPP, ABHEBEN }

    private val bild = ImageView(stil.c)
    private val tx = stil.text(if (art == Stilart.STOPP) 15f else 14f, stil.monoFett, Farbe.ACCENT, versalien = true, sperrung = 0.08f)
    private val balken = Paint().apply { color = Farbe.HOLD_FILL }
    private var anteil = 0f
    private var lauf: ValueAnimator? = null
    private var zeichen: Zeichen.Art? = null
    private var frei: Boolean? = null
    /** Halten statt Drücken, in Millisekunden; 0 = ein Druck. */
    var halteMs = 0L
    /** Text, solange gehalten wird. */
    var halteText: String? = null
    private var ruheText = ""

    init {
        val innen = stil.zeile().apply { gravity = Gravity.CENTER }
        val g = stil.dp(when (art) { Stilart.STOPP -> 22; Stilart.HAUPT -> 16; else -> 0 })
        if (g > 0) innen.addView(bild, LinearLayout.LayoutParams(g, g).apply { rightMargin = stil.dp(8) })
        tx.maxLines = 2; tx.gravity = Gravity.CENTER
        innen.addView(tx)
        addView(innen, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setWillNotDraw(false)
        isClickable = true; isFocusable = true
        setze("", null, true)
        // Bedienhilfen lösen einen Klick ohne Berührung aus; für Halte-Knöpfe gilt das bewusst nicht.
        setOnClickListener { if (halteMs == 0L && isEnabled) beiDruck() }
        setOnTouchListener { _, e ->
            if (!isEnabled) return@setOnTouchListener true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { isPressed = true; if (halteMs > 0) halten() }
                MotionEvent.ACTION_UP -> { val innenLiegend = e.x >= 0 && e.y >= 0 && e.x <= width && e.y <= height; isPressed = false; if (halteMs > 0) loslassen() else if (innenLiegend) performClick() }
                MotionEvent.ACTION_CANCEL -> { isPressed = false; loslassen() }
            }
            true
        }
    }

    fun setze(text: String, zeichen: Zeichen.Art?, frei: Boolean) {
        ruheText = text
        contentDescription = text
        if (lauf == null) tx.zeige(text)
        if (zeichen == this.zeichen && frei == this.frei) return
        this.zeichen = zeichen; this.frei = frei
        isEnabled = frei
        val (schrift, flaeche) = when {
            !frei && art == Stilart.STOPP -> Farbe.BG to stil.flaeche(Farbe.STOP, Farbe.STOP)
            !frei -> Farbe.TEXT_DIM to stil.flaeche(0, Farbe.DECO)
            art == Stilart.STOPP -> Farbe.BG to stil.flaeche(Farbe.STOP, Farbe.STOP)
            else -> Farbe.ACCENT to stil.flaeche(Farbe.ACCENT_FILL, Farbe.ACCENT)
        }
        alpha = if (!frei && art == Stilart.STOPP) 0.5f else 1f
        tx.setTextColor(schrift)
        background = flaeche
        bild.sichtbar(zeichen != null && frei)
        if (zeichen != null) bild.setImageDrawable(Zeichen(zeichen, schrift, aussparung = if (art == Stilart.STOPP) Farbe.STOP else Farbe.BG, groessePx = stil.dp(22)))
    }

    /** Die Haltezeit misst eine Uhr, nicht die Animation: die läuft bei abgeschalteten Systemanimationen sofort durch. */
    private val ausloesen = Runnable { balkenStoppen(); tx.zeige(ruheText); if (isEnabled && isPressed) beiDruck() }

    private fun halten() {
        balkenStoppen()
        halteText?.let { tx.zeige(it) }
        postDelayed(ausloesen, halteMs)
        lauf = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = halteMs; interpolator = LinearInterpolator()
            addUpdateListener { anteil = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun balkenStoppen() { lauf?.cancel(); lauf = null; anteil = 0f; invalidate() }

    private fun loslassen() { removeCallbacks(ausloesen); if (lauf != null) { balkenStoppen(); tx.zeige(ruheText) } }

    override fun onDetachedFromWindow() { removeCallbacks(ausloesen); balkenStoppen(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (anteil > 0f) c.drawRect(0f, 0f, width * anteil, height.toFloat(), balken)
        if (isPressed && halteMs == 0L && isEnabled) { balken.color = if (art == Stilart.STOPP) 0x33000000 else Farbe.ACCENT_GLOW; c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), balken); balken.color = Farbe.HOLD_FILL }
    }

    override fun setPressed(pressed: Boolean) { super.setPressed(pressed); invalidate() }
}

/** Kleiner Knopf der Leiste: Kürzel in Versalien, Rahmen; [gewaehlt] = Teal mit Unterstrich. */
@SuppressLint("ViewConstructor")
internal class LeistenKnopf(private val stil: Stil, beschreibung: String, beiDruck: () -> Unit) : TextView(stil.c) {
    private var gewaehlt: Boolean? = null

    init {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11f); typeface = stil.monoMittel; isAllCaps = true; letterSpacing = 0.06f
        gravity = Gravity.CENTER; includeFontPadding = false; maxLines = 1
        contentDescription = beschreibung
        isClickable = true; isFocusable = true
        setOnClickListener { beiDruck() }
        setze("", false)
    }

    fun setze(text: String, gewaehlt: Boolean) {
        zeige(text)
        if (gewaehlt == this.gewaehlt) return
        this.gewaehlt = gewaehlt
        setTextColor(if (gewaehlt) Farbe.ACCENT else Farbe.TEXT)
        background = stil.flaeche(if (gewaehlt) Farbe.ACCENT_FILL else Farbe.PANEL, if (gewaehlt) Farbe.ACCENT else Farbe.PANEL_LINE)
    }
}
