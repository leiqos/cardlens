package com.cardlens.tcg.scan

import android.graphics.Bitmap

/**
 * Wahrnehmungs-Hashes (perceptual hashes) fuer den visuellen Karten-Abgleich.
 *
 * Der Fingerabdruck kombiniert vier 64-Bit-Hashes:
 *  - **dHash/aHash der ganzen Karte**: grobe Struktur und Flaechenverteilung.
 *  - **dHash/aHash der Artwork-Region** (oberer Kartenbereich ohne Rand):
 *    Name, Textbox und Rahmen sind bei verschiedenen Drucken derselben Karte
 *    identisch — die Unterschiede stecken im Artwork. Ein eigener Hash der
 *    Artwork-Region trennt Editionen deshalb deutlich schaerfer.
 *
 * Der Abgleich vergleicht die gewichtete **Hamming-Distanz** (0..384):
 * gleiche Karte ≈ 0–60, andere Karte > 130 — sprach- und OCR-unabhaengig,
 * funktioniert fuer alle TCGs gleichermassen.
 */
object PerceptualHash {

    /** Kombinierter Fingerabdruck: ganze Karte + Artwork-Region + Helligkeit. */
    data class Fingerprint(
        val dHash: Long,
        val aHash: Long,
        val artDHash: Long,
        val artAHash: Long,
        val titleDHash: Long,
        val footerDHash: Long,
        val footerAHash: Long,
        val artRedHash: Long,
        val artGreenHash: Long,
        val artBlueHash: Long,
        /** Mittlere Helligkeit 0..255 — Grundlage des Schwachlicht-Hinweises. */
        val meanLuma: Int
    )

    private data class Hashes(val d: Long, val a: Long, val meanLuma: Int)
    private data class ColorHashes(val red: Long, val green: Long, val blue: Long)

    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** dHash (9×8 Nachbarschaftsvergleiche) + aHash (8×8 vs. Mittel) in einem Durchgang. */
    private fun hashes(bitmap: Bitmap): Hashes {
        val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        val px = IntArray(9 * 8)
        scaled.getPixels(px, 0, 9, 0, 0, 9, 8)
        if (scaled != bitmap) scaled.recycle()
        val lums = IntArray(9 * 8) { luminance(px[it]) }

        var dHash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (lums[y * 9 + x] > lums[y * 9 + x + 1]) dHash = dHash or (1L shl bit)
                bit++
            }
        }

        // aHash aus den linken 8×8 des 9×8-Rasters (spart die zweite Skalierung)
        var sum = 0L
        for (y in 0 until 8) for (x in 0 until 8) sum += lums[y * 9 + x]
        val mean = sum / 64.0
        var aHash = 0L
        var i = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (lums[y * 9 + x] > mean) aHash = aHash or (1L shl i)
                i++
            }
        }
        return Hashes(dHash, aHash, (sum / 64).toInt())
    }

    /** Per-channel average hashes retain artwork palette while tolerating exposure shifts. */
    private fun colorHashes(bitmap: Bitmap): ColorHashes {
        val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val px = IntArray(64)
        scaled.getPixels(px, 0, 8, 0, 0, 8, 8)
        if (scaled != bitmap) scaled.recycle()
        val meanR = px.sumOf { (it shr 16) and 0xFF } / 64.0
        val meanG = px.sumOf { (it shr 8) and 0xFF } / 64.0
        val meanB = px.sumOf { it and 0xFF } / 64.0
        var r = 0L; var g = 0L; var b = 0L
        px.forEachIndexed { index, pixel ->
            if (((pixel shr 16) and 0xFF) > meanR) r = r or (1L shl index)
            if (((pixel shr 8) and 0xFF) > meanG) g = g or (1L shl index)
            if ((pixel and 0xFF) > meanB) b = b or (1L shl index)
        }
        return ColorHashes(r, g, b)
    }

    private fun crop(bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap {
        val x = (bitmap.width * left).toInt().coerceIn(0, bitmap.width - 1)
        val y = (bitmap.height * top).toInt().coerceIn(0, bitmap.height - 1)
        val w = (bitmap.width * (right - left)).toInt().coerceIn(1, bitmap.width - x)
        val h = (bitmap.height * (bottom - top)).toInt().coerceIn(1, bitmap.height - y)
        return Bitmap.createBitmap(bitmap, x, y, w, h)
    }

    /**
     * Fingerabdruck eines Kartenbilds (Kamera-Ausschnitt oder API-Scan).
     * Wichtig: Kamera- und Kandidatenbild durchlaufen dieselbe Geometrie,
     * damit die Distanzen vergleichbar bleiben.
     */
    fun fingerprint(bitmap: Bitmap): Fingerprint {
        val whole = hashes(bitmap)

        // Artwork-Region: 8–92 % Breite, 10–58 % Hoehe — deckt bei allen
        // unterstuetzten TCGs das Bildfenster ab und meidet Rand und Textbox.
        val w = bitmap.width
        val h = bitmap.height
        val art = if (w >= 16 && h >= 16) crop(bitmap, 0.08f, 0.10f, 0.92f, 0.58f) else bitmap
        val artHashes = hashes(art)
        val artColors = colorHashes(art)
        if (art != bitmap) art.recycle()

        val title = if (w >= 16 && h >= 16) crop(bitmap, 0.04f, 0.03f, 0.96f, 0.24f) else bitmap
        val titleHashes = hashes(title)
        if (title != bitmap) title.recycle()
        val footer = if (w >= 16 && h >= 16) crop(bitmap, 0.02f, 0.76f, 0.98f, 0.99f) else bitmap
        val footerHashes = hashes(footer)
        if (footer != bitmap) footer.recycle()

        return Fingerprint(
            whole.d, whole.a,
            artHashes.d, artHashes.a,
            titleHashes.d,
            footerHashes.d, footerHashes.a,
            artColors.red, artColors.green, artColors.blue,
            whole.meanLuma
        )
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /**
     * Gewichtete Distanz (0 = identisch, 384 = maximal verschieden).
     * Die dHashes zaehlen doppelt (beleuchtungsrobuster), die Artwork-Region
     * traegt genauso stark bei wie die ganze Karte — dort unterscheiden sich
     * Editionen tatsaechlich.
     */
    fun distance(a: Fingerprint, b: Fingerprint): Int {
        // Raw maximum 832; scale back to the historic 0..384 range so the
        // calibrated confidence thresholds retain their meaning.
        val raw = hamming(a.dHash, b.dHash) +
            hamming(a.aHash, b.aHash) +
            hamming(a.artDHash, b.artDHash) * 3 +
            hamming(a.artAHash, b.artAHash) +
            hamming(a.titleDHash, b.titleDHash) +
            hamming(a.footerDHash, b.footerDHash) * 2 +
            hamming(a.footerAHash, b.footerAHash) +
            hamming(a.artRedHash, b.artRedHash) +
            hamming(a.artGreenHash, b.artGreenHash) +
            hamming(a.artBlueHash, b.artBlueHash)
        return (raw * 384 + 416) / 832
    }
}
