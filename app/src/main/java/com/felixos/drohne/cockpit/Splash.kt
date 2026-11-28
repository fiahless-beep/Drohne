package com.felixos.drohne.cockpit

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.core.content.res.ResourcesCompat
import com.felixos.drohne.R

/**
 * Startbild „Wake“ (Felix 28.11.2026): zwei Sensoraugen gehen auf und flackern, Radarringe pulsen, die Arme
 * fahren aus, dann rückt das Zeichen hoch und der Name steht. Liegt über dem Cockpit, bis es sich selbst
 * entfernt; ein Tipp überspringt es. Die App startet dahinter ungebremst.
 */
internal class Splash(c: Context) : View(c) {
    private val d = resources.displayMetrics.density
    private val wort: Typeface = try { ResourcesCompat.getFont(c, R.font.archivo_expanded_extrabold) } catch (_: Throwable) { null } ?: Typeface.DEFAULT_BOLD
    private val mono: Typeface = try { ResourcesCompat.getFont(c, R.font.jetbrains_mono_medium) } catch (_: Throwable) { null } ?: Typeface.MONOSPACE
    private val fuell = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Farbe.ACCENT }
    private val strich = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Farbe.ACCENT; style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val schein = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pfad = Path()
    private var t = 0f
    private var lauf: ValueAnimator? = null

    companion object {
        private const val DAUER_MS = 2600L
        /** Legt das Startbild über die Activity und spielt es einmal. */
        fun zeigen(a: Activity) {
            val s = Splash(a)
            a.addContentView(s, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            s.elevation = 1000f
            s.start()
        }
    }

    init { isClickable = true; setOnClickListener { ende() } }

    private fun start() {
        lauf = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DAUER_MS; interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate(); if (t >= 1f) ende() }
            start()
        }
    }

    private fun ende() {
        lauf?.cancel(); lauf = null
        (parent as? ViewGroup)?.removeView(this)
    }

    override fun onDetachedFromWindow() { lauf?.cancel(); lauf = null; super.onDetachedFromWindow() }

    /** Anteil 0..1 des Abschnitts [a, b] der Gesamtzeit in Sekunden. */
    private fun ab(a: Float, b: Float): Float = ((t * DAUER_MS / 1000f - a) / (b - a)).coerceIn(0f, 1f)
    private fun weich(x: Float): Float = x * x * (3f - 2f * x)

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val aus = 1f - ab(2.3f, 2.6f)
        cv.drawColor(((0xFF * aus).toInt() shl 24))
        if (aus <= 0f) return
        val alpha = (255 * aus).toInt()
        val einheit = h / 360f                       // Entwurf: 800 x 360
        val hoch = weich(ab(1.25f, 1.85f))            // Zeichen rückt hoch und wird kleiner
        val mass = einheit * (2.6f + (1.25f - 2.6f) * hoch)
        val cx = w / 2f; val cy = h / 2f + (128f - 180f) * einheit * hoch

        // Schein hinter den Augen
        val glimmen = ab(0.45f, 1.0f)
        if (glimmen > 0f) {
            schein.shader = RadialGradient(cx, cy, 90f * mass, intArrayOf((0x48 * glimmen * aus).toInt() shl 24 or 0x1EE8B0, 0x001EE8B0), null, Shader.TileMode.CLAMP)
            cv.save(); cv.scale(1f, 0.52f, cx, cy); cv.drawCircle(cx, cy, 90f * mass, schein); cv.restore()
        }
        // Radarringe
        strich.strokeWidth = d
        for (i in 0..2) {
            val r = ab(0.7f + i * 0.28f, 1.9f + i * 0.28f)
            if (r <= 0f || r >= 1f) continue
            strich.alpha = (230 * (1f - r) * aus).toInt()
            cv.drawCircle(cx, cy, (0.2f + 0.8f * r) * 120f * mass, strich)
        }
        // Augen: gehen in zwei Stufen auf, flackern kurz
        val auf = ab(0.25f, 0.6f).let { if (it < 0.6f) it / 0.6f * 0.12f else 0.12f + (it - 0.6f) / 0.4f * 0.88f }
        val sek = t * DAUER_MS / 1000f
        val flackern = if (sek in 0.68f..0.74f) 0.2f else if (sek in 0.82f..0.88f) 0.35f else 1f
        if (auf > 0f) {
            fuell.alpha = (alpha * flackern).toInt()
            for (seite in intArrayOf(-1, 1)) {
                pfad.rewind()
                fun p(x: Float, y: Float, erster: Boolean = false) { val px = cx + seite * x * mass; val py = cy + (-2f + (y + 2f) * auf) * mass; if (erster) pfad.moveTo(px, py) else pfad.lineTo(px, py) }
                p(34f, -12f, true); p(7f, -2f); p(7f, 8f); p(30f, -2f); pfad.close()
                cv.drawPath(pfad, fuell)
            }
        }
        // Rotorarme fahren aus
        val arm = ab(0.85f, 1.2f)
        if (arm > 0f) {
            strich.strokeWidth = 3.5f * mass; strich.alpha = alpha
            for (seite in intArrayOf(-1, 1)) {
                val x0 = cx + seite * 18f * mass; val y0 = cy - 10f * mass
                val x1 = cx + seite * 32f * mass; val y1 = cy - 24f * mass
                val k = (arm * 2f).coerceAtMost(1f)
                cv.drawLine(x0, y0, x0 + (x1 - x0) * k, y0 + (y1 - y0) * k, strich)
                if (arm > 0.5f) { val b = (arm - 0.5f) * 2f * 12f * mass; cv.drawLine(x1 - b, y1, x1 + b, y1, strich) }
            }
        }
        // Wortmarke: Sperrung zieht sich zusammen
        val name = ab(1.5f, 2.0f)
        if (name > 0f) {
            text.typeface = wort; text.textSize = 70f * einheit; text.color = 0xF1F3F1 or ((alpha * name).toInt() shl 24)
            text.letterSpacing = 0.5f * (1f - weich(name)) - 0.02f; text.textAlign = Paint.Align.CENTER
            cv.drawText("SKYTAC", w / 2f, h / 2f + (252f - 180f) * einheit, text)
        }
        val zeile = ab(1.9f, 2.2f)
        if (zeile > 0f) {
            text.typeface = mono; text.textSize = 11f * einheit; text.color = 0x8A9A93 or ((alpha * zeile).toInt() shl 24)
            text.letterSpacing = 0.16f; text.textAlign = Paint.Align.CENTER
            cv.drawText("AI MISSION CONTROL", w / 2f, h / 2f + (286f - 180f) * einheit, text)
        }
    }
}
