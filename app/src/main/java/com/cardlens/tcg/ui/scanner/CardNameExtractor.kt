package com.cardlens.tcg.ui.scanner

import com.cardlens.tcg.model.CardIdentifier
import com.cardlens.tcg.model.CardIdentifierDetector
import com.google.mlkit.vision.text.Text

/** Ergebnis der Analyse eines Kamerabilds. */
data class ScanReading(
    val nameCandidate: String?,
    val identifiers: List<CardIdentifier>,
    val linesInFrame: Int
)

/**
 * Liest aus dem OCR-Ergebnis eines Kamerabilds:
 *  1. aufgedruckte Druck-Kennungen (Set-Code+Nummer, Passcode, Karten-ID …)
 *     — die identifizieren Spiel UND exakte Edition,
 *  2. den Kartennamen als Fallback (grosse Zeile oben bzw. unten bei One Piece).
 *
 * Es zaehlt nur Text innerhalb des Zielrahmens — Text ausserhalb (Buecher,
 * Tastaturen, Hintergrund) wird ignoriert.
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

    // Zielrahmen-Region im Analysebild (relativ). Grosszuegig gefasst, weil
    // Vorschau- und Analyse-Ausschnitt nicht exakt deckungsgleich sind.
    private const val FRAME_LEFT = 0.06f
    private const val FRAME_RIGHT = 0.94f
    private const val FRAME_TOP = 0.08f
    private const val FRAME_BOTTOM = 0.96f

    /** Dichter Fliesstext (Buchseite) hat viele Zeilen — dann keinen Namen raten. */
    private const val MAX_LINES_FOR_NAME = 16

    fun extract(text: Text, imageWidth: Int, imageHeight: Int): ScanReading {
        if (imageHeight <= 0 || imageWidth <= 0) return ScanReading(null, emptyList(), 0)

        val framedLines = text.textBlocks
            .flatMap { it.lines }
            .filter { line ->
                val box = line.boundingBox ?: return@filter false
                val cx = box.centerX().toFloat() / imageWidth
                val cy = box.centerY().toFloat() / imageHeight
                cx in FRAME_LEFT..FRAME_RIGHT && cy in FRAME_TOP..FRAME_BOTTOM
            }

        val identifiers = CardIdentifierDetector.detect(framedLines.map { it.text })

        val name = if (framedLines.size in 2..MAX_LINES_FOR_NAME) {
            val minLineHeight = imageHeight * 0.02f
            framedLines
                .mapNotNull { line -> score(line, imageHeight, minLineHeight) }
                .maxByOrNull { it.second }
                ?.first
        } else {
            null // zu wenig Kontext (einzelne Woerter) oder Fliesstext
        }

        return ScanReading(name, identifiers, framedLines.size)
    }

    private fun score(line: Text.Line, imageHeight: Int, minLineHeight: Float): Pair<String, Float>? {
        val box = line.boundingBox ?: return null
        if (box.height() < minLineHeight) return null
        val topRatio = box.top.toFloat() / imageHeight

        // Zwei Namenszonen: oben (Magic, Pokémon, Yu-Gi-Oh!, Lorcana) und
        // unten (One Piece druckt den Namen im unteren Kartenbanner).
        // Die Kartenmitte (Faehigkeitstext) wird ignoriert.
        val zoneWeight = when {
            topRatio <= 0.55f -> 1.5f - topRatio
            topRatio >= 0.72f -> 0.8f
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
