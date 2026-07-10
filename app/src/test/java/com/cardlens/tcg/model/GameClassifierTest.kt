package com.cardlens.tcg.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spiel-Erkennung aus OCR-Zeilen — jeder Fall entspricht dem Text, den
 * ML Kit von einer echten Karte des jeweiligen Spiels liest.
 */
class GameClassifierTest {

    private fun classify(vararg lines: String) = GameClassifier.classify(lines.toList())

    @Test
    fun `magic karte auf englisch`() {
        assertEquals(
            TcgGame.MAGIC,
            classify("Lightning Bolt", "Instant", "Lightning Bolt deals 3 damage to any target.", "MID • EN", "123/281 R")
        )
    }

    @Test
    fun `magic karte auf deutsch mit kampfwerten`() {
        assertEquals(
            TcgGame.MAGIC,
            classify("Grizzlybären", "Kreatur — Bär", "2/2", "BLB · DE")
        )
    }

    @Test
    fun `magic kampfwerte zaehlen nicht als pokemon nummer`() {
        // Die entscheidende Falle: "123/281" sieht aus wie eine Pokémon-Nummer,
        // aber die Typzeile macht klar, dass es Magic ist.
        val game = classify("Serra Angel", "Creature — Angel", "Flying, vigilance", "4/4", "123/281")
        assertEquals(TcgGame.MAGIC, game)
    }

    @Test
    fun `pokemon karte auf deutsch`() {
        assertEquals(
            TcgGame.POKEMON,
            classify("Glurak", "120 KP", "Schwäche ×2", "Rückzug", "025/185")
        )
    }

    @Test
    fun `pokemon karte auf englisch`() {
        assertEquals(
            TcgGame.POKEMON,
            classify("Charizard ex", "HP 330", "Evolves from Charmeleon", "Weakness", "199/165")
        )
    }

    @Test
    fun `yugioh karte mit atk def`() {
        assertEquals(
            TcgGame.YUGIOH,
            classify("Blauäugiger w. Drache", "LOB-DE001", "ATK/3000 DEF/2500", "89631139")
        )
    }

    @Test
    fun `one piece karte`() {
        assertEquals(
            TcgGame.ONEPIECE,
            classify("Monkey.D.Luffy", "LEADER", "OP01-003", "DON!!×1")
        )
    }

    @Test
    fun `lorcana karte`() {
        assertEquals(
            TcgGame.LORCANA,
            classify("Elsa – Snow Queen", "©Disney", "207/204 • EN • 1")
        )
    }

    @Test
    fun `star wars unlimited karte`() {
        assertEquals(
            TcgGame.STARWARS,
            classify("Darth Vader", "SOR", "123/252", "™ & © Lucasfilm Ltd.")
        )
    }

    @Test
    fun `dragon ball fusion world karte`() {
        assertEquals(
            TcgGame.DRAGONBALL,
            classify("Son Goku", "FB01-001", "©BIRD STUDIO/SHUEISHA")
        )
    }

    @Test
    fun `unklare eingabe liefert null`() {
        assertNull(classify("irgendein Text", "ohne Merkmale"))
        assertNull(classify())
    }
}
