package com.felixos.drohne.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class WpmlTest {
    private val center = LatLon(38.5170, -9.0130) // Feld bei Azeitão (nur Testwert)

    @Test fun squareHasFiveCornersAndRightSize() {
        val m = Missions.testSquare(center, sideM = 30.0, heightM = 30.0)
        assertEquals(5, m.waypoints.size)
        assertEquals(120.0, m.lengthM, 0.5)
        assertEquals(4, m.waypoints.count { it.takePhoto })
        m.waypoints.zipWithNext { a, b -> assertEquals(30.0, Geo.distance(a.pos, b.pos), 0.2) }
    }

    @Test fun kmzIsWellFormed() {
        val m = Missions.testSquare(center)
        val f = Wpml.writeKmz(m, File.createTempFile("test", ".kmz"), nowMs = 0)
        ZipFile(f).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(listOf("wpmz/template.kml", "wpmz/waylines.wpml"), names)
            val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            for (n in names) {
                val doc = dbf.newDocumentBuilder().parse(zip.getInputStream(zip.getEntry(n)))
                val pms = doc.getElementsByTagNameNS("http://www.opengis.net/kml/2.2", "Placemark")
                assertEquals(5, pms.length)
                val photos = doc.getElementsByTagNameNS("http://www.dji.com/wpmz/1.0.6", "actionActuatorFunc")
                    .let { l -> (0 until l.length).count { l.item(it).textContent == "takePhoto" } }
                assertEquals(4, photos)
            }
        }
        assertTrue(f.length() > 0)
    }
}
