package com.felixos.drohne.cockpit

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Die Flächen hinter den Leistenknöpfen CC, Log und Set (Felix 22.11.2026: nicht als Pop-up, sondern im Hauptfenster
 * wie der System-Bildschirm, ein und aus, im Stil des Interfaces). Jede Fläche füllt die Bühne; STOP bleibt im Flug
 * in der Fußzeile des Cockpits erreichbar.
 */

/** Rahmen mit Eckwinkeln wie im HUD: Kopfzeile (Titel links, Zusatz und Knöpfe rechts), darunter der Inhalt. */
@SuppressLint("ViewConstructor")
internal class TafelRahmen(private val stil: Stil, titel: String) : LinearLayout(stil.c) {
    private val ecke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Farbe.ACCENT; style = Paint.Style.STROKE; strokeWidth = stil.dp(2f) }
    val titel = stil.text(13f, stil.monoFett, Farbe.ACCENT, versalien = true, sperrung = 0.1f)
    val zusatz = stil.text(11f, stil.monoMittel, Farbe.TEXT_DIM, versalien = true, sperrung = 0.06f)
    val kopf = stil.zeile()

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        setBackgroundColor(Farbe.BG)
        setPadding(stil.dp(10), stil.dp(8), stil.dp(10), stil.dp(8))
        isClickable = true
        this.titel.text = titel
        kopf.addView(this.titel)
        kopf.addView(zusatz, stil.lp(0, Stil.WRAP, 1f, links = 10))
        addView(kopf, stil.lp(Stil.MATCH, stil.dp(34)))
        addView(View(stil.c).apply { setBackgroundColor(Farbe.ACCENT_DIM) }, stil.lp(Stil.MATCH, stil.dp(1), oben = 4))
    }

    fun kopfKnopf(text: String, beschreibung: String, beiDruck: () -> Unit): LeistenKnopf =
        LeistenKnopf(stil, beschreibung, beiDruck).apply { setze(text, false); setPadding(stil.dp(12), 0, stil.dp(12), 0) }
            .also { kopf.addView(it, LinearLayout.LayoutParams(Stil.WRAP, stil.dp(32)).apply { leftMargin = stil.dp(6) }) }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val a = stil.dp(14f); val r = stil.dp(1f); val w = width - r; val h = height - r
        c.drawLines(floatArrayOf(r, a, r, r, r, r, a, r, w - a, r, w, r, w, r, w, a, r, h - a, r, h, r, h, a, h, w - a, h, w, h, w, h, w, h - a), ecke)
    }
}

/** Protokoll als Leitstand-Terminal: Zeilennummer, Uhrzeit, Meldung; Warnungen in Orange. Folgt dem Ende, bis man hochscrollt. */
internal class LogTafel(private val stil: Stil, wurzel: FrameLayout, private val gast: Gastgeber) {
    val rahmen = TafelRahmen(stil, "Flight log")
    private val text = stil.text(11f, stil.mono, Farbe.TEXT, zeilen = Int.MAX_VALUE)
    private val rolle = ScrollView(stil.c)
    private var stand = ""
    private var folgt = true
    private val warnWort = Regex("warn|fehl|error|failed|abbruch|stop|verweiger|nicht |lost|keine ", RegexOption.IGNORE_CASE)

    init {
        rahmen.kopfKnopf("End", "Jump to the newest line") { folgt = true; rolle.post { rolle.fullScroll(View.FOCUS_DOWN) } }
        rahmen.kopfKnopf("Copy", "Copy the log") { gast.protokollKopieren() }
        text.setLineSpacing(0f, 1.25f); text.ellipsize = null; text.setTextIsSelectable(false)
        text.setPadding(stil.dp(8), stil.dp(6), stil.dp(8), stil.dp(6))
        rolle.background = stil.flaeche(Farbe.PANEL)
        rolle.isVerticalScrollBarEnabled = true
        rolle.addView(text, FrameLayout.LayoutParams(-1, -2))
        rolle.setOnScrollChangeListener { _, _, y, _, _ -> folgt = y + rolle.height >= text.height - stil.dp(24) }
        rahmen.addView(rolle, stil.lp(Stil.MATCH, 0, 1f, oben = 6))
        rahmen.visibility = View.GONE
        wurzel.addView(rahmen, FrameLayout.LayoutParams(-1, -1))
    }

    fun zeige() {
        val (kopf, zeilen) = gast.protokollText()
        val neu = kopf + "\u0000" + zeilen.size + "\u0000" + (zeilen.lastOrNull() ?: "")
        if (neu == stand) return
        stand = neu
        val b = SpannableStringBuilder()
        fun farbig(t: String, f: Int) { val a = b.length; b.append(t); b.setSpan(ForegroundColorSpan(f), a, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        kopf.lines().filter { it.isNotBlank() }.forEach { farbig("// $it\n", Farbe.TEXT_MUTED) }
        farbig("// ${zeilen.size} LINES · NEWEST LAST\n\n", Farbe.TEXT_MUTED)
        zeilen.forEachIndexed { i, z ->
            farbig(String.format("%03d ", i + 1), Farbe.DECO)
            // Zeile aus DroneManager.log: „HH:mm:ss  Meldung“
            val zeit = if (z.length > 10 && z[2] == ':' && z[5] == ':') z.substring(0, 8) else null
            val rest = if (zeit != null) z.substring(8).trimStart() else z
            if (zeit != null) farbig("$zeit ", Farbe.ACCENT_DIM)
            farbig(rest + "\n", if (warnWort.containsMatchIn(rest)) Farbe.WARN_TEXT else Farbe.TEXT)
        }
        text.text = b
        rahmen.zusatz.zeige("live · ${zeilen.size} lines")
        if (folgt) rolle.post { rolle.fullScroll(View.FOCUS_DOWN) }
    }

    fun geoeffnet() { folgt = true; stand = ""; zeige() }
}

/** Einstellungen als Liste im Fenster. Untermenüs ersetzen die Liste; „Back“ geht eine Ebene zurück. */
internal class SetTafel(private val stil: Stil, wurzel: FrameLayout) {
    val rahmen = TafelRahmen(stil, "Settings")
    /** [oeffner] = die Handlung, die diese Ebene geöffnet hat; mit ihr wird die Liste nach einer Änderung frisch geholt. */
    private class Ebene(val titel: String, val eintraege: List<Pair<String, () -> Unit>>, val oeffner: (() -> Unit)?, val text: String? = null)
    private val stapel = ArrayList<Ebene>()
    private val liste = stil.spalte()
    private val zurueck = rahmen.kopfKnopf("Back", "One level back") { zurueck() }
    private val jetzt = Regex("^(.*?) \\((now|jetzt) (.*)\\)$")
    private var naechsterOeffner: (() -> Unit)? = null

    init {
        val rolle = ScrollView(stil.c).apply { addView(liste, FrameLayout.LayoutParams(-1, -2)) }
        rahmen.addView(rolle, stil.lp(Stil.MATCH, 0, 1f, oben = 6))
        rahmen.visibility = View.GONE
        wurzel.addView(rahmen, FrameLayout.LayoutParams(-1, -1))
    }

    /** Die Fläche neu füllen: [oeffner] ruft das Hauptmenü des Gastgebers, das über [ebene] zurückkommt. */
    fun oeffnen(oeffner: () -> Unit) { stapel.clear(); liste.removeAllViews(); naechsterOeffner = oeffner; oeffner(); naechsterOeffner = null }

    fun ebene(titel: String, eintraege: List<Pair<String, () -> Unit>>) {
        stapel.add(Ebene(titel, eintraege.filter { it.first != "Cancel" }, naechsterOeffner)); naechsterOeffner = null
        zeichnen()
    }

    /** Eine Ebene nur mit Text (Erklärung, Ergebnis). */
    fun text(titel: String, text: String) { stapel.add(Ebene(titel, emptyList(), null, text)); naechsterOeffner = null; zeichnen() }

    /** false = es gab nichts zurückzugehen (oberste Ebene). */
    fun zurueck(): Boolean { if (stapel.size <= 1) return false; stapel.removeAt(stapel.size - 1); auffrischen(); return true }
    fun leeren() { stapel.clear(); liste.removeAllViews() }

    /** Oberste Ebene mit ihrer Öffner-Handlung neu holen, damit Werte wie „(now …)“ und „(on)“ stimmen. */
    private fun auffrischen() {
        if (stapel.isEmpty()) return
        val e = stapel.removeAt(stapel.size - 1); val n = stapel.size
        val o = e.oeffner
        if (o != null) { naechsterOeffner = o; o(); naechsterOeffner = null }
        if (stapel.size == n) { stapel.add(e); zeichnen() }
    }

    /** Ein Eintrag wurde gedrückt: öffnet er keine neue Ebene, hat er etwas geschaltet, also die Liste frisch holen. */
    private fun druck(tun: () -> Unit) {
        val n = stapel.size; val oben = stapel.lastOrNull()
        naechsterOeffner = tun; tun(); naechsterOeffner = null
        if (stapel.size == n && stapel.lastOrNull() === oben) auffrischen()
    }

    private fun zeichnen() {
        rahmen.titel.zeige(stapel.joinToString(" / ") { it.titel.substringBefore(" (") })
        rahmen.zusatz.zeige("")
        zurueck.visibility = if (stapel.size > 1) View.VISIBLE else View.INVISIBLE
        liste.removeAllViews()
        stapel.last().text?.let { t ->
            liste.addView(stil.text(14f, stil.sans, Farbe.TEXT, zeilen = Int.MAX_VALUE).apply {
                text = t; ellipsize = null; setLineSpacing(0f, 1.35f)
                background = stil.flaeche(Farbe.PANEL); setPadding(stil.dp(12), stil.dp(10), stil.dp(12), stil.dp(10))
            }, stil.lp(Stil.MATCH, Stil.WRAP))
        }
        stapel.last().eintraege.forEachIndexed { i, (text, tun) ->
            val z = stil.zeile().apply {
                background = stil.flaeche(Farbe.PANEL); isClickable = true; isFocusable = true
                setPadding(stil.dp(10), 0, stil.dp(10), 0); minimumHeight = stil.dp(46)
                contentDescription = text
                setOnClickListener { druck(tun) }
            }
            val m = jetzt.find(text)
            z.addView(stil.text(11f, stil.monoMittel, Farbe.ACCENT_DIM).apply { this.text = String.format("%02d", i + 1) })
            z.addView(stil.text(14f, stil.sans, Farbe.TEXT, zeilen = 2).apply { this.text = m?.groupValues?.get(1) ?: text }, stil.lp(0, Stil.WRAP, 1f, links = 10))
            if (m != null) z.addView(stil.text(12f, stil.monoMittel, Farbe.ACCENT, versalien = true, sperrung = 0.04f).apply { this.text = m.groupValues[3] }, stil.lp(Stil.WRAP, Stil.WRAP, links = 8))
            z.addView(stil.text(16f, stil.monoFett, Farbe.ACCENT_DIM).apply { this.text = "›" }, stil.lp(Stil.WRAP, Stil.WRAP, links = 10))
            liste.addView(z, stil.lp(Stil.MATCH, Stil.WRAP, oben = if (i == 0) 0 else 4))
        }
    }
}

/**
 * Central Command (Felix 22.11.2026, vorher „Ask Claude“): Frage eintippen, die App schickt Standbild und Flugwerte mit,
 * die Antwort steht im Verlauf. Schlägt Central Command eine Handlung vor, erscheint dafür ein eigener Knopf; ohne
 * Druck darauf passiert nichts.
 */
internal class CcTafel(private val stil: Stil, wurzel: FrameLayout, private val gast: Gastgeber) {
    val rahmen = TafelRahmen(stil, "Central Command")
    private val eingabe = EditText(stil.c)
    private val senden = Knopf(stil, Knopf.Stilart.HAUPT) { abschicken() }
    private val sprache = Sprache(stil.c)
    /** Der große Knopf: drücken, sprechen; nach der Sprechpause geht die Frage von selbst ab. Noch einmal drücken = sofort ab. */
    private val sprechen = Knopf(stil, Knopf.Stilart.HAUPT) { sprechKnopf() }
    private val gehoert = stil.text(14f, stil.sans, Farbe.TEXT, zeilen = 2)
    /** Vorlesen ist aus (Felix 22.11.2026: „Antwort muss nicht vorgelesen werden“); der Knopf schaltet es bei Bedarf ein. */
    private var vorlesen = false
    private val sprachKnopf: LeistenKnopf = rahmen.kopfKnopf("Lang " + sprache.kuerzel, "Language for speaking and reading aloud: German or English") { sprachKnopf.setze("Lang " + sprache.wechseln(), false) }
    private val stimmKnopf = rahmen.kopfKnopf("Voice off", "Read answers aloud: on or off") { vorlesenSchalten() }
    private val verlauf = stil.text(13f, stil.sans, Farbe.TEXT, zeilen = Int.MAX_VALUE)
    private val rolle = ScrollView(stil.c)
    private val vorschlag = Knopf(stil, Knopf.Stilart.HAUPT) { val t = aktion; vorschlagZeigen(null); t?.second?.invoke() }
    private var aktion: Pair<String, () -> Unit>? = null
    private var wartet = false
    private val text = SpannableStringBuilder()

    init {
        eingabe.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f); typeface = stil.mono
            setTextColor(Farbe.TEXT); setHintTextColor(Farbe.TEXT_MUTED)
            hint = "What do you see? What next?"
            background = stil.flaeche(Farbe.PANEL, Farbe.ACCENT_DIM)
            setPadding(stil.dp(10), 0, stil.dp(10), 0)
            maxLines = 1; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { abschicken(); true } else false }
        }
        // Sprechen zuerst (Felix: im Flug wird nicht getippt); die Zeile zum Tippen bleibt darunter als Ersatz.
        val sprechZeile = stil.zeile()
        sprechZeile.addView(sprechen, stil.lp(stil.dp(220), stil.dp(56)))
        sprechZeile.addView(gehoert, stil.lp(0, Stil.WRAP, 1f, links = 12))
        rahmen.addView(sprechZeile, stil.lp(Stil.MATCH, Stil.WRAP, oben = 8))
        val zeile = stil.zeile()
        zeile.addView(eingabe, stil.lp(0, stil.dp(44), 1f))
        zeile.addView(senden, stil.lp(stil.dp(120), stil.dp(44), links = 8))
        rahmen.addView(zeile, stil.lp(Stil.MATCH, Stil.WRAP, oben = 8))
        verlauf.setLineSpacing(0f, 1.3f); verlauf.ellipsize = null
        verlauf.setPadding(stil.dp(8), stil.dp(6), stil.dp(8), stil.dp(6))
        rolle.background = stil.flaeche(Farbe.PANEL)
        rolle.addView(verlauf, FrameLayout.LayoutParams(-1, -2))
        rahmen.addView(rolle, stil.lp(Stil.MATCH, 0, 1f, oben = 6))
        rahmen.addView(vorschlag, stil.lp(Stil.MATCH, stil.dp(48), oben = 6))
        vorschlag.visibility = View.GONE
        rahmen.visibility = View.GONE
        wurzel.addView(rahmen, FrameLayout.LayoutParams(-1, -1))
        zeile("//", "Press Speak and ask. Central Command gets a still of the live view (switch to the live view first), position, height and battery. It answers and may suggest one action. Nothing happens until you press the suggestion.", Farbe.TEXT_MUTED)
        knopfZeigen()
    }

    private fun zeile(wer: String, t: String, farbe: Int) {
        val a = text.length
        text.append(wer).append("  ")
        text.setSpan(ForegroundColorSpan(Farbe.ACCENT_DIM), a, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val b = text.length
        text.append(t).append("\n\n")
        text.setSpan(ForegroundColorSpan(farbe), b, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        verlauf.text = text
        rolle.post { rolle.fullScroll(View.FOCUS_DOWN) }
    }

    private fun knopfZeigen() {
        senden.setze(if (wartet) "Looking …" else "Send", null, !wartet)
        sprechen.setze(if (sprache.hoert) "Listening … tap to send" else if (wartet) "Looking …" else "Speak", null, !wartet)
    }

    private fun vorlesenSchalten() {
        vorlesen = !vorlesen
        stimmKnopf.setze(if (vorlesen) "Voice on" else "Voice off", false)
        if (!vorlesen) sprache.schweigen()
    }

    private fun sprechKnopf() {
        if (wartet) return
        if (sprache.hoert) { sprache.genug(); return }
        val grund = gast.ccGesperrt()
        if (grund != null) return zeile("!!", grund, Farbe.WARN_TEXT)
        if (!sprache.erkennungDa) return zeile("!!", "Speech recognition is not available on this phone. Type the question below.", Farbe.WARN_TEXT)
        if (!sprache.mikrofonErlaubt) { gast.mikrofonErlauben(); return zeile("!!", "Allow the microphone, then press Speak again.", Farbe.WARN_TEXT) }
        gehoert.zeige("…"); gehoert.farbe(Farbe.TEXT_DIM)
        sprache.hoeren(
            teil = { gehoert.zeige(it) },
            fertig = { t -> knopfZeigen(); if (t == null) { gehoert.zeige("Nothing understood. Press Speak again.") } else { gehoert.zeige(""); abschicken(t) } },
            fehler = { f -> knopfZeigen(); gehoert.zeige(""); zeile("!!", f, Farbe.WARN_TEXT) })
        knopfZeigen()
    }

    private fun vorschlagZeigen(a: Pair<String, () -> Unit>?) {
        aktion = a
        vorschlag.sichtbar(a != null)
        if (a != null) vorschlag.setze("Do it: ${a.first}", Zeichen.Art.PLAY, true)
    }

    private fun abschicken(gesprochen: String? = null) {
        if (wartet) return
        val grund = gast.ccGesperrt()
        if (grund != null) return zeile("!!", grund, Farbe.WARN_TEXT)
        val frage = gesprochen ?: eingabe.text.toString().trim().ifBlank { "What do you see, and what do you suggest?" }
        eingabe.setText("")
        (stil.c.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(eingabe.windowToken, 0)
        vorschlagZeigen(null)
        zeile("YOU", frage, Farbe.TEXT)
        wartet = true; knopfZeigen()
        rahmen.zusatz.zeige("looking …")
        gast.ccFragen(frage) { antwort, fehler, a ->
            wartet = false; knopfZeigen()
            rahmen.zusatz.zeige("")
            zeile(if (fehler) "!!" else "CC", antwort, if (fehler) Farbe.WARN_TEXT else Farbe.ACCENT)
            if (vorlesen && rahmen.visibility == View.VISIBLE) sprache.sprechen(antwort)
            vorschlagZeigen(a)
        }
    }

    fun geoeffnet() { rahmen.zusatz.zeige(gast.ccGesperrt()?.let { "not ready" } ?: "ready") }
    fun geschlossen() {
        sprache.schliessen(); gehoert.zeige(""); knopfZeigen()
        (stil.c.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(eingabe.windowToken, 0)
    }
}

@Suppress("unused") private val ungenutzt: Class<TextView> = TextView::class.java
