package com.cardlens.tcg.model

/**
 * Eindeutige Druck-Kennung, wie sie auf modernen TCG-Karten aufgedruckt ist.
 * Ein Treffer identifiziert Spiel UND exakte Edition — damit reicht ein
 * einziger API-Call statt einer Suche ueber alle Spiele.
 */
sealed interface CardIdentifier {
    val display: String

    /** Magic: Set-Code + Sammlernummer, z. B. "MID · 123" (unten links). */
    data class Magic(
        val setCode: String,
        val number: String,
        val language: String? = null
    ) : CardIdentifier {
        override val display get() = listOfNotNull(
            setCode.uppercase(), number, language?.uppercase()
        ).joinToString(" · ")
    }

    /** Pokémon: Nummer/Setgroesse, z. B. "219/191" (unten). */
    data class Pokemon(
        val number: String,
        val printedTotal: String,
        /** Printed on Scarlet & Violet-era cards (e.g. MEW, OBF, SVI). */
        val setCode: String? = null,
        val language: String? = null
    ) : CardIdentifier {
        override val display get() = listOfNotNull(
            setCode?.uppercase(), "$number/$printedTotal", language?.uppercase()
        ).joinToString(" · ")
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

    /** Star Wars: Unlimited — Set-Code + Nummer, z. B. "SOR · 123". */
    data class StarWars(val setCode: String, val number: String) : CardIdentifier {
        override val display get() = "${setCode.uppercase()} · $number"
    }

    /** Dragon Ball Fusion World: Karten-Code, z. B. "FB01-001" (unten links). */
    data class DragonBall(val code: String, val parallelMarks: Int = 0) : CardIdentifier {
        override val display get() = code + "★".repeat(parallelMarks)
    }

    /** Riftbound: set + collector number + optional printed variant suffix. */
    data class Riftbound(
        val setCode: String,
        val number: String,
        val printedTotal: String,
        val variant: String? = null
    ) : CardIdentifier {
        override val display get() =
            "${setCode.uppercase()} · $number${variant.orEmpty()}/$printedTotal"
    }
}

/**
 * Stark = das Muster bestimmt Spiel und Druck strukturell eindeutig
 * (Praefix-Whitelist, Set-Code + Sprache, 8-stelliger Passcode …).
 * Schwach ist nur das nackte Nummer/Total-Paar (Pokémon) — es kommt auf
 * fast jeder Magic-/Lorcana-Karte ebenfalls vor und braucht deshalb
 * mehr Bestaetigung, bevor es eine Suche ausloesen darf.
 */
val CardIdentifier.isStrong: Boolean
    get() = this !is CardIdentifier.Pokemon

/**
 * True only when the printed identifier selects one artwork/printing in the
 * backing catalogue. Some strong-looking identifiers identify the game and
 * card rules text, but deliberately do not distinguish collectible variants:
 * One Piece parallels and Dragon Ball alt arts reuse the base card number;
 * a Yu-Gi-Oh! passcode and a Pokémon number/total can span several products.
 */
val CardIdentifier.identifiesPrinting: Boolean
    get() = when (this) {
        is CardIdentifier.Magic,
        is CardIdentifier.YugiohSet,
        is CardIdentifier.Lorcana,
        is CardIdentifier.StarWars,
        is CardIdentifier.Riftbound -> true
        is CardIdentifier.Pokemon -> setCode != null
        is CardIdentifier.YugiohPasscode,
        is CardIdentifier.OnePiece,
        is CardIdentifier.DragonBall -> false
    }

/** Zu welchem Spiel gehoert die Kennung — fuer den Spiel-Filter des Scanners. */
val CardIdentifier.game: TcgGame
    get() = when (this) {
        is CardIdentifier.Magic -> TcgGame.MAGIC
        is CardIdentifier.Pokemon -> TcgGame.POKEMON
        is CardIdentifier.YugiohPasscode, is CardIdentifier.YugiohSet -> TcgGame.YUGIOH
        is CardIdentifier.OnePiece -> TcgGame.ONEPIECE
        is CardIdentifier.Lorcana -> TcgGame.LORCANA
        is CardIdentifier.StarWars -> TcgGame.STARWARS
        is CardIdentifier.DragonBall -> TcgGame.DRAGONBALL
        is CardIdentifier.Riftbound -> TcgGame.RIFTBOUND
    }

/**
 * Findet Kennungen in rohen OCR-Zeilen. Rueckgabe ist nach Verlaesslichkeit
 * sortiert (eindeutige Muster zuerst) — der Aufrufer probiert sie der Reihe
 * nach, bis ein Lookup trifft.
 *
 * Gegen OCR-Verwechsler (O↔0, I↔1, S↔5 …) laeuft die Erkennung zusaetzlich
 * ueber eine "reparierte" Variante jeder Zeile, in der eindeutig falsch
 * gelesene Zeichen anhand ihrer Nachbarn korrigiert werden.
 */
object CardIdentifierDetector {

    /** One Piece + Dragon Ball Fusion World teilen das Muster "XX01-025". */
    private val onePieceRegex =
        Regex("""\b(OP|ST|EB|PRB)\s?(\d{2})\s?-\s?(\d{3})\b""", RegexOption.IGNORE_CASE)

    private val dragonBallRegex =
        Regex("""\b(FB|FS|FP|SB)\s?(\d{2})\s?-\s?(\d{2,3})\s*([★☆*]{0,2})""", RegexOption.IGNORE_CASE)

    private val ygoPasscodeRegex = Regex("""(?<!\d)(\d{8})(?!\d)""")

    // "LOB-EN001" / "SDY-006" / "MP19-EN123" — YGO-Auflagen-Code.
    // One-Piece-/Dragon-Ball-IDs sehen aehnlich aus und werden ausgeschlossen.
    private val ygoSetRegex = Regex("""\b([A-Z][A-Z0-9]{1,4})\s?-\s?([A-Z]{0,2}\d{3})\b""")
    private val ygoExcludedPrefix = Regex("""(OP|ST|EB|PRB|FB|FS|FP|SB)\d{2}""")

    // "MID • EN" / "BLB·DE" / "MID EN" — Set-Code gefolgt von Sprachkuerzel.
    // Der Trennpunkt ist optional: OCR liest ihn oft als Leerzeichen oder gar nicht.
    private val magicSetRegex = Regex(
        """\b([A-Z][A-Z0-9]{2,5})\s*[•·∙.*-]?\s*(EN|DE|FR|IT|ES|PT|JA|JP|KO|RU|ZH|PH)\b"""
    )

    // "207/204 • EN • 1" — Lorcana: Nummer/Total, Sprache, Setnummer
    private val lorcanaRegex =
        Regex(
            """\b(\d{1,3}[A-Z]?)\s*/\s*\d{1,3}\s*[•·∙.-]?\s*[A-Z]{2}\s*[•·∙.-]?\s*([A-Z0-9]{1,8})\b""",
            RegexOption.IGNORE_CASE
        )

    // Star Wars: Unlimited — bekannte Set-Codes (Whitelist gegen Fehltreffer)
    private val threeLetterSetRegex = Regex("""\b([A-Z]{3})\b""")
    private val knownSwuSetRegex = Regex(
        """\b(SOR|SHD|TWI|JTL|LOF|SEC|LAW|IBH|ASH)\b""",
        RegexOption.IGNORE_CASE
    )

    // Riftbound prints set + number/total; a suffix (e.g. 202a) selects an art variant.
    private val riftboundRegex = Regex(
        """\b([A-Z]{3})\s*[•·∙.-]?\s*0*(\d{1,3})([A-Z]?)\s*/\s*0*(\d{2,3})\b""",
        RegexOption.IGNORE_CASE
    )

    // "219/191" bzw. modern "0123/0281" — Sammlernummer/Setgroesse
    private val slashNumberRegex = Regex("""\b(\d{1,4}[A-Z]?)\s*/\s*(\d{2,4})\b""", RegexOption.IGNORE_CASE)

    // Eigenstaendige Sammlernummer-Zeile, z. B. "0123" oder "123 R"
    private val bareNumberRegex = Regex("""^\s*0*(\d{1,4}[A-Z]?)\s*[CURMSL]?\s*$""", RegexOption.IGNORE_CASE)

    /**
     * @param lines alle OCR-Zeilen der Karte (fuer strukturell eindeutige
     *   Muster wie "OP01-025" oder den Yu-Gi-Oh!-Passcode).
     * @param numberZone nur die Zeilen der unteren Kartenkante, von oben nach
     *   unten sortiert. Nummern-Muster (Sammlernummer, Slash-Paar, Set-Code)
     *   zaehlen ausschliesslich hier — sonst werden Manakosten oben rechts,
     *   Kampfwerte oder Loyalitaet als Sammlernummer fehlgedeutet
     *   (z. B. "LTC · 4" statt "LTC · 329" bei einer 4er-Manakosten-Karte).
     */
    fun detect(
        lines: List<String>,
        numberZone: List<String> = lines,
        gameHint: TcgGame? = null
    ): List<CardIdentifier> {
        // Rohe + reparierte Zeilen durchsuchen (Reihenfolge bleibt raeumlich).
        val allLines = withRepairs(lines)
        val zoneLines = if (numberZone === lines) allLines else withRepairs(numberZone)

        val strong = LinkedHashSet<CardIdentifier>()
        var magicSet: String? = null
        var footerLanguage: String? = null
        var swuSet: String? = null
        var collectorNumber: String? = null
        var slashMatch: Pair<String, String>? = null

        // Strukturell eindeutige Muster duerfen ueberall auf der Karte stehen
        // (der YGO-Set-Code sitzt z. B. mittig unter dem Artwork).
        for (line in allLines) {
            onePieceRegex.find(line)?.let { m ->
                strong += CardIdentifier.OnePiece(
                    "${m.groupValues[1].uppercase()}${m.groupValues[2]}-${m.groupValues[3]}"
                )
            }
            dragonBallRegex.find(line)?.let { m ->
                strong += CardIdentifier.DragonBall(
                    "${m.groupValues[1].uppercase()}${m.groupValues[2]}-${m.groupValues[3].padStart(3, '0')}",
                    parallelMarks = m.groupValues[4].length
                )
            }
            ygoPasscodeRegex.find(line)?.let { m ->
                strong += CardIdentifier.YugiohPasscode(m.groupValues[1])
            }
            ygoSetRegex.find(line)?.let { m ->
                val prefix = m.groupValues[1].uppercase()
                if (!prefix.matches(ygoExcludedPrefix)) {
                    strong += CardIdentifier.YugiohSet(
                        "$prefix-${m.groupValues[2].uppercase()}"
                    )
                }
            }
        }

        // Positionsgebundene Muster: nur untere Kartenkante. Bei Slash-Paar
        // und Sammlernummer gewinnt der UNTERSTE Treffer — Kampfwerte und
        // Loyalitaet stehen oberhalb der gedruckten Sammlernummer.
        for (line in zoneLines) {
            if (gameHint == TcgGame.RIFTBOUND) {
                riftboundRegex.find(line)?.let { m ->
                    strong += CardIdentifier.Riftbound(
                        setCode = m.groupValues[1].uppercase(),
                        number = m.groupValues[2],
                        printedTotal = m.groupValues[4],
                        variant = m.groupValues[3].lowercase().ifEmpty { null }
                    )
                }
            }
            lorcanaRegex.find(line)?.let { m ->
                strong += CardIdentifier.Lorcana(
                    setNumber = m.groupValues[2],
                    number = m.groupValues[1].trimStart('0').ifEmpty { "0" }
                )
            }
            magicSetRegex.find(line)?.let { m ->
                val code = m.groupValues[1]
                if (!code.all(Char::isDigit)) {
                    magicSet = code
                    footerLanguage = m.groupValues[2]
                }
            }
            if (gameHint == TcgGame.STARWARS) {
                threeLetterSetRegex.find(line)?.let { m -> swuSet = m.groupValues[1] }
            } else if (gameHint == null) {
                knownSwuSetRegex.find(line)?.let { m -> swuSet = m.groupValues[1].uppercase() }
            }
            slashNumberRegex.find(line)?.let { m ->
                slashMatch = m.groupValues[1] to m.groupValues[2]
            }
            bareNumberRegex.find(line)?.let { m ->
                collectorNumber = m.groupValues[1]
            }
        }

        val found = LinkedHashSet<CardIdentifier>(strong)

        // Kampfwerte ("3/3") vs. Sammlernummer ("123/281"): ein Slash-Paar
        // zaehlt nur mit plausibler Setgroesse (>= 20) als Sammlernummer.
        val slashCollector = slashMatch?.takeIf { (it.second.toIntOrNull() ?: 0) >= 20 }
        val slashNumber = slashCollector?.first?.trimStart('0')?.ifEmpty { "0" }

        // Magic braucht Set-Code UND Nummer (Nummer aus Slash-Paar oder eigener Zeile)
        magicSet?.takeIf { gameHint == null || gameHint == TcgGame.MAGIC }?.let { set ->
            val number = slashNumber ?: collectorNumber
            if (number != null) found += CardIdentifier.Magic(set, number, footerLanguage)
        }

        // Star Wars: Unlimited — Set-Code (Whitelist) + Nummer
        swuSet?.let { set ->
            val number = slashNumber ?: collectorNumber
            if (number != null) found += CardIdentifier.StarWars(set, number)
        }

        // Nummer/Total ohne Set-Code → Pokémon-Kandidat (schwaechstes Signal).
        slashCollector?.let { (num, total) ->
            if ((magicSet == null || gameHint == TcgGame.POKEMON) && swuSet == null &&
                gameHint != TcgGame.MAGIC && gameHint != TcgGame.LORCANA &&
                gameHint != TcgGame.RIFTBOUND
            ) {
                found += CardIdentifier.Pokemon(
                    number = num.trimStart('0').ifEmpty { "0" },
                    printedTotal = total.trimStart('0').ifEmpty { "0" },
                    setCode = magicSet?.takeIf { gameHint == TcgGame.POKEMON },
                    language = footerLanguage?.takeIf { gameHint == TcgGame.POKEMON }
                )
            }
        }

        return found.toList()
    }

    /** Reparierte Variante jeder Zeile direkt hinter dem Original einreihen. */
    private fun withRepairs(lines: List<String>): List<String> =
        lines.flatMap { line ->
            val repaired = repairLine(line)
            if (repaired != line) listOf(line, repaired) else listOf(line)
        }

    // ---- OCR-Verwechsler-Reparatur ------------------------------------------

    private val toDigit = mapOf('O' to '0', 'o' to '0', 'I' to '1', 'l' to '1', 'i' to '1', 'S' to '5', 'B' to '8', 'Z' to '2')
    private val toLetter = mapOf('0' to 'O', '1' to 'I', '5' to 'S', '8' to 'B', '2' to 'Z', '6' to 'G')

    /**
     * Korrigiert Zeichen nur, wenn die Nachbarschaft eindeutig ist:
     * ein Buchstabe zwischen Ziffern wird zur Ziffer ("46O0" → "4600"),
     * eine Ziffer zwischen Buchstaben zum Buchstaben ("M1D" → "MID").
     * Gemischte Nachbarschaft ("FB01") bleibt unangetastet.
     */
    private fun repairLine(line: String): String {
        val chars = line.toCharArray()
        for (i in chars.indices) {
            val c = chars[i]
            val prev = chars.getOrNull(i - 1)
            val next = chars.getOrNull(i + 1)
            val nearDigit = prev?.isDigit() == true || next?.isDigit() == true
            val nearLetter = prev?.isLetter() == true || next?.isLetter() == true
            chars[i] = when {
                nearDigit && !nearLetter -> toDigit[c] ?: c
                nearLetter && !nearDigit -> toLetter[c.takeIf { it.isDigit() } ?: ' '] ?: c
                else -> c
            }
        }
        return String(chars)
    }
}
