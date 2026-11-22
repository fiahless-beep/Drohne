package com.felixos.drohne.cockpit

import android.view.Gravity
import android.widget.FrameLayout
import com.felixos.drohne.CockpitZustand
import java.util.Locale

/**
 * Regel für das Vollbild (Felix 22.11.2026): erlaubt nur, solange nichts die Aufmerksamkeit braucht. Bei einer Warnung,
 * Funkabriss, ausbleibenden Daten oder Pause endet es von selbst, damit Warnzeile und Knöpfe sofort da sind.
 * Ohne Verbindung (Karte ansehen, nach dem Flug etwas suchen) bleibt es erlaubt.
 */
internal fun vollbildErlaubt(z: CockpitZustand): Boolean =
    z.warnung == null && !z.funkabriss && z.keineDatenSeitS == null && z.phase != CockpitZustand.Phase.PAUSIERT

/**
 * Was im Vollbild über der Ansicht bleibt: oben links der Knopf zum Verlassen und eine kleine Zeile Höhe und Akku,
 * unten rechts ein kleiner STOP. Die Fläche selbst fängt keine Berührung ab; ein Tipp ins Livebild wählt weiter ein Motiv.
 */
internal class VollbildSchirm(private val stil: Stil, wurzel: FrameLayout, verlassen: () -> Unit, stoppen: () -> Unit) {
    private val werte = stil.text(12f, stil.monoMittel, Farbe.TEXT, versalien = true, sperrung = 0.04f)
    private val stopp = Knopf(stil, Knopf.Stilart.STOPP, stoppen)

    init {
        val raus = LeistenKnopf(stil, "Leave full screen", verlassen).apply { setze("Exit", false) }
        wurzel.addView(raus, FrameLayout.LayoutParams(stil.dp(48), stil.dp(48), Gravity.TOP or Gravity.START).apply { leftMargin = stil.dp(8); topMargin = stil.dp(8) })
        werte.setBackgroundColor(Farbe.PANEL_A)
        werte.setPadding(stil.dp(6), stil.dp(4), stil.dp(6), stil.dp(4))
        wurzel.addView(werte, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = stil.dp(62); topMargin = stil.dp(8) })
        stopp.contentDescription = "Stop and return home"
        wurzel.addView(stopp, FrameLayout.LayoutParams(stil.dp(112), stil.dp(52), Gravity.BOTTOM or Gravity.END).apply { rightMargin = stil.dp(8); bottomMargin = stil.dp(8) })
    }

    fun zeige(z: CockpitZustand, stoppFrei: Boolean) {
        val hoehe = if (z.datenDa) z.hoeheM?.let { "ALT ${Math.round(it)} m" } else null
        val akku = if (z.datenDa) z.akkuProzent?.let { "BAT $it %" } else null
        val t = listOfNotNull(hoehe, akku).joinToString(" · ")
        werte.zeige(t); werte.sichtbar(t.isNotEmpty())
        stopp.setze("Stop", Zeichen.Art.STOP, stoppFrei)
        stopp.contentDescription = "Stop and return home"
    }
}
