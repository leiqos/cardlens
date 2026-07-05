package com.cardlens.tcg.data.remote

import com.cardlens.tcg.model.CardFact
import com.cardlens.tcg.model.PriceEntry
import com.cardlens.tcg.model.TcgCard
import com.cardlens.tcg.model.TcgGame
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.net.URLEncoder

/**
 * Star Wars: Unlimited — https://www.swu-db.com/api (kein API-Key noetig).
 * Felder sind in PascalCase; Preise kommen als Strings in USD.
 */
interface SwuService {
    @GET("cards/search")
    suspend fun search(@Query("q") query: String): SwuList

    /** Exakte Karte ueber Set-Code + Nummer, z. B. TWI/255. */
    @GET("cards/{set}/{number}")
    suspend fun byNumber(
        @Path("set") set: String,
        @Path("number") number: String
    ): SwuCard

    companion object {
        const val BASE_URL = "https://api.swu-db.com/"
    }
}

@Serializable
data class SwuList(val data: List<SwuCard> = emptyList())

@Serializable
data class SwuCard(
    @SerialName("Set") val set: String? = null,
    @SerialName("Number") val number: String? = null,
    @SerialName("Name") val name: String,
    @SerialName("Subtitle") val subtitle: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("Aspects") val aspects: List<String>? = null,
    @SerialName("Traits") val traits: List<String>? = null,
    @SerialName("Arenas") val arenas: List<String>? = null,
    @SerialName("Cost") val cost: String? = null,
    @SerialName("Power") val power: String? = null,
    @SerialName("HP") val hp: String? = null,
    @SerialName("Rarity") val rarity: String? = null,
    @SerialName("FrontText") val frontText: String? = null,
    @SerialName("FrontArt") val frontArt: String? = null,
    @SerialName("MarketPrice") val marketPrice: String? = null,
    @SerialName("FoilPrice") val foilPrice: String? = null,
    @SerialName("VariantType") val variantType: String? = null,
    @SerialName("Artist") val artist: String? = null
)

fun SwuCard.toTcgCard(): TcgCard {
    val fullName = if (!subtitle.isNullOrBlank()) "$name – $subtitle" else name
    val tcgplayerUrl = "https://www.tcgplayer.com/search/star-wars-unlimited/product?q=" +
        URLEncoder.encode(name, "UTF-8")
    val priceList = buildList {
        marketPrice?.toDoubleOrNull()?.takeIf { it > 0 }
            ?.let { add(PriceEntry("TCGplayer", "Normal", it, "USD", tcgplayerUrl)) }
        foilPrice?.toDoubleOrNull()?.takeIf { it > 0 }
            ?.let { add(PriceEntry("TCGplayer", "Foil", it, "USD", tcgplayerUrl)) }
    }
    val facts = buildList {
        aspects?.takeIf { it.isNotEmpty() }?.let { add(CardFact("Aspekte", it.joinToString(", "))) }
        cost?.takeIf { it.isNotBlank() }?.let { add(CardFact("Kosten", it)) }
        power?.takeIf { it.isNotBlank() }?.let { add(CardFact("Power", it)) }
        hp?.takeIf { it.isNotBlank() }?.let { add(CardFact("HP", it)) }
        traits?.takeIf { it.isNotEmpty() }?.let { add(CardFact("Merkmale", it.joinToString(", "))) }
        arenas?.takeIf { it.isNotEmpty() }?.let { add(CardFact("Arena", it.joinToString(", "))) }
        rarity?.let { add(CardFact("Seltenheit", it)) }
        artist?.let { add(CardFact("Illustration", it)) }
    }
    val id = listOfNotNull(set, number).joinToString("-").ifEmpty { name }
    return TcgCard(
        id = "STARWARS:$id",
        game = TcgGame.STARWARS,
        name = fullName,
        subtitle = listOfNotNull(type, aspects?.joinToString("/")).joinToString(" · ").takeIf { it.isNotBlank() },
        setName = listOfNotNull(set, number).joinToString(" · ").takeIf { it.isNotBlank() },
        setCode = set,
        collectorNumber = number,
        rarity = rarity,
        artist = artist,
        imageSmall = frontArt,
        imageLarge = frontArt,
        text = frontText,
        facts = facts,
        prices = priceList,
        typeLine = type,
        colors = aspects ?: emptyList()
    )
}
