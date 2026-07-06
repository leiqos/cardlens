package com.cardlens.tcg.scan

import android.graphics.Bitmap

/**
 * Wahrnehmungs-Hashes (perceptual hashes) fuer den visuellen Karten-Abgleich.
 *
 * Zwei komplementaere 64-Bit-Hashes:
 *  - **dHash** (Differenz-Hash): robust gegen Helligkeit/Kontrast, erkennt Struktur.
 *  - **aHash** (Durchschnitts-Hash): grobe Flaechenverteilung, guter Tiebreaker.
 *
 * Der Abgleich vergleicht die **Hamming-Distanz** (Anzahl unterschiedlicher Bits):
 * gleiche Karte ≈ 0–14, andere Karte > 20. So laesst sich aus vielen Druck-
 * Kandidaten (z. B. 64 „Lightning Bolt") der visuell passende auswaehlen —
 * sprach- und OCR-unabhaengig, funktioniert fuer alle TCGs gleichermassen.
 */
object PerceptualHash {

    /** Kombinierter 128-Bit-Fingerabdruck (dHash in [0], aHash in [1]). */
    data class Fingerprint(val dHash: Long, val aHash: Long)

    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** Difference-Hash: 9×8 Graustufen, je Zeile 8 Nachbarschaftsvergleiche. */
    fun dHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        val px = IntArray(9 * 8)
        scaled.getPixels(px, 0, 9, 0, 0, 9, 8)
        if (scaled != bitmap) scaled.recycle()
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val left = luminance(px[y * 9 + x])
                val right = luminance(px[y * 9 + x + 1])
                if (left > right) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    /** Average-Hash: 8×8 Graustufen, Bit = Pixel heller als Bildmittel. */
    fun aHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val px = IntArray(64)
        scaled.getPixels(px, 0, 8, 0, 0, 8, 8)
        if (scaled != bitmap) scaled.recycle()
        val lums = IntArray(64) { luminance(px[it]) }
        val mean = lums.average()
        var hash = 0L
        for (i in 0 until 64) if (lums[i] > mean) hash = hash or (1L shl i)
        return hash
    }

    fun fingerprint(bitmap: Bitmap): Fingerprint = Fingerprint(dHash(bitmap), aHash(bitmap))

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /**
     * Gewichtete Distanz beider Hashes (0 = identisch, 128 = maximal verschieden).
     * dHash zaehlt doppelt, da strukturunabhaengiger von Beleuchtung.
     */
    fun distance(a: Fingerprint, b: Fingerprint): Int =
        hamming(a.dHash, b.dHash) * 2 + hamming(a.aHash, b.aHash)
}
