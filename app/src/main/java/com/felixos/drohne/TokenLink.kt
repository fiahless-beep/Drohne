package com.felixos.drohne

/**
 * GitHub-Token ohne Tippen (Felix 22.11.2026): ein QR-Code mit dem Link `felixdrohne://token?v=<token>`, gescannt mit der
 * Kamera-App des Handys, öffnet die App. Hier nur das Lesen und Prüfen des Links (rein, ohne Android, getestet);
 * gespeichert wird erst nach Rückfrage in der Oberfläche. Der Token gehört nie ins Protokoll.
 */
object TokenLink {
    const val SCHEME = "felixdrohne"
    const val HOST = "token"

    sealed class Result {
        /** Kein Token-Link (anderer Start der App): nichts tun. */
        object NotALink : Result()
        class Token(val value: String) : Result() {
            /** Für die Rückfrage: nur die letzten 4 Zeichen. */
            val ending: String get() = value.takeLast(4)
            override fun toString() = "Token(…)"   // nie den Wert ausgeben
        }
        /** Token-Link, aber nicht brauchbar; [reason] nennt nie den Inhalt. */
        class Invalid(val reason: String) : Result()
    }

    // Fine-grained (github_pat_…) und klassische Tokens (ghp_… und Verwandte), wie GitHub sie ausstellt.
    private val FINE = Regex("github_pat_[A-Za-z0-9_]{22,255}")
    private val CLASSIC = Regex("gh[pousr]_[A-Za-z0-9]{36,255}")

    fun looksLikeToken(t: String): Boolean = FINE.matches(t) || CLASSIC.matches(t)

    fun parse(link: String?): Result {
        if (link.isNullOrBlank()) return Result.NotALink
        val u = try { java.net.URI(link.trim()) } catch (_: Throwable) { return if (link.trim().startsWith("$SCHEME:", ignoreCase = true)) Result.Invalid("The link is not readable.") else Result.NotALink }
        if (!SCHEME.equals(u.scheme, ignoreCase = true)) return Result.NotALink
        if (!HOST.equals(u.host, ignoreCase = true) || !u.rawPath.isNullOrEmpty() && u.rawPath != "/") return Result.Invalid("This link is not a token link.")
        if (u.rawUserInfo != null || u.port != -1 || u.rawFragment != null) return Result.Invalid("This link is not a token link.")
        val params = (u.rawQuery ?: "").split('&').filter { it.isNotEmpty() }
        if (params.size != 1 || !params[0].startsWith("v=")) return Result.Invalid("The link must carry exactly one token (v=…).")
        val raw = params[0].substring(2)
        if (raw.contains('%') || raw.contains('+')) return Result.Invalid("The token contains characters a GitHub token never has.")
        if (!looksLikeToken(raw)) return Result.Invalid("This does not look like a GitHub token (github_pat_… or ghp_…).")
        return Result.Token(raw)
    }

    /**
     * Kamen die Einstellungen aus einem Backup zurück? Erster Start einer frischen Installation (kein Merker, nie aktualisiert),
     * und trotzdem sind schon Einstellungen da.
     */
    fun restoredFromBackup(firstStartOfInstall: Boolean, firstInstallMs: Long, lastUpdateMs: Long, storedEntries: Int): Boolean =
        firstStartOfInstall && firstInstallMs == lastUpdateMs && storedEntries > 0
}
