package com.felixos.drohne.plan

import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Wettervorhersage für den Startplatz (Felix 22.11.2026: „zumindest grob Windstärke und Richtung“).
 * Quelle Open-Meteo, ohne Schlüssel. Gesendet wird nur die auf 0,01° gerundete Position (etwa 1 km), nie der genaue Startpunkt.
 * Die Vorhersage gilt für 10 m über Grund in einem Kilometerraster: grobe Orientierung, kein Messwert am Ort.
 * Reine Rechnung (Adresse bauen, Antwort lesen), damit sie ohne Gerät testbar ist; geholt wird in der Oberfläche.
 */
object Weather {
    class Report(val windMs: Double, val fromDeg: Double, val gustMs: Double, val gustNext2hMs: Double?) {
        /** Eine Zeile für Plan-Karte und Protokoll. */
        fun line(): String {
            val gust = maxOf(gustMs, gustNext2hMs ?: 0.0)
            return String.format(Locale.US, "Forecast wind %.0f m/s from %s (%d°), gusts up to %.0f m/s in the next 2 h%s",
                windMs, compass(fromDeg), fromDeg.roundToInt(), gust, advice(gust))
        }
    }

    /** Mini 4 Pro: laut Handbuch bis 12 m/s Windfestigkeit. Böen ab 8 m/s machen enge Abstände und ruhige Bilder unsicher. */
    fun advice(gustMs: Double): String = when {
        gustMs >= 12.0 -> ". TOO WINDY: do not fly."
        gustMs >= 8.0 -> ". Gusty: stay high, keep away from obstacles."
        else -> ""
    }

    fun compass(deg: Double): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((deg % 360 + 360) % 360) / 45.0).roundToInt() % 8]
    }

    fun url(pos: LatLon): String = String.format(Locale.US,
        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=wind_speed_10m,wind_direction_10m,wind_gusts_10m" +
            "&hourly=wind_gusts_10m&forecast_hours=3&wind_speed_unit=ms", pos.lat, pos.lon)

    /** null = Antwort nicht brauchbar (Fehler des Dienstes, fehlende oder unsinnige Werte). */
    fun parse(text: String): Report? = try {
        val o = JSONObject(text)
        val c = o.getJSONObject("current")
        val wind = c.getDouble("wind_speed_10m"); val from = c.getDouble("wind_direction_10m"); val gust = c.getDouble("wind_gusts_10m")
        val next = o.optJSONObject("hourly")?.optJSONArray("wind_gusts_10m")?.let { a ->
            (0 until a.length()).mapNotNull { i -> if (a.isNull(i)) null else a.getDouble(i) }.filter { it.isFinite() && it >= 0 }.maxOrNull()
        }
        if (!wind.isFinite() || !from.isFinite() || !gust.isFinite() || wind < 0 || gust < 0 || wind > 100 || gust > 100) null
        else Report(wind, from, gust, next?.takeIf { it <= 100 })
    } catch (_: Throwable) { null }
}
