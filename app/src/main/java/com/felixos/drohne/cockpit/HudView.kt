package com.felixos.drohne.cockpit

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.felixos.drohne.CockpitZustand

/**
 * HUD über der Bildfläche: Eckmarken, Fadenkreuz, Kompassband, kleine Bahnkarte (Norden oben, ohne Koordinaten).
 * Zeichnet nur; Berührungen gehen an Livebild und Karte darunter durch.
 */
@SuppressLint("ViewConstructor")
internal class HudView(private val stil: Stil) : View(stil.c) {
    /** Höhe der Leisten oben (Status- und Warnzeile), unter denen das HUD beginnt. */
    var obenPx = 0; set(v) { if (v != field) { field = v; invalidate() } }
    /** Fadenkreuz und Eckmarken: nur über dem Livebild. */
    var mitFadenkreuz = true; set(v) { if (v != field) { field = v; invalidate() } }
    /** Abstrakte Bahnkarte: nicht nötig, wenn die echte Karte gezeigt wird. */
    var mitBahn = true; set(v) { if (v != field) { field = v; invalidate() } }

    private var kurs: Double? = null
    private var bahn: CockpitZustand.Bahn? = null

    private val linie = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Farbe.ACCENT }
    private val flaeche = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val schrift = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val pfad = Path()
    private val r = RectF()
    private val strichel = DashPathEffect(floatArrayOf(stil.dp(3f), stil.dp(3f)), 0f)

    fun zeige(z: CockpitZustand) {
        val k = if (z.datenDa) z.kursGrad else null
        val b = z.bahn
        if (k == kurs && b == null && bahn == null) return
        kurs = k; bahn = b
        invalidate()
    }

    private fun schatten(p: Paint, an: Boolean) { if (an) p.setShadowLayer(stil.dp(2f), 0f, 0f, Farbe.HUD_SHADOW) else p.clearShadowLayer() }

    override fun onDraw(c: Canvas) {
        val links = stil.dp(150f)
        val oben = obenPx.toFloat()
        val w = width.toFloat(); val h = height.toFloat()
        if (w - links < stil.dp(120f) || h - oben < stil.dp(100f)) return
        val mitteX = links + (w - links) / 2
        if (mitFadenkreuz) { ecken(c, links + stil.dp(8f), oben + stil.dp(8f), w - stil.dp(8f), h - stil.dp(8f)); fadenkreuz(c, mitteX, oben + (h - oben) * 0.44f, minOf(stil.dp(124f), (h - oben) * 0.5f)) }
        band(c, mitteX, oben + stil.dp(10f))
        if (mitBahn) karte(c, w - stil.dp(14f + 120f), h - stil.dp(14f + 84f))
    }

    private fun ecken(c: Canvas, l: Float, t: Float, rr: Float, b: Float) {
        val a = stil.dp(14f)
        linie.color = Farbe.ACCENT; linie.strokeWidth = stil.dp(2f); linie.pathEffect = null; schatten(linie, true)
        pfad.reset()
        pfad.moveTo(l, t + a); pfad.lineTo(l, t); pfad.lineTo(l + a, t)
        pfad.moveTo(rr - a, t); pfad.lineTo(rr, t); pfad.lineTo(rr, t + a)
        pfad.moveTo(l, b - a); pfad.lineTo(l, b); pfad.lineTo(l + a, b)
        pfad.moveTo(rr - a, b); pfad.lineTo(rr, b); pfad.lineTo(rr, b - a)
        c.drawPath(pfad, linie)
    }

    /** Maße aus dem Prototyp (Zeichenfläche 150 × 150). */
    private fun fadenkreuz(c: Canvas, cx: Float, cy: Float, groesse: Float) {
        val s = groesse / 150f
        c.save(); c.translate(cx - groesse / 2, cy - groesse / 2); c.scale(s, s)
        schatten(linie, true)
        linie.color = Farbe.ACCENT_DIM; linie.strokeWidth = 1.6f
        linie.pathEffect = DashPathEffect(floatArrayOf(58f, 11.1f), 0f)
        c.save(); c.rotate(-39f, 75f, 75f); c.drawCircle(75f, 75f, 44f, linie); c.restore()
        linie.pathEffect = null
        for (x in floatArrayOf(55f, 87f)) { c.drawLine(x, 20f, x + 8f, 20f, linie); c.drawLine(x, 130f, x + 8f, 130f, linie) }
        for (x in floatArrayOf(55f, 90f)) { c.drawLine(x, 34f, x + 5f, 34f, linie); c.drawLine(x, 116f, x + 5f, 116f, linie) }
        linie.color = Farbe.ACCENT; linie.strokeWidth = 1.4f
        c.drawLine(75f, 6f, 75f, 46f, linie); c.drawLine(75f, 104f, 75f, 144f, linie); c.drawLine(6f, 75f, 46f, 75f, linie); c.drawLine(104f, 75f, 144f, 75f, linie)
        c.drawLine(63f, 75f, 69f, 75f, linie); c.drawLine(81f, 75f, 87f, 75f, linie); c.drawLine(75f, 63f, 75f, 69f, linie); c.drawLine(75f, 81f, 75f, 87f, linie)
        flaeche.color = Farbe.ACCENT; c.drawCircle(75f, 75f, 1.8f, flaeche)
        c.restore()
    }

    /** Kompassband: 2 dp je Grad, Strich alle 5°, lang alle 15°, Himmelsrichtung oder Gradzahl alle 30°/45°. */
    private fun band(c: Canvas, cx: Float, t: Float) {
        val b = minOf(stil.dp(190f), width - stil.dp(150f) - stil.dp(16f)); val hoehe = stil.dp(28f)
        r.set(cx - b / 2, t, cx + b / 2, t + hoehe)
        flaeche.color = Farbe.PANEL_A; c.drawRect(r, flaeche)
        linie.pathEffect = null; schatten(linie, false); linie.color = Farbe.PANEL_LINE; linie.strokeWidth = stil.dp(1f); c.drawRect(r, linie)
        val k = kurs
        schrift.typeface = stil.monoMittel; schrift.textSize = stil.px(10f); schrift.color = Farbe.TEXT
        if (k != null) {
            c.save(); c.clipRect(r)
            val jeGrad = stil.dp(2f)
            var d = Math.ceil((k - 52) / 5).toInt() * 5
            while (d <= k + 52) {
                val x = cx + ((d - k) * jeGrad).toFloat()
                val n = ((d % 360) + 360) % 360
                val lang = n % 15 == 0
                linie.color = if (lang) Farbe.ACCENT else Farbe.ACCENT_DIM
                c.drawLine(x, t, x, t + stil.dp(if (lang) 8f else 4f), linie)
                if ((n % 45 == 0 || n % 30 == 0) && Math.abs(d - k) * 2 > 32)
                    c.drawText(if (n % 45 == 0) arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[n / 45] else String.format(java.util.Locale.US, "%03d", n), x, t + hoehe - stil.dp(3f), schrift)
                d += 5
            }
            c.restore()
        }
        val halb = stil.dp(if (k != null) 22f else 11f)
        r.set(cx - halb, t, cx + halb, t + hoehe)
        flaeche.color = Farbe.BG; c.drawRect(r, flaeche)
        linie.color = Farbe.ACCENT; c.drawLine(r.left, t, r.left, t + hoehe, linie); c.drawLine(r.right, t, r.right, t + hoehe, linie)
        schrift.typeface = stil.monoFett; schrift.textSize = stil.px(13f); schrift.color = Farbe.ACCENT
        c.drawText(if (k != null) String.format(java.util.Locale.US, "%03d°", ((Math.round(k).toInt() % 360) + 360) % 360) else "–", cx, t + hoehe / 2 + stil.px(13f) * 0.36f, schrift)
    }

    private fun karte(c: Canvas, l: Float, t: Float) {
        val b = stil.dp(120f); val hoehe = stil.dp(84f)
        r.set(l, t, l + b, t + hoehe)
        flaeche.color = Farbe.PANEL_A; c.drawRect(r, flaeche)
        linie.pathEffect = null; schatten(linie, false); linie.strokeWidth = stil.dp(1f); linie.color = Farbe.GRID
        var g = stil.dp(12f)
        while (g < b) { c.drawLine(l + g, t, l + g, t + hoehe, linie); g += stil.dp(12f) }
        g = stil.dp(12f)
        while (g < hoehe) { c.drawLine(l, t + g, l + b, t + g, linie); g += stil.dp(12f) }
        linie.color = Farbe.PANEL_LINE; c.drawRect(r, linie)
        schrift.typeface = stil.monoFett; schrift.textSize = stil.px(10f); schrift.color = Farbe.TEXT
        c.drawText("N↑", l + stil.dp(11f), t + stil.dp(12f), schrift)
        val ba = bahn ?: return
        if (ba.punkte.isEmpty()) return
        // Alles, was gezeigt wird, in den Kasten einpassen (gleicher Maßstab in beiden Richtungen).
        var minO = Float.MAX_VALUE; var maxO = -Float.MAX_VALUE; var minN = Float.MAX_VALUE; var maxN = -Float.MAX_VALUE
        fun nimm(p: CockpitZustand.Bahn.Punkt?) { if (p == null) return; minO = minOf(minO, p.ostM); maxO = maxOf(maxO, p.ostM); minN = minOf(minN, p.nordM); maxN = maxOf(maxN, p.nordM) }
        ba.punkte.forEach(::nimm); nimm(ba.heim); nimm(ba.drohne)
        val rand = stil.dp(12f)
        val m = minOf((b - 2 * rand) / maxOf(maxO - minO, 1f), (hoehe - 2 * rand) / maxOf(maxN - minN, 1f))
        val ox = l + b / 2 - (minO + maxO) / 2 * m; val oy = t + hoehe / 2 + (minN + maxN) / 2 * m
        fun x(p: CockpitZustand.Bahn.Punkt) = ox + p.ostM * m
        fun y(p: CockpitZustand.Bahn.Punkt) = oy - p.nordM * m
        c.save(); c.clipRect(r)
        val bis = ba.erledigtBis.coerceIn(-1, ba.punkte.size - 1)
        fun strecke(von: Int, nach: Int) { pfad.reset(); for (i in von..nach) { val p = ba.punkte[i]; if (i == von) pfad.moveTo(x(p), y(p)) else pfad.lineTo(x(p), y(p)) } }
        if (bis < ba.punkte.size - 1) { strecke(maxOf(bis, 0), ba.punkte.size - 1); linie.color = Farbe.ACCENT_DIM; linie.strokeWidth = stil.dp(1.4f); linie.pathEffect = strichel; c.drawPath(pfad, linie) }
        linie.pathEffect = null
        if (bis > 0) { strecke(0, bis); linie.color = Farbe.ACCENT; linie.strokeWidth = stil.dp(1.6f); c.drawPath(pfad, linie) }
        // Wegpunkte als Punkte; bei sehr vielen nur jeden n-ten, damit die Bahn lesbar bleibt.
        val schritt = maxOf(1, ba.punkte.size / 24)
        for (i in ba.punkte.indices step schritt) { val p = ba.punkte[i]; flaeche.color = if (i <= bis) Farbe.ACCENT else Farbe.ACCENT_DIM; c.drawCircle(x(p), y(p), stil.dp(1.8f), flaeche) }
        ba.heim?.let { hp ->
            val hx = x(hp); val hy = y(hp); val e = stil.dp(5f)
            pfad.reset(); pfad.moveTo(hx, hy - e); pfad.lineTo(hx + e, hy - e * 0.2f); pfad.lineTo(hx + e, hy + e); pfad.lineTo(hx - e, hy + e); pfad.lineTo(hx - e, hy - e * 0.2f); pfad.close()
            flaeche.color = Farbe.BG; c.drawPath(pfad, flaeche)
            linie.color = Farbe.TEXT; linie.strokeWidth = stil.dp(1.3f); c.drawPath(pfad, linie)
        }
        val dr = ba.drohne
        if (dr != null && kurs != null) {
            val e = stil.dp(1f)
            c.save(); c.translate(x(dr), y(dr)); c.rotate(kurs!!.toFloat())
            pfad.reset(); pfad.moveTo(0f, -8 * e); pfad.lineTo(5 * e, 5 * e); pfad.lineTo(0f, 2 * e); pfad.lineTo(-5 * e, 5 * e); pfad.close()
            flaeche.color = Farbe.ACCENT; c.drawPath(pfad, flaeche)
            linie.color = Farbe.BG; linie.strokeWidth = stil.dp(0.8f); c.drawPath(pfad, linie)
            c.restore()
        }
        c.restore()
    }
}
