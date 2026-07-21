package com.cardlens.tcg.data.remote

import com.cardlens.tcg.model.CardFact
import com.cardlens.tcg.model.PriceEntry
import com.cardlens.tcg.model.TcgCard
import com.cardlens.tcg.model.TcgGame
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/** Pokémon TCG — https://pokemontcg.io (API-Key optional, erhoeht Rate-Limit) */
interface PokemonService {
    @GET("cards")
    suspend fun search(
        @Query("q") query: String,
        @Query("pageSize") pageSize: Int = 24,
        @Query("orderBy") orderBy: String = "-set.releaseDate"
    ): PokemonList

    companion object {
        const val BASE_URL = "https://api.pokemontcg.io/v2/"
        /** Baut die Query fuer eine Namenssuche, z. B. name:"Glurak*" */
        fun nameQuery(name: String) = "name:\"${name.replace("\"", "")}*\""
    }
}

@Serializable
data class PokemonList(val data: List<PokemonCard> = emptyList())

@Serializable
data class PokemonSet(
    val id: String? = null,
    val name: String? = null,
    val series: String? = null,
    val releaseDate: String? = null,
    val printedTotal: Int? = null,
    val ptcgoCode: String? = null
)

@Serializable
data class PokemonImages(val small: String? = null, val large: String? = null)

@Serializable
data class TcgplayerVariant(
    val low: Double? = null,
    val mid: Double? = null,
    val high: Double? = null,
    val market: Double? = null,
    val directLow: Double? = null
)

@Serializable
data class Tcgplayer(
    val url: String? = null,
    val prices: Map<String, TcgplayerVariant>? = null
)

@Serializable
data class CardmarketPrices(
    val averageSellPrice: Double? = null,
    val trendPrice: Double? = null,
    val lowPrice: Double? = null,
    val reverseHoloTrend: Double? = null
)

@Serializable
data class Cardmarket(val url: String? = null, val prices: CardmarketPrices? = null)

@Serializable
data class PokemonCard(
    val id: String,
    val name: String,
    val supertype: String? = null,
    val subtypes: List<String>? = null,
    val hp: String? = null,
    val types: List<String>? = null,
    val number: String? = null,
    val rarity: String? = null,
    val artist: String? = null,
    val flavorText: String? = null,
    val set: PokemonSet? = null,
    val images: PokemonImages? = null,
    val tcgplayer: Tcgplayer? = null,
    val cardmarket: Cardmarket? = null
)

private val variantLabels = mapOf(
    "normal" to "Normal",
    "holofoil" to "Holofoil",
    "reverseHolofoil" to "Reverse Holo",
    "1stEditionHolofoil" to "1. Edition Holo",
    "1stEditionNormal" to "1. Edition",
    "unlimitedHolofoil" to "Unlimited Holo"
)

fun PokemonCard.toTcgCard(): TcgCard {
    val priceList = buildList {
        cardmarket?.prices?.trendPrice?.let {
            add(PriceEntry("Cardmarket", "Trend", it, "EUR", cardmarket.url))
        }
        cardmarket?.prices?.averageSellPrice?.let {
            add(PriceEntry("Cardmarket", "Ø Verkauf (30 Tage)", it, "EUR", cardmarket.url))
        }
        tcgplayer?.prices?.forEach { (variant, p) ->
            p.market?.let {
                add(PriceEntry("TCGplayer", variantLabels[variant] ?: variant, it, "USD", tcgplayer.url))
            }
        }
    }
    val subtitleParts = listOfNotNull(supertype, subtypes?.joinToString(" · "))
    val facts = buildList {
        hp?.let { add(CardFact("KP", it)) }
        types?.takeIf { it.isNotEmpty() }?.let { add(CardFact("Typ", it.joinToString(", "))) }
        rarity?.let { add(CardFact("Seltenheit", it)) }
        set?.releaseDate?.let { add(CardFact("Erschienen", it)) }
        artist?.let { add(CardFact("Illustration", it)) }
    }
    return TcgCard(
        id = "POKEMON:$id",
        game = TcgGame.POKEMON,
        name = name,
        subtitle = subtitleParts.joinToString(" – ").takeIf { it.isNotBlank() },
        setName = listOfNotNull(set?.name, number?.let { n ->
            set?.printedTotal?.let { "$n/$it" } ?: n
        }).joinToString(" · ").takeIf { it.isNotBlank() },
        setCode = set?.ptcgoCode ?: set?.id,
        collectorNumber = number,
        rarity = rarity,
        artist = artist,
        imageSmall = images?.small ?: images?.large,
        imageLarge = images?.large ?: images?.small,
        text = flavorText,
        facts = facts,
        prices = priceList,
        externalUrl = cardmarket?.url ?: tcgplayer?.url
    )
}
