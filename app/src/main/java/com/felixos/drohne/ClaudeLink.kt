package com.felixos.drohne

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Direct line from the app to Claude (Felix 13.11.): a still image plus telemetry and a question go to the
 * Claude Messages API; the answer is text and at most one command from a short fixed list.
 * Claude never steers in real time. Every command is shown to the pilot and then runs through the same
 * checks as any plan (MainActivity.flyFromHere). Flying, sensors, guards and sticks stay local.
 *
 * Plain HTTPS on purpose: the Anthropic Java SDK would lift the OkHttp/Kotlin versions the DJI SDK runs on.
 * The key stays on this phone (SharedPreferences), never in the repo or a chat.
 */
object ClaudeLink {
    private const val PREFS = "drohne"
    private const val KEY = "claude_key"
    private const val MODEL = "claude-opus-5-5"

    /** Eigener Claude-Schlüssel dieses Handys, falls eingetragen. */
    fun eigenerKey(c: Context): String? = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.trim()?.ifBlank { null }
    /** Womit Anfragen laufen: eigener Schlüssel geht vor, sonst das Skytac-Konto ([Konto.MARKE]); null = weder noch. */
    fun key(c: Context): String? = eigenerKey(c) ?: if (Konto.angemeldet) Konto.MARKE else null
    fun saveKey(c: Context, key: String) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, key.trim()).apply() }

    /** What Claude answered: text for the pilot and optionally one command with its input. */
    class Reply(val text: String, val command: String?, val input: JSONObject?, val error: String? = null)

    private const val SYSTEM = "You are the copilot of a small camera drone (DJI Mini 4 Pro) flown through the pilot's own app. " +
        "You receive one still image from the drone camera (when available), the telemetry and the pilot's request. " +
        "The drone is hovering or on the ground while you answer; your answer takes several seconds, so you never steer in real time. " +
        "Answer the pilot in one or two short plain sentences: what you see and what you suggest. " +
        "If an action would help, call exactly one tool. Image positions are fractions of the image: x from 0 (left) to 1 (right), y from 0 (top) to 1 (bottom). " +
        "A position can only be converted to a place on the ground when it lies below the horizon. " +
        "The app shows your suggestion to the pilot, checks it against buildings, terrain, battery and limits, and may refuse it. " +
        "Never suggest flying low over people or closer than needed. If you are unsure what the pilot wants, use ask_pilot. " +
        "If the image is missing or shows nothing useful, say so. " +
        "The request starts in square brackets with the view the pilot is looking at and the flight state. Answer about that view. " +
        "If the request belongs to another view (missions, settings, system status, log, map or live view), say in one sentence where it belongs and suggest nothing. " +
        "If the request is impossible or not allowed (for example above 120 m, over people, taking off by voice), say why in one sentence and name the nearest thing that is possible. " +
        "If it is unclear, ask one short question with ask_pilot."

    private fun tool(name: String, description: String, props: JSONObject, required: List<String>) = JSONObject()
        .put("name", name).put("description", description).put("strict", true)
        .put("input_schema", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)).put("additionalProperties", false))

    private fun num(d: String) = JSONObject().put("type", "number").put("description", d)
    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)

    private val tools = JSONArray()
        .put(tool("look_closer", "Fly above the place seen at this image position, point the camera straight down, hover 6 s and take a photo. For checking a place: parking space, terrace, roof.",
            JSONObject().put("x", num("horizontal image position 0..1")).put("y", num("vertical image position 0..1")).put("why", str("one short sentence for the pilot")), listOf("x", "y", "why")))
        .put(tool("photo_circle", "Fly a circle with 8 photos around the subject seen at this image position, camera on the subject. For a subject worth photographing from all sides.",
            JSONObject().put("x", num("horizontal image position 0..1")).put("y", num("vertical image position 0..1")).put("radius_m", num("circle radius in metres, 30 or 50")).put("why", str("one short sentence for the pilot")), listOf("x", "y", "radius_m", "why")))
        .put(tool("resume_plan", "Continue the paused or waiting flight plan.", JSONObject().put("why", str("one short sentence for the pilot")), listOf("why")))
        .put(tool("return_home", "Stop and return to the start point. For low battery, wind, or when the job is done.", JSONObject().put("why", str("one short sentence for the pilot")), listOf("why")))
        .put(tool("ask_pilot", "Ask the pilot one short question when the request is unclear.", JSONObject().put("question", str("the question")), listOf("question")))

    private fun bild(jpeg: ByteArray) = JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)))

    /** Eine Anfrage an die Messages API; gibt die Inhaltsblöcke oder den Fehlertext für die Anzeige zurück. Blockiert. */
    private fun senden(apiKey: String, art: String, system: String, content: JSONArray, tools: JSONArray?, toolChoice: JSONObject? = null): Pair<JSONArray?, String?> {
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 4000)
            .put("system", system)
            .put("output_config", JSONObject().put("effort", "low"))
            .put("fallbacks", "default")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        if (tools != null) body.put("tools", tools)
        if (toolChoice != null) body.put("tool_choice", toolChoice)
        // Mit Skytac-Konto geht dieselbe Anfrage an den Skytac-Server; er setzt Schlüssel und Modell und zählt Credits.
        val konto = apiKey == Konto.MARKE
        val token = if (konto) Konto.token() ?: return null to "Skytac sign-in expired. Sign in again under Set → Data → Skytac account." else null
        val conn = (URL(if (konto) Konto.SERVER + "/v1/messages" else "https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10_000; readTimeout = if (konto) 130_000 else 90_000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (konto) {
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("x-skytac-kind", art)
            } else {
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", "2026-10-21")
                setRequestProperty("anthropic-beta", "server-side-fallback-2026-07-01")
            }
        }
        // Schreiben hat bei HttpURLConnection keine eigene Zeitgrenze: ein Wächter trennt nach 150 s, was am schwachen Netz hängt.
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(bytes.size)
        val waechter = java.util.Timer("cc-timeout", true)
        waechter.schedule(object : java.util.TimerTask() { override fun run() { try { conn.disconnect() } catch (_: Throwable) {} } }, 150_000)
        return try {
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }.getOrDefault(raw.take(200))
                if (konto) return null to when (code) {
                    401 -> "Skytac sign-in expired. Sign in again under Set → Data → Skytac account."
                    402 -> "Not enough credits for this request. Nothing was charged."
                    else -> msg.ifBlank { "Skytac error $code." } }
                return null to when (code) { 401 -> "Claude key not accepted (401). Check Settings → Data → Claude key."; 429 -> "Claude is rate limited (429). Try again in a moment."; else -> "Claude error $code: $msg" }
            }
            if (konto) conn.getHeaderField("x-skytac-credits")?.toIntOrNull()?.let { Konto.creditsMerken(it) }
            val json = JSONObject(raw)
            if (json.optString("stop_reason") == "refusal") return null to "Claude declined this request."
            json.getJSONArray("content") to null
        } catch (t: Throwable) {
            null to "No connection to Claude: ${t.javaClass.simpleName} ${t.message ?: ""}".trim()
        } finally { waechter.cancel(); conn.disconnect() }
    }

    /** Blocking call; run on a worker thread. [jpeg] may be null (no live view). */
    fun ask(apiKey: String, question: String, telemetry: String, jpeg: ByteArray?): Reply {
        val content = JSONArray()
        if (jpeg != null) content.put(bild(jpeg))
        content.put(JSONObject().put("type", "text").put("text", (if (jpeg == null) "No camera image is available right now.\n\n" else "") + "Telemetry:\n$telemetry\n\nPilot: $question"))
        val (blocks, fehler) = senden(apiKey, "ask", SYSTEM, content, tools)
        if (blocks == null) return Reply("", null, null, fehler)
        val text = StringBuilder(); var cmd: String? = null; var input: JSONObject? = null
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            when (b.optString("type")) {
                "text" -> text.append(b.optString("text")).append(' ')
                "tool_use" -> if (cmd == null) { cmd = b.optString("name"); input = b.optJSONObject("input") }
            }
        }
        return Reply(text.toString().trim(), cmd, input)
    }

    // ---------------------------------------------------------------- Missionen 03 und 04: Standbilder auswerten (ohne Flugbefehle)

    private const val SYSTEM_NACHSEHEN = "You look at one still image from a small camera drone. The drone hovers straight above a place with the camera pointing straight down; the top of the image is the direction the drone is heading. " +
        "The pilot sent the drone there to answer one question about the place. Answer that question in at most three short plain sentences: first the answer, then what in the image shows it. " +
        "If the image does not show enough to answer (too high, shadow, blur, wrong place), say so plainly instead of guessing, and say what would help. " +
        "Do not identify people. You cannot steer the drone and you suggest no flight action."

    /** 03 Check a place: die Frage des Piloten zu einem Standbild von oben. Antworttext oder Fehlertext ([zweites] true = Fehler). Blockiert. */
    fun nachsehen(apiKey: String, frage: String, jpeg: ByteArray, hoeheM: Int): Pair<String, Boolean> {
        val content = JSONArray().put(bild(jpeg)).put(JSONObject().put("type", "text").put("text", "Height above the start point: $hoeheM m.\n\nQuestion: $frage"))
        val (blocks, fehler) = senden(apiKey, "check", SYSTEM_NACHSEHEN, content, null)
        if (blocks == null) return (fehler ?: "No answer.") to true
        val text = (0 until blocks.length()).map { blocks.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString(" ") { it.optString("text") }.trim()
        return (text.ifBlank { "No answer." }) to text.isBlank()
    }

    /** Ein Kandidat aus der Suche: Nummer des Bildes (wie übergeben), Stelle im Bild als Anteile, ein Satz Begründung. */
    class Fund(val bild: Int, val x: Double, val y: Double, val warum: String)
    class Suchergebnis(val text: String, val funde: List<Fund>, val fehler: String? = null)

    private const val SYSTEM_SUCHEN = "You look through still images from a small camera drone that swept an area in a grid. Every image is taken straight down; the top of each image is the direction the drone was heading, not necessarily north. " +
        "Each image is preceded by a line with its number. The pilot is looking for a kind of spot. Always answer by calling the report_candidates tool exactly once, never with plain text. Report the places that fit, best first, at most five: " +
        "image number, position in that image as fractions (x from 0 left to 1 right, y from 0 top to 1 bottom) and one short plain sentence why it fits and what the pilot should check on site. " +
        "The same place can appear in two overlapping images: report it once. If nothing fits, report an empty list and say in the summary what you saw instead. " +
        "Do not guess what the image does not show. Do not identify people. You cannot steer the drone."

    private val suchTool = JSONArray().put(tool("report_candidates", "Report the places that fit what the pilot is looking for.",
        JSONObject()
            .put("summary", str("one or two short plain sentences for the pilot: what was found overall"))
            .put("candidates", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object").put("additionalProperties", false)
                .put("properties", JSONObject().put("image", JSONObject().put("type", "integer").put("description", "number of the image as given")).put("x", num("horizontal position 0..1")).put("y", num("vertical position 0..1")).put("why", str("one short plain sentence")))
                .put("required", JSONArray(listOf("image", "x", "y", "why"))))),
        listOf("summary", "candidates")))

    /** 04 Find a spot: [bilder] = Nummer und JPEG je Rasterpunkt. Blockiert. */
    fun suchen(apiKey: String, was: String, bilder: List<Pair<Int, ByteArray>>, hoeheM: Int): Suchergebnis {
        val content = JSONArray()
        bilder.forEach { (nr, jpg) -> content.put(JSONObject().put("type", "text").put("text", "Image $nr:")); content.put(bild(jpg)) }
        content.put(JSONObject().put("type", "text").put("text", "All images are taken from about $hoeheM m above the start point.\n\nThe pilot is looking for: $was"))
        // Keine erzwungene Tool-Wahl: das Modell lehnt sie mit 400 ab. Der Systemtext verlangt das Werkzeug, strict hält das Schema.
        val (blocks, fehler) = senden(apiKey, "spot", SYSTEM_SUCHEN, content, suchTool)
        if (blocks == null) return Suchergebnis("", emptyList(), fehler)
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            if (b.optString("type") != "tool_use") continue
            val inp = b.optJSONObject("input") ?: continue
            val arr = inp.optJSONArray("candidates") ?: JSONArray()
            val erlaubt = bilder.map { it.first }.toSet()
            val funde = (0 until arr.length()).mapNotNull { k -> arr.optJSONObject(k)?.let { c -> Fund(c.optInt("image", -1), c.optDouble("x", 0.5), c.optDouble("y", 0.5), c.optString("why")) } }
                .filter { it.bild in erlaubt && !it.x.isNaN() && !it.y.isNaN() }.take(5)
            return Suchergebnis(inp.optString("summary"), funde)
        }
        return Suchergebnis("", emptyList(), "Central Command gave no usable answer.")
    }

    fun jpeg(b: Bitmap): ByteArray {
        val w = 1280
        val scaled = if (b.width > w) Bitmap.createScaledBitmap(b, w, (b.height.toLong() * w / b.width).toInt().coerceAtLeast(1), true) else b
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }
}
