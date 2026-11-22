package com.felixos.drohne

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenLinkTest {
    // Erfundene Werte im Format echter Tokens.
    private val fine = "github_pat_" + "A1b2C3d4E5".repeat(2) + "aa_" + "Zz09".repeat(14) + "wxyz"
    private val classic = "ghp_" + "a1B2".repeat(9)

    @Test fun acceptsFineGrainedAndClassicTokens() {
        val a = TokenLink.parse("felixdrohne://token?v=$fine") as TokenLink.Result.Token
        assertEquals(fine, a.value); assertEquals("wxyz", a.ending)
        val b = TokenLink.parse("  FelixDrohne://token/?v=$classic\n") as TokenLink.Result.Token
        assertEquals(classic, b.value)
    }

    @Test fun otherStartsAreNotALink() {
        for (s in listOf(null, "", "   ", "https://example.org/?v=$classic", "content://x/y", "nur text"))
            assertTrue("$s", TokenLink.parse(s) is TokenLink.Result.NotALink)
    }

    @Test fun rejectsWrongShape() {
        val bad = listOf(
            "felixdrohne://token",                                  // kein Token
            "felixdrohne://token?v=",                               // leer
            "felixdrohne://token?v=hallo",                          // kein GitHub-Format
            "felixdrohne://token?v=ghp_kurz",                       // zu kurz
            "felixdrohne://token?v=$classic&v=$classic",            // doppelt
            "felixdrohne://token?v=$classic&x=1",                   // Beiwerk
            "felixdrohne://token?x=$classic",                       // falscher Name
            "felixdrohne://andere?v=$classic",                      // falsches Ziel
            "felixdrohne://token/pfad?v=$classic",                  // Pfad
            "felixdrohne://token?v=$classic#frag",                  // Fragment
            "felixdrohne://nutzer@token?v=$classic",                // Benutzerteil
            "felixdrohne://token?v=ghp_%41" + "a1B2".repeat(9),     // kodierte Zeichen
            "felixdrohne://token?v=$classic%0A",                    // Zeilenumbruch
            "felixdrohne://token?v=ghp_" + "a1B2".repeat(8) + "a1 2", // Leerzeichen → Link nicht lesbar
            "felixdrohne:token?v=$classic",                         // ohne //
        )
        for (s in bad) assertTrue(s.take(40), TokenLink.parse(s) is TokenLink.Result.Invalid)
    }

    /** Weder Fehlermeldung noch toString dürfen den Token enthalten (beides kann im Protokoll landen). */
    @Test fun neverLeaksTheToken() {
        val ok = TokenLink.parse("felixdrohne://token?v=$classic")
        assertFalse(ok.toString().contains(classic))
        val secret = "ghp_" + "Q".repeat(20)
        val bad = TokenLink.parse("felixdrohne://token?v=$secret&x=1") as TokenLink.Result.Invalid
        assertFalse(bad.reason.contains(secret)); assertFalse(bad.reason.contains("QQQQ"))
    }

    @Test fun restoreIsOnlyReportedOnFreshInstallWithSettings() {
        assertTrue(TokenLink.restoredFromBackup(true, 1000, 1000, 3))
        assertFalse(TokenLink.restoredFromBackup(true, 1000, 1000, 0))     // frische Installation ohne Backup
        assertFalse(TokenLink.restoredFromBackup(true, 1000, 2000, 3))     // Update einer bestehenden Installation
        assertFalse(TokenLink.restoredFromBackup(false, 1000, 1000, 3))    // späterer Start
    }
}
