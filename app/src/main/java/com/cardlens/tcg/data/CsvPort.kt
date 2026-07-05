package com.cardlens.tcg.data

import com.cardlens.tcg.data.local.CollectionEntry
import com.cardlens.tcg.data.local.DeckCard
import com.cardlens.tcg.model.CardCondition
import com.cardlens.tcg.model.TcgGame

/**
 * CSV-Import/-Export im verbreiteten Sammlungs-Spaltenlayout (u. a. von
 * ManaBox verwendet) plus eigene Zusatzspalten fuer Nicht-Magic-Spiele.
 */
object CsvPort {

    private val HEADER = listOf(
        "Name", "Set code", "Set name", "Collector number", "Foil", "Rarity",
        "Quantity", "Scryfall ID", "Purchase price", "Misprint", "Altered",
        "Condition", "Language", "Purchase price currency",
        // Zusatzspalten (fuer Re-Import und Nicht-Magic-Spiele)
        "Game", "Card ID", "Wishlist"
    )

    /** Zustand → snake_case-Schreibweise, wie sie gaengige CSVs verwenden. */
    private fun conditionOut(code: String): String = when (CardCondition.fromCode(code)) {
        CardCondition.MINT -> "mint"
        CardCondition.NEAR_MINT -> "near_mint"
        CardCondition.EXCELLENT -> "excellent"
        CardCondition.GOOD -> "good"
        CardCondition.LIGHT_PLAYED -> "light_played"
        CardCondition.PLAYED -> "played"
        CardCondition.POOR -> "poor"
    }

    private fun conditionIn(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "mint", "m" -> "M"
        "near_mint", "near mint", "nm" -> "NM"
        "excellent", "ex" -> "EX"
        "good", "gd" -> "GD"
        "light_played", "lightly played", "lp" -> "LP"
        "played", "pl" -> "PL"
        "poor", "po", "damaged" -> "PO"
        else -> "NM"
    }

    fun exportCsv(entries: List<CollectionEntry>): String = buildString {
        appendLine(HEADER.joinToString(",") { quote(it) })
        for (e in entries) {
            val scryfallId = if (e.game == TcgGame.MAGIC.name) e.cardId.substringAfter(':') else ""
            val row = listOf(
                e.name,
                e.setCode.orEmpty(),
                e.setName.orEmpty(),
                e.collectorNumber.orEmpty(),
                if (e.foil) "foil" else "normal",
                e.rarity.orEmpty(),
                e.quantity.toString(),
                scryfallId,
                e.purchasePrice?.toString().orEmpty(),
                if (e.misprint) "true" else "false",
                if (e.altered) "true" else "false",
                conditionOut(e.condition),
                e.language,
                e.purchaseCurrency.orEmpty(),
                e.game,
                e.cardId,
                if (e.wishlist) "true" else "false"
            )
            appendLine(row.joinToString(",") { quote(it) })
        }
    }

    private fun quote(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value

    /** Eine importierte CSV-Zeile — noch nicht gegen die APIs aufgeloest. */
    data class CsvRow(
        val name: String,
        val setCode: String?,
        val setName: String?,
        val collectorNumber: String?,
        val foil: Boolean,
        val rarity: String?,
        val quantity: Int,
        val scryfallId: String?,
        val purchasePrice: Double?,
        val purchaseCurrency: String?,
        val misprint: Boolean,
        val altered: Boolean,
        val condition: String,
        val language: String,
        val game: TcgGame?,
        val cardId: String?,
        val wishlist: Boolean
    )

    /** Toleranter Header-Lookup: Gross-/Kleinschreibung und Unterstriche egal. */
    private fun headerIndex(header: List<String>): Map<String, Int> =
        header.withIndex().associate { (i, name) ->
            name.trim().lowercase().replace("_", " ") to i
        }

    fun parseCsv(text: String): List<CsvRow> {
        val records = parseRecords(text)
        if (records.isEmpty()) return emptyList()
        val idx = headerIndex(records.first())
        fun field(record: List<String>, vararg names: String): String? {
            for (n in names) {
                val i = idx[n] ?: continue
                return record.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
            }
            return null
        }
        return records.drop(1).mapNotNull { rec ->
            val name = field(rec, "name") ?: return@mapNotNull null
            CsvRow(
                name = name,
                setCode = field(rec, "set code", "set"),
                setName = field(rec, "set name"),
                collectorNumber = field(rec, "collector number", "number"),
                foil = field(rec, "foil")?.lowercase()?.let { it == "foil" || it == "true" || it == "etched" } == true,
                rarity = field(rec, "rarity"),
                quantity = field(rec, "quantity", "count", "qty")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                scryfallId = field(rec, "scryfall id"),
                purchasePrice = field(rec, "purchase price")?.replace(',', '.')?.toDoubleOrNull(),
                purchaseCurrency = field(rec, "purchase price currency", "currency"),
                misprint = field(rec, "misprint")?.equals("true", true) == true,
                altered = field(rec, "altered")?.equals("true", true) == true,
                condition = conditionIn(field(rec, "condition")),
                language = field(rec, "language")?.lowercase()?.take(2) ?: "en",
                game = field(rec, "game")?.let { g -> TcgGame.entries.firstOrNull { it.name.equals(g, true) } },
                cardId = field(rec, "card id"),
                wishlist = field(rec, "wishlist")?.equals("true", true) == true
            )
        }
    }

    /** Minimaler RFC-4180-Parser (Anfuehrungszeichen, Kommas, Zeilenumbrueche). */
    private fun parseRecords(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val record = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        fun endField() { record.add(field.toString()); field.clear() }
        fun endRecord() {
            endField()
            if (record.any { it.isNotBlank() }) records.add(record.toList())
            record.clear()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> endField()
                c == '\r' -> { /* ueberspringen, \n beendet die Zeile */ }
                c == '\n' -> endRecord()
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || record.isNotEmpty()) endRecord()
        return records
    }

    // ---- Decklisten ---------------------------------------------------------

    /** Deck als Text-Deckliste ("4 Lightning Bolt"), Bereiche getrennt. */
    fun exportDeckText(cards: List<DeckCard>): String = buildString {
        val byBoard = cards.groupBy { it.board }
        byBoard["COMMANDER"]?.let { commander ->
            appendLine("// Commander")
            commander.forEach { appendLine("${it.quantity} ${it.name}") }
            appendLine()
        }
        byBoard["MAIN"]?.let { main ->
            appendLine("// Hauptdeck")
            main.forEach { appendLine("${it.quantity} ${it.name}") }
        }
        byBoard["SIDE"]?.let { side ->
            appendLine()
            appendLine("// Sideboard")
            side.forEach { appendLine("${it.quantity} ${it.name}") }
        }
    }.trim()

    /**
     * Text-Deckliste einlesen. Akzeptiert "4 Name", "4x Name" und nackte
     * Namen; Kommentare (//, #) und Bereichs-Ueberschriften werden ignoriert,
     * "Sideboard"-Marker wechselt den Bereich.
     */
    fun parseDeckList(text: String): List<Triple<Int, String, String>> {
        val result = mutableListOf<Triple<Int, String, String>>()
        var board = "MAIN"
        val lineRegex = Regex("""^\s*(\d+)\s*x?\s+(.+?)\s*$""")
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("//") || line.startsWith("#")) {
                if (line.contains("side", ignoreCase = true)) board = "SIDE"
                if (line.contains("commander", ignoreCase = true)) board = "COMMANDER"
                continue
            }
            if (line.equals("sideboard", ignoreCase = true) || line.equals("sideboard:", ignoreCase = true)) {
                board = "SIDE"; continue
            }
            val m = lineRegex.find(line)
            if (m != null) {
                result += Triple(m.groupValues[1].toInt().coerceIn(1, 99), m.groupValues[2], board)
            } else {
                result += Triple(1, line, board)
            }
        }
        return result
    }
}
