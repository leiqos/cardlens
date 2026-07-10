package com.cardlens.tcg.scan

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Graustufenbild als flaches Array — bewusst ohne android.graphics, damit die
 * Kantenerkennung in reinen JVM-Unit-Tests laeuft.
 */
class LumaImage(val width: Int, val height: Int, val data: IntArray) {
    init {
        require(data.size == width * height) { "Luma-Puffer passt nicht zu $width x $height" }
    }

    operator fun get(x: Int, y: Int): Int = data[y * width + x]
}

/**
 * Vier Eckpunkte der erkannten Karte, im Uhrzeigersinn ab oben links.
 * Die Reihenfolge entspricht [android.graphics.Matrix.setPolyToPoly]-Quellpunkten.
 */
data class CardQuad(
    val topLeftX: Float, val topLeftY: Float,
    val topRightX: Float, val topRightY: Float,
    val bottomRightX: Float, val bottomRightY: Float,
    val bottomLeftX: Float, val bottomLeftY: Float
) {
    fun toFloatArray(): FloatArray = floatArrayOf(
        topLeftX, topLeftY,
        topRightX, topRightY,
        bottomRightX, bottomRightY,
        bottomLeftX, bottomLeftY
    )

    fun scaled(sx: Float, sy: Float): CardQuad = CardQuad(
        topLeftX * sx, topLeftY * sy,
        topRightX * sx, topRightY * sy,
        bottomRightX * sx, bottomRightY * sy,
        bottomLeftX * sx, bottomLeftY * sy
    )

    companion object {
        fun fromRect(left: Float, top: Float, right: Float, bottom: Float): CardQuad =
            CardQuad(left, top, right, top, right, bottom, left, bottom)
    }
}

/**
 * Ergebnis der Kartensuche: Quad (bei Fehlschlag = Zielrahmen), Vertrauen 0..1
 * und wie viele der vier Kanten tatsaechlich im Bild gefunden wurden.
 */
data class CardDetection(
    val quad: CardQuad,
    val confidence: Float,
    val snappedSides: Int
)

/**
 * Findet die physische Karte in der Naehe des Zielrahmens.
 *
 * Erste Prinzipien statt Heuristik-Stapel: eine Karte ist ein helles/dunkles
 * Rechteck mit vier geraden, kontraststarken Kanten. Fuer jede Seite des
 * Zielrahmens wird in einem Suchband die Linie mit dem staerksten
 * Luminanz-Gradienten gesucht (je Seitenhaelfte ein Stuetzpunkt, damit auch
 * leicht gekippte Karten getroffen werden). Die vier Linien werden zu Ecken
 * geschnitten; das Quad ist die Grundlage der perspektivischen Entzerrung.
 *
 * Faellt eine Kante aus (Karte randlos vor gleichfarbigem Hintergrund), wird
 * fuer diese Seite die Rahmenlinie beibehalten — die Erkennung ist damit nie
 * schlechter als der bisherige starre Ausschnitt.
 */
object CardDetector {

    /** Suchband je Seite, relativ zur Kartenbreite/-hoehe. */
    private const val BAND = 0.13f

    /** Ecken-Aussparung beim Kanten-Scan (runde Kartenecken, Finger). */
    private const val CORNER_INSET = 0.14f

    /** Normalisierte Mindest-Gradientenstaerke, ab der eine Kante als gefunden gilt. */
    private const val MIN_EDGE_STRENGTH = 0.035f

    /** Staerke, ab der eine Kante als "voll sicher" in das Vertrauen eingeht. */
    private const val FULL_EDGE_STRENGTH = 0.12f

    /** Max. Versatz der beiden Halbseiten-Stuetzpunkte (sonst Ausreisser). */
    private const val MAX_TILT = 0.08f

    /** Eine Linie durch zwei Stuetzpunkte plus normalisierte Kantenstaerke. */
    private data class Edge(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val strength: Float
    )

    fun detect(
        image: LumaImage,
        guideLeft: Float,
        guideTop: Float,
        guideRight: Float,
        guideBottom: Float
    ): CardDetection {
        val fallback = CardDetection(
            CardQuad.fromRect(guideLeft, guideTop, guideRight, guideBottom),
            confidence = 0f,
            snappedSides = 0
        )
        val cardW = guideRight - guideLeft
        val cardH = guideBottom - guideTop
        if (cardW < 24f || cardH < 24f || image.width < 32 || image.height < 32) return fallback

        val left = verticalEdge(image, guideLeft, cardW, guideTop, guideBottom)
        val right = verticalEdge(image, guideRight, cardW, guideTop, guideBottom)
        val top = horizontalEdge(image, guideTop, cardH, guideLeft, guideRight)
        val bottom = horizontalEdge(image, guideBottom, cardH, guideLeft, guideRight)

        val snapped = listOf(left, right, top, bottom).count { it.strength >= MIN_EDGE_STRENGTH }
        if (snapped == 0) return fallback

        val topLeft = intersect(top, left) ?: return fallback
        val topRight = intersect(top, right) ?: return fallback
        val bottomRight = intersect(bottom, right) ?: return fallback
        val bottomLeft = intersect(bottom, left) ?: return fallback

        val quad = CardQuad(
            topLeft.first, topLeft.second,
            topRight.first, topRight.second,
            bottomRight.first, bottomRight.second,
            bottomLeft.first, bottomLeft.second
        )
        if (!plausible(quad, image, cardW, cardH)) return fallback

        val confidence = listOf(left, right, top, bottom)
            .map { min(it.strength / FULL_EDGE_STRENGTH, 1f) }
            .average()
            .toFloat()
        return CardDetection(quad, confidence, snapped)
    }

    /**
     * Staerkste vertikale Kante im Band um [guideX]. Zwei Stuetzpunkte
     * (obere/untere Seitenhaelfte) erlauben leichte Kartenneigung.
     */
    private fun verticalEdge(
        image: LumaImage,
        guideX: Float,
        cardW: Float,
        top: Float,
        bottom: Float
    ): Edge {
        val band = cardW * BAND
        val xMin = max(1, (guideX - band).toInt())
        val xMax = min(image.width - 2, (guideX + band).toInt())
        val inset = (bottom - top) * CORNER_INSET
        val spans = arrayOf(
            (top + inset).toInt() to (top + (bottom - top) * 0.48f).toInt(),
            (top + (bottom - top) * 0.52f).toInt() to (bottom - inset).toInt()
        )
        if (xMin >= xMax) return Edge(guideX, top, guideX, bottom, 0f)

        val bestX = FloatArray(2) { guideX }
        val bestStrength = FloatArray(2)
        for (half in 0..1) {
            val (y0, y1) = spans[half]
            val yStart = max(1, y0)
            val yEnd = min(image.height - 2, y1)
            if (yStart >= yEnd) continue
            var best = 0L
            var bestAt = guideX.toInt()
            for (x in xMin..xMax) {
                var sum = 0L
                var y = yStart
                while (y <= yEnd) {
                    sum += abs(image[x + 1, y] - image[x - 1, y])
                    y += 2
                }
                if (sum > best) {
                    best = sum
                    bestAt = x
                }
            }
            val samples = (yEnd - yStart) / 2 + 1
            bestX[half] = bestAt.toFloat()
            bestStrength[half] = best.toFloat() / (samples * 255f * 2f)
        }

        // Ausreisser: die Halbseiten widersprechen sich zu stark → schwaechere
        // Haelfte auf die staerkere ziehen (eine gerade Kante bleibt gerade).
        if (abs(bestX[0] - bestX[1]) > cardW * MAX_TILT) {
            val strongest = if (bestStrength[0] >= bestStrength[1]) 0 else 1
            bestX[1 - strongest] = bestX[strongest]
            bestStrength[1 - strongest] = bestStrength[strongest] * 0.5f
        }

        val yc0 = top + (bottom - top) * 0.3f
        val yc1 = top + (bottom - top) * 0.7f
        return Edge(bestX[0], yc0, bestX[1], yc1, min(bestStrength[0], bestStrength[1]))
    }

    /** Staerkste horizontale Kante im Band um [guideY] — Spalten statt Zeilen. */
    private fun horizontalEdge(
        image: LumaImage,
        guideY: Float,
        cardH: Float,
        left: Float,
        right: Float
    ): Edge {
        val band = cardH * BAND
        val yMin = max(1, (guideY - band).toInt())
        val yMax = min(image.height - 2, (guideY + band).toInt())
        val inset = (right - left) * CORNER_INSET
        val spans = arrayOf(
            (left + inset).toInt() to (left + (right - left) * 0.48f).toInt(),
            (left + (right - left) * 0.52f).toInt() to (right - inset).toInt()
        )
        if (yMin >= yMax) return Edge(left, guideY, right, guideY, 0f)

        val bestY = FloatArray(2) { guideY }
        val bestStrength = FloatArray(2)
        for (half in 0..1) {
            val (x0, x1) = spans[half]
            val xStart = max(1, x0)
            val xEnd = min(image.width - 2, x1)
            if (xStart >= xEnd) continue
            var best = 0L
            var bestAt = guideY.toInt()
            for (y in yMin..yMax) {
                var sum = 0L
                var x = xStart
                while (x <= xEnd) {
                    sum += abs(image[x, y + 1] - image[x, y - 1])
                    x += 2
                }
                if (sum > best) {
                    best = sum
                    bestAt = y
                }
            }
            val samples = (xEnd - xStart) / 2 + 1
            bestY[half] = bestAt.toFloat()
            bestStrength[half] = best.toFloat() / (samples * 255f * 2f)
        }

        if (abs(bestY[0] - bestY[1]) > cardH * MAX_TILT) {
            val strongest = if (bestStrength[0] >= bestStrength[1]) 0 else 1
            bestY[1 - strongest] = bestY[strongest]
            bestStrength[1 - strongest] = bestStrength[strongest] * 0.5f
        }

        val xc0 = left + (right - left) * 0.3f
        val xc1 = left + (right - left) * 0.7f
        return Edge(xc0, bestY[0], xc1, bestY[1], min(bestStrength[0], bestStrength[1]))
    }

    /** Schnittpunkt zweier Geraden (je durch zwei Punkte); null bei Parallelitaet. */
    private fun intersect(a: Edge, b: Edge): Pair<Float, Float>? {
        val d1x = a.x2 - a.x1
        val d1y = a.y2 - a.y1
        val d2x = b.x2 - b.x1
        val d2y = b.y2 - b.y1
        val denom = d1x * d2y - d1y * d2x
        if (abs(denom) < 1e-4f) return null
        val t = ((b.x1 - a.x1) * d2y - (b.y1 - a.y1) * d2x) / denom
        return (a.x1 + t * d1x) to (a.y1 + t * d1y)
    }

    /**
     * Groben Unsinn verwerfen: Ecken muessen nahe am Bild liegen und die
     * Seitenlaengen in der Naehe des Zielrahmens bleiben (±25 %).
     */
    private fun plausible(quad: CardQuad, image: LumaImage, cardW: Float, cardH: Float): Boolean {
        val marginX = image.width * 0.05f
        val marginY = image.height * 0.05f
        val xs = floatArrayOf(quad.topLeftX, quad.topRightX, quad.bottomRightX, quad.bottomLeftX)
        val ys = floatArrayOf(quad.topLeftY, quad.topRightY, quad.bottomRightY, quad.bottomLeftY)
        for (x in xs) if (x < -marginX || x > image.width + marginX) return false
        for (y in ys) if (y < -marginY || y > image.height + marginY) return false

        val topLen = abs(quad.topRightX - quad.topLeftX)
        val bottomLen = abs(quad.bottomRightX - quad.bottomLeftX)
        val leftLen = abs(quad.bottomLeftY - quad.topLeftY)
        val rightLen = abs(quad.bottomRightY - quad.topRightY)
        val wOk = topLen in cardW * 0.75f..cardW * 1.25f && bottomLen in cardW * 0.75f..cardW * 1.25f
        val hOk = leftLen in cardH * 0.75f..cardH * 1.25f && rightLen in cardH * 0.75f..cardH * 1.25f
        return wOk && hOk
    }
}
