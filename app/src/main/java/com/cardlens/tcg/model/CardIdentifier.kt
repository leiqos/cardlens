package com.cardlens.tcg.model

/**
 * Eindeutige Druck-Kennung, wie sie auf modernen TCG-Karten aufgedruckt ist.
 * Ein Treffer identifiziert Spiel UND exakte Edition — damit reicht ein
 * einziger API-Call statt einer Suche ueber alle Spiele.
 */
sealed interface CardIdentifier {
    val display: String

    /** Magic: Set-Code + Sammlernummer, z. B. "MID · 123" (unten links). */
    data class Magic(val setCode: String, val number: String) : CardIdentifier {
        override val display get() = "${setCode.uppercase()} · $number"
    }

    /** Pokémon: Nummer/Setgroesse, z. B. "219/191" (unten). */
    data class Pokemon(val number: String, val printedTotal: String) : CardIdentifier {
        override val display get() = "$number/$printedTotal"
    }

    /** Yu-Gi-Oh!: 8-stelliger Passcode (unten links). */
    data class YugiohPasscode(val passcode: String) : CardIdentifier {
        override val display get() = "#$passcode"
    }

    /** Yu-Gi-Oh!: Set-Code der Auflage, z. B. "LOB-EN001" (unter dem Artwork). */
    data class YugiohSet(val setCode: String) : CardIdentifier {
        override val display get() = setCode
    }

    /** One Piece: Karten-ID, z. B. "OP01-025" (unten links). */
    data class OnePiece(val cardSetId: String) : CardIdentifier {
        override val display get() = cardSetId
    }

    /** Lorcana: Kartennummer + Set, z. B. "207/204 · EN · 1". */
    data class Lorcana(val setNumber: String, val number: String) : CardIdentifier {
        override val display get() = "$number · Set $setNumber"
    }
}

/**
 * Findet Kennungen in rohen OCR-Zeilen. Rueckgabe ist nach Verlaesslichkeit
 * sortiert (eindeutige Muster zuerst) — der Aufrufer probiert sie der Reihe
 * nach, bis ein Lookup trifft.
 */
object CardIdentifierDetector {

    private val onePieceRegex =
        Regex("""\b(OP|ST|EB|PRB)\s?(\d{2})\s?-\s?(\d{3})\b""", RegexOption.IGNORE_CASE)

    private val ygoPasscodeRegex = Regex("""(?<!\d)(\d{8})(?!\d)""")

    // "LOB-EN001" / "SDY-006" / "MP19-EN123" — YGO-Auflagen-Code.
    // One-Piece-IDs (OP01-025 …) sehen aehnlich aus und werden ausgeschlossen.
    private val ygoSetRegex = Regex("""\b([A-Z][A-Z0-9]{1,4})\s?-\s?([A-Z]{0,2}\d{3})\b""")

    // "MID • EN" / "BLB·DE" / "MID EN" — Set-Code gefolgt von Sprachkuerzel.
    // Der Trennpunkt ist optional: OCR liest ihn oft als Leerzeichen oder gar nicht.
    private val magicSetRegex = Regex(
        """\b([A-Z][A-Z0-9]{2,3})\s*[•·∙.*-]?\s*(EN|DE|FR|IT|ES|PT|JA|JP|KO|RU|ZH|PH)\b"""
    )

    // "207/204 • EN • 1" — Lorcana: Nummer/Total, Sprache, Setnummer
    private val lorcanaRegex =
        Regex("""\b(\d{1,3})\s*/\s*\d{1,3}\s*[•·∙.-]?\s*[A-Z]{2}\s*[•·∙.-]?\s*(\d{1,2})\b""")

    // "219/191" — Pokémon (und Magic) Sammlernummer/Setgroesse
    private val slashNumberRegex = Regex("""\b(\d{1,3})\s*/\s*(\d{2,3})\b""")

    // Eigenstaendige Sammlernummer-Zeile, z. B. "0123" oder "123 R"
    private val bareNumberRegex = Regex("""^\s*0*(\d{1,4})\s*[CURMS]?\s*$""")

    fun detect(lines: List<String>): List<CardIdentifier> {
        val found = LinkedHashSet<CardIdentifier>()

        var magicSet: String? = null
        var collectorNumber: String? = null
        var slashMatch: Pair<String, String>? = null

        for (line in lines) {
            onePieceRegex.find(line)?.let { m ->
                found += CardIdentifier.OnePiece(
                    "${m.groupValues[1].uppercase()}${m.groupValues[2]}-${m.groupValues[3]}"
                )
            }
            ygoPasscodeRegex.find(line)?.let { m ->
                found += CardIdentifier.YugiohPasscode(m.groupValues[1])
            }
            lorcanaRegex.find(line)?.let { m ->
                found += CardIdentifier.Lorcana(
                    setNumber = m.groupValues[2],
                    number = m.groupValues[1].trimStart('0').ifEmpty { "0" }
                )
            }
            magicSetRegex.find(line)?.let { m ->
                val code = m.groupValues[1]
                if (!code.all(Char::isDigit)) magicSet = code
            }
            ygoSetRegex.find(line)?.let { m ->
                val prefix = m.groupValues[1]
                val isOnePiece = prefix.uppercase().matches(Regex("(OP|ST|EB|PRB)\\d{2}"))
                if (!isOnePiece) {
                    found += CardIdentifier.YugiohSet(
                        "${prefix.uppercase()}-${m.groupValues[2].uppercase()}"
                    )
                }
            }
            slashNumberRegex.find(line)?.let { m ->
                if (slashMatch == null) slashMatch = m.groupValues[1] to m.groupValues[2]
            }
            bareNumberRegex.find(line)?.let { m ->
                if (collectorNumber == null) collectorNumber = m.groupValues[1]
            }
        }

        // Magic braucht Set-Code UND Nummer (Nummer aus Slash-Paar oder eigener Zeile)
        magicSet?.let { set ->
            val number = slashMatch?.first?.trimStart('0')?.ifEmpty { "0" }
                ?: collectorNumber
            if (number != null) {
                found += CardIdentifier.Magic(set, number)
            }
        }

        // Nummer/Total ohne Magic-Set-Code → Pokémon-Kandidat
        // (Setgroessen sind >= 30; filtert Magic-Kampfwerte wie "3/3")
        slashMatch?.let { (num, total) ->
            if (magicSet == null && (total.toIntOrNull() ?: 0) >= 30) {
                found += CardIdentifier.Pokemon(
                    number = num.trimStart('0').ifEmpty { "0" },
                    printedTotal = total
                )
            }
        }

        return found.toList()
    }
}
