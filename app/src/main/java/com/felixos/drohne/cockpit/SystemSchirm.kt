package com.felixos.drohne.cockpit

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.PathParser
import com.felixos.drohne.CockpitZustand
import java.util.Locale

/**
 * System-Bildschirm („Lagebild“) nach dem Prototyp: links Selbsttest und Akku, in der Mitte Draufsicht und
 * Höhenverlauf, rechts Verbindung, GPS/Sensoren und Umgebung. Zeigt nur, was der Flugkern wirklich meldet.
 */
internal class SystemSchirm(private val stil: Stil, wurzel: FrameLayout, private val gast: Gastgeber) {
    private val selbst = Kachel(stil, "Self test", mitStatus = false)
    private val akku = Kachel(stil, "Battery")
    private val akkuZahl = stil.text(28f, stil.monoFett, Farbe.ACCENT)
    private val akkuRest = stil.text(12f, stil.monoMittel, Farbe.TEXT)
    private val akkuZellen = Zellen(stil, 10)
    private val planBraucht = akku_zeile("Plan needs")
    private val reserve = akku_zeile("Reserve")
    private val draufsicht = Kachel(stil, "Top view")
    private val draht = DrahtView(stil)
    private val verlauf = Kachel(stil, "ALT · START · 60 s", mitStatus = false)
    private val verlaufJetzt = stil.label("–")
    private val kurve = KurveView(stil)
    private val link = Kachel(stil, "Link")
    private val geraet = link.zeile("Drone")
    private val fernst = link.zeile("Controller")
    private val livebild = link.zeile("Live view")
    private val alter = link.zeile("Last data")
    private val gps = Kachel(stil, "GPS · Sensors")
    private val sat = gps.zeile("Satellites")
    private val heim = gps.zeile("Home point")
    private val hindernis = gps.zeile("Obstacle sens.")
    private val waechter = gps.zeile("Flight guards")
    private val bild = gps.zeile("Image guard")
    private val umwelt = Kachel(stil, "Environment")
    private val wind = umwelt.zeile("Wind")
    private val tag = umwelt.zeile("Daylight")
    private val limit = umwelt.zeile("Height limit")
    private val transit = umwelt.zeile("Transit height")
    private val rueck = umwelt.zeile("Return height")
    private val vorhersage = stil.text(11f, stil.sans, Farbe.TEXT_DIM, zeilen = 3)

    private fun akku_zeile(name: String) = WertZeile(stil, name)

    init {
        val reihe = stil.zeile().apply { setBackgroundColor(Farbe.BG); gravity = Gravity.FILL_VERTICAL; isClickable = true }
        val l = stil.spalte(); val m = stil.spalte(); val r = stil.spalte()

        selbst.addView(stil.text(12f, stil.sans, Farbe.TEXT_DIM, zeilen = 3).apply { text = "Sensors and DJI health messages. Result opens in a dialog." })
        selbst.addView(LeistenKnopf(stil, "Run self test") { gast.selbsttest() }.apply { setze("Run self test", false) }, stil.lp(Stil.MATCH, stil.dp(36), oben = 6))
        l.addView(selbst, stil.lp(Stil.MATCH, Stil.WRAP))
        val gross = stil.zeile().apply { gravity = Gravity.BOTTOM }
        gross.addView(akkuZahl); gross.addView(akkuRest, LinearLayout.LayoutParams(Stil.WRAP, Stil.WRAP).apply { leftMargin = stil.dp(6); bottomMargin = stil.dp(4) })
        akku.addView(gross)
        akku.addView(akkuZellen, stil.lp(Stil.MATCH, stil.dp(6), oben = 3))
        akku.addView(planBraucht, stil.lp(Stil.MATCH, Stil.WRAP, oben = 4)); akku.addView(reserve, stil.lp(Stil.MATCH, Stil.WRAP))
        l.addView(akku, stil.lp(Stil.MATCH, 0, 1f, oben = 6))

        draufsicht.addView(draht, stil.lp(Stil.MATCH, 0, 1f))
        m.addView(draufsicht, stil.lp(Stil.MATCH, 0, 1f))
        (verlauf.getChildAt(0) as LinearLayout).addView(verlaufJetzt)
        verlauf.addView(kurve, stil.lp(Stil.MATCH, 0, 1f))
        m.addView(verlauf, stil.lp(Stil.MATCH, stil.dp(92), oben = 6))

        r.addView(link, stil.lp(Stil.MATCH, Stil.WRAP))
        r.addView(gps, stil.lp(Stil.MATCH, Stil.WRAP, oben = 6))
        umwelt.addView(vorhersage, stil.lp(Stil.MATCH, Stil.WRAP, oben = 3))
        r.addView(umwelt, stil.lp(Stil.MATCH, 0, 1f, oben = 6))

        reihe.addView(l, stil.lp(stil.dp(204), Stil.MATCH))
        reihe.addView(m, LinearLayout.LayoutParams(0, Stil.MATCH, 1f).apply { leftMargin = stil.dp(6); rightMargin = stil.dp(6) })
        reihe.addView(r, stil.lp(stil.dp(212), Stil.MATCH))
        wurzel.addView(reihe, FrameLayout.LayoutParams(-1, -1))
    }

    /** [hoehen] = Höhe über Start je Sekunde, ältester Wert zuerst, NaN = kein Wert. */
    fun zeige(z: CockpitZustand, hoehen: FloatArray, windText: Pair<String?, Ton>) {
        val da = z.datenDa
        val verbunden = z.verbindung == CockpitZustand.Verbindung.VERBUNDEN
        fun <T> d(v: T?): T? = if (da) v else null

        val b = d(z.akkuProzent)
        val akkuWarn = b != null && (z.akkuStufe == CockpitZustand.Stufe.NIEDRIG || z.akkuStufe == CockpitZustand.Stufe.WARNUNG)
        akku.warnung(akkuWarn)
        when { b == null -> akku.status.setze(Ton.AUS, "–"); z.akkuStufe == CockpitZustand.Stufe.NIEDRIG -> akku.status.setze(Ton.WARN, "WARN · LOW"); akkuWarn -> akku.status.setze(Ton.WARN, "WARN"); else -> akku.status.setze(Ton.OK, "OK") }
        akkuZahl.zeige(if (b != null) "$b %" else "–"); akkuZahl.farbe(when { b == null -> Farbe.TEXT_DIM; akkuWarn -> Farbe.WARN_TEXT; else -> Farbe.ACCENT })
        akkuRest.zeige(if (b != null && z.restMin != null) "≈${z.restMin} min left" else "")
        akkuZellen.setze(if (b != null) Math.round(b / 10f) else 0, if (akkuWarn) Farbe.WARN else Farbe.ACCENT)
        val braucht = z.plan.brauchtProzent
        planBraucht.wert(braucht?.let { "$it %" }, if (b != null && braucht != null && b < braucht) Ton.WARN else Ton.NEUTRAL)
        reserve.wert("${z.plan.reserveProzent} %")

        val schwebt = z.phase == CockpitZustand.Phase.PAUSIERT
        when { !da -> draufsicht.status.setze(Ton.AUS, "–"); schwebt -> draufsicht.status.setze(Ton.NEUTRAL, z.phaseText, Zeichen.Art.PAUSE); else -> draufsicht.status.setze(Ton.OK, z.phaseText) }
        val se = d(z.sensoren)
        draht.setze(if (se == null) null else z.sensorenOk, if (se == null) "SENS –" else if (z.sensorenOk) "SENS ON" else "! SENS " + se.uppercase(Locale.US))
        kurve.setze(hoehen, da)
        verlaufJetzt.zeige(d(z.hoeheM)?.let { "${Math.round(it)} m" } ?: "–")

        val linkWarn = z.funkabriss || z.keineDatenSeitS != null
        link.warnung(linkWarn)
        when { z.funkabriss -> link.status.setze(Ton.WARN, "LINK LOST"); z.keineDatenSeitS != null -> link.status.setze(Ton.WARN, "NO DATA"); verbunden -> link.status.setze(Ton.OK, "LINK OK"); else -> link.status.setze(Ton.AUS, "NO LINK") }
        geraet.wert(if (z.simulation) "simulation" else if (verbunden) (z.geraet ?: "connected") else null)
        fernst.wert(when (z.verbindung) { CockpitZustand.Verbindung.VERBUNDEN -> "connected"; CockpitZustand.Verbindung.NUR_FERNSTEUERUNG -> "RC only"; CockpitZustand.Verbindung.SDK_FEHLER -> "SDK error"; CockpitZustand.Verbindung.SDK_STARTET -> "SDK starting"; else -> null },
            if (z.verbindung == CockpitZustand.Verbindung.NUR_FERNSTEUERUNG || z.verbindung == CockpitZustand.Verbindung.SDK_FEHLER) Ton.WARN else Ton.NEUTRAL)
        livebild.wert(if (!verbunden) null else if (gast.livebildAn) "on" else "off")
        val a = z.letzteDatenS
        alter.wert(if (z.funkabriss || a == null) null else if (a >= 10) "${a.toInt()} s" else String.format(Locale.US, "%.1f s", a), if (z.keineDatenSeitS != null) Ton.WARN else Ton.NEUTRAL)

        val s = d(z.satelliten)
        val gpsWarn = da && (!z.gpsOk || (se != null && !z.sensorenOk))
        gps.warnung(se != null && !z.sensorenOk)
        when { !da -> gps.status.setze(Ton.AUS, "–"); gpsWarn -> gps.status.setze(Ton.WARN, "WARN"); else -> gps.status.setze(Ton.OK, "OK") }
        sat.wert(s?.toString(), if (s != null && !z.gpsOk && z.heimGesetzt) Ton.WARN else if (s != null) Ton.OK else Ton.NEUTRAL)
        heim.wert(if (!da) null else if (z.heimGesetzt) "set" else "not set", if (da && !z.heimGesetzt) Ton.WARN else Ton.NEUTRAL)
        hindernis.wert(se, if (se != null && !z.sensorenOk) Ton.WARN else Ton.NEUTRAL)
        waechter.wert(if (!z.waechter.flugwaechter) "OFF" else if (z.waechter.duerfenPausieren) "on · may pause" else "on · observe", if (!z.waechter.flugwaechter) Ton.WARN else Ton.NEUTRAL)
        bild.wert(if (!z.waechter.bildwaechter) "OFF" else if (z.waechter.bildwaechterDarfPausieren) "on · may pause" else "on · observe")

        val windWarn = windText.second == Ton.WARN
        umwelt.warnung(windWarn)
        when { !da -> umwelt.status.setze(Ton.AUS, "–"); windWarn || z.tageslicht == false -> umwelt.status.setze(Ton.WARN, "WARN"); else -> umwelt.status.setze(Ton.OK, "OK") }
        wind.wert(windText.first, windText.second)
        tag.wert(z.tageslicht?.let { if (it) "yes" else "no" }, if (z.tageslicht == false) Ton.WARN else Ton.NEUTRAL)
        limit.wert(d(z.hoehenlimitM)?.let { "$it m" })
        transit.wert("${z.plan.transitHoeheM} m")
        rueck.wert(z.plan.rueckkehrHoeheM?.let { "$it m" })
        vorhersage.zeige(z.wind.vorhersage ?: "Wind forecast: – (comes with Get plan)")
    }
}

/** Draufsicht der Drohne als Drahtgitter (Zeichnung aus dem Prototyp, Fläche 300 × 150); vorn der Sensorbogen. */
@SuppressLint("ViewConstructor")
internal class DrahtView(private val stil: Stil) : View(stil.c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tx = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strichel = DashPathEffect(floatArrayOf(3f, 3f), 0f)
    private fun pfad(d: String): Path = PathParser.createPathFromPathData(d)
    private val arme = pfad("M138 58L107 46l-3-6M162 58l31-12 3-6M139 94l-38 16-3 6M161 94l38 16 3 6")
    private val armeDim = pfad("M138 64l-32-13M162 64l32-13M140 88l-40 18M160 88l40 18")
    private val rumpf = pfad("M142 30h16l7 9 3 20v30l-4 18-6 8h-16l-6-8-4-18V59l3-20z")
    private val gitter = pfad("M142 30l8 16 8-16M135 39l15 7 15-7M132 59l18-13 18 13M132 59l18 15 18-15M132 89l18-15 18 15M136 107l14-33 14 33M142 115l8-14 8 14M150 46v69M132 74h36")
    private val kamera = pfad("M145 30v-6h10v6M147 24v-4h6v4")
    private val bogen = pfad("M134 14q16-10 32 0")
    private val hinweise = pfad("M78 44H52M222 44h26M72 114H52M228 114h20M168 10h36")
    private val motoren = arrayOf(104f to 44f, 196f to 44f, 98f to 114f, 202f to 114f)
    private var sensorOk: Boolean? = null
    private var sensorText = "SENS –"

    fun setze(ok: Boolean?, text: String) { if (ok == sensorOk && text == sensorText) return; sensorOk = ok; sensorText = text; invalidate() }

    override fun onDraw(c: Canvas) {
        val raster = stil.dp(16f)
        p.pathEffect = null; p.strokeWidth = stil.dp(1f); p.color = Farbe.GRID
        var g = (width % raster) / 2; while (g < width) { c.drawLine(g, 0f, g, height.toFloat(), p); g += raster }
        g = (height % raster) / 2; while (g < height) { c.drawLine(0f, g, width.toFloat(), g, p); g += raster }
        val s = minOf(width / 300f, height / 150f)
        if (s <= 0f) return
        c.save(); c.translate((width - 300 * s) / 2, (height - 150 * s) / 2); c.scale(s, s)
        p.color = Farbe.ACCENT_DIM; p.strokeWidth = 1.1f; p.pathEffect = strichel
        motoren.forEach { (x, y) -> c.drawCircle(x, y, 22f, p) }
        p.pathEffect = null; p.strokeWidth = 0.8f; c.drawPath(armeDim, p)
        f.color = Farbe.ACCENT_GLOW; c.drawPath(rumpf, f)
        c.drawPath(gitter, p)
        p.color = Farbe.ACCENT; p.strokeWidth = 1.1f
        c.drawPath(arme, p); c.drawPath(rumpf, p); c.drawPath(kamera, p)
        motoren.forEach { (x, y) -> c.drawCircle(x, y, 5f, p) }
        if (sensorOk == false) { p.color = Farbe.WARN; p.strokeWidth = 2f } else if (sensorOk == null) p.color = Farbe.ACCENT_DIM
        c.drawPath(bogen, p)
        p.color = Farbe.TEXT_MUTED; p.strokeWidth = 0.8f; c.drawPath(hinweise, p)
        tx.typeface = stil.monoMittel; tx.textSize = 8f; tx.color = Farbe.TEXT
        tx.textAlign = Paint.Align.RIGHT; c.drawText("M1 FL", 48f, 47f, tx); c.drawText("M3 RL", 48f, 117f, tx)
        tx.textAlign = Paint.Align.LEFT; c.drawText("M2 FR", 252f, 47f, tx); c.drawText("M4 RR", 252f, 117f, tx)
        tx.color = when (sensorOk) { true -> Farbe.ACCENT; false -> Farbe.WARN_TEXT; null -> Farbe.TEXT_DIM }
        c.drawText(sensorText, 208f, 13f, tx)
        c.restore()
    }
}

/** Höhe über Start der letzten 60 Sekunden: Linie, Fläche, Endpunkt mit Wert; eine Skala für alles. */
@SuppressLint("ViewConstructor")
internal class KurveView(private val stil: Stil) : View(stil.c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tx = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linie = Path(); private val flaeche = Path()
    private var werte = FloatArray(0)
    private var daten = false

    fun setze(w: FloatArray, datenDa: Boolean) { werte = w; daten = datenDa; invalidate() }

    override fun onDraw(c: Canvas) {
        val l = stil.dp(24f); val r = width - stil.dp(32f); val t = stil.dp(4f); val b = height - stil.dp(14f)
        if (r <= l || b <= t) return
        val gueltig = werte.filter { !it.isNaN() }
        val max = maxOf(60f, Math.ceil((gueltig.maxOrNull() ?: 0f) / 30.0).toFloat() * 30f)
        fun y(v: Float) = b - (v.coerceIn(0f, max) / max) * (b - t)
        p.style = Paint.Style.STROKE; p.strokeWidth = stil.dp(1f); p.color = Farbe.GRID
        c.drawLine(l, t, r, t, p); c.drawLine(l, (t + b) / 2, r, (t + b) / 2, p)
        p.color = Farbe.PANEL_LINE; c.drawLine(l, t, l, b, p); c.drawLine(l, b, r, b, p)
        tx.typeface = stil.mono; tx.textSize = stil.px(10f); tx.color = Farbe.TEXT_DIM; tx.textAlign = Paint.Align.RIGHT
        val halb = stil.px(10f) * 0.36f
        c.drawText(max.toInt().toString(), l - stil.dp(4f), t + halb * 2, tx); c.drawText((max / 2).toInt().toString(), l - stil.dp(4f), (t + b) / 2 + halb, tx); c.drawText("0", l - stil.dp(4f), b + halb, tx)
        tx.textAlign = Paint.Align.LEFT; c.drawText("-60 s", l, height - stil.dp(2f), tx)
        tx.textAlign = Paint.Align.RIGHT; c.drawText("now", r, height - stil.dp(2f), tx)
        val n = werte.size
        if (n >= 2) {
            // Lücken (kein Wert) unterbrechen die Linie, statt Werte zu erfinden.
            linie.reset(); flaeche.reset()
            var offen = false; var startX = 0f; var letztX = 0f
            for (i in 0 until n) {
                val v = werte[i]; val x = l + (r - l) * i / (n - 1)
                if (v.isNaN()) { if (offen) { flaeche.lineTo(letztX, b); flaeche.lineTo(startX, b); flaeche.close(); offen = false }; continue }
                if (!offen) { linie.moveTo(x, y(v)); flaeche.moveTo(x, y(v)); startX = x; offen = true } else { linie.lineTo(x, y(v)); flaeche.lineTo(x, y(v)) }
                letztX = x
            }
            if (offen) { flaeche.lineTo(letztX, b); flaeche.lineTo(startX, b); flaeche.close() }
            val deck = if (daten) 255 else 64
            p.style = Paint.Style.FILL; p.color = Farbe.ACCENT_FILL; p.alpha = if (daten) 0x17 else 0x08; c.drawPath(flaeche, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = stil.dp(1.5f); p.color = Farbe.ACCENT; p.alpha = deck; c.drawPath(linie, p); p.alpha = 255
        }
        val letzter = werte.lastOrNull()
        if (daten && letzter != null && !letzter.isNaN()) {
            val e = stil.dp(3.5f)
            p.style = Paint.Style.FILL; p.color = Farbe.ACCENT_GLOW; c.drawRect(r - 2 * e, y(letzter) - 2 * e, r + 2 * e, y(letzter) + 2 * e, p)
            p.color = Farbe.ACCENT; c.drawRect(r - e, y(letzter) - e, r + e, y(letzter) + e, p)
            tx.typeface = stil.monoFett; tx.textSize = stil.px(11f); tx.color = Farbe.ACCENT; tx.textAlign = Paint.Align.LEFT
            c.drawText(Math.round(letzter).toString(), r + stil.dp(8f), y(letzter) + stil.px(11f) * 0.36f, tx)
        } else {
            tx.typeface = stil.monoMittel; tx.textSize = stil.px(12f); tx.color = Farbe.WARN_TEXT; tx.textAlign = Paint.Align.CENTER
            c.drawText("NO DATA", (l + r) / 2, (t + b) / 2 + stil.px(12f) * 0.36f, tx)
        }
    }
}
