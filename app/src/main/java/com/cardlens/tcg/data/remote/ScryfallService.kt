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

/** Magic: The Gathering — https://scryfall.com/docs/api (kein API-Key noetig) */
interface ScryfallService {
    @GET("cards/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("order") order: String = "released",
        @Query("unique") unique: String = "cards"
    ): ScryfallList

    /** Exakter Druck ueber Set-Code + Sammlernummer, z. B. mid/123. */
    @GET("cards/{setCode}/{number}")
    suspend fun byCollector(
        @Path("setCode") setCode: String,
        @Path("number") number: String
    ): ScryfallCard

    /** Same exact printing in the language printed in the footer. */
    @GET("cards/{setCode}/{number}/{language}")
    suspend fun byCollectorLanguage(
        @Path("setCode") setCode: String,
        @Path("number") number: String,
        @Path("language") language: String
    ): ScryfallCard

    /** Einzelne Karte per Scryfall-UUID (CSV-Import). */
    @GET("cards/{id}")
    suspend fun byId(@Path("id") id: String): ScryfallCard

    /** Offizielle Rulings zu einer Karte. */
    @GET("cards/{id}/rulings")
    suspend fun rulings(@Path("id") id: String): ScryfallRulingList

    /** Namens-Autovervollstaendigung fuer die Suche. */
    @GET("cards/autocomplete")
    suspend fun autocomplete(@Query("q") query: String): ScryfallCatalog

    /** Exakter Kartenname → guenstigster/aktuellster Druck (Decklisten-Import). */
    @GET("cards/named")
    suspend fun byName(@Query("exact") name: String): ScryfallCard

    /** Alle bekannten Sets — dient dem Scanner als Set-Code-Whitelist. */
    @GET("sets")
    suspend fun sets(): ScryfallSetList

    companion object {
        const val BASE_URL = "https://api.scryfall.com/"
    }
}

@Serializable
data class ScryfallSetList(val data: List<ScryfallSetInfo> = emptyList())

@Serializable
data class ScryfallSetInfo(val code: String)

@Serializable
data class ScryfallList(val data: List<ScryfallCard> = emptyList())

@Serializable
data class ScryfallCatalog(val data: List<String> = emptyList())

@Serializable
data class ScryfallRuling(
    @SerialName("published_at") val publishedAt: String? = null,
    val comment: String = ""
)

@Serializable
data class ScryfallRulingList(val data: List<ScryfallRuling> = emptyList())

@Serializable
data class ScryfallImageUris(
    val small: String? = null,
    val normal: String? = null,
    val large: String? = null
)

@Serializable
data class ScryfallFace(
    val name: String? = null,
    @SerialName("type_line") val typeLine: String? = null,
    @SerialName("oracle_text") val oracleText: String? = null,
    @SerialName("mana_cost") val manaCost: String? = null,
    @SerialName("image_uris") val imageUris: ScryfallImageUris? = null
)

@Serializable
data class ScryfallPrices(
    val usd: String? = null,
    @SerialName("usd_foil") val usdFoil: String? = null,
    val eur: String? = null,
    @SerialName("eur_foil") val eurFoil: String? = null
)

@Serializable
data class ScryfallCard(
    val id: String,
    @SerialName("oracle_id") val oracleId: String? = null,
    val name: String,
    val lang: String? = null,
    @SerialName("set_name") val setName: String? = null,
    @SerialName("set") val setCode: String? = null,
    @SerialName("collector_number") val collectorNumber: String? = null,
    val rarity: String? = null,
    val artist: String? = null,
    @SerialName("type_line") val typeLine: String? = null,
    @SerialName("oracle_text") val oracleText: String? = null,
    @SerialName("mana_cost") val manaCost: String? = null,
    val cmc: Double? = null,
    val colors: List<String>? = null,
    @SerialName("color_identity") val colorIdentity: List<String>? = null,
    val legalities: Map<String, String>? = null,
    @SerialName("released_at") val releasedAt: String? = null,
    @SerialName("image_uris") val imageUris: ScryfallImageUris? = null,
    @SerialName("card_faces") val cardFaces: List<ScryfallFace>? = null,
    val prices: ScryfallPrices? = null,
    @SerialName("purchase_uris") val purchaseUris: Map<String, String>? = null,
    @SerialName("scryfall_uri") val scryfallUri: String? = null
)

fun ScryfallCard.toTcgCard(): TcgCard {
    val images = imageUris ?: cardFaces?.firstOrNull()?.imageUris
    val cardmarketUrl = purchaseUris?.get("cardmarket")
    val tcgplayerUrl = purchaseUris?.get("tcgplayer")
    val priceList = buildList {
        prices?.eur?.toDoubleOrNull()?.let { add(PriceEntry("Cardmarket", "Normal", it, "EUR", cardmarketUrl)) }
        prices?.eurFoil?.toDoubleOrNull()?.let { add(PriceEntry("Cardmarket", "Foil", it, "EUR", cardmarketUrl)) }
        prices?.usd?.toDoubleOrNull()?.let { add(PriceEntry("TCGplayer", "Normal", it, "USD", tcgplayerUrl)) }
        prices?.usdFoil?.toDoubleOrNull()?.let { add(PriceEntry("TCGplayer", "Foil", it, "USD", tcgplayerUrl)) }
    }
    val effectiveManaCost = manaCost?.takeIf { it.isNotBlank() }
        ?: cardFaces?.firstOrNull()?.manaCost?.takeIf { it.isNotBlank() }
    val facts = buildList {
        effectiveManaCost?.let { add(CardFact("Manakosten", it)) }
        rarity?.let { add(CardFact("Seltenheit", it.replaceFirstChar(Char::uppercase))) }
        releasedAt?.let { add(CardFact("Erschienen", it)) }
        artist?.let { add(CardFact("Illustration", it)) }
    }
    return TcgCard(
        id = "MAGIC:$id",
        game = TcgGame.MAGIC,
        name = name,
        subtitle = typeLine ?: cardFaces?.firstOrNull()?.typeLine,
        setName = setName,
        setCode = setCode,
        collectorNumber = collectorNumber,
        rarity = rarity,
        artist = artist,
        imageSmall = images?.small ?: images?.normal,
        imageLarge = images?.large ?: images?.normal,
        text = oracleText ?: cardFaces?.mapNotNull { it.oracleText }?.joinToString("\n\n---\n\n")?.takeIf { it.isNotBlank() },
        facts = facts,
        prices = priceList,
        externalUrl = scryfallUri,
        manaCost = effectiveManaCost,
        manaValue = cmc,
        colors = colors ?: emptyList(),
        colorIdentity = colorIdentity ?: emptyList(),
        typeLine = typeLine ?: cardFaces?.firstOrNull()?.typeLine,
        legalities = legalities ?: emptyMap(),
        releasedAt = releasedAt,
        oracleId = oracleId
    )
}
