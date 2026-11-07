package com.felixos.drohne

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Schreibt Abstürze in eine Datei, damit die App sie beim nächsten Start anzeigen kann. */
object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "absturz.txt")

    fun install(c: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { write(c, "Absturz im Thread ${t.name}", e) } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
    }

    fun write(c: Context, title: String, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val head = "$title\n" +
            SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date()) +
            "\nApp ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MODEL}\n\n"
        file(c).appendText(head + sw.toString().take(6000) + "\n\n")
    }

    /** Liefert den letzten Absturzbericht und löscht ihn. */
    fun takeReport(c: Context): String? {
        val f = file(c)
        if (!f.exists()) return null
        val text = f.readText()
        f.delete()
        return text.ifBlank { null }
    }
}
