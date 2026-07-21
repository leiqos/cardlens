package com.cardlens.tcg.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kennungs-Erkennung aus rohen OCR-Zeilen — inkl. der Reparatur typischer
 * OCR-Verwechsler (O↔0, I↔1 …). Jeder Fall entspricht einer realen
 * Kartenunterkante, wie ML Kit sie liefert.
 */
class CardIdentifierDetectorTest {

    private fun detect(vararg lines: String) = CardIdentifierDetector.detect(lines.toList())

    // ---- Magic ---------------------------------------------------------------

    @Test
    fun `magic set code mit sprache und sammlernummer`() {
        val ids = detect("MID • EN", "123/281 M")
        assertTrue(CardIdentifier.Magic("MID", "123", "EN") in ids)
    }

    @Test
    fun `magic moderne vierstellige sammlernummer`() {
        val ids = detect("BLB · DE", "0123/0281 R")
        assertTrue(CardIdentifier.Magic("BLB", "123", "DE") in ids)
    }

    @Test
    fun `magic ocr fehler im set code wird repariert`() {
        // OCR liest "MID" als "M1D" — Ziffer zwischen Buchstaben → Buchstabe
        val ids = detect("M1D EN", "45/281")
        assertTrue(ids.any { it is CardIdentifier.Magic && it.setCode == "MID" })
    }

    @Test
    fun `magic slash paar erzeugt keinen pokemon kandidaten`() {
        val ids = detect("MID • EN", "123/281")
        assertFalse(ids.any { it is CardIdentifier.Pokemon })
    }

    // ---- Pokémon ---------------------------------------------------------------

    @Test
    fun `pokemon nummer mit setgroesse`() {
        val ids = detect("219/191")
        assertTrue(CardIdentifier.Pokemon("219", "191") in ids)
    }

    @Test
    fun `pokemon kleine sets wie celebrations`() {
        val ids = detect("4/25")
        assertTrue(CardIdentifier.Pokemon("4", "25") in ids)
    }

    @Test
    fun `magic kampfwerte sind keine pokemon nummer`() {
        assertFalse(detect("3/3").any { it is CardIdentifier.Pokemon })
    }

    // ---- Yu-Gi-Oh! -------------------------------------------------------------

    @Test
    fun `ygo passcode und set code`() {
        val ids = detect("LOB-EN001", "89631139")
        assertTrue(CardIdentifier.YugiohPasscode("89631139") in ids)
        assertTrue(CardIdentifier.YugiohSet("LOB-EN001") in ids)
    }

    @Test
    fun `ygo set code ohne sprachkuerzel`() {
        assertTrue(CardIdentifier.YugiohSet("SDY-006") in detect("SDY-006"))
    }

    // ---- One Piece / Dragon Ball -------------------------------------------------

    @Test
    fun `one piece karten id`() {
        assertTrue(CardIdentifier.OnePiece("OP01-025") in detect("OP01-025"))
    }

    @Test
    fun `one piece ocr null statt O wird repariert`() {
        assertTrue(detect("0P01-025").any { it is CardIdentifier.OnePiece })
    }

    @Test
    fun `dragon ball code ist kein ygo set`() {
        val ids = detect("FB01-001")
        assertTrue(CardIdentifier.DragonBall("FB01-001") in ids)
        assertFalse(ids.any { it is CardIdentifier.YugiohSet })
    }

    @Test
    fun `one piece id ist kein ygo set`() {
        assertFalse(detect("ST05-017").any { it is CardIdentifier.YugiohSet })
    }

    // ---- Lorcana ---------------------------------------------------------------

    @Test
    fun `lorcana nummer sprache set`() {
        val ids = detect("207/204 • EN • 1")
        assertTrue(CardIdentifier.Lorcana(setNumber = "1", number = "207") in ids)
    }

    // ---- Star Wars: Unlimited -----------------------------------------------------

    @Test
    fun `star wars set code mit nummer`() {
        val ids = detect("SOR", "123/252")
        assertTrue(CardIdentifier.StarWars("SOR", "123") in ids)
        // Whitelist-Treffer verhindert den schwaecheren Pokémon-Kandidaten
        assertFalse(ids.any { it is CardIdentifier.Pokemon })
    }

    @Test
    fun `unbekannter dreibuchstaben code ist kein star wars set`() {
        assertFalse(detect("XYZ", "123/252").any { it is CardIdentifier.StarWars })
    }

    // ---- Nummern-Zone (untere Kartenkante) ------------------------------------------

    @Test
    fun `manakosten oben zaehlen nicht als sammlernummer`() {
        // Reale Fehllesung: "Trading Post" (LTC, Manakosten 4) wurde als
        // "LTC · 4" gedeutet → voellig andere Karte (Sauron). Die "4" oben
        // rechts steht NICHT in der Nummern-Zone und darf nicht zaehlen.
        val ids = CardIdentifierDetector.detect(
            lines = listOf("Trading Post", "4", "Artifact", "0329", "LTC • EN"),
            numberZone = listOf("0329", "LTC • EN")
        )
        assertTrue(CardIdentifier.Magic("LTC", "329", "EN") in ids)
        assertFalse(ids.any { it is CardIdentifier.Magic && it.number == "4" })
    }

    @Test
    fun `kampfwerte in der nummern-zone sind keine sammlernummer`() {
        // Kreatur: P/T "3/3" steht knapp ueber der Sammlernummer "0329".
        val ids = detect("LTC • EN", "3/3", "0329")
        assertTrue(CardIdentifier.Magic("LTC", "329", "EN") in ids)
        assertFalse(ids.any { it is CardIdentifier.Magic && it.number == "3" })
    }

    @Test
    fun `unterster slash-treffer gewinnt`() {
        // "20/20"-Kampfwerte ueber der echten Sammlernummer "123/281":
        // der untere Treffer ist die gedruckte Nummer.
        val ids = detect("MID • EN", "20/20", "123/281")
        assertTrue(CardIdentifier.Magic("MID", "123", "EN") in ids)
    }

    @Test
    fun `ohne nummern-zone bleibt das verhalten unveraendert`() {
        val ids = CardIdentifierDetector.detect(listOf("MID • EN", "123/281 M"))
        assertTrue(CardIdentifier.Magic("MID", "123", "EN") in ids)
    }

    // ---- Reihenfolge / Robustheit --------------------------------------------------

    @Test
    fun `starke kennungen stehen vor schwachen`() {
        val ids = detect("OP01-025", "12/102")
        assertEquals(CardIdentifier.OnePiece("OP01-025"), ids.first())
    }

    @Test
    fun `leere eingabe liefert nichts`() {
        assertTrue(detect().isEmpty())
        assertTrue(detect("Blitzableiter", "Kreatur – Elementar").isEmpty())
    }

    @Test
    fun `shared numbers are not treated as exact collectible printings`() {
        assertFalse(CardIdentifier.OnePiece("OP01-025").identifiesPrinting)
        assertFalse(CardIdentifier.DragonBall("FB01-001").identifiesPrinting)
        assertFalse(CardIdentifier.YugiohPasscode("89631139").identifiesPrinting)
        assertTrue(CardIdentifier.Magic("MID", "123").identifiesPrinting)
        assertTrue(CardIdentifier.StarWars("SOR", "123").identifiesPrinting)
    }

    @Test
    fun `modern pokemon footer is not misclassified as magic`() {
        val ids = CardIdentifierDetector.detect(
            lines = listOf("Charizard ex", "330 HP", "OBF EN", "125/197"),
            numberZone = listOf("OBF EN", "125/197"),
            gameHint = TcgGame.POKEMON
        )
        assertTrue(CardIdentifier.Pokemon("125", "197", "OBF", "EN") in ids)
        assertFalse(ids.any { it is CardIdentifier.Magic })
        assertTrue(ids.filterIsInstance<CardIdentifier.Pokemon>().single().identifiesPrinting)
    }

    @Test
    fun `same footer is magic only with magic context`() {
        val ids = CardIdentifierDetector.detect(
            lines = listOf("Creature — Dragon", "WOE EN", "0123/0266"),
            numberZone = listOf("WOE EN", "0123/0266"),
            gameHint = TcgGame.MAGIC
        )
        assertTrue(CardIdentifier.Magic("WOE", "123", "EN") in ids)
        assertFalse(ids.any { it is CardIdentifier.Pokemon })
    }

    @Test
    fun `new star wars sets work without app update when game is known`() {
        val ids = CardIdentifierDetector.detect(
            listOf("STAR WARS", "LAW", "011/260"),
            listOf("LAW", "011/260"),
            TcgGame.STARWARS
        )
        assertTrue(CardIdentifier.StarWars("LAW", "11") in ids)
    }

    @Test
    fun `riftbound variant suffix selects exact showcase printing`() {
        val ids = CardIdentifierDetector.detect(
            listOf("RIFTBOUND", "OGN 202a/298"),
            listOf("OGN 202a/298"),
            TcgGame.RIFTBOUND
        )
        assertTrue(CardIdentifier.Riftbound("OGN", "202", "298", "a") in ids)
    }

    @Test
    fun `dragon ball parallel stars are preserved`() {
        assertTrue(CardIdentifier.DragonBall("FB01-001", 2) in detect("FB01-001★★"))
    }
}
