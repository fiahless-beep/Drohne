package com.felixos.drohne.cockpit

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Grenze Oberfläche/Flugkern (Zielbild Schritt 3b), als Erlaubnisliste: Dateien im Paket cockpit dürfen aus dem
 * App-Paket nur den Vertrag verwenden, und die Zeichenfolge „dji.“ darf in ihnen nirgends vorkommen.
 * Läuft mit den Unit-Tests, also in jedem CI-Bau vor der APK.
 */
class GrenzeTest {
    private val erlaubt = setOf("FlugSitzung", "CockpitZustand", "Aktionen", "StartErgebnis", "Pruefzeile", "Anzeige", "R", "BuildConfig")

    private fun dateien(): List<File> {
        val dir = listOf("src/main/java/com/felixos/drohne/cockpit", "app/src/main/java/com/felixos/drohne/cockpit").map(::File).firstOrNull { it.isDirectory }
        assertTrue("Paket cockpit nicht gefunden (Arbeitsverzeichnis ${File(".").absolutePath})", dir != null)
        val f = dir!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("Paket cockpit ist leer", f.isNotEmpty())
        return f
    }

    @Test fun keinSdkInDerOberflaeche() {
        val treffer = dateien().flatMap { f -> f.readLines().mapIndexedNotNull { i, z -> if (z.contains("dji.")) "${f.name}:${i + 1}" else null } }
        assertTrue("„dji.“ in der Oberfläche: $treffer", treffer.isEmpty())
    }

    @Test fun nurDerVertragAusDemAppPaket() {
        val re = Regex("""com\.felixos\.drohne\.([A-Za-z_]+)""")
        val treffer = dateien().flatMap { f ->
            f.readLines().mapIndexedNotNull { i, z ->
                if (z.trimStart().startsWith("package ")) return@mapIndexedNotNull null
                val fremd = re.findAll(z).map { it.groupValues[1] }.filter { it != "cockpit" && it !in erlaubt }.toList()
                if (fremd.isEmpty()) null else "${f.name}:${i + 1} $fremd"
            }
        }
        assertTrue("Oberfläche greift am Vertrag vorbei in den Kern: $treffer", treffer.isEmpty())
    }
}
