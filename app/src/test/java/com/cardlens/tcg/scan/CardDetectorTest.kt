package com.cardlens.tcg.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Synthetische Testbilder fuer die Kantenerkennung: helle "Karte" auf dunklem
 * Hintergrund, wahlweise verschoben oder gekippt gegenueber dem Zielrahmen.
 */
class CardDetectorTest {

    private val width = 320
    private val height = 427

    /** Zielrahmen wie im Scanner: mittig, leicht nach oben versetzt. */
    private val guideLeft = 48f
    private val guideTop = 60f
    private val guideRight = 272f
    private val guideBottom = 373f

    private fun image(card: (x: Int, y: Int) -> Boolean): LumaImage {
        val data = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (card(x, y)) 190 else 35
        }
        return LumaImage(width, height, data)
    }

    private fun detect(img: LumaImage): CardDetection =
        CardDetector.detect(img, guideLeft, guideTop, guideRight, guideBottom)

    @Test
    fun `findet exakt am Rahmen ausgerichtete Karte`() {
        val img = image { x, y -> x in 48..272 && y in 60..373 }
        val d = detect(img)
        assertTrue("Karte muss erkannt werden (conf=${d.confidence})", d.confidence > 0.3f)
        assertEquals(4, d.snappedSides)
        assertEquals(48f, d.quad.topLeftX, 3f)
        assertEquals(60f, d.quad.topLeftY, 3f)
        assertEquals(272f, d.quad.bottomRightX, 3f)
        assertEquals(373f, d.quad.bottomRightY, 3f)
    }

    @Test
    fun `schnappt auf verschobene Karte im Suchband`() {
        // Karte 14 px nach rechts und 10 px nach unten verschoben
        val img = image { x, y -> x in 62..286 && y in 70..383 }
        val d = detect(img)
        assertTrue(d.confidence > 0.3f)
        assertEquals(62f, d.quad.topLeftX, 3f)
        assertEquals(70f, d.quad.topLeftY, 3f)
        assertEquals(286f, d.quad.topRightX, 3f)
        assertEquals(383f, d.quad.bottomLeftY, 3f)
    }

    @Test
    fun `folgt leicht gekippter Kante`() {
        // Linke/rechte Kante wandern ueber die Hoehe um 12 px (leichte Neigung)
        val img = image { x, y ->
            val t = (y - 60) / 313f
            val left = 48 + 12 * t
            val right = 272 + 12 * t
            y in 60..373 && x >= left && x <= right
        }
        val d = detect(img)
        assertTrue(d.confidence > 0.3f)
        // Obere linke Ecke nahe 48, untere linke Ecke nahe 60
        assertTrue(
            "obere/untere linke Ecke sollen der Neigung folgen " +
                "(oben=${d.quad.topLeftX}, unten=${d.quad.bottomLeftX})",
            d.quad.bottomLeftX - d.quad.topLeftX > 5f
        )
    }

    @Test
    fun `faellt ohne Kanten auf den Zielrahmen zurueck`() {
        val img = image { _, _ -> false } // gleichmaessig dunkel
        val d = detect(img)
        assertEquals(0f, d.confidence, 0.0001f)
        assertEquals(0, d.snappedSides)
        assertEquals(guideLeft, d.quad.topLeftX, 0.01f)
        assertEquals(guideTop, d.quad.topLeftY, 0.01f)
        assertEquals(guideRight, d.quad.bottomRightX, 0.01f)
        assertEquals(guideBottom, d.quad.bottomRightY, 0.01f)
    }

    @Test
    fun `ignoriert Karte weit ausserhalb des Suchbands`() {
        // Kante 60 px neben dem Rahmen — ausserhalb des 13-%-Suchbands;
        // dort darf nichts gefunden werden, was das Quad unplausibel verzerrt.
        val img = image { x, y -> x in 120..200 && y in 130..300 }
        val d = detect(img)
        // Entweder Rueckfall auf den Rahmen oder ein plausibles Quad nahe am Rahmen
        val widthDetected = d.quad.topRightX - d.quad.topLeftX
        assertTrue(
            "Quad-Breite muss nahe der Rahmenbreite bleiben (war $widthDetected)",
            abs(widthDetected - (guideRight - guideLeft)) < (guideRight - guideLeft) * 0.3f
        )
    }

    @Test
    fun `quad skalierung`() {
        val q = CardQuad.fromRect(10f, 20f, 30f, 40f).scaled(2f, 0.5f)
        assertEquals(20f, q.topLeftX, 0.001f)
        assertEquals(10f, q.topLeftY, 0.001f)
        assertEquals(60f, q.bottomRightX, 0.001f)
        assertEquals(20f, q.bottomRightY, 0.001f)
    }
}
