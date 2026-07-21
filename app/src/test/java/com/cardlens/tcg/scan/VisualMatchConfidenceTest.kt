package com.cardlens.tcg.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualMatchConfidenceTest {

    @Test
    fun `close runner up blocks automatic variant selection`() {
        val confidence = VisualMatchConfidence.score(best = 60, second = 68, compared = 2)
        assertTrue(confidence < 0.8f)
    }

    @Test
    fun `clear visual winner is trusted`() {
        assertEquals(0.95f, VisualMatchConfidence.score(52, 90, 4), 0.001f)
    }

    @Test
    fun `single candidate still needs a close absolute match`() {
        assertEquals(0.9f, VisualMatchConfidence.score(50, Int.MAX_VALUE, 1), 0.001f)
        assertEquals(0.1f, VisualMatchConfidence.score(130, Int.MAX_VALUE, 1), 0.001f)
    }

    @Test
    fun `no comparable image has zero confidence`() {
        assertEquals(0f, VisualMatchConfidence.score(Int.MAX_VALUE, Int.MAX_VALUE, 0), 0f)
    }
}
