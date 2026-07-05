package com.cardlens.tcg.model

/** Kartenzustand nach gaengiger Marktskala (Cardmarket/TCGplayer). */
enum class CardCondition(val code: String, val label: String) {
    MINT("M", "Mint"),
    NEAR_MINT("NM", "Near Mint"),
    EXCELLENT("EX", "Excellent"),
    GOOD("GD", "Good"),
    LIGHT_PLAYED("LP", "Light Played"),
    PLAYED("PL", "Played"),
    POOR("PO", "Poor");

    companion object {
        fun fromCode(code: String?): CardCondition =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(code, ignoreCase = true) }
                ?: NEAR_MINT
    }
}

/** Kartensprache (Druck), kompatibel mit den ueblichen CSV-Kuerzeln. */
enum class CardLanguage(val code: String, val label: String) {
    EN("en", "Englisch"),
    DE("de", "Deutsch"),
    FR("fr", "Französisch"),
    IT("it", "Italienisch"),
    ES("es", "Spanisch"),
    PT("pt", "Portugiesisch"),
    JA("ja", "Japanisch"),
    KO("ko", "Koreanisch"),
    RU("ru", "Russisch"),
    ZH("zh", "Chinesisch");

    companion object {
        fun fromCode(code: String?): CardLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: EN
    }
}

/** Deck-Bereich. */
enum class DeckBoard(val label: String) {
    MAIN("Hauptdeck"),
    SIDE("Sideboard"),
    COMMANDER("Commander")
}

/** Formate je Spiel; Legalitaets-Key entspricht dem Scryfall-Feld. */
enum class DeckFormat(val game: TcgGame, val label: String, val legalityKey: String?) {
    // Magic
    STANDARD(TcgGame.MAGIC, "Standard", "standard"),
    PIONEER(TcgGame.MAGIC, "Pioneer", "pioneer"),
    MODERN(TcgGame.MAGIC, "Modern", "modern"),
    LEGACY(TcgGame.MAGIC, "Legacy", "legacy"),
    VINTAGE(TcgGame.MAGIC, "Vintage", "vintage"),
    COMMANDER(TcgGame.MAGIC, "Commander / EDH", "commander"),
    PAUPER(TcgGame.MAGIC, "Pauper", "pauper"),
    LIMITED(TcgGame.MAGIC, "Limited / Draft", null),
    // Andere Spiele
    POKEMON_STANDARD(TcgGame.POKEMON, "Standard", null),
    POKEMON_EXPANDED(TcgGame.POKEMON, "Expanded", null),
    YGO_ADVANCED(TcgGame.YUGIOH, "Advanced", null),
    ONEPIECE_STANDARD(TcgGame.ONEPIECE, "Standard", null),
    LORCANA_CORE(TcgGame.LORCANA, "Core Constructed", null),
    CASUAL(TcgGame.MAGIC, "Casual / Frei", null);

    companion object {
        fun forGame(game: TcgGame): List<DeckFormat> =
            entries.filter { it.game == game } + CASUAL

        fun fromName(name: String?): DeckFormat =
            entries.firstOrNull { it.name == name } ?: CASUAL
    }
}
