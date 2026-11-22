package com.felixos.drohne.plan

/** Reicht der Kameraspeicher für die Fotos des Plans? Reine Rechnung für die Startprüfung. */
object Storage {
    /** Großzügig je Foto (12 MP JPEG lag am 22.11.2026 bei etwa 10 MB). */
    const val MB_PER_PHOTO = 12

    /** Text für die Startprüfung oder null, wenn es reicht, der Plan keine Fotos macht oder der Speicher noch nicht gelesen ist. */
    fun problem(photos: Int, freeMb: Int?): String? {
        if (photos <= 0 || freeMb == null) return null
        val need = photos * MB_PER_PHOTO
        return if (freeMb < need) "Camera storage: $freeMb MB free, $photos photos need about $need MB. Format or change the SD card." else null
    }
}
