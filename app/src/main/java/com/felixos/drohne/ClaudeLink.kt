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

    fun key(c: Context): String? = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.trim()?.ifBlank { null }
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
        "If the image is missing or shows nothing useful, say so."

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

    /** Blocking call; run on a worker thread. [jpeg] may be null (no live view). */
    fun ask(apiKey: String, question: String, telemetry: String, jpeg: ByteArray?): Reply {
        val content = JSONArray()
        if (jpeg != null) content.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
        content.put(JSONObject().put("type", "text").put("text", (if (jpeg == null) "No camera image is available right now.\n\n" else "") + "Telemetry:\n$telemetry\n\nPilot: $question"))
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 4000)
            .put("system", SYSTEM)
            .put("output_config", JSONObject().put("effort", "low"))
            .put("fallbacks", "default")
            .put("tools", tools)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 60_000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2026-10-21")
            setRequestProperty("anthropic-beta", "server-side-fallback-2026-07-01")
        }
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }.getOrDefault(raw.take(200))
                return Reply("", null, null, when (code) { 401 -> "Claude key not accepted (401). Check Settings → Data → Claude key."; 429 -> "Claude is rate limited (429). Try again in a moment."; else -> "Claude error $code: $msg" })
            }
            val json = JSONObject(raw)
            if (json.optString("stop_reason") == "refusal") return Reply("", null, null, "Claude declined this request.")
            val blocks = json.getJSONArray("content")
            val text = StringBuilder(); var cmd: String? = null; var input: JSONObject? = null
            for (i in 0 until blocks.length()) {
                val b = blocks.getJSONObject(i)
                when (b.optString("type")) {
                    "text" -> text.append(b.optString("text")).append(' ')
                    "tool_use" -> if (cmd == null) { cmd = b.optString("name"); input = b.optJSONObject("input") }
                }
            }
            Reply(text.toString().trim(), cmd, input)
        } catch (t: Throwable) {
            Reply("", null, null, "No connection to Claude: ${t.javaClass.simpleName} ${t.message ?: ""}".trim())
        } finally { conn.disconnect() }
    }

    fun jpeg(b: Bitmap): ByteArray {
        val w = 1280
        val scaled = if (b.width > w) Bitmap.createScaledBitmap(b, w, (b.height.toLong() * w / b.width).toInt().coerceAtLeast(1), true) else b
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }
}
