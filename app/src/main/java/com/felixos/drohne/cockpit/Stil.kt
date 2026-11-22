package com.felixos.drohne.cockpit

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.felixos.drohne.R

/** Farbtokens der Art-Direction „Military Tactical“ (22.11.2026). Dieselben Werte stehen für XML in res/values/colors.xml. */
internal object Farbe {
    const val BG = 0xFF05080A.toInt()
    const val PANEL = 0xFF0B1012.toInt()
    const val PANEL_A = 0xE00B1012.toInt()
    const val PANEL_LINE = 0xFF1C262B.toInt()
    const val GRID = 0xFF121A1E.toInt()
    const val ACCENT = 0xFF1EE8B0.toInt()
    const val ACCENT_DIM = 0xFF15826A.toInt()
    const val ACCENT_GLOW = 0x2E1EE8B0
    const val ACCENT_FILL = 0x171EE8B0
    const val TEXT = 0xFFD7E2DC.toInt()
    const val TEXT_MUTED = 0xFF6E7E78.toInt()
    const val TEXT_DIM = 0xFF8A9A93.toInt()
    const val DECO = 0xFF2E3A36.toInt()
    const val WARN = 0xFFFF7A1A.toInt()
    const val WARN_TEXT = 0xFFFFB27A.toInt()
    const val WARN_PANEL = 0xFF2A1708.toInt()
    const val STOP = 0xFFFF3B30.toInt()
    const val STOP_PRESS = 0xFFD92F26.toInt()
    const val HUD_SHADOW = 0xE6020405.toInt()
    const val HOLD_FILL = 0x47D7E2DC
}

/** Tonlage eines Status: nie nur Farbe, immer auch Zeichenform und Klartext (siehe [Abzeichen]). */
internal enum class Ton { OK, WARN, AUS, NEUTRAL }

/** Schriften, Maße und kleine Bausteine für alle Cockpit-Flächen. */
internal class Stil(val c: Context) {
    private val d = c.resources.displayMetrics.density
    val mono: Typeface = lade(R.font.jetbrains_mono_regular, Typeface.MONOSPACE)
    val monoMittel: Typeface = lade(R.font.jetbrains_mono_medium, Typeface.MONOSPACE)
    val monoFett: Typeface = lade(R.font.jetbrains_mono_bold, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD))
    val sans: Typeface = lade(R.font.inter_regular, Typeface.SANS_SERIF)
    val sansHalbfett: Typeface = lade(R.font.inter_semibold, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD))

    private fun lade(id: Int, ersatz: Typeface): Typeface = try { ResourcesCompat.getFont(c, id) ?: ersatz } catch (_: Throwable) { ersatz }

    fun dp(v: Float): Float = v * d
    fun dp(v: Int): Int = Math.round(v * d)
    /** Schriftgrad in Pixeln für Canvas; folgt der Schriftgröße des Systems nicht (feste Instrumentenmaße). */
    fun px(spAlsDp: Float): Float = spAlsDp * d

    fun text(grad: Float, schrift: Typeface, farbe: Int, versalien: Boolean = false, sperrung: Float = 0f, zeilen: Int = 1): TextView = TextView(c).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, grad)
        typeface = schrift
        setTextColor(farbe)
        isAllCaps = versalien
        letterSpacing = sperrung
        includeFontPadding = false
        maxLines = zeilen
        ellipsize = TextUtils.TruncateAt.END
        fontFeatureSettings = "tnum"
    }

    /** Versalien-Label (ALT · START, BATTERY …). */
    fun label(t: String, farbe: Int = Farbe.TEXT): TextView = text(12f, monoMittel, farbe, versalien = true, sperrung = 0.06f).apply { text = t }

    fun flaeche(fuellung: Int, linie: Int = Farbe.PANEL_LINE, strich: Int = 1): Drawable = GradientDrawable().apply {
        setColor(fuellung); cornerRadius = dp(2f); if (strich > 0) setStroke(dp(strich), linie)
    }

    fun spalte(): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    fun zeile(): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun luecke(): View = View(c)

    fun lp(w: Int, h: Int, gewicht: Float = 0f, oben: Int = 0, links: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, gewicht).apply { topMargin = dp(oben); leftMargin = dp(links) }

    companion object {
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    }
}

/** Text nur setzen, wenn er sich geändert hat (die Anzeige wird mehrmals je Sekunde neu gefüllt). */
internal fun TextView.zeige(t: CharSequence) { if (text.toString() != t.toString()) text = t }
internal fun TextView.farbe(f: Int) { if (currentTextColor != f) setTextColor(f) }
internal fun View.sichtbar(an: Boolean) { val v = if (an) View.VISIBLE else View.GONE; if (visibility != v) visibility = v }
