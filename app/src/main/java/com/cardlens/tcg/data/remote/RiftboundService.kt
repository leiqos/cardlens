package com.cardlens.tcg.data.remote

import com.cardlens.tcg.model.CardFact
import com.cardlens.tcg.model.TcgCard
import com.cardlens.tcg.model.TcgGame
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.net.URLEncoder

/**
 * Riftbound (League of Legends TCG) — https://riftscribe.gg (kein API-Key noetig).
 * Die Suche liefert nur Typeahead-Daten (Name/Typ/Set/Thumbnail); Details
 * (Faktion, Werte, Text, grosses Bild) kommen aus dem Einzelkarten-Endpunkt.
 */
interface RiftboundService {
    @GET("api/cards/search")
    suspend fun search(
        @Query("q") query: String,
        // RiftScribe begrenzt limit auf max. 20 — hoehere Werte liefern HTTP 422.
        @Query("limit") limit: Int = 20
    ): List<RiftboundSearchCard>

    @GET("api/cards/{id}")
    suspend fun byId(@Path("id") id: String): RiftboundCard

    companion object {
        const val BASE_URL = "https://riftscribe.gg/"
    }
}

@Serializable
data class RiftboundSearchCard(
    @SerialName("card_id") val cardId: String,
    val name: String,
    val type: String? = null,
    @SerialName("set_id") val setId: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null
)

@Serializable
data class RiftboundStats(
    val energy: Int? = null,
    val might: Int? = null,
    val power: Int? = null
)

@Serializable
data class RiftboundCard(
    val id: String,
    val name: String,
    @SerialName("set_id") val setId: String? = null,
    @SerialName("collector_number") val collectorNumber: Int? = null,
    val rarity: String? = null,
    val faction: String? = null,
    val type: String? = null,
    val stats: RiftboundStats? = null,
    val image: String? = null,
    val description: String? = null,
    @SerialName("flavor_text") val flavorText: String? = null,
    val keywords: List<String>? = null
)

/** Typeahead-Treffer → schlankes Kartenmodell (ohne Werte/Preis). */
fun RiftboundSearchCard.toTcgCard(): TcgCard = TcgCard(
    id = "RIFTBOUND:$cardId",
    game = TcgGame.RIFTBOUND,
    name = name,
    subtitle = type,
    setName = setId,
    setCode = setId,
    imageSmall = thumbnailUrl,
    imageLarge = thumbnailUrl,
    typeLine = type
)

/** Vollstaendige Karte (Detail-Endpunkt). */
fun RiftboundCard.toTcgCard(): TcgCard {
    val cardmarketUrl = "https://www.cardmarket.com/en/Riftbound/Products/Search?searchString=" +
        URLEncoder.encode(name, "UTF-8")
    val facts = buildList {
        faction?.let { add(CardFact("Fraktion", it.replaceFirstChar(Char::uppercase))) }
        stats?.energy?.let { add(CardFact("Energie", it.toString())) }
        stats?.might?.let { add(CardFact("Macht", it.toString())) }
        stats?.power?.let { add(CardFact("Power", it.toString())) }
        keywords?.takeIf { it.isNotEmpty() }?.let { add(CardFact("Schlüsselwörter", it.joinToString(", "))) }
        rarity?.let { add(CardFact("Seltenheit", it.replaceFirstChar(Char::uppercase))) }
    }
    return TcgCard(
        id = "RIFTBOUND:$id",
        game = TcgGame.RIFTBOUND,
        name = name,
        subtitle = listOfNotNull(type, faction?.replaceFirstChar(Char::uppercase))
            .joinToString(" · ").takeIf { it.isNotBlank() },
        setName = listOfNotNull(setId, collectorNumber?.toString()).joinToString(" · ").takeIf { it.isNotBlank() },
        setCode = setId,
        collectorNumber = collectorNumber?.toString(),
        rarity = rarity,
        imageSmall = image,
        imageLarge = image,
        text = listOfNotNull(description, flavorText).joinToString("\n\n").takeIf { it.isNotBlank() },
        facts = facts,
        // Riftscribe liefert keine Preise — Link auf Cardmarket zur manuellen Suche.
        prices = emptyList(),
        externalUrl = cardmarketUrl,
        typeLine = type,
        manaValue = stats?.energy?.toDouble(),
        colors = faction?.let { listOf(it.uppercase()) } ?: emptyList()
    )
}
