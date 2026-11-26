package com.felixos.drohne.sprache

import com.felixos.drohne.CockpitZustand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Das Verhalten des früheren Cockpit.lokal() (Stand 60fd609), Satz für Satz: Anhalten sofort, Heim nur als Vorschlag mit
 * Druck, Abheben und Starten immer abgelehnt, Verneinung, Frage und lange Sätze lösen nichts aus. Diese Tests standen
 * vor dem Umbau fest und müssen gleich bleiben, was auch immer an Regeln oder Modell dazukommt.
 */
class BestandTest {
    private fun e(satz: String, z: CockpitZustand) = Lagen.entscheide(satz, z, if (z.fliegt) Sicht.LIVE else Sicht.KARTE)

    private val haltWorte = listOf("Stopp", "Stop", "stoppp", "Halt", "Pause", "Pausieren", "Anhalten", "Warte", "Wait", "Hold", "Stop here.", "Bitte anhalten", "Please stop", "Drohne stopp",
        "Drone stop", "Stopp!", "Halt an", "Hold on", "Warte mal kurz", "Drohne anhalten", "Bitte die Drohne pausieren")
    private val heimWorte = listOf("Come home", "Return home", "Fly home", "Go home", "Come back home", "Komm nach Hause", "Flieg zurück", "Fliege heim", "Komm bitte nach Hause", "Nach Hause", "Come back")
    private val startWorte = listOf("Start", "Take off", "Takeoff", "Abheben", "Starten", "Heb ab", "Los", "Go", "Take off now", "Abheben jetzt", "Start bitte", "Go please", "Starten jetzt bitte")

    @Test fun anhaltenPausiertDenLaufendenPlanSofort() = haltWorte.forEach { s ->
        val r = e(s, Lagen.planLaeuft)
        assertTrue("$s → ${Lagen.text(r)}", r is Entscheidung.Ausfuehren && r.befehl == Befehl.Anhalten && r.dazu == null)
        assertEquals(s, "Pausing. The drone hovers, you have control.", (r as Entscheidung.Ausfuehren).sagt)
    }

    @Test fun anhaltenAmBodenSagtNur() = haltWorte.forEach { s -> assertAntwort(s, "Nothing is flying.", e(s, Lagen.boden)) }

    @Test fun anhaltenImPausiertenPlan() = haltWorte.forEach { s -> assertAntwort(s, "Already paused. The drone hovers.", e(s, Lagen.pausiert)) }

    @Test fun anhaltenImHandflug() = haltWorte.forEach { s ->
        assertAntwort(s, "No plan is running that I could pause. You fly by hand; to end it press Stop · Return home.", e(s, Lagen.handflug)) }

    @Test fun heimIstImFlugNurEinVorschlag() = heimWorte.forEach { s ->
        for (z in listOf(Lagen.planLaeuft, Lagen.pausiert, Lagen.handflug)) {
            val r = e(s, z)
            assertTrue("$s → ${Lagen.text(r)}", r is Entscheidung.Vorschlag && r.befehl == Befehl.Heim)
            r as Entscheidung.Vorschlag
            assertEquals("Stop · Return home", r.knopf)
            assertEquals("Return home stops the plan and flies back to the start point. Press to confirm.", r.sagt)
            assertEquals(0L, r.halteMs)
        }
    }

    @Test fun heimAmBodenSagtNur() = heimWorte.forEach { s -> assertAntwort(s, "Nothing is flying.", e(s, Lagen.boden)) }

    @Test fun stoppUndHeimPausiertUndSchlaegtHeimVor() {
        for (s in listOf("Stopp und komm heim", "Stop and come home", "Halt, flieg zurück")) {
            val r = e(s, Lagen.planLaeuft)
            assertTrue("$s → ${Lagen.text(r)}", r is Entscheidung.Ausfuehren && r.befehl == Befehl.Anhalten && r.dazu?.befehl == Befehl.Heim)
            assertEquals("Pausing. The drone hovers, you have control. Return home needs your press.", (r as Entscheidung.Ausfuehren).sagt)
            assertEquals("Stop · Return home", r.dazu!!.knopf)
            // Ohne laufenden Plan, aber in der Luft: nur der Vorschlag.
            val h = e(s, Lagen.handflug)
            assertTrue("$s → ${Lagen.text(h)}", h is Entscheidung.Vorschlag && h.befehl == Befehl.Heim)
            assertEquals("Return home flies back to the start point. Press to confirm.", (h as Entscheidung.Vorschlag).sagt)
            assertAntwort(s, "Nothing is flying.", e(s, Lagen.boden))
        }
    }

    @Test fun abhebenUndStartenWerdenImmerAbgelehnt() = startWorte.forEach { s ->
        for ((name, z) in Lagen.alle) assertAntwort("$s ($name)", "Take-off stays with your thumb: hold the start button. I never take off by voice.", e(s, z))
    }

    /** Früher gingen diese Sätze an Central Command (das im laufenden Plan gesperrt ist); lokal geschah nichts. Das bleibt: nichts Flugwirksames. */
    @Test fun verneinungFrageUndLangeSaetzeLoesenNichtsAus() {
        val saetze = listOf("Nicht anhalten", "Don't stop", "Do not stop", "Never stop", "No stop", "Kein Stopp", "Stopp nicht", "Soll ich anhalten?", "Should I stop", "Can you stop", "Kann ich anhalten",
            "Warum anhalten", "Stop?", "Flieg nicht nach Hause", "Don't come home", "Soll ich nach Hause", "Come home?", "Komm noch nicht nach Hause",
            "Stopp ich wollte nur sagen dass alles gut aussieht", "Bitte komm jetzt sofort ganz schnell nach Hause zurück")
        for (s in saetze) for (z in listOf(Lagen.planLaeuft, Lagen.pausiert, Lagen.handflug, Lagen.folgen, Lagen.boden)) {
            val r = e(s, z)
            assertTrue("$s → ${Lagen.text(r)}", r is Entscheidung.Nichts || r is Entscheidung.Antwort)
        }
    }

    private fun assertAntwort(satz: String, erwartet: String, r: Entscheidung) {
        assertTrue("$satz → ${Lagen.text(r)}", r is Entscheidung.Antwort)
        assertEquals(satz, erwartet, (r as Entscheidung.Antwort).text)
    }
}
