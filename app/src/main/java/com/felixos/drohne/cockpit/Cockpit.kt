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
    fun protokoll()
    fun protokollKopieren()
    fun einstellungen()
    fun copilot()
    fun selbsttest()
}

/**
 * Das neue Cockpit („Military Tactical“, freigegeben 22.11.2026): Leiste links, Live/HUD-Bildschirm und System-Bildschirm.
 * Kennt vom Flugkern nur den Vertrag: liest [CockpitZustand] aus der [FlugSitzung] und ruft [Aktionen].
 */
class Cockpit(c: Context, leiste: LinearLayout, buehne: FrameLayout, feld: FrameLayout, private val liveWurzel: View, private val systemWurzel: FrameLayout, vollbildWurzel: FrameLayout, private val gast: Gastgeber) {
    private val stil = Stil(c)
    private val aktionen: Aktionen = FlugSitzung
    private val live = LiveSchirm(stil, aktionen, buehne, feld)
    private val system = SystemSchirm(stil, systemWurzel, gast)
    private val ui = Handler(Looper.getMainLooper())
    private val vollSchirm = VollbildSchirm(stil, vollbildWurzel, verlassen = { vollbildSetzen(false) }, stoppen = { vollbildSetzen(false); live.stoppen() })
    private var systemAn = false
    /** Kleiner STOP über dem System-Bildschirm, solange die Drohne fliegt oder die Motoren laufen: er verdeckt sonst den großen. */
    private val systemStopp = Knopf(stil, Knopf.Stilart.STOPP) { live.stoppen() }
    private var ruhigVorher = true
    /** Vollbild der gerade gezeigten Ansicht; wird nicht gespeichert, nach dem App-Start immer aus. */
    var vollbild = false; private set
    private var hell = true
    private var laeuft = false

    private val kAnsicht = LeistenKnopf(stil, "Switch view: map, live view, 3D") { gast.ansichtWechseln(); leisteZeigen() }
    private val kEinpassen = LeistenKnopf(stil, "Fit map / 3D view near or wide") { gast.einpassen() }
    private val kVoll = LeistenKnopf(stil, "Full screen") { vollbildSetzen(true) }
    private val kSystem = LeistenKnopf(stil, "System status") { systemAn = !systemAn; leisteZeigen(); zeige(aktionen.zustand()) }
    private val kCopilot = LeistenKnopf(stil, "Ask Claude") { gast.copilot() }
    private val kProtokoll = LeistenKnopf(stil, "Log (hold to copy)") { gast.protokoll() }
    private val kMehr = LeistenKnopf(stil, "Settings") { gast.einstellungen() }

    /** Höhe über Start je Sekunde für den Verlauf im System-Bildschirm; NaN = kein Wert. */
    private val hoehen = FloatArray(60) { Float.NaN }
    private var letzteProbeMs = 0L

    private val zuhoerer: (CockpitZustand) -> Unit = { z -> if (Looper.myLooper() == Looper.getMainLooper()) zeige(z) else ui.post { zeige(z) } }
    private val takt = object : Runnable { override fun run() { hell = !hell; live.blinken(hell); if (laeuft) ui.postDelayed(this, 500) } }

    init {
        kProtokoll.setOnLongClickListener { gast.protokollKopieren(); true }
        listOf(kAnsicht, kEinpassen, kVoll, kSystem, kCopilot, kProtokoll, kMehr).forEachIndexed { i, k ->
            leiste.addView(k, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { if (i > 0) topMargin = stil.dp(4) })
        }
        kCopilot.setze("AI", false); kProtokoll.setze("Log", false); kMehr.setze("Set", false)
        live.beiLeistenhoehe = { raenderMelden() }
        systemStopp.contentDescription = "Stop and return home"
        systemStopp.visibility = View.GONE
        systemWurzel.addView(systemStopp, FrameLayout.LayoutParams(stil.dp(112), stil.dp(52), Gravity.BOTTOM or Gravity.END).apply { rightMargin = stil.dp(8); bottomMargin = stil.dp(8) })
        leisteZeigen()
    }

    fun start() { if (laeuft) return; laeuft = true; FlugSitzung.addZuhoerer(zuhoerer); ui.post(takt) }
    fun stopp() { laeuft = false; FlugSitzung.removeZuhoerer(zuhoerer); ui.removeCallbacks(takt) }

    /** Der Gastgeber hat die Bildfläche gewechselt (auch von selbst, etwa beim Verbinden). */
    fun leisteZeigen() {
        val a = gast.ansicht
        kAnsicht.setze(a.kuerzel, !systemAn)
        kEinpassen.setze(if (a == Ansicht.DREI_D) "Zoom" else "Fit", false)
        kEinpassen.isEnabled = a != Ansicht.LIVEBILD && !systemAn
        kEinpassen.alpha = if (kEinpassen.isEnabled) 1f else 0.35f
        kVoll.setze("Full", false)
        vollKnopfZeigen(aktionen.zustand())
        kSystem.setze("Sys", systemAn)
        systemWurzel.sichtbar(systemAn)
        // Livebild-Fläche bleibt darunter bestehen; nur die Bedienflächen des Live-Bildschirms werden verdeckt.
        liveWurzel.importantForAccessibility = if (systemAn) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        live.hud.mitFadenkreuz = a == Ansicht.LIVEBILD
        // Die kleine Bahnkarte nur über dem Livebild: Karte und 3D-Ansicht zeigen den Weg selbst.
        live.hud.mitBahn = a == Ansicht.LIVEBILD
        raenderMelden()
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
        if (neu) vollSchirm.zeige(aktionen.zustand(), live.stoppFrei)
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
        // Kommt eine Warnung, Pause oder ein Funkabriss, während der System-Bildschirm offen ist: zurück zum Live-Bildschirm
        // mit Warnzeile und Knöpfen. Nur beim Wechsel, damit man ihn danach bewusst wieder öffnen kann.
        val ruhig = vollbildErlaubt(z)
        if (systemAn && ruhigVorher && !ruhig) { systemAn = false; leisteZeigen() }
        ruhigVorher = ruhig
        if (systemAn) {
            systemStopp.visibility = if (z.fliegt || z.motorenAn) View.VISIBLE else View.GONE
            systemStopp.setze("Stop", Zeichen.Art.STOP, live.stoppFrei)
            systemStopp.contentDescription = "Stop and return home"
        }
        vollKnopfZeigen(z)
        if (vollbild) vollSchirm.zeige(z, live.stoppFrei)
        live.zeige(z)
        if (systemAn) system.zeige(z, hoehen, windText(z))
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
