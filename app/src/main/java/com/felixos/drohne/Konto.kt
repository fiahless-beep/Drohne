package com.felixos.drohne

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Skytac-Konto: Anmeldung per E-Mail-Code beim Skytac-Server. Mit Konto braucht die App keinen eigenen
 * Claude-Schlüssel; der Server hält ihn und zählt Credits. Fliegen, Wächter und Sticks bleiben auf dem Handy.
 * Alle Netzaufrufe blockieren: auf einem Arbeits-Thread aufrufen.
 */
object Konto {
    const val SERVER = "https://skytac-api.fly.dev"
    /** Steht anstelle eines Claude-Schlüssels, wenn Anfragen über das Konto laufen. */
    const val MARKE = "skytac-account"
    private const val PREFS = "skytac_konto"
    @Volatile private var app: Context? = null

    fun init(c: Context) { app = c.applicationContext }
    private fun prefs() = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val email: String? get() = prefs()?.getString("email", null)
    val angemeldet: Boolean get() = !prefs()?.getString("refresh", null).isNullOrBlank()
    /** Zuletzt vom Server gemeldetes Guthaben; null = noch unbekannt. */
    val credits: Int? get() = prefs()?.takeIf { it.contains("credits") }?.getInt("credits", 0)

    fun creditsMerken(n: Int) { prefs()?.edit()?.putInt("credits", n)?.apply() }
    fun abmelden() { prefs()?.edit()?.clear()?.apply() }

    /** Antwort des Servers: Statuscode und JSON (leer, wenn keins kam). */
    class Antwort(val code: Int, val json: JSONObject) {
        val ok get() = code in 200..299
        val fehler: String get() = json.optJSONObject("error")?.optString("message")?.ifBlank { null } ?: "Server error $code."
    }

    private fun post(pfad: String, body: JSONObject): Antwort {
        val conn = (URL(SERVER + pfad).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 25_000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            Antwort(code, runCatching { JSONObject(raw) }.getOrDefault(JSONObject()))
        } catch (t: Throwable) {
            Antwort(0, JSONObject().put("error", JSONObject().put("message", "No connection to Skytac: ${t.javaClass.simpleName}")))
        } finally { conn.disconnect() }
    }

    /** Schickt den Code an die Adresse. null = verschickt, sonst der Fehlertext. */
    fun codeAnfordern(mail: String): String? = post("/v1/auth/start", JSONObject().put("email", mail.trim())).let { if (it.ok) null else it.fehler }

    /** Löst den Code ein und meldet an. null = angemeldet, sonst der Fehlertext. */
    fun codeEinloesen(mail: String, code: String): String? {
        val a = post("/v1/auth/verify", JSONObject().put("email", mail.trim()).put("code", code.trim()))
        if (!a.ok) return a.fehler
        sitzungMerken(a.json, mail.trim()); return null
    }

    /** Legt ein Konto mit Passwort an. Ergebnis: null = Code aus der Mail eingeben, "" = gleich angemeldet, sonst Fehlertext. */
    fun registrieren(mail: String, passwort: String): String? {
        val a = post("/v1/auth/signup", JSONObject().put("email", mail.trim()).put("password", passwort))
        if (!a.ok) return a.fehler
        if (a.json.optString("access_token").isNotBlank()) { sitzungMerken(a.json, mail.trim()); return "" }
        return null
    }

    /** Meldet mit E-Mail und Passwort an. null = angemeldet, sonst der Fehlertext. */
    fun mitPasswort(mail: String, passwort: String): String? {
        val a = post("/v1/auth/password", JSONObject().put("email", mail.trim()).put("password", passwort))
        if (!a.ok) return a.fehler
        sitzungMerken(a.json, mail.trim()); return null
    }

    /** Adresse für die Anmeldung mit Google im Browser. Der Prüfwert bleibt auf dem Handy (PKCE). */
    fun googleAdresse(): String {
        val roh = ByteArray(48).also { java.security.SecureRandom().nextBytes(it) }
        val flags = android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        val verifier = android.util.Base64.encodeToString(roh, flags)
        val challenge = android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)), flags)
        prefs()?.edit()?.putString("verifier", verifier)?.apply()
        return "$SERVER/v1/auth/oauth/google?challenge=$challenge"
    }

    /** Rückweg aus dem Browser (skytac://auth?code=…). null = angemeldet, sonst der Fehlertext. */
    fun googleFertig(code: String): String? {
        val verifier = prefs()?.getString("verifier", null) ?: return "Sign-in was not started on this phone. Try again."
        val a = post("/v1/auth/exchange", JSONObject().put("code", code).put("verifier", verifier))
        prefs()?.edit()?.remove("verifier")?.apply()
        if (!a.ok) return a.fehler
        sitzungMerken(a.json, null); return null
    }

    private fun sitzungMerken(j: JSONObject, mail: String?) {
        prefs()?.edit()?.apply {
            putString("access", j.optString("access_token")); putString("refresh", j.optString("refresh_token"))
            putLong("bis", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000)
            (j.optString("email").ifBlank { mail })?.let { putString("email", it) }
        }?.apply()
    }

    /** Gültiges Anmelde-Token; erneuert es, wenn es in weniger als zwei Minuten abläuft. null = neu anmelden. */
    @Synchronized fun token(erzwingen: Boolean = false): String? {
        val p = prefs() ?: return null
        val refresh = p.getString("refresh", null)?.ifBlank { null } ?: return null
        val access = p.getString("access", null)?.ifBlank { null }
        if (!erzwingen && access != null && p.getLong("bis", 0) - System.currentTimeMillis() > 120_000) return access
        val a = post("/v1/auth/refresh", JSONObject().put("refresh_token", refresh))
        if (a.ok) { sitzungMerken(a.json, null); return a.json.optString("access_token").ifBlank { null } }
        // Nur bei klarer Absage abmelden; ohne Netz bleibt die Anmeldung erhalten.
        if (a.code == 401 || a.code == 400) p.edit().remove("access").remove("refresh").apply()
        return if (a.code == 0) access else null
    }
}
