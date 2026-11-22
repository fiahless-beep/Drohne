package com.felixos.drohne.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StorageTest {
    @Test fun `volle Karte sperrt, wie am 22-11-2026`() { assertNotNull(Storage.problem(124, 515)); assertNotNull(Storage.problem(16, 3)) }
    @Test fun `genug Platz, kein Foto oder unbekannt sperrt nicht`() {
        assertNull(Storage.problem(124, 124 * Storage.MB_PER_PHOTO)); assertNull(Storage.problem(0, 0)); assertNull(Storage.problem(80, null))
    }
    @Test fun `Text nennt Platz und Bedarf`() { assertEquals("Camera storage: 3 MB free, 16 photos need about 192 MB. Format or change the SD card.", Storage.problem(16, 3)) }
}
