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

    /** 13.11.: DJI lehnte Starts ab („Waypoint turning intercept error“), wenn Kurven benachbarter Punkte sich überlappten. */
    @Test fun turnDampingNeverOverlapsOnShortLegs() {
        val sq = Missions.testSquare(center, sideM = 30.0, heightM = 30.0)
        val pts = sq.waypoints.map { it.copy(takePhoto = false, hoverSec = 0) }.toMutableList()
        pts.add(2, pts[1].copy(pos = Geo.offset(pts[1].pos, 8.0, 90.0)))   // 8-m-Abschnitt
        pts.add(4, pts[3].copy(pos = Geo.offset(pts[3].pos, 1.5, 0.0)))    // 1,5-m-Abschnitt → anhalten
        val m = sq.copy(waypoints = pts)
        for (i in 0 until pts.size - 1) {
            val leg = Geo.distance(pts[i].pos, pts[i + 1].pos)
            assertTrue("Abschnitt $i: ${Wpml.damping(i, m)} + ${Wpml.damping(i + 1, m)} >= $leg", Wpml.damping(i, m) + Wpml.damping(i + 1, m) < leg)
        }
        assertEquals(3.55, Wpml.damping(1, m), 0.06)
        assertEquals(0.0, Wpml.damping(3, m), 0.01)
        assertEquals(0.0, Wpml.damping(0, m), 0.01)
    }
}
