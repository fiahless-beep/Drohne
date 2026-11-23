package com.felixos.drohne.cockpit

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.felixos.drohne.Aktionen
import com.felixos.drohne.CockpitZustand
import com.felixos.drohne.FlugSitzung
import java.util.Locale

/** Was auf der Bildfläche liegt; das HUD richtet sich danach. */
enum class Ansicht(val kuerzel: String) { KARTE("Map"), LIVEBILD("Live"), DREI_D("3D") }

/**
 * Was das Cockpit vom Gastgeber (der Activity) braucht: Bildfläche umschalten und die Dialoge, die noch dort wohnen
 * (Einstellungen, Protokoll, Copilot, Selbsttest). Flugwirksames läuft nie hierüber, sondern über [Aktionen].
 */
interface Gastgeber {
    val ansicht: Ansicht
    /** Das Livebild der Drohne kommt gerade an. */
    val livebildAn: Boolean
    fun ansichtWechseln()
    /** Karte einpassen bzw. in der 3D-Ansicht zwischen NEAR und WIDE wechseln. */
    fun einpassen()
    /** Was das Cockpit von der Bildfläche überdeckt (Pixel je Rand); die 3D-Ansicht hält Drohne und Zahlen im freien Teil. */
    fun bildRaender(links: Int, oben: Int, rechts: Int, unten: Int)
    /** Vollbild: Leiste, Aktionsfeld und HUD ausblenden, die Ansicht füllt den Bildschirm; false = zurück. */
    fun vollbild(an: Boolean)
    /** Kopfzeilen (App, Gerät) und die Zeilen des Protokolls, älteste zuerst. */
    fun protokollText(): Pair<String, List<String>>
    fun protokollKopieren()
    /** Öffnet das Hauptmenü der Einstellungen; es kommt über [Cockpit.menue] zurück in die Set-Fläche. */
    fun einstellungen()
    /** Central Command: Grund, warum gerade keine Frage geht (Schlüssel fehlt, Plan läuft); null = bereit. */
    fun ccGesperrt(): String?
    /** Android nach dem Mikrofon fragen (für das Sprechen mit Central Command). */
    fun mikrofonErlauben()
    /** Frage an Central Command; [antwort] bekommt Text, ob es ein Fehler ist, und höchstens einen Vorschlag (Name, Handlung). */
    fun ccFragen(frage: String, antwort: (String, Boolean, Pair<String, () -> Unit>?) -> Unit)
    fun selbsttest()
    /** Die Karte nach vorn holen (Ortswahl einer Mission). */
    fun karteZeigen()
    /** Die Orte der gewählten Mission haben sich geändert: die Karte holt sie sich selbst aus dem Flugkern und zeichnet sie. */
    fun missionsOrte()
}

/**
 * Das neue Cockpit („Military Tactical“, freigegeben 22.11.2026): Leiste links, Live/HUD-Bildschirm und System-Bildschirm.
 * Kennt vom Flugkern nur den Vertrag: liest [CockpitZustand] aus der [FlugSitzung] und ruft [Aktionen].
 */
class Cockpit(c: Context, leiste: LinearLayout, buehne: FrameLayout, feld: FrameLayout, private val liveWurzel: View, private val systemWurzel: FrameLayout, vollbildWurzel: FrameLayout, sprechWurzel: FrameLayout, private val gast: Gastgeber) {
    private val stil = Stil(c)
    private val aktionen: Aktionen = FlugSitzung
    private val live = LiveSchirm(stil, aktionen, buehne, feld)
    /** Bühne der Flächen (System, CC, Log, Set); darunter im Flug die Fußzeile mit STOP. */
    private val tafelFlaeche = FrameLayout(c)
    private val system = SystemSchirm(stil, tafelFlaeche, gast)
    private val logTafel = LogTafel(stil, tafelFlaeche, gast)
    private val setTafel = SetTafel(stil, tafelFlaeche)
    private val missionen = MissionenTafel(stil, tafelFlaeche, aktionen, planHolen = { tafelSetzen(Tafel.KEINE); aktionen.planLaden() }, sprechen = { sprechen.sprechen() },
        diktieren = { was, fertig -> sprechen.diktieren(was, fertig) }, zumCockpit = { tafelSetzen(Tafel.KEINE) }, still = { sprechen.schliessen() })
    /** Balken unten auf der Karte, solange der Ort einer Mission gewählt wird: was zu tun ist, Zurück und Fertig. */
    private val ortBalken = stil.spalte()
    private val ortTitel = stil.text(12f, stil.monoFett, Farbe.ACCENT, versalien = true, sperrung = 0.08f)
    private val ortSatz = stil.text(14f, stil.sans, Farbe.TEXT, zeilen = 3)
    private val ortZurueck = LeistenKnopf(stil, "Undo the last tap") { aktionen.ortZurueck() }
    private val ortFertig = Knopf(stil, Knopf.Stilart.HAUPT) { aktionen.ortFertig() }
    private var ortswahl = false
    private var orteSchluessel = ""
    private val sprechen: SprechEbene = SprechEbene(stil, sprechWurzel, gast, kontext = { kontext() }, lokal = { lokal(it) })
    private enum class Tafel { KEINE, SYSTEM, MSN, LOG, SET }
    private var tafel = Tafel.KEINE
    private val systemAn: Boolean get() = tafel != Tafel.KEINE
    private val fuss = stil.zeile()
    private val fussWerte = stil.text(13f, stil.monoMittel, Farbe.TEXT, versalien = true, sperrung = 0.04f)
    private val ui = Handler(Looper.getMainLooper())
    private val vollSchirm = VollbildSchirm(stil, vollbildWurzel, verlassen = { vollbildSetzen(false) }, stoppen = { vollbildSetzen(false); live.stoppen() })
    /** STOP in der Fußzeile unter den Flächen, solange die Drohne fliegt oder die Motoren laufen: die Flächen verdecken den großen. */
    private val systemStopp = Knopf(stil, Knopf.Stilart.STOPP) { live.stoppen() }
    private var lageVorher: String? = null
    /** Vollbild der gerade gezeigten Ansicht; wird nicht gespeichert, nach dem App-Start immer aus. */
    var vollbild = false; private set
    private var hell = true
    private var laeuft = false

    private val kAnsicht = LeistenKnopf(stil, "Switch view: map, live view, 3D") { if (ortswahl) aktionen.ortFertig(); gast.ansichtWechseln(); leisteZeigen() }
    private val kEinpassen = LeistenKnopf(stil, "Fit map / 3D view near or wide") { gast.einpassen() }
    private val kVoll = LeistenKnopf(stil, "Full screen") { vollbildSetzen(true) }
    private val kSystem = LeistenKnopf(stil, "System status") { tafelSchalten(Tafel.SYSTEM) }
    private val kMissionen = LeistenKnopf(stil, "Missions") { tafelSchalten(Tafel.MSN) }
    private val kProtokoll = LeistenKnopf(stil, "Log (hold to copy)") { tafelSchalten(Tafel.LOG) }
    private val kMehr = LeistenKnopf(stil, "Settings") { tafelSchalten(Tafel.SET) }

    /** Höhe über Start je Sekunde für den Verlauf im System-Bildschirm; NaN = kein Wert. */
    private val hoehen = FloatArray(60) { Float.NaN }
    private var letzteProbeMs = 0L

    private val zuhoerer: (CockpitZustand) -> Unit = { z -> if (Looper.myLooper() == Looper.getMainLooper()) zeige(z) else ui.post { zeige(z) } }
    private val takt = object : Runnable { override fun run() { hell = !hell; live.blinken(hell); if (laeuft) ui.postDelayed(this, 500) } }

    init {
        kProtokoll.setOnLongClickListener { gast.protokollKopieren(); true }
        listOf(kMissionen, kAnsicht, kEinpassen, kVoll, kSystem, kProtokoll, kMehr).forEachIndexed { i, k ->
            leiste.addView(k, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { if (i > 0) topMargin = stil.dp(4) })
        }
        live.beiLeistenhoehe = { raenderMelden() }
        systemWurzel.setBackgroundColor(Farbe.BG)
        systemWurzel.addView(tafelFlaeche, FrameLayout.LayoutParams(-1, -1))
        // Fußzeile: eigene Zeile unter den Flächen, damit STOP nichts verdeckt (Felix 22.11.2026: Environment war verdeckt).
        systemStopp.contentDescription = "Stop and return home"
        fuss.setBackgroundColor(Farbe.BG)
        // Berührungen nicht zum verdeckten Live-Bildschirm durchlassen.
        fuss.isClickable = true; systemWurzel.isClickable = true
        fuss.addView(fussWerte, stil.lp(0, Stil.WRAP, 1f, links = 10))
        fuss.addView(systemStopp, LinearLayout.LayoutParams(stil.dp(190), stil.dp(52)))
        fuss.visibility = View.GONE
        systemWurzel.addView(fuss, FrameLayout.LayoutParams(-1, stil.dp(58), Gravity.BOTTOM))
        ortBalken.background = stil.flaeche(Farbe.BG, Farbe.ACCENT)
        ortBalken.setPadding(stil.dp(10), stil.dp(8), stil.dp(10), stil.dp(8))
        ortBalken.isClickable = true   // ein Tipp auf den Balken ist kein Tipp auf die Karte
        // Eine Zeile: links Titel und Satz, rechts Undo und Done; die Flugwerte links unten sind solange ausgeblendet.
        val ortReihe = stil.zeile()
        val ortLinks = stil.spalte()
        ortLinks.addView(ortTitel, stil.lp(Stil.MATCH, Stil.WRAP))
        ortSatz.setLineSpacing(0f, 1.2f)
        ortLinks.addView(ortSatz, stil.lp(Stil.MATCH, Stil.WRAP, oben = 3))
        ortReihe.addView(ortLinks, stil.lp(0, Stil.WRAP, 1f))
        ortZurueck.setze("Undo", false); ortZurueck.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13f)
        ortReihe.addView(ortZurueck, LinearLayout.LayoutParams(stil.dp(76), stil.dp(48)).apply { leftMargin = stil.dp(8) })
        ortReihe.addView(ortFertig, LinearLayout.LayoutParams(stil.dp(96), stil.dp(48)).apply { leftMargin = stil.dp(8) })
        ortBalken.addView(ortReihe, stil.lp(Stil.MATCH, Stil.WRAP))
        ortBalken.visibility = View.GONE
        buehne.addView(ortBalken, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = stil.dp(8); rightMargin = stil.dp(92); bottomMargin = stil.dp(8) })
        leisteZeigen()
    }

    fun start() { if (laeuft) return; laeuft = true; FlugSitzung.addZuhoerer(zuhoerer); ui.post(takt) }
    fun stopp() { sprechen.schliessen(); laeuft = false; FlugSitzung.removeZuhoerer(zuhoerer); ui.removeCallbacks(takt) }

    /** Der Gastgeber hat die Bildfläche gewechselt (auch von selbst, etwa beim Verbinden). */
    fun leisteZeigen() {
        val a = gast.ansicht
        kAnsicht.setze(a.kuerzel, !systemAn)
        kEinpassen.setze(if (a == Ansicht.DREI_D) "Zoom" else "Fit", false)
        kEinpassen.isEnabled = a != Ansicht.LIVEBILD && !systemAn
        kEinpassen.alpha = if (kEinpassen.isEnabled) 1f else 0.35f
        kVoll.setze("Full", false)
        vollKnopfZeigen(aktionen.zustand())
        kSystem.setze("Sys", tafel == Tafel.SYSTEM)
        kMissionen.setze("Msn", tafel == Tafel.MSN); kProtokoll.setze("Log", tafel == Tafel.LOG); kMehr.setze("Set", tafel == Tafel.SET)
        systemWurzel.sichtbar(systemAn)
        system.wurzel.sichtbar(tafel == Tafel.SYSTEM); missionen.rahmen.sichtbar(tafel == Tafel.MSN)
        logTafel.rahmen.sichtbar(tafel == Tafel.LOG); setTafel.rahmen.sichtbar(tafel == Tafel.SET)
        // Livebild-Fläche bleibt darunter bestehen; nur die Bedienflächen des Live-Bildschirms werden verdeckt.
        liveWurzel.importantForAccessibility = if (systemAn) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        live.hud.mitFadenkreuz = a == Ansicht.LIVEBILD
        // Die kleine Bahnkarte nur über dem Livebild: Karte und 3D-Ansicht zeigen den Weg selbst.
        live.hud.mitBahn = a == Ansicht.LIVEBILD
        raenderMelden()
    }

    /** Leistenknopf: dieselbe Fläche noch einmal = zu, eine andere = wechseln. */
    private fun tafelSchalten(t: Tafel) = tafelSetzen(if (tafel == t) Tafel.KEINE else t)

    private fun tafelSetzen(t: Tafel) {
        if (t == tafel) return
        // Eine Fläche über der laufenden Ortswahl beendet sie (der Brief bleibt), sonst stünde die Seite im falschen Zustand.
        if (ortswahl && t != Tafel.KEINE) aktionen.ortFertig()
        sprechen.schliessen()
        if (t != Tafel.KEINE && vollbild) vollbildSetzen(false)
        tafel = t
        when (t) {
            Tafel.SET -> setTafel.oeffnen { gast.einstellungen() }
            Tafel.LOG -> logTafel.geoeffnet()
            else -> {}
        }
        leisteZeigen(); zeige(aktionen.zustand())
    }

    /** Ein Menü des Gastgebers in der Set-Fläche zeigen (statt als Pop-up); öffnet die Fläche, wenn sie zu ist. */
    fun menue(titel: String, eintraege: List<Pair<String, () -> Unit>>) {
        if (tafel != Tafel.SET) { if (vollbild) vollbildSetzen(false); tafel = Tafel.SET; setTafel.leeren() }
        setTafel.ebene(titel, eintraege)
        leisteZeigen(); zeige(aktionen.zustand())
    }

    /** Die Set-Fläche ist offen: Erklärungen und Auswahlen des Gastgebers gehören dann dorthin statt in ein Pop-up. */
    val setOffen: Boolean get() = tafel == Tafel.SET
    fun setText(titel: String, text: String) { if (setOffen) setTafel.text(titel, text) }
    fun setZurueck() { if (setOffen) setTafel.zurueck() }

    /** Für die Zurück-Taste: in Set eine Ebene zurück, sonst die offene Fläche schließen; false = es war keine offen. */
    fun tafelZurueck(): Boolean {
        if (sprechen.offen) { sprechen.schliessen(); return true }
        if (ortswahl) { aktionen.ortFertig(); return true }
        if (tafel == Tafel.KEINE) return false
        if (tafel == Tafel.SET && setTafel.zurueck()) return true
        if (tafel == Tafel.MSN && missionen.zurueck()) return true
        tafelSetzen(Tafel.KEINE); return true
    }

    /** Links die Flugwerte (wie im HUD 150 dp), oben Status-, Warnzeile und Kompassband. */
    private fun raenderMelden() = if (vollbild) gast.bildRaender(0, 0, 0, 0) else gast.bildRaender(stil.dp(150), live.hud.obenPx + stil.dp(46), 0, 0)

    private fun vollKnopfZeigen(z: CockpitZustand) {
        kVoll.isEnabled = !systemAn && vollbildErlaubt(z)
        kVoll.alpha = if (kVoll.isEnabled) 1f else 0.35f
    }

    /** Vollbild ein oder aus. Ein geht nur, solange [vollbildErlaubt]; der Grund steht sonst in der Warnzeile. */
    private fun vollbildSetzen(an: Boolean) {
        val neu = an && !systemAn && vollbildErlaubt(aktionen.zustand())
        if (neu == vollbild) return
        vollbild = neu
        gast.vollbild(neu)
        raenderMelden()
        sprechen.lage(0, !neu)
        if (neu) vollSchirm.zeige(aktionen.zustand(), live.stoppFrei)
    }

    /** Was der Pilot gerade sieht; daran ist das Sprechen gebunden. */
    private fun kontext(): SprechEbene.Kontext {
        val z = aktionen.zustand()
        val flug = when {
            z.phase == CockpitZustand.Phase.PAUSIERT -> "plan paused, drone hovering"
            z.plan.laeuft -> "plan running"
            z.fliegt -> "in flight"
            z.motorenAn -> "motors on"
            z.verbindung != CockpitZustand.Verbindung.VERBUNDEN -> "drone not connected"
            else -> "on the ground"
        }
        val imFlug = z.fliegt || z.motorenAn
        return when (tafel) {
            Tafel.SYSTEM -> SprechEbene.Kontext("System status · $flug", "“What does the wind warning mean?”" + if (imFlug) " · “Stop here.”" else "")
            Tafel.LOG -> SprechEbene.Kontext("Log · $flug", "“What went wrong in the last flight?”" + if (imFlug) " · “Stop here.”" else "")
            Tafel.SET -> SprechEbene.Kontext("Settings · $flug", "“Where do I switch the map?”" + if (imFlug) " · “Stop here.”" else "")
            Tafel.MSN -> SprechEbene.Kontext(missionen.kontext + (if (z.auftrag.art != 0) " (${z.auftrag.phase.name.lowercase(Locale.US)})" else "") + " · $flug", "“Which mission do I need to check a parking lot?”" + if (imFlug) " · “Stop here.”" else "")
            Tafel.KEINE -> SprechEbene.Kontext(gast.ansicht.kuerzel + " view · $flug" + (if (z.plan.geladen) " · plan “${z.plan.name}”" else ""),
                if (z.plan.laeuft && z.phase != CockpitZustand.Phase.PAUSIERT) "“Stop here.” · “Come home.”" else if (imFlug) "“What do you see?” · “Look closer at the roof.” · “Come home.”" else "“Is the plan ready to fly?” · “What is missing for the start?”")
        }
    }

    /**
     * Was ohne Central Command gehen muss, weil es im Flug sofort und ohne Netz wirken soll: Anhalten. Heimkommen wird nur
     * vorgeschlagen und braucht einen Druck (es gibt Startplätze, an denen ein Heimflug falsch wäre). Abheben und Starten nie.
     */
    private var warnLageVorher = "|false"
    private fun lokal(t: String): SprechEbene.Lokal? {
        val s = t.lowercase(Locale.ROOT).trim().trimEnd('.', '!', '?')
        val z = aktionen.zustand()
        val woerter = s.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        // Verneinungen und Fragen entscheidet nicht das Muster, sondern Central Command (oder niemand): lieber nichts tun als das Falsche.
        val unsicher = t.trim().endsWith("?") || woerter.size > 6 ||
            Regex("\\b(don't|dont|do not|not|never|no|nicht|kein|keine|nie|why|should|can|could|what|when|warum|soll|sollte|kann|was|wann)\\b").containsMatchIn(s)
        val anhalten = !unsicher && Regex("^(bitte |please |drohne |drone )?(stop+|stopp|halt|pause|pausieren|anhalten|warte|wait|hold)\\b").containsMatchIn(s) ||
            !unsicher && Regex("\\b(anhalten|pausieren)$").containsMatchIn(s)
        val heim = !unsicher && Regex("\\b(come|return|fly|go) (back )?home\\b|\\b(komm|flieg|fliege) (bitte )?(nach hause|zurück|heim)\\b|^(nach hause|return home|come back)$").containsMatchIn(s)
        val start = Regex("^(start|take ?off|abheben|starten|heb ab|los|go)( now| jetzt| bitte| please)*$").matches(s)
        val heimVorschlag = "Stop · Return home" to { val j = aktionen.zustand(); if (j.fliegt || j.motorenAn) aktionen.stoppUndHeim() }
        return when {
            anhalten -> if (aktionen.hauptaktionWaehlen() == CockpitZustand.Hauptaktion.PAUSE) {
                    aktionen.pausieren()
                    SprechEbene.Lokal("Pausing. The drone hovers, you have control." + if (heim) " Return home needs your press." else "", if (heim) heimVorschlag else null)
                }
                else if (heim && (z.fliegt || z.motorenAn)) SprechEbene.Lokal("Return home flies back to the start point. Press to confirm.", heimVorschlag)
                else if (z.phase == CockpitZustand.Phase.PAUSIERT) SprechEbene.Lokal("Already paused. The drone hovers.")
                else if (z.fliegt) SprechEbene.Lokal("No plan is running that I could pause. You fly by hand; to end it press Stop · Return home.")
                else SprechEbene.Lokal("Nothing is flying.")
            heim -> if (z.fliegt || z.motorenAn) SprechEbene.Lokal("Return home stops the plan and flies back to the start point. Press to confirm.", heimVorschlag)
                else SprechEbene.Lokal("Nothing is flying.")
            start -> SprechEbene.Lokal("Take-off stays with your thumb: hold the start button. I never take off by voice.")
            else -> null
        }
    }

    /** Für die Zurück-Taste: verlässt das Vollbild; false = es war keins an. */
    fun vollbildVerlassen(): Boolean { if (!vollbild) return false; vollbildSetzen(false); return true }

    private fun zeige(z: CockpitZustand) {
        val jetzt = System.currentTimeMillis()
        if (jetzt - letzteProbeMs >= 1000) {
            letzteProbeMs = jetzt
            System.arraycopy(hoehen, 1, hoehen, 0, hoehen.size - 1)
            hoehen[hoehen.size - 1] = if (z.datenDa) z.hoeheM?.toFloat() ?: Float.NaN else Float.NaN
        }
        if (vollbild && !vollbildErlaubt(z)) vollbildSetzen(false)
        // Orte werden am Boden gewählt: hebt die Drohne ab, endet die Ortswahl und die Flugwerte sind wieder da.
        if (ortswahl && (z.fliegt || z.motorenAn)) aktionen.ortFertig()
        ortswahlZeigen(z.auftrag)
        // Kommt eine neue Warnung, Pause oder ein Funkabriss, während der System-Bildschirm offen ist: zurück zum Live-Bildschirm
        // mit Warnzeile und Knöpfen. Nur beim Wechsel der Art (Ziffern zählen nicht), damit man ihn danach bewusst wieder öffnen kann.
        val lage = if (vollbildErlaubt(z)) null else (z.warnung?.titel?.filterNot { it.isDigit() } ?: "") + "|" + z.funkabriss + "|" + (z.phase == CockpitZustand.Phase.PAUSIERT)
        if (systemAn && lage != null && lage != lageVorher) { tafel = Tafel.KEINE; leisteZeigen() }
        lageVorher = lage
        // Der Sprechstreifen liegt über der Warnzeile: kommt eine neue Warnung oder reißt der Funk ab, macht er Platz.
        val warnLage = (z.warnung?.titel?.filterNot { it.isDigit() } ?: "") + "|" + z.funkabriss
        if (warnLage != warnLageVorher && warnLage != "|false" && sprechen.offen) sprechen.schliessen()
        warnLageVorher = warnLage
        if (systemAn) {
            val imFlug = z.fliegt || z.motorenAn || z.funkabriss
            if (fuss.visibility != (if (imFlug) View.VISIBLE else View.GONE)) {
                fuss.sichtbar(imFlug)
                (tafelFlaeche.layoutParams as FrameLayout.LayoutParams).bottomMargin = if (imFlug) stil.dp(58) else 0
                tafelFlaeche.requestLayout()
            }
            if (imFlug) {
                systemStopp.setze("Stop · Return home", Zeichen.Art.STOP, live.stoppFrei)
                systemStopp.contentDescription = "Stop and return home"
                val hoehe = if (z.datenDa) z.hoeheM?.let { "ALT ${Math.round(it)} m" } else null
                val akku = if (z.datenDa) z.akkuProzent?.let { "BAT $it %" } else null
                fussWerte.zeige(listOfNotNull(hoehe, akku, z.warnung?.titel).joinToString("  ·  "))
                fussWerte.farbe(if (z.warnung != null) Farbe.WARN_TEXT else Farbe.TEXT)
            }
            if (tafel == Tafel.LOG) logTafel.zeige()
        }
        sprechen.lage(if (systemAn && (z.fliegt || z.motorenAn || z.funkabriss)) stil.dp(58) else 0, !vollbild, systemAn)
        live.zeige(z)
        if (tafel == Tafel.SYSTEM) system.zeige(z, hoehen, windText(z))
        if (tafel == Tafel.MSN) missionen.zeige(z)
    }

    /**
     * Ortswahl einer Mission: die Missions-Fläche macht der Karte Platz, ein Balken sagt, was zu tippen ist; mit „Done“
     * (oder der Zurück-Taste) kommt der Brief wieder. Die Orte selbst zeichnet der Gastgeber auf die Karte.
     */
    private fun ortswahlZeigen(a: CockpitZustand.Auftrag) {
        val an = a.phase == CockpitZustand.AuftragPhase.ORT
        if (an != ortswahl) {
            ortswahl = an
            if (an) { if (vollbild) vollbildSetzen(false); sprechen.schliessen(); tafel = Tafel.KEINE; gast.karteZeigen() } else tafel = Tafel.MSN
            ortBalken.sichtbar(an); live.werteSichtbar(!an)
            leisteZeigen()
        }
        val s = "${a.art}|${a.phase}|${a.ortText}|${a.ecken}|${a.felder.joinToString { it.gewaehlt.toString() }}|${a.kandidaten.size}"
        if (s != orteSchluessel) { orteSchluessel = s; gast.missionsOrte() }
        if (!an) return
        val flaeche = a.ortArt == 2
        ortTitel.zeige("0${a.art} · " + when (a.art) { 6 -> "tap the corners"; 4 -> "tap the middle"; 7 -> "tap the crowd"; else -> "tap the place" })
        val satz = a.ortHinweis ?: when {
            flaeche && a.ecken == 0 -> "Tap the corners of the property on the map, one after the other around it."
            flaeche && a.ecken < 3 -> "${a.ecken} corner${if (a.ecken == 1) "" else "s"}. At least three are needed."
            flaeche -> "${a.ortText}. Tap more corners or press Done."
            a.ortText == null -> when (a.art) { 4 -> "Tap the middle of the search area on the map."; 7 -> "Tap the middle of the crowd on the map. The red circle is the keep-out zone."; else -> "Tap the place on the map." }
            else -> "Place ${a.ortText}. Tap again to move it, or press Done."
        }
        ortSatz.zeige(satz); ortSatz.farbe(if (a.ortHinweis != null) Farbe.WARN_TEXT else Farbe.TEXT)
        ortZurueck.alpha = if (a.ortText != null) 1f else 0.35f
        ortFertig.setze(if (a.ortText == null) "Back" else "Done", null, true)
    }
}

    /** Wind: Messwert der Drohne im Flug; ohne Messwert die Warnstufe; ohne beides „–“. */
internal fun windText(z: CockpitZustand): Pair<String?, Ton> {
        if (!z.datenDa) return null to Ton.AUS
        val wi = z.wind
        val mess = wi.ms?.let { String.format(Locale.US, "%.0f m/s", it) + (wi.richtung?.let { r -> " " + r.split('_').joinToString("") { p -> p.take(1) } } ?: "") }
        return when (wi.warnung) {
            2 -> ((mess?.let { "$it · " } ?: "") + "strong") to Ton.WARN
            1 -> ((mess?.let { "$it · " } ?: "") + "moderate") to Ton.WARN
            else -> (mess ?: if (wi.warnung == 0) "no warning" else null) to Ton.NEUTRAL
        }
    }
