package com.cardlens.tcg.model

/**
 * Bestimmt das Kartenspiel aus den OCR-Zeilen eines Kamerabilds — BEVOR
 * gesucht wird. Jede Karte traegt eindeutige gedruckte Merkmale:
 *
 *  - **Magic**: Typzeile ("Creature/Kreatur — …"), Set-Code + Sprache
 *    ("MID • EN"), Kampfwerte "3/3" unten rechts, "Wizards of the Coast".
 *  - **Pokémon**: "120 KP/HP" oben, "Schwäche/Weakness", "Rückzug/Retreat",
 *    "Entwickelt sich aus …", ©Pokémon.
 *  - **Yu-Gi-Oh!**: "ATK/2000 DEF/1500", Set-Code "LOB-DE001", 8-stelliger
 *    Passcode, KONAMI.
 *  - **One Piece**: Karten-ID "OP01-025", "DON!!", COUNTER, BANDAI.
 *  - **Lorcana**: ©Disney, Karten-ID "207/204 • EN • 1", "Lorcana".
 *  - **Star Wars: Unlimited**: Set-Codes SOR/SHD/…, "STAR WARS", Lucasfilm.
 *  - **Dragon Ball**: Karten-ID "FB01-001", BIRD STUDIO/SHUEISHA.
 *  - **Riftbound**: "RIFTBOUND", Riot Games.
 *
 * Das Ergebnis begrenzt die Suche auf das richtige Spiel und blockiert
 * mehrdeutige Kennungen (das nackte "123/281" steht auch auf Magic-Karten).
 */
object GameClassifier {

    /**
     * Calibrated result used by the scanner.  Keeping the runner-up margin is
     * important: OCR often finds generic words such as "Leader" or "Stage"
     * on several games, and a high raw score is not trustworthy when another
     * game is almost as plausible.
     */
    data class Classification(
        val game: TcgGame?,
        val bestScore: Int,
        val margin: Int,
        val scores: Map<TcgGame, Int>
    ) {
        val isConfident: Boolean get() = game != null
    }

    private data class Rule(val regex: Regex, val points: Int)

    private fun rules(vararg pairs: Pair<String, Int>): List<Rule> =
        pairs.map { (p, pts) -> Rule(Regex(p, RegexOption.IGNORE_CASE), pts) }

    private val evidence: Map<TcgGame, List<Rule>> = mapOf(
        TcgGame.MAGIC to rules(
            """\b(creature|kreatur|instant|spontanzauber|sorcery|hexerei|enchantment|verzauberung|artifact|artefakt|planeswalker|battle//?|legendary|legendär|legendaere?)\b""" to 2,
            """wizards of the coast|\bWotC\b""" to 3,
            // Modern footer: collector number, rarity, set code and language.
            """\b[A-Z][A-Z0-9]{2,4}\s*[•·∙.*-]?\s*(EN|DE|FR|IT|ES|PT|JA|JP|KO|RU|ZH|PH)\b""" to 2,
            """\b(illustrated by|illus\.)\b""" to 1,
            """\bdeine? bibliothek|your library|kampfschaden|combat damage\b""" to 2,
            """\b(flying|vigilance|trample|deathtouch|lifelink|haste|reach|menace|ward|hexproof)\b""" to 2
        ),
        TcgGame.POKEMON to rules(
            """\b\d{1,3}\s?(KP|HP)\b|\b(KP|HP)\s?\d{1,3}\b""" to 4,
            """\b(schwäche|schwaeche|weakness|resistenz|resistance|rückzug|rueckzug|retreat)\b""" to 2,
            """entwickelt sich aus|evolves from""" to 3,
            """©\s?20\d\d\s?(pokémon|pokemon)|\bthe pokémon company\b""" to 3,
            """\b(basic|basis|stage|phase)\s?[123]?\b""" to 1
        ),
        TcgGame.YUGIOH to rules(
            """\b(ATK|DEF)\s*[/：:]?\s*\d{1,4}\b""" to 3,
            """\bKONAMI\b""" to 3,
            """\b(effekt|effect)\s*[/]?\s*(monster)\b|\[\s*(monster|zauber|falle|spell|trap)""" to 2,
            """©\s*studio dice|1st edition|limited edition""" to 2,
            """\b[A-Z][A-Z0-9]{2,4}\s?-\s?(DE|EN|FR|IT|SP|PT|JP)\d{3}\b""" to 3
        ),
        TcgGame.ONEPIECE to rules(
            """\b(OP|ST|EB|PRB)\d{2}\s?-\s?\d{3}\b""" to 4,
            """DON!!?""" to 3,
            """\b(counter|blocker|leader|trigger)\b""" to 1,
            """ONE PIECE""" to 3,
            """©\s*eiichiro oda|carddass""" to 3
        ),
        TcgGame.LORCANA to rules(
            """\blorcana\b""" to 4,
            """©\s?disney|\bdisney\b""" to 3,
            """\b\d{1,3}\s*/\s*\d{1,3}\s*[•·∙.-]\s*[A-Z]{2}\s*[•·∙.-]\s*\d{1,2}\b""" to 3,
            """\b(inkwell|ravensburger)\b""" to 3
        ),
        TcgGame.STARWARS to rules(
            """\b(SOR|SHD|TWI|JTL|LOF|SEC|LAW|IBH|ASH)\b""" to 3,
            """STAR WARS|lucasfilm|\bLFL\b""" to 3,
            """\bunlimited\b""" to 1,
            """fantasy flight games|\bFFG\b""" to 2
        ),
        TcgGame.DRAGONBALL to rules(
            """\b(FB|FS|FP|SB)\d{2}\s?-\s?\d{2,3}\b""" to 4,
            """DRAGON BALL|bird studio|shueisha""" to 3,
            """\bfusion world\b""" to 3
        ),
        TcgGame.RIFTBOUND to rules(
            """\briftbound\b""" to 4,
            """riot games""" to 3,
            """\b(might|energy)\b.*\b(unit|legend|gear|battlefield)\b""" to 2
        )
    )

    /** Kampfwerte "3/3" (beide klein) sind Magic-Indiz, keine Sammlernummer. */
    private val smallSlash = Regex("""\b(\d{1,2})\s*/\s*(\d{1,2})\b""")

    /**
     * Liefert das wahrscheinlichste Spiel oder null, wenn die Evidenz nicht
     * reicht bzw. zwei Spiele gleichauf liegen.
     */
    fun classifyDetailed(lines: List<String>): Classification {
        if (lines.isEmpty()) return Classification(null, 0, 0, emptyMap())
        val text = lines.joinToString("\n")
        val scores = evidence.mapValues { (_, ruleList) ->
            ruleList.sumOf { rule -> if (rule.regex.containsMatchIn(text)) rule.points else 0 }
        }.toMutableMap()

        // Kleine X/Y-Werte ohne Pokémon-Evidenz deuten auf Magic-Kampfwerte
        smallSlash.find(text)?.let { m ->
            val a = m.groupValues[1].toIntOrNull() ?: 99
            val b = m.groupValues[2].toIntOrNull() ?: 99
            if (a <= 20 && b <= 20 && (scores[TcgGame.POKEMON] ?: 0) == 0) {
                scores[TcgGame.MAGIC] = (scores[TcgGame.MAGIC] ?: 0) + 1
            }
        }

        val sorted = scores.entries.sortedWith(
            compareByDescending<Map.Entry<TcgGame, Int>> { it.value }.thenBy { it.key.ordinal }
        )
        val best = sorted.first()
        val second = sorted.getOrNull(1)?.value ?: 0
        val margin = best.value - second
        // Four points means either one structurally unique identifier or two
        // independent layout/text signals. A two-point lead prevents generic
        // vocabulary shared by multiple TCGs from forcing a game.
        val game = best.key.takeIf { best.value >= MIN_SCORE && margin >= MIN_MARGIN }
        return Classification(game, best.value, margin, scores.toMap())
    }

    fun classify(lines: List<String>): TcgGame? = classifyDetailed(lines).game

    private const val MIN_SCORE = 4
    private const val MIN_MARGIN = 2
}
