package com.felixos.drohne

/**
 * Schätzt aus zwei Graubildern, ob sich die Bildmitte aufbläht („Looming“): eine Fläche, die schnell
 * näher kommt, wird im Bild größer. Reines Rechnen ohne Modell, läuft auf jedem Handy.
 * Liefert den Vergrößerungsfaktor der Bildmitte (1,0 = keine Änderung, 1,1 = 10 % größer).
 */
object Looming {
    const val W = 64
    const val H = 36

    /** Y-Ebene (NV21/YUV420) auf W×H verkleinern (Blockmittelwert). */
    fun downscale(y: ByteArray, offset: Int, width: Int, height: Int): FloatArray {
        val out = FloatArray(W * H)
        val bw = width / W; val bh = height / H
        for (j in 0 until H) for (i in 0 until W) {
            var sum = 0L
            val x0 = i * bw; val y0 = j * bh
            var n = 0
            var yy = y0
            while (yy < y0 + bh) {
                val row = offset + yy * width
                var xx = x0
                while (xx < x0 + bw) { sum += (y[row + xx].toInt() and 0xFF); n++; xx += 2 }
                yy += 2
            }
            out[j * W + i] = if (n == 0) 0f else sum.toFloat() / n
        }
        return out
    }

    /** Bilineares Lesen mit Randklemmung. */
    private fun sample(img: FloatArray, x: Float, y: Float): Float {
        val xc = x.coerceIn(0f, (W - 1).toFloat()); val yc = y.coerceIn(0f, (H - 1).toFloat())
        val x0 = xc.toInt(); val y0 = yc.toInt()
        val x1 = minOf(x0 + 1, W - 1); val y1 = minOf(y0 + 1, H - 1)
        val fx = xc - x0; val fy = yc - y0
        val a = img[y0 * W + x0] * (1 - fx) + img[y0 * W + x1] * fx
        val b = img[y1 * W + x0] * (1 - fx) + img[y1 * W + x1] * fx
        return a * (1 - fy) + b * fy
    }

    /**
     * Vergleicht die Mitte von [prev] mit [cur] bei Skalen 0,94…1,20 und kleinen Verschiebungen.
     * Gibt die Skala zurück, bei der [cur] dem vergrößerten [prev] am ähnlichsten ist, oder null bei zu wenig Struktur.
     */
    fun expansion(prev: FloatArray, cur: FloatArray): Double? {
        val cx = W / 2f; val cy = H / 2f
        val rx = W / 4; val ry = H / 4   // Mittelbereich: halbe Breite/Höhe
        // Struktur prüfen: ohne Kontrast ist jede Skala gleich gut
        var mean = 0f; var n = 0
        for (j in -ry until ry) for (i in -rx until rx) { mean += prev[((cy + j).toInt()) * W + (cx + i).toInt()]; n++ }
        mean /= n
        var varSum = 0f
        for (j in -ry until ry) for (i in -rx until rx) { val d = prev[((cy + j).toInt()) * W + (cx + i).toInt()] - mean; varSum += d * d }
        if (varSum / n < 25f) return null  // Standardabweichung < 5 Graustufen
        var bestS = 1.0; var bestErr = Float.MAX_VALUE
        val scales = doubleArrayOf(0.94, 0.97, 1.0, 1.03, 1.06, 1.09, 1.12, 1.16, 1.20)
        for (s in scales) {
            for (dy in -2..2) for (dx in -2..2) {
                var err = 0f
                for (j in -ry until ry step 1) for (i in -rx until rx step 1) {
                    val p = prev[((cy + j).toInt()) * W + (cx + i).toInt()]
                    val c = sample(cur, (cx + i * s + dx).toFloat(), (cy + j * s + dy).toFloat())
                    val d = p - c; err += d * d
                }
                if (err < bestErr) { bestErr = err; bestS = s }
            }
        }
        return bestS
    }
}
