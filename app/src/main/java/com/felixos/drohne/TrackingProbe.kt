package com.felixos.drohne

import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightAssistantKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager

/**
 * Tracking-Test (Felix 13.11.): fragt NUR LESEND ab, ob die Drohne unserer App die undokumentierten
 * ActiveTrack-/QuickShot-/MasterShot-Werte des DJI-SDK liefert. Schickt keine Befehle, ändert nichts.
 * Zusätzlich 10 s mithören, ob erkannte Ziele (Rahmen) gemeldet werden.
 */
object TrackingProbe {
    private val keys: List<Pair<String, DJIKeyInfo<*>>> = listOf(
        "Verfolgt gerade (IsInTracking)" to FlightAssistantKey.KeyIsInTracking,
        "Tracking läuft (IsTrackingExecuting)" to FlightAssistantKey.KeyIsTrackingExecuting,
        "Einzelziel-Tracking (IsInSingleTracking)" to FlightAssistantKey.KeyIsInSingleTracking,
        "Mehrfach-Tracking möglich (IsMultipleTrackingEnable)" to FlightAssistantKey.KeyIsMultipleTrackingEnable,
        "Mehrfach-Tracking an (MultiTrackingEnabled)" to FlightAssistantKey.KeyMultiTrackingEnabled,
        "Auto-Erkennung (ActiveTrackAutoDetectEnabled)" to FlightAssistantKey.KeyActiveTrackAutoDetectEnabled,
        "Erkannte Ziele (MultipleTrackBoxInfos)" to FlightAssistantKey.KeyMultipleTrackBoxInfos,
        "Tracking-Status (MultipleTrackStatus)" to FlightAssistantKey.KeyMultipleTrackStatus,
        "Vision-Status (VisionMultipleActiveTrackStatus)" to FlightAssistantKey.KeyVisionMultipleActiveTrackStatus,
        "Bewegungsart (ActiveTrackMovementMode)" to FlightAssistantKey.KeyActiveTrackMovementMode,
        "Max. Tracking-Tempo (MaximumTrackingSpeed)" to FlightAssistantKey.KeyMaximumTrackingSpeed,
        "Zielrahmen (MLTrackingBox)" to FlightAssistantKey.KeyMLTrackingBox,
        "QuickShot-Arten (QuickShotModeRange)" to FlightAssistantKey.KeyQuickShotModeRange,
        "QuickShot-Art (QuickShotMode)" to FlightAssistantKey.KeyQuickShotMode,
        "QuickShot-Zustand (QuickShotState)" to FlightAssistantKey.KeyQuickShotState,
        "MasterShot-Zustand (MasterShotState)" to FlightAssistantKey.KeyMasterShotState,
        "Spotlight/POI-Zustand (SmartEyeState)" to FlightAssistantKey.KeySmartEyeState,
        "Spotlight/POI-Art (SmartEyeMode)" to FlightAssistantKey.KeySmartEyeMode,
        "Hyperlapse-Art (HyperlapseMode)" to FlightAssistantKey.KeyHyperlapseMode,
    )

    private fun show(v: Any?): String = when (v) {
        null -> "leer"
        is List<*> -> "${v.size} Einträge" + if (v.isNotEmpty()) ": " + v.take(4).joinToString("; ") { short(it) } else ""
        else -> short(v)
    }
    private fun short(v: Any?): String = try {
        val m = v?.javaClass?.getMethod("toJson"); (m?.invoke(v)?.toString() ?: v.toString()).take(160)
    } catch (_: Throwable) { v.toString().take(160) }

    private fun <T> one(km: dji.v5.manager.interfaces.IKeyManager, holder: Any, info: DJIKeyInfo<T>, label: String, line: (String) -> Unit, push: (String) -> Unit) {
        val key = KeyTools.createKey(info)
        try {
            km.getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T> {
                override fun onSuccess(v: T?) { line("✓ $label: ${show(v)}") }
                override fun onFailure(error: IDJIError) { line("✗ $label: ${error.errorCode() ?: ""} ${error.description() ?: ""}".trim()) }
            })
            km.listen(key, holder) { _, new -> if (new != null) push("$label → ${show(new)}") }
        } catch (t: Throwable) { line("✗ $label: ${t.javaClass.simpleName} ${t.message}") }
    }

    /** Ergebnis als Text (eine Zeile je Wert), nach spätestens ~12 s. */
    fun run(done: (String) -> Unit) {
        if (Simulator.active || !DroneManager.sdkReady) return done("Drohne nicht verbunden.")
        val lines = arrayOfNulls<String>(keys.size)
        val pushes = java.util.Collections.synchronizedList(mutableListOf<String>())
        val holder = Any()
        val km = KeyManager.getInstance()
        keys.forEachIndexed { i, (label, info) -> one(km, holder, info, label, { lines[i] = it }, { if (pushes.size < 40) pushes += it }) }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { km.cancelListen(holder) } catch (_: Throwable) {}
            val ok = lines.count { it?.startsWith("✓") == true }
            val text = buildString {
                appendLine("Tracking-Test (nur gelesen): $ok von ${keys.size} Werten beantwortet")
                appendLine()
                keys.forEachIndexed { i, (label, _) -> appendLine(lines[i] ?: "… $label: keine Antwort in 10 s") }
                appendLine()
                appendLine("Meldungen der Drohne in 10 s: ${pushes.size}")
                pushes.distinct().take(15).forEach { appendLine("· $it") }
            }
            DroneManager.log(text.lineSequence().first())
            done(text)
        }, 10_000)
    }
}
