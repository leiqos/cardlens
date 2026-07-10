package com.cardlens.tcg.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.camera.core.ImageProxy
import com.cardlens.tcg.model.CardIdentifierDetector
import com.cardlens.tcg.model.isStrong
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Geometrie des Zielrahmens im View-Koordinatensystem. Overlay (Compose) und
 * Analyse teilen sich diese eine Definition — frueher wichen angezeigter
 * Rahmen und analysierter Ausschnitt voneinander ab.
 */
object ScanGuide {
    const val WIDTH_FRACTION = 0.78f
    const val ASPECT = 88f / 63f
    private const val VERTICAL_BIAS = 0.05f
    private const val MAX_HEIGHT_FRACTION = 0.68f

    /** Rahmen als [links, oben, rechts, unten]. */
    fun rect(viewWidth: Float, viewHeight: Float): FloatArray {
        var w = viewWidth * WIDTH_FRACTION
        var h = w * ASPECT
        if (h > viewHeight * MAX_HEIGHT_FRACTION) {
            h = viewHeight * MAX_HEIGHT_FRACTION
            w = h / ASPECT
        }
        val left = (viewWidth - w) / 2f
        val top = ((viewHeight - h) / 2f - viewHeight * VERTICAL_BIAS)
            .coerceAtLeast(viewHeight * 0.02f)
        return floatArrayOf(left, top, left + w, top + h)
    }
}

/**
 * Verarbeitet Kamerabilder zur Karten-Erkennung — ausserhalb der UI:
 *
 *  1. Zielrahmen aus View- in Bildkoordinaten uebersetzen (FILL_CENTER-Mapping
 *     der PreviewView — der Nutzer sieht einen Ausschnitt des Sensorbilds).
 *  2. [CardDetector]: physische Kartenkanten im Suchband um den Rahmen finden.
 *  3. Perspektivische Entzerrung des Karten-Quads auf ein kanonisches
 *     63:88-Bild — dieselbe Geometrie wie die API-Scans der Kandidaten.
 *  4. Perceptual-Hash-Fingerabdruck des entzerrten Bilds (Editions-Abgleich).
 *  5. OCR auf dem entzerrten Bild: Name + Kennungen; bei Bedarf ein zweiter
 *     Durchgang auf dem 2x vergroesserten unteren Kartenband (Kleindruck).
 *
 * Ein Frame wird erst angenommen, wenn der vorige vollstaendig verarbeitet
 * ist (busy-Flag) — CameraX verwirft aeltere Frames per KEEP_ONLY_LATEST.
 */
class ScanAnalyzer(
    /** Pro Frame: Overlay-Quad (View-normiert 0..1), Karte erkannt?, Fingerabdruck. */
    private val onFrame: (FloatArray?, Boolean, PerceptualHash.Fingerprint) -> Unit,
    /** Nach jedem OCR-Durchgang: Name, Kennungen, Spiel-Hinweis. */
    private val onReading: (ScanReading) -> Unit,
    /** Lohnt der Kennungs-Zoom-Zweitpass gerade noch? */
    private val needsIdentifierBoost: () -> Boolean
) {
    val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val busy = AtomicBoolean(false)
    @Volatile private var closed = false

    fun analyze(proxy: ImageProxy, viewWidth: Int, viewHeight: Int) {
        if (closed || !busy.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val raw = proxy.toBitmap()
        proxy.close()
        try {
            val frame = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                    .also { if (it != raw) raw.recycle() }
            } else {
                raw
            }
            process(frame, viewWidth, viewHeight)
        } catch (t: Throwable) {
            busy.set(false)
        }
    }

    fun close() {
        closed = true
        executor.shutdown()
        recognizer.close()
    }

    private fun process(frame: Bitmap, viewWidth: Int, viewHeight: Int) {
        // 1. Zielrahmen in Bildkoordinaten
        val guide = guideRectInImage(frame.width, frame.height, viewWidth, viewHeight)

        // 2. Kartenkanten im verkleinerten Graustufenbild suchen
        val detScale = min(1f, DETECT_WIDTH / frame.width.toFloat())
        val luma = toLuma(frame, detScale)
        val detection = CardDetector.detect(
            luma,
            guide[0] * detScale, guide[1] * detScale,
            guide[2] * detScale, guide[3] * detScale
        )
        val quad = detection.quad.scaled(1f / detScale, 1f / detScale)
        val cardFound = detection.confidence >= CONFIDENCE_DETECTED
        val frameW = frame.width.toFloat()
        val frameH = frame.height.toFloat()

        // 3. Entzerren auf das kanonische Kartenformat
        val card = rectify(frame, quad)
        frame.recycle()

        // 4. Fingerabdruck + Overlay melden
        val fingerprint = PerceptualHash.fingerprint(card)
        onFrame(
            quadToViewNormalized(quad, frameW, frameH, viewWidth, viewHeight)
                .takeIf { cardFound },
            cardFound,
            fingerprint
        )

        // 5. OCR auf dem entzerrten Kartenbild
        var strongIdentifierSeen = false
        recognizer.process(InputImage.fromBitmap(card, 0))
            .addOnSuccessListener { text ->
                val reading = CardNameExtractor.extract(text, card.width, card.height)
                strongIdentifierSeen = reading.identifiers.any { it.isStrong }
                onReading(reading)
            }
            .addOnCompleteListener {
                if (!strongIdentifierSeen && needsIdentifierBoost() && !closed) {
                    zoomPass(card)
                } else {
                    card.recycle()
                    busy.set(false)
                }
            }
    }

    /**
     * Kennungs-Zoom: unteres Kartenband (ab 55 % — deckt auch den Yu-Gi-Oh!-
     * Set-Code unterhalb des Artworks ab) auf doppelte Groesse skaliert.
     */
    private fun zoomPass(card: Bitmap) {
        val top = (card.height * 0.55f).toInt()
        val band = Bitmap.createBitmap(card, 0, top, card.width, card.height - top)
        val scaled = Bitmap.createScaledBitmap(band, band.width * 2, band.height * 2, true)
        band.recycle()
        // Kontrast strecken: Karten-IDs sind oft Goldfolie auf buntem Artwork
        // (One Piece, Dragon Ball) — mit niedrigem Kontrast liest ML Kit sie
        // nicht. Graustufen + Perzentil-Streckung macht den Kleindruck lesbar.
        val zoomed = enhanceContrast(scaled)
        if (zoomed != scaled) scaled.recycle()
        recognizer.process(InputImage.fromBitmap(zoomed, 0))
            .addOnSuccessListener { text ->
                val lines = text.textBlocks.flatMap { it.lines }
                    .filter { it.boundingBox != null }
                    .sortedBy { it.boundingBox!!.top }
                // Nummern-Zone der Karte (ab 78 %) in Band-Koordinaten:
                // das Band beginnt bei 55 % → (0.78-0.55)/0.45 der Bandhoehe.
                val zoneTop = zoomed.height * 0.51f
                val identifiers = CardIdentifierDetector.detect(
                    lines.map { it.text },
                    lines.filter { it.boundingBox!!.top >= zoneTop }.map { it.text }
                )
                if (identifiers.isNotEmpty()) {
                    onReading(ScanReading(null, identifiers, 0))
                }
            }
            .addOnCompleteListener {
                zoomed.recycle()
                card.recycle()
                busy.set(false)
            }
    }

    // ---- Geometrie ----------------------------------------------------------

    /**
     * Zielrahmen (View-Koordinaten) → Bildkoordinaten. PreviewView zeigt mit
     * FILL_CENTER einen zentrierten Ausschnitt des Analysebilds; ohne dieses
     * Mapping analysiert man andere Pixel, als der Rahmen anzeigt.
     */
    private fun guideRectInImage(imgW: Int, imgH: Int, viewW: Int, viewH: Int): FloatArray {
        if (viewW <= 0 || viewH <= 0) return ScanGuide.rect(imgW.toFloat(), imgH.toFloat())
        val vr = ScanGuide.rect(viewW.toFloat(), viewH.toFloat())
        val scale = max(viewW.toFloat() / imgW, viewH.toFloat() / imgH)
        val cropLeft = (imgW - viewW / scale) / 2f
        val cropTop = (imgH - viewH / scale) / 2f
        return floatArrayOf(
            cropLeft + vr[0] / scale, cropTop + vr[1] / scale,
            cropLeft + vr[2] / scale, cropTop + vr[3] / scale
        )
    }

    /** Karten-Quad (Bildkoordinaten) → View-normierte Koordinaten fuers Overlay. */
    private fun quadToViewNormalized(
        quad: CardQuad,
        imgW: Float,
        imgH: Float,
        viewW: Int,
        viewH: Int
    ): FloatArray? {
        if (viewW <= 0 || viewH <= 0) return null
        val scale = max(viewW / imgW, viewH / imgH)
        val cropLeft = (imgW - viewW / scale) / 2f
        val cropTop = (imgH - viewH / scale) / 2f
        val pts = quad.toFloatArray()
        val out = FloatArray(8)
        for (i in 0 until 4) {
            out[i * 2] = ((pts[i * 2] - cropLeft) * scale / viewW)
            out[i * 2 + 1] = ((pts[i * 2 + 1] - cropTop) * scale / viewH)
        }
        return out
    }

    /** Perspektivische Entzerrung des Quads auf das kanonische Kartenbild. */
    private fun rectify(src: Bitmap, quad: CardQuad): Bitmap {
        val out = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888)
        val matrix = Matrix()
        val ok = matrix.setPolyToPoly(
            quad.toFloatArray(), 0,
            floatArrayOf(
                0f, 0f,
                CARD_WIDTH.toFloat(), 0f,
                CARD_WIDTH.toFloat(), CARD_HEIGHT.toFloat(),
                0f, CARD_HEIGHT.toFloat()
            ), 0,
            4
        )
        if (!ok) {
            // Degeneriertes Quad — sollte durch die Plausibilitaets-Checks nicht
            // vorkommen; zur Sicherheit das unskalierte Bild zurueckgeben.
            out.recycle()
            return src.copy(Bitmap.Config.ARGB_8888, false)
        }
        Canvas(out).drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * Graustufen-Kontrastspreizung (5.-95. Luminanz-Perzentil auf 0..255).
     * Liefert das Original zurueck, wenn der Kontrast bereits ausreicht.
     */
    private fun enhanceContrast(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val hist = IntArray(256)
        for (p in px) {
            hist[((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000]++
        }
        val total = w * h
        var acc = 0
        var low = 0
        while (low < 255 && acc + hist[low] < total / 20) { acc += hist[low]; low++ }
        acc = 0
        var high = 255
        while (high > 0 && acc + hist[high] < total / 20) { acc += hist[high]; high-- }
        val range = high - low
        if (range <= 0 || range > 200) return src // Kontrast reicht schon

        for (i in px.indices) {
            val p = px[i]
            val luma = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            val v = ((luma - low) * 255 / range).coerceIn(0, 255)
            px[i] = 0xFF shl 24 or (v shl 16) or (v shl 8) or v
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** Verkleinertes Graustufenbild fuer die Kantenerkennung. */
    private fun toLuma(src: Bitmap, scale: Float): LumaImage {
        val w = max(32, (src.width * scale).toInt())
        val h = max(32, (src.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        if (scaled != src) scaled.recycle()
        for (i in px.indices) {
            val p = px[i]
            px[i] = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        return LumaImage(w, h, px)
    }

    private companion object {
        /** Kantenerkennung laeuft auf ~320 px Breite — schnell und stabil. */
        const val DETECT_WIDTH = 320f

        /** Kanonisches entzerrtes Kartenbild (63:88), gross genug fuer OCR. */
        const val CARD_WIDTH = 640
        const val CARD_HEIGHT = 894

        /** Ab diesem Detektor-Vertrauen gilt: Karte liegt im Rahmen. */
        const val CONFIDENCE_DETECTED = 0.30f
    }
}
