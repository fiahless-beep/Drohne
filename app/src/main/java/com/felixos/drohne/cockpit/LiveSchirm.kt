package com.felixos.drohne.cockpit

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.felixos.drohne.Aktionen
import com.felixos.drohne.CockpitZustand
import com.felixos.drohne.CockpitZustand.Hauptaktion
import com.felixos.drohne.Pruefzeile
import com.felixos.drohne.StartErgebnis
import java.util.Locale

/**
 * Hauptbildschirm Live/HUD nach dem freigegebenen Prototyp: über der Bildfläche Statuszeile, feste Warnzeile, HUD und
 * die drei Flugwerte; rechts das Aktionsfeld mit Plan, Fortschritt, Startprüfung, einem Hauptknopf und STOP.
 * Zeigt nur [CockpitZustand] und ruft [Aktionen]; entscheidet nichts.
 */
internal class LiveSchirm(private val stil: Stil, private val aktionen: Aktionen, buehne: FrameLayout, feld: FrameLayout) {
    val hud = HudView(stil)
    /** Die Leisten oben haben ihre Höhe geändert (die 3D-Ansicht richtet ihre Bildmitte danach). */
    var beiLeistenhoehe: (() -> Unit)? = null

    // Bildfläche
    private val phase = Abzeichen(stil, neutral = true)
    private val link = Abzeichen(stil)
    private val gps = Abzeichen(stil)
    private val sens = Abzeichen(stil)
    private val warnZeile = stil.zeile()
    private val warnBild = ImageView(stil.c)
    private val warnText = stil.text(15f, stil.sans, Farbe.TEXT_DIM, zeilen = 2)
    private var warnAn: Boolean? = null
    private val alt = Wert("ALT · START", "m")
    private val tempo = Wert("SPEED", "m/s")
    private val akku = Wert("BATTERY", "%", mitStatus = true)

    // Aktionsfeld
    private val planName = stil.text(15f, stil.sansHalbfett, Farbe.TEXT, zeilen = 2)
    private val planZeile = stil.text(12f, stil.mono, Farbe.TEXT_DIM)
    /** Plan-Check in einem Satz: Zahl der Akkus; orange, wenn der Plan erst einen Vermessungsflug braucht. */
    private val planSatz = stil.text(12f, stil.sans, Farbe.TEXT, zeilen = 3)
    private val fortschritt = stil.spalte()
    private val wp = stil.text(15f, stil.monoFett, Farbe.ACCENT)
    private val wpProzent = stil.label("")
    private val ticks = Zellen(stil, 12)
    private val naechstes = stil.text(12f, stil.sans, Farbe.TEXT, zeilen = 3)
    private val distanz = WertZeile(stil, "DIST · HOME", hell = true)
    private val peilung = WertZeile(stil, "HOME BRG", hell = true)
    private val wind = WertZeile(stil, "WIND", hell = true)
    private val pruefliste = stil.spalte()
    private val werteSpalte = stil.spalte().apply { isClickable = true }
    private var pruefSchluessel = ""
    private val haupt = Knopf(stil, Knopf.Stilart.HAUPT) { hauptaktion() }
    private val abheben = Knopf(stil, Knopf.Stilart.ABHEBEN) { abhebenAusloesen() }
    private val stopp = Knopf(stil, Knopf.Stilart.STOPP) { stoppen() }
    private val knoepfe = stil.spalte()
    private val keinFunk = stil.zeile()
    private val keinFunkText = stil.text(12f, stil.sans, Farbe.TEXT, zeilen = 4)
    private var stoppGesperrtBis = 0L
    private var abhebenText: String? = null
    private var letzter = CockpitZustand()

    /** Eine der drei großen Flugwert-Kacheln. */
    private inner class Wert(name: String, einheit: String, mitStatus: Boolean = false) {
        val kachel = stil.spalte()
        val zahl = stil.text(32f, stil.monoFett, Farbe.ACCENT)
        val rest = stil.text(13f, stil.monoMittel, Farbe.TEXT)
        val status = Abzeichen(stil)
        val zellen = Zellen(stil, 10)
        private var warn: Boolean? = null

        init {
            kachel.setPadding(stil.dp(8), stil.dp(4), stil.dp(8), stil.dp(6))
            val kopf = stil.zeile()
            kopf.addView(stil.label(name), stil.lp(0, Stil.WRAP, 1f))
            if (mitStatus) kopf.addView(status)
            kachel.addView(kopf, stil.lp(Stil.MATCH, Stil.WRAP))
            val reihe = stil.zeile().apply { gravity = Gravity.BOTTOM }
            reihe.addView(zahl)
            reihe.addView(stil.text(13f, stil.mono, Farbe.TEXT).apply { text = einheit; setPadding(stil.dp(4), 0, 0, stil.dp(5)) })
            if (mitStatus) { rest.gravity = Gravity.END; rest.setPadding(0, 0, 0, stil.dp(5)); reihe.addView(rest, stil.lp(0, Stil.WRAP, 1f)) }
            kachel.addView(reihe, stil.lp(Stil.MATCH, Stil.WRAP))
            if (mitStatus) kachel.addView(zellen, stil.lp(Stil.MATCH, stil.dp(5), oben = 3))
            warnung(false)
        }

        fun warnung(an: Boolean) { if (warn == an) return; warn = an; kachel.background = stil.flaeche(if (an) Farbe.WARN_PANEL else Farbe.PANEL_A, if (an) Farbe.WARN else Farbe.PANEL_LINE) }
        fun zeige(t: String?, an: Boolean = false) { zahl.zeige(t ?: "–"); zahl.farbe(when { t == null -> Farbe.TEXT_DIM; an -> Farbe.WARN_TEXT; else -> Farbe.ACCENT }); warnung(an && t != null) }
    }

    init {
        // ---- Bildfläche: HUD, darüber oben Status- und Warnzeile, unten links die Flugwerte
        buehne.addView(hud, FrameLayout.LayoutParams(-1, -1))
        // Leisten und Kacheln fangen Berührungen ab, damit ein Tipp darauf kein Motiv auf Karte oder Livebild wählt.
        val oben = stil.spalte().apply { isClickable = true }
        val streifen = stil.zeile().apply { setBackgroundColor(Farbe.PANEL_A); setPadding(stil.dp(8), stil.dp(5), stil.dp(8), stil.dp(5)) }
        streifen.addView(phase, stil.lp(0, Stil.WRAP, 1f))
        streifen.addView(link); streifen.addView(gps, stil.lp(Stil.WRAP, Stil.WRAP, links = 10)); streifen.addView(sens, stil.lp(Stil.WRAP, Stil.WRAP, links = 10))
        oben.addView(streifen, stil.lp(Stil.MATCH, Stil.WRAP))
        oben.addView(View(stil.c).apply { setBackgroundColor(Farbe.PANEL_LINE) }, stil.lp(Stil.MATCH, stil.dp(1)))
        warnZeile.gravity = Gravity.TOP
        warnZeile.minimumHeight = stil.dp(50)
        warnZeile.addView(warnBild, stil.lp(stil.dp(18), stil.dp(18), oben = 1))
        warnZeile.addView(warnText, stil.lp(0, Stil.WRAP, 1f, links = 8))
        warnZeile.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        oben.addView(warnZeile, stil.lp(Stil.MATCH, Stil.WRAP))
        buehne.addView(oben, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        oben.addOnLayoutChangeListener { _, _, _, _, unten, _, _, _, _ -> if (hud.obenPx != unten) { hud.obenPx = unten; beiLeistenhoehe?.invoke() } }

        val werte = werteSpalte
        werte.addView(alt.kachel, stil.lp(Stil.MATCH, Stil.WRAP))
        werte.addView(tempo.kachel, stil.lp(Stil.MATCH, Stil.WRAP, oben = 4))
        werte.addView(akku.kachel, stil.lp(Stil.MATCH, Stil.WRAP, oben = 4))
        buehne.addView(werte, FrameLayout.LayoutParams(stil.dp(134), -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = stil.dp(8); bottomMargin = stil.dp(8) })

        // ---- Aktionsfeld
        val p = stil.spalte().apply { background = stil.flaeche(Farbe.PANEL); setPadding(stil.dp(10), stil.dp(6), stil.dp(10), stil.dp(10)) }
        val plan = stil.spalte().apply { isClickable = true; contentDescription = "Plan details"; setOnClickListener { planDetails() } }
        plan.addView(planName, stil.lp(Stil.MATCH, Stil.WRAP))
        plan.addView(planZeile, stil.lp(Stil.MATCH, Stil.WRAP, oben = 1))
        planSatz.setLineSpacing(0f, 1.15f)
        plan.addView(planSatz, stil.lp(Stil.MATCH, Stil.WRAP, oben = 3))
        p.addView(plan, stil.lp(Stil.MATCH, Stil.WRAP))
        val kopf = stil.zeile()
        kopf.addView(wp, stil.lp(0, Stil.WRAP, 1f)); kopf.addView(wpProzent)
        fortschritt.addView(kopf, stil.lp(Stil.MATCH, Stil.WRAP))
        fortschritt.addView(ticks, stil.lp(Stil.MATCH, stil.dp(8), oben = 3))
        p.addView(fortschritt, stil.lp(Stil.MATCH, Stil.WRAP, oben = 4))
        naechstes.setLineSpacing(0f, 1.2f)
        p.addView(naechstes, stil.lp(Stil.MATCH, Stil.WRAP, oben = 4))
        p.addView(distanz, stil.lp(Stil.MATCH, Stil.WRAP, oben = 4)); p.addView(peilung, stil.lp(Stil.MATCH, Stil.WRAP)); p.addView(wind, stil.lp(Stil.MATCH, Stil.WRAP))
        p.addView(ScrollView(stil.c).apply { isVerticalScrollBarEnabled = false; addView(pruefliste) }, stil.lp(Stil.MATCH, 0, 1f, oben = 4))

        knoepfe.addView(haupt, stil.lp(Stil.MATCH, stil.dp(48)))
        val unten = stil.zeile()
        abheben.halteText = "Keep holding …"
        unten.addView(abheben, LinearLayout.LayoutParams(0, stil.dp(56), 1f).apply { rightMargin = stil.dp(8) })
        unten.addView(stopp, stil.lp(0, stil.dp(56), 1.25f))
        knoepfe.addView(unten, stil.lp(Stil.MATCH, Stil.WRAP, oben = 10))
        p.addView(knoepfe, stil.lp(Stil.MATCH, Stil.WRAP, oben = 6))

        // Funkabriss: statt der Knöpfe, die ohne Verbindung nichts bewirken, der Hinweis auf die Fernsteuerung.
        keinFunk.gravity = Gravity.TOP
        keinFunk.background = stil.flaeche(Farbe.WARN_PANEL, Farbe.WARN)
        keinFunk.setPadding(stil.dp(8), stil.dp(8), stil.dp(8), stil.dp(8))
        keinFunk.minimumHeight = stil.dp(64)
        keinFunk.addView(ImageView(stil.c).apply { setImageDrawable(Zeichen(Zeichen.Art.WARN, Farbe.WARN, aussparung = Farbe.WARN_PANEL, groessePx = stil.dp(20))) }, stil.lp(stil.dp(20), stil.dp(20)))
        val kf = stil.spalte()
        kf.addView(stil.text(14f, stil.monoFett, Farbe.WARN_TEXT, versalien = true, sperrung = 0.04f, zeilen = 3).apply { text = "No link · Use RTH button on RC" }, stil.lp(Stil.MATCH, Stil.WRAP))
        kf.addView(keinFunkText, stil.lp(Stil.MATCH, Stil.WRAP, oben = 6))
        keinFunk.addView(kf, stil.lp(0, Stil.WRAP, 1f, links = 8))
        keinFunk.visibility = View.GONE
        p.addView(keinFunk, stil.lp(Stil.MATCH, Stil.WRAP, oben = 6))
        feld.addView(p, FrameLayout.LayoutParams(-1, -1))
    }

    /** Warnzeichen blinkt mit 1 Hz ([hell] wechselt jede halbe Sekunde); Normalwerte blinken nie. */
    fun blinken(hell: Boolean) { warnBild.alpha = if (warnAn == true && !hell) 0.25f else 1f }

    fun zeige(z: CockpitZustand) {
        letzter = z
        val da = z.datenDa
        val verbunden = z.verbindung == CockpitZustand.Verbindung.VERBUNDEN
        hud.zeige(z)

        // Statuszeile
        val phasenText = z.phaseText.uppercase(Locale.US) + (if (z.simulation) " · SIM" else "") + (z.keineDatenSeitS?.let { " · NO DATA $it s" } ?: "")
        when {
            z.funkabriss || z.keineDatenSeitS != null || z.verbindung == CockpitZustand.Verbindung.SDK_FEHLER -> phase.setze(Ton.WARN, phasenText)
            !verbunden -> phase.setze(Ton.AUS, phasenText)
            z.phase == CockpitZustand.Phase.PAUSIERT -> phase.setze(Ton.NEUTRAL, phasenText, Zeichen.Art.PAUSE)
            else -> phase.setze(Ton.NEUTRAL, phasenText)
        }
        when {
            z.funkabriss -> link.setze(Ton.WARN, "LINK LOST")
            z.keineDatenSeitS != null -> link.setze(Ton.WARN, "NO DATA")
            verbunden -> link.setze(Ton.OK, "LINK OK")
            z.verbindung == CockpitZustand.Verbindung.NUR_FERNSTEUERUNG -> link.setze(Ton.WARN, "RC ONLY")
            else -> link.setze(Ton.AUS, "NO LINK")
        }
        val sat = z.satelliten
        gps.setze(when { !da || sat == null -> Ton.AUS; z.gpsOk -> Ton.OK; else -> Ton.WARN }, "GPS " + (if (da && sat != null) sat.toString() else "–"))
        val se = z.sensoren
        sens.setze(when { !da || se == null -> Ton.AUS; z.sensorenOk -> Ton.OK; else -> Ton.WARN }, "SENS " + (if (da && se != null) se.uppercase(Locale.US) else "–"))

        // Warnzeile: fester Platz, Form und Klartext
        val w = z.warnung
        if (warnAn != (w != null)) {
            warnAn = w != null
            warnZeile.background = if (w != null) android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.ColorDrawable(Farbe.WARN), android.graphics.drawable.ColorDrawable(Farbe.WARN_PANEL))).apply { setLayerInset(1, stil.dp(4), 0, 0, stil.dp(1)) }
                else android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.ColorDrawable(Farbe.PANEL_LINE), android.graphics.drawable.ColorDrawable(Farbe.PANEL_A))).apply { setLayerInset(1, 0, 0, 0, stil.dp(1)) }
            warnZeile.setPadding(stil.dp(if (w != null) 12 else 8), stil.dp(5), stil.dp(8), stil.dp(6))
            warnBild.setImageDrawable(Zeichen(if (w != null) Zeichen.Art.WARN else Zeichen.Art.OK, if (w != null) Farbe.WARN else Farbe.ACCENT_DIM, aussparung = if (w != null) Farbe.WARN_PANEL else Farbe.PANEL, groessePx = stil.dp(18)))
            warnBild.alpha = 1f
        }
        val titel = if (w != null) "WARN · ${w.titel}" else "OK"
        val sb = SpannableStringBuilder(titel)
        sb.setSpan(Schriftspanne(stil.monoFett), 0, titel.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (w != null) sb.setSpan(ForegroundColorSpan(Farbe.WARN_TEXT), 0, titel.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("  ").append(w?.text ?: "No warnings.")
        warnText.zeige(sb)
        warnText.farbe(if (w != null) Farbe.TEXT else Farbe.TEXT_DIM)

        // Flugwerte
        alt.zeige(if (da) z.hoeheM?.let { Math.round(it).toString() } else null)
        tempo.zeige(if (da) z.tempoMs?.let { String.format(Locale.US, "%.1f", it) } else null)
        val b = if (da) z.akkuProzent else null
        val akkuWarn = z.akkuStufe == CockpitZustand.Stufe.NIEDRIG || z.akkuStufe == CockpitZustand.Stufe.WARNUNG
        akku.zeige(b?.toString(), an = akkuWarn)
        akku.rest.zeige(if (b != null && z.restMin != null) "≈${z.restMin} min" else "")
        when { b == null -> akku.status.setze(Ton.AUS, "–"); z.akkuStufe == CockpitZustand.Stufe.NIEDRIG -> akku.status.setze(Ton.WARN, "LOW"); akkuWarn -> akku.status.setze(Ton.WARN, "WARN"); else -> akku.status.setze(Ton.OK, "OK") }
        akku.zellen.setze(if (b != null) Math.round(b / 10f) else 0, if (akkuWarn) Farbe.WARN else Farbe.ACCENT)

        // Aktionsfeld
        planName.zeige(z.plan.name); planZeile.zeige(z.plan.zeile)
        val arbeit = z.auftrag.arbeitet
        val checkSatz = when {
            arbeit != null -> arbeit
            z.plan.laeuft || !z.plan.geladen || z.fliegt -> null
            z.plan.vermessungEmpfohlen -> "Survey flight first: this plan goes low without a height model. Details: tap here."
            else -> z.plan.akkuSatz
        }
        planSatz.sichtbar(checkSatz != null)
        if (checkSatz != null) { planSatz.zeige(checkSatz); planSatz.farbe(if (arbeit == null && (z.plan.vermessungEmpfohlen || z.plan.akkuZuWeit)) Farbe.WARN_TEXT else if (arbeit != null) Farbe.ACCENT else Farbe.TEXT) }
        fortschritt.sichtbar(z.plan.fortschrittZeigen)
        if (z.plan.fortschrittZeigen) {
            val n = z.plan.punkte; val i = z.plan.punktIndex
            wp.zeige(if (i >= 0 && n > 0) "WP ${i + 1}/$n" else "WP –/$n")
            wpProzent.zeige("${z.plan.fortschrittProzent} %")
            val zellen = n.coerceIn(1, 24)
            val jetzt = if (i >= 0 && n > 0) (i * zellen / n).coerceIn(0, zellen - 1) else -1
            ticks.setze(if (z.plan.fortschrittProzent >= 100) zellen else maxOf(jetzt, 0), Farbe.ACCENT_DIM, if (z.plan.fortschrittProzent >= 100) -1 else jetzt, Farbe.ACCENT, zellen)
        }
        val (vor, satz) = if (z.plan.laeuft && !z.funkabriss && z.keineDatenSeitS == null) "Next: " to z.plan.naechstes else "Now: " to z.statusText
        val zeit = String.format(Locale.US, "  %d:%02d", z.statusSeitS / 60, z.statusSeitS % 60)
        val ns = SpannableStringBuilder(vor + satz + zeit)
        ns.setSpan(Schriftspanne(stil.sansHalbfett), 0, vor.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ns.setSpan(ForegroundColorSpan(Farbe.TEXT_DIM), ns.length - zeit.length, ns.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        naechstes.zeige(ns)
        distanz.wert(if (da) z.distanzHeimM?.let { "$it m" } else null)
        peilung.wert(if (da) z.peilungHeimGrad?.let { String.format(Locale.US, "%03d°", it) } else null)
        windText(z).let { (t, ton) -> wind.wert(t, ton) }
        pruefliste(z)

        // Knöpfe
        // Funkabriss: Hauptknopf weg (Abheben ist ohne Verbindung ohnehin aus), STOP bleibt (die Erkennung kann irren, dann muss er erreichbar sein).
        haupt.sichtbar(!z.funkabriss)
        keinFunk.sichtbar(z.funkabriss)
        if (z.funkabriss) keinFunkText.zeige(if (z.plan.aufDrohne && z.plan.beiFunkabriss != null) "If the plan was running, the drone will ${z.plan.beiFunkabriss}. Check controller and cable." else "Check controller and cable.")
        haupt.setze(z.hauptaktion.text, when (z.hauptaktion) { Hauptaktion.PAUSE -> Zeichen.Art.PAUSE; Hauptaktion.FORTSETZEN, Hauptaktion.START -> Zeichen.Art.PLAY; else -> null }, z.hauptaktionFrei)
        abheben.sichtbar(z.abhebenMoeglich)
        if (!z.abhebenMoeglich) abhebenText = null
        abheben.halteMs = z.abhebenHalteMs
        abheben.setze(abhebenText ?: "Hold: take off", null, true)
        val amBoden = !z.fliegt && !z.motorenAn && !z.folgenAktiv
        stopp.setze(if (z.abhebenMoeglich) "Stop" else if (amBoden) "Stop · Home" else "Stop · Return home", Zeichen.Art.STOP, System.currentTimeMillis() >= stoppGesperrtBis)
        stopp.contentDescription = "Stop and return home"
    }

    /** Während der Ortswahl einer Mission (Drohne am Boden) machen die Flugwerte dem Balken Platz. */
    fun werteSichtbar(an: Boolean) = werteSpalte.sichtbar(an)

    private fun pruefliste(z: CockpitZustand) {
        val zeilen = if (z.startpruefungZeigen && !z.funkabriss) z.pruefliste else emptyList()
        val schluessel = zeilen.joinToString("|") { "${it.ok}${it.text}" }
        if (schluessel == pruefSchluessel) return
        pruefSchluessel = schluessel
        pruefliste.removeAllViews()
        zeilen.forEach { pruefliste.addView(pruefZeile(it), stil.lp(Stil.MATCH, Stil.WRAP)) }
    }

    /** Prüfzeile: Zeichenform und Text; ein Problem lässt sich antippen und zeigt den vollen Satz. */
    private fun pruefZeile(r: Pruefzeile): View = stil.zeile().apply {
        gravity = Gravity.TOP
        setPadding(0, stil.dp(3), 0, stil.dp(3))
        val (art, farbe) = when (r.ok) { true -> Zeichen.Art.OK to Farbe.ACCENT; false -> Zeichen.Art.WARN to Farbe.WARN; null -> Zeichen.Art.AUS to Farbe.TEXT_DIM }
        addView(ImageView(stil.c).apply { setImageDrawable(Zeichen(art, farbe, aussparung = Farbe.PANEL, groessePx = stil.dp(13))) }, stil.lp(stil.dp(13), stil.dp(13), oben = 1))
        addView(stil.text(12f, stil.sans, if (r.ok == null) Farbe.TEXT_DIM else Farbe.TEXT, zeilen = 2).apply { text = r.text }, stil.lp(0, Stil.WRAP, 1f, links = 6))
        r.detail?.let { d -> isClickable = true; setOnClickListener { AlertDialog.Builder(stil.c).setMessage(d).setPositiveButton("OK", null).show() } }
    }

    // ---------------------------------------------------------------- Handlungen (alle über den Vertrag)

    private fun hauptaktion() {
        when (aktionen.hauptaktionWaehlen()) {
            Hauptaktion.PAUSE -> aktionen.pausieren()
            Hauptaktion.FORTSETZEN -> aktionen.fortsetzen()
            Hauptaktion.PLAN_HOLEN -> aktionen.planLaden()
            Hauptaktion.HOCHLADEN -> aktionen.hochladen()
            Hauptaktion.START -> startFragen()
            else -> {}
        }
    }

    /** Erst die Prüfung zeigen, dann fragen; den Start selbst prüft der Flugkern noch einmal. */
    private fun startFragen() {
        if (aktionen.startProbleme().isNotEmpty()) return startNichtMoeglich()
        AlertDialog.Builder(stil.c).setTitle("Start the plan?")
            .setMessage(letzter.plan.text + (aktionen.akkuHinweis()?.let { "\n\n" + it } ?: "") + "\n\nSurroundings clear? Thumbs on the sticks. Pause: button, any stick, or the pause button on the controller.")
            .setPositiveButton("Start") { _, _ -> (aktionen.starten() as? StartErgebnis.Verweigert)?.let { startNichtMoeglich(it.probleme) } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun startNichtMoeglich(gruende: List<String> = emptyList()) {
        val liste = stil.spalte().apply { setPadding(stil.dp(20), stil.dp(8), stil.dp(20), stil.dp(4)) }
        // Der Grund der Starttür zuerst: die Prüfliste allein kann lauter Haken zeigen (z. B. Drohne schon in der Luft).
        gruende.forEach { liste.addView(pruefZeile(Pruefzeile(false, it, null)), stil.lp(Stil.MATCH, Stil.WRAP)) }
        aktionen.pruefliste(voll = true).forEach { liste.addView(pruefZeile(it), stil.lp(Stil.MATCH, Stil.WRAP)) }
        AlertDialog.Builder(stil.c).setTitle("Cannot start")
            .setView(ScrollView(stil.c).apply { setBackgroundColor(Farbe.PANEL); addView(liste) })
            .setPositiveButton("OK", null).show()
    }

    private fun abhebenAusloesen() {
        when (val r = aktionen.abheben()) {
            is StartErgebnis.Gestartet -> abhebenText = "Taking off …"
            is StartErgebnis.Verweigert -> { abhebenText = null; if (r.probleme.isNotEmpty()) AlertDialog.Builder(stil.c).setTitle("No takeoff").setMessage(r.probleme.joinToString("\n")).setPositiveButton("OK", null).show() }
        }
        zeige(aktionen.zustand())
    }

    /** Der STOP im Vollbild teilt sich die Sperre mit dem großen. */
    val stoppFrei: Boolean get() = System.currentTimeMillis() >= stoppGesperrtBis

    /** Ein Druck löst aus; danach ist die Stelle 1,5 s gesperrt, damit ein Doppeltipp nichts Zweites trifft. */
    fun stoppen() {
        if (System.currentTimeMillis() < stoppGesperrtBis) return
        stoppGesperrtBis = System.currentTimeMillis() + 1500
        aktionen.stoppUndHeim()
        zeige(aktionen.zustand())
        stopp.postDelayed({ zeige(aktionen.zustand()) }, 1550)
    }

    /** Langer Text zum Plan (Strecke, Höhe, Akku, Wind-Vorhersage, Funkabriss). */
    private fun planDetails() {
        val t = letzter.plan.text
        if (t.isBlank()) return
        AlertDialog.Builder(stil.c).setTitle(letzter.plan.name).setMessage(t)
            .setPositiveButton("OK", null)
            .setNeutralButton("Reload plan") { _, _ -> aktionen.planLaden() }.show()
    }

    /** Schriftwechsel innerhalb eines Textes (fetter Vorspann). */
    private class Schriftspanne(private val schrift: Typeface) : android.text.style.MetricAffectingSpan() {
        override fun updateDrawState(p: android.text.TextPaint) { p.typeface = schrift }
        override fun updateMeasureState(p: android.text.TextPaint) { p.typeface = schrift }
    }
}
