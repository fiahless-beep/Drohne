package com.felixos.drohne.cockpit

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Sprechen und Vorlesen für Central Command (Felix 22.11.2026: „geht bei Central Command dann auf Voice. Da will ich
 * ja auf keinen Fall dann tippen“). Erkennung und Stimme kommen vom Handy (Google), in der Sprache des Geräts.
 * Alles auf dem UI-Thread.
 */
internal class Sprache(private val c: Context) {
    private var hoerer: SpeechRecognizer? = null
    private var stimme: TextToSpeech? = null
    private var stimmeBereit = false
    var hoert = false; private set
    private val prefs = c.getSharedPreferences("cc", Context.MODE_PRIVATE)
    /** Sprache für Erkennung und Stimme: „de“ oder „en“; Vorgabe ist die Sprache des Handys. */
    var kuerzel: String = prefs.getString("lang", null) ?: if (Locale.getDefault().language == "de") "de" else "en"
        private set
    private val ort: Locale get() = if (kuerzel == "de") Locale.GERMANY else Locale.US
    fun wechseln(): String = setzen(if (kuerzel == "de") "en" else "de")
    /** Sprech-Sprache von außen setzen (Sprachbefehl „Sprich Deutsch“); alles außer „de“ gilt als Englisch. */
    fun setzen(k: String): String { kuerzel = if (k == "de") "de" else "en"; prefs.edit().putString("lang", kuerzel).apply(); try { if (stimmeBereit) stimme?.language = ort } catch (_: Throwable) {}; return kuerzel }

    val mikrofonErlaubt: Boolean get() = ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val erkennungDa: Boolean get() = SpeechRecognizer.isRecognitionAvailable(c)

    /** Zuhören bis zur Sprechpause. [teil] = was bisher verstanden wurde; [fertig] = Endtext oder null (nichts verstanden); [fehler] = Klartext. */
    fun hoeren(teil: (String) -> Unit, fertig: (String?) -> Unit, fehler: (String) -> Unit) {
        schweigen()
        abbrechen()
        val h = try { SpeechRecognizer.createSpeechRecognizer(c) } catch (t: Throwable) { return fehler("Speech recognition is not available on this phone.") }
        hoerer = h; hoert = true
        h.setRecognitionListener(object : RecognitionListener {
            private fun text(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.ifBlank { null }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) { if (hoerer !== h) return; text(partialResults)?.let(teil) }
            override fun onResults(results: Bundle?) { if (hoerer !== h) return; ende(); fertig(text(results)) }
            override fun onError(error: Int) {
                if (hoerer !== h) return
                ende()
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> fertig(null)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fehler("Microphone not allowed. Allow it for this app in the Android settings.")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> fehler("Speech recognition needs the internet and did not get through.")
                    else -> fehler("Speech recognition failed (code $error). Try again.")
                }
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, ort.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, c.packageName)
        try { h.startListening(i) } catch (t: Throwable) { ende(); fehler("Speech recognition could not start.") }
    }

    /** Zuhören beenden und auswerten, was bis hier gesagt wurde. */
    fun genug() { try { hoerer?.stopListening() } catch (_: Throwable) {} }

    fun abbrechen() { val h = hoerer; hoerer = null; hoert = false; try { h?.cancel(); h?.destroy() } catch (_: Throwable) {} }

    private fun ende() { val h = hoerer; hoerer = null; hoert = false; try { h?.destroy() } catch (_: Throwable) {} }

    /** Text vorlesen; die Stimme wird beim ersten Mal geholt, der Text dann nachgereicht. */
    fun sprechen(text: String) {
        val s = stimme
        if (s != null && stimmeBereit) { s.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cc"); return }
        if (s != null) return
        stimme = TextToSpeech(c.applicationContext) { status ->
            stimmeBereit = status == TextToSpeech.SUCCESS
            if (stimmeBereit) { try { stimme?.language = ort } catch (_: Throwable) {}; stimme?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cc") }
            else stimme = null
        }
    }

    fun schweigen() { try { stimme?.stop() } catch (_: Throwable) {} }

    fun schliessen() { abbrechen(); schweigen() }
}
