package com.cardlens.tcg.model

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
enum class TcgGame(val label: String, val shortLabel: String) {
    MAGIC("Magic: The Gathering", "Magic"),
    POKEMON("Pokémon TCG", "Pokémon"),
    YUGIOH("Yu-Gi-Oh!", "Yu-Gi-Oh!"),
    ONEPIECE("One Piece Card Game", "One Piece"),
    LORCANA("Disney Lorcana", "Lorcana"),
    STARWARS("Star Wars: Unlimited", "Star Wars"),
    DRAGONBALL("Dragon Ball Fusion World", "Dragon Ball"),
    RIFTBOUND("Riftbound (LoL)", "Riftbound")
}

@Serializable
data class PriceEntry(
    val source: String,          // z. B. "Cardmarket", "TCGplayer", "eBay"
    val label: String,           // z. B. "Trend", "Foil", "Holofoil"
    val amount: Double,
    val currency: String,        // "EUR" oder "USD"
    val url: String? = null
)

@Serializable
data class CardFact(val label: String, val value: String)

/**
 * Einheitliches Kartenmodell ueber alle unterstuetzten TCGs.
 * `id` ist global eindeutig: "<GAME>:<api-id>".
 *
 * Alle neuen Felder haben Defaults, damit aeltere in der DB gespeicherte
 * JSON-Snapshots weiterhin deserialisierbar bleiben.
 */
@Serializable
data class TcgCard(
    val id: String,
    val game: TcgGame,
    val name: String,
    val subtitle: String? = null,       // Typzeile / Kartentyp
    val setName: String? = null,
    val setCode: String? = null,        // z. B. "mid" (Scryfall), "swsh9" …
    val collectorNumber: String? = null,
    val rarity: String? = null,
    val artist: String? = null,
    val imageSmall: String? = null,
    val imageLarge: String? = null,
    val text: String? = null,           // Regeltext / Beschreibung
    val facts: List<CardFact> = emptyList(),
    val prices: List<PriceEntry> = emptyList(),
    val externalUrl: String? = null,
    // --- Deckbau / Statistik ---
    val manaCost: String? = null,       // "{2}{U}{U}" (Magic) / Ink-Kosten etc.
    val manaValue: Double? = null,      // konvertierte Kosten (CMC / Ink / Level)
    val colors: List<String> = emptyList(),        // W/U/B/R/G bzw. Ink/Typ-Farbe
    val colorIdentity: List<String> = emptyList(), // Commander-Farbidentitaet
    val typeLine: String? = null,       // volle Typzeile fuer Kartentyp-Statistik
    val legalities: Map<String, String> = emptyMap(), // format -> legal/banned/…
    val releasedAt: String? = null,
    val oracleId: String? = null        // gruppiert alle Drucke derselben Karte
) {
    /** Nur der API-Teil der globalen ID, z. B. die Scryfall-UUID. */
    val apiId: String get() = id.substringAfter(':')

    /** Grober Kartentyp aus der Typzeile (fuer Deck-Statistiken). */
    val primaryType: String
        get() {
            val line = (typeLine ?: subtitle ?: "").substringBefore("—").substringBefore("//")
            val known = listOf(
                "Creature", "Kreatur", "Instant", "Sorcery", "Enchantment",
                "Artifact", "Planeswalker", "Battle", "Land",
                "Pokémon", "Trainer", "Energy",
                "Monster", "Spell", "Trap",
                "Character", "Leader", "Event", "Stage",
                "Action", "Item", "Song", "Location",
                // Star Wars: Unlimited / Riftbound / Dragon Ball
                "Unit", "Base", "Upgrade", "Extra", "Rune", "Legend", "Gear", "Spell"
            )
            return known.firstOrNull { line.contains(it, ignoreCase = true) }
                ?: line.trim().split(' ').lastOrNull().orEmpty().ifEmpty { "Sonstige" }
        }
}

fun TcgCard.primaryPrice(preferredCurrency: String): PriceEntry? =
    prices.firstOrNull { it.currency == preferredCurrency }
        ?: prices.firstOrNull()

/** Preis passend zur Variante (Foil bevorzugt Foil-Eintraege). */
fun TcgCard.variantPrice(preferredCurrency: String, foil: Boolean): PriceEntry? {
    val foilish = prices.filter {
        it.label.contains("foil", ignoreCase = true) || it.label.contains("holo", ignoreCase = true)
    }
    val normal = prices - foilish.toSet()
    val pool = if (foil && foilish.isNotEmpty()) foilish else normal.ifEmpty { prices }
    return pool.firstOrNull { it.currency == preferredCurrency } ?: pool.firstOrNull()
}

fun formatPrice(amount: Double, currency: String): String = when (currency) {
    "EUR" -> String.format(Locale.GERMANY, "%,.2f €", amount)
    "USD" -> String.format(Locale.US, "$%,.2f", amount)
    else -> String.format(Locale.GERMANY, "%,.2f %s", amount, currency)
}
