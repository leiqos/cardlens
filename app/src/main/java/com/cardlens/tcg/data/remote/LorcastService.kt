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

/** Disney Lorcana — https://lorcast.com/docs/api (kein API-Key noetig) */
interface LorcastService {
    @GET("cards/search")
    suspend fun search(@Query("q") query: String): LorcastList

    /** Exakte Karte ueber Setnummer + Kartennummer, z. B. 1/207. */
    @GET("cards/{setCode}/{number}")
    suspend fun byNumber(
        @Path("setCode") setCode: String,
        @Path("number") number: String
    ): LorcastCard

    companion object {
        const val BASE_URL = "https://api.lorcast.com/v0/"
    }
}

@Serializable
data class LorcastList(val results: List<LorcastCard> = emptyList())

@Serializable
data class LorcastSet(val name: String? = null, val code: String? = null)

@Serializable
data class LorcastDigitalImages(
    val small: String? = null,
    val normal: String? = null,
    val large: String? = null
)

@Serializable
data class LorcastImageUris(val digital: LorcastDigitalImages? = null)

@Serializable
data class LorcastPrices(
    val usd: String? = null,
    @SerialName("usd_foil") val usdFoil: String? = null
)

@Serializable
data class LorcastCard(
    val id: String,
    val name: String,
    val version: String? = null,
    val ink: String? = null,
    val cost: Int? = null,
    val lore: Int? = null,
    val strength: Int? = null,
    val willpower: Int? = null,
    val rarity: String? = null,
    val text: String? = null,
    @SerialName("collector_number") val collectorNumber: String? = null,
    val illustrators: List<String>? = null,
    val set: LorcastSet? = null,
    @SerialName("image_uris") val imageUris: LorcastImageUris? = null,
    val prices: LorcastPrices? = null
)

fun LorcastCard.toTcgCard(): TcgCard {
    val images = imageUris?.digital
    val tcgplayerUrl = "https://www.tcgplayer.com/search/lorcana/product?q=" +
        URLEncoder.encode(name, "UTF-8")
    val priceList = buildList {
        prices?.usd?.toDoubleOrNull()?.let { add(PriceEntry("TCGplayer", "Normal", it, "USD", tcgplayerUrl)) }
        prices?.usdFoil?.toDoubleOrNull()?.let { add(PriceEntry("TCGplayer", "Foil", it, "USD", tcgplayerUrl)) }
    }
    val facts = buildList {
        ink?.let { add(CardFact("Tinte", it)) }
        cost?.let { add(CardFact("Kosten", it.toString())) }
        lore?.let { add(CardFact("Lore", it.toString())) }
        strength?.let { add(CardFact("Staerke", it.toString())) }
        willpower?.let { add(CardFact("Willenskraft", it.toString())) }
        rarity?.let { add(CardFact("Seltenheit", it)) }
        illustrators?.takeIf { it.isNotEmpty() }?.let { add(CardFact("Illustration", it.joinToString(", "))) }
    }
    return TcgCard(
        id = "LORCANA:$id",
        game = TcgGame.LORCANA,
        name = if (version != null) "$name – $version" else name,
        subtitle = listOfNotNull(ink, rarity).joinToString(" · ").takeIf { it.isNotBlank() },
        setName = listOfNotNull(set?.name, collectorNumber).joinToString(" · ").takeIf { it.isNotBlank() },
        collectorNumber = collectorNumber,
        rarity = rarity,
        imageSmall = images?.small ?: images?.normal,
        imageLarge = images?.large ?: images?.normal,
        text = text,
        facts = facts,
        prices = priceList
    )
}
