package com.cardlens.tcg.scan

import com.cardlens.tcg.model.CardIdentifier
import com.cardlens.tcg.model.CardIdentifierDetector
import com.cardlens.tcg.model.GameClassifier
import com.cardlens.tcg.model.TcgGame
import com.google.mlkit.vision.text.Text

/** Ergebnis der Analyse eines entzerrten Kartenbilds. */
data class ScanReading(
    val nameCandidate: String?,
    val identifiers: List<CardIdentifier>,
    val linesInFrame: Int,
    /** Aus den gedruckten Merkmalen erkanntes Spiel (null = unklar). */
    val gameHint: TcgGame? = null
)

/**
 * Liest aus dem OCR-Ergebnis eines perspektivisch entzerrten Kartenbilds:
 *  1. aufgedruckte Druck-Kennungen (Set-Code+Nummer, Passcode, Karten-ID …)
 *     — die identifizieren Spiel UND exakte Edition,
 *  2. den Kartennamen als Fallback.
 *
 * Weil das Bild bereits exakt auf die Karte zugeschnitten ist, sind die
 * Namenszonen kartenrelativ und deutlich enger als frueher am Vollbild:
 *  - oben (≤ 22 %): Magic, Pokémon, Yu-Gi-Oh!, Star Wars, Dragon Ball
 *  - Mitte (48–70 %): Lorcana (Name unter dem Artwork)
 *  - unten (72–93 %): One Piece (Namensbanner)
 * Der Faehigkeitstext dazwischen wird ignoriert.
 */
object CardNameExtractor {

    private val stopWords = setOf(
        // Pokémon
        "BASIC", "BASIS", "STAGE", "PHASE", "TRAINER", "ENERGY", "ENERGIE",
        "SUPPORTER", "UNTERSTUETZER", "ITEM", "EVOLVES", "ENTWICKELT",
        // Magic
        "CREATURE", "KREATUR", "INSTANT", "SPONTANZAUBER", "SORCERY", "HEXEREI",
        "ENCHANTMENT", "VERZAUBERUNG", "ARTIFACT", "ARTEFAKT", "PLANESWALKER",
        "LAND", "LEGENDARY", "LEGENDAERE",
        // Yu-Gi-Oh!
        "EFFECT", "MONSTER", "SPELL", "TRAP", "ZAUBER", "FALLE", "ATK", "DEF",
        // One Piece
        "CHARACTER", "LEADER", "EVENT", "DON", "COUNTER", "BLOCKER", "TRIGGER",
        // Allgemein
        "HP", "KP", "LV", "LEVEL", "CARD", "KARTE"
    )

    /**
     * Ohne Karte im Rahmen liefert die Rueckfall-Entzerrung Hintergrund
     * (Buchseiten, Verpackungen) — dichter Fliesstext hat viele Zeilen,
     * dann wird kein Name geraten.
     */
    private const val MAX_LINES_FOR_NAME = 26

    /**
     * Nummern-Zone: unterster Kartenstreifen, in dem Sammlernummer und
     * Set-Code gedruckt sind. Manakosten (oben rechts), Kampfwerte und
     * Loyalitaet liegen darueber und duerfen nie als Sammlernummer zaehlen.
     */
    const val NUMBER_ZONE_TOP = 0.78f

    fun extract(text: Text, imageWidth: Int, imageHeight: Int): ScanReading {
        if (imageHeight <= 0 || imageWidth <= 0) return ScanReading(null, emptyList(), 0)

        val lines = text.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
        val lineTexts = lines.map { it.text }
        val numberZone = lines
            .filter { it.boundingBox!!.top >= imageHeight * NUMBER_ZONE_TOP }
            .sortedBy { it.boundingBox!!.top }
            .map { it.text }
        val identifiers = CardIdentifierDetector.detect(lineTexts, numberZone)
        val gameHint = GameClassifier.classify(lineTexts)

        val name = if (lines.size in 2..MAX_LINES_FOR_NAME) {
            val minLineHeight = imageHeight * 0.025f
            lines
                .mapNotNull { line -> score(line, imageHeight, minLineHeight) }
                .maxByOrNull { it.second }
                ?.first
        } else {
            null // zu wenig Kontext (einzelne Woerter) oder Fliesstext
        }

        return ScanReading(name, identifiers, lines.size, gameHint)
    }

    private fun score(line: Text.Line, imageHeight: Int, minLineHeight: Float): Pair<String, Float>? {
        val box = line.boundingBox ?: return null
        if (box.height() < minLineHeight) return null
        val topRatio = box.top.toFloat() / imageHeight

        // Kartenrelative Namenszonen; die Mitte (Faehigkeitstext) faellt raus.
        val zoneWeight = when {
            topRatio <= 0.22f -> 1.6f - topRatio
            topRatio in 0.48f..0.70f -> 0.7f
            topRatio in 0.72f..0.93f -> 0.8f
            else -> return null
        }

        val cleaned = clean(line.text) ?: return null
        val letters = cleaned.count { it.isLetter() }
        if (letters < 3 || letters < cleaned.length * 0.6) return null
        if (cleaned.uppercase() in stopWords) return null
        // Einzelne GROSSBUCHSTABEN-Woerter sind meist Umgebungstext
        // (Tastatur, Verpackung), keine Kartennamen.
        if (!cleaned.contains(' ') && cleaned.length <= 8 && cleaned == cleaned.uppercase()) return null

        // Grosse Zeilen gewinnen; laengere Namen sind verlaesslicher.
        val lengthBonus = 1f + minOf(cleaned.length, 14) / 28f
        val score = box.height() * zoneWeight * lengthBonus
        return cleaned to score
    }

    private fun clean(raw: String): String? {
        var s = raw
            .replace(Regex("[^\\p{L}\\p{N}'’\\-,.&: ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        // KP/HP-Werte am Zeilenende entfernen ("Glurak 120 KP" -> "Glurak")
        s = s.replace(Regex("\\b\\d+\\s*(KP|HP)\\b.*$", RegexOption.IGNORE_CASE), "").trim()
        // Reste von Symbolen/Ziffern an den Raendern abschneiden
        s = s.trim { !it.isLetter() && it != '\'' && it != '’' }
        return s.takeIf { it.length in 3..32 }
    }
}
