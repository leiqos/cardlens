package com.cardlens.tcg.data.remote

import com.cardlens.tcg.model.CardFact
import com.cardlens.tcg.model.PriceEntry
import com.cardlens.tcg.model.TcgCard
import com.cardlens.tcg.model.TcgGame
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query
import java.net.URLEncoder

/**
 * Dragon Ball Fusion World — https://apitcg.com (API-Key erforderlich, kostenlos
 * registrierbar). Der Key wird optional aus gradle.properties gelesen; ohne Key
 * liefert die API einen Fehler, der wie eine leere Suche behandelt wird.
 */
interface DragonBallService {
    @GET("api/dragon-ball-fusion/cards")
    suspend fun search(
        @Query("name") name: String,
        @Query("limit") limit: Int = 40
    ): DbList

    companion object {
        const val BASE_URL = "https://apitcg.com/"
    }
}

/** apitcg.com liefert Treffer je nach Endpunkt unter "cards" oder "data". */
@Serializable
data class DbList(
    val cards: List<DbCard> = emptyList(),
    val data: List<DbCard> = emptyList()
) {
    val all: List<DbCard> get() = cards.ifEmpty { data }
}

@Serializable
data class DbImages(val small: String? = null, val large: String? = null)

@Serializable
data class DbSet(val name: String? = null)

@Serializable
data class DbCard(
    val id: String? = null,
    val code: String? = null,
    val name: String,
    val rarity: String? = null,
    val color: String? = null,
    val cardType: String? = null,
    val cost: String? = null,
    val specifiedCost: String? = null,
    val power: String? = null,
    val comboPower: String? = null,
    val comboEnergy: String? = null,
    val features: String? = null,
    val effect: String? = null,
    val getIt: String? = null,
    val images: DbImages? = null,
    val set: DbSet? = null
)

fun DbCard.toTcgCard(): TcgCard? {
    val cardId = id ?: code ?: return null
    val tcgplayerUrl = "https://www.tcgplayer.com/search/dragon-ball-super-fusion-world/product?q=" +
        URLEncoder.encode(name, "UTF-8")
    // apitcg selbst liefert keine Preise — Link zur manuellen Suche.
    val prices = emptyList<PriceEntry>()
    val facts = buildList {
        color?.takeIf { it.isNotBlank() }?.let { add(CardFact("Farbe", it)) }
        cost?.takeIf { it.isNotBlank() }?.let { add(CardFact("Kosten", it)) }
        specifiedCost?.takeIf { it.isNotBlank() }?.let { add(CardFact("Spez. Kosten", it)) }
        power?.takeIf { it.isNotBlank() }?.let { add(CardFact("Power", it)) }
        comboPower?.takeIf { it.isNotBlank() }?.let { add(CardFact("Combo-Power", it)) }
        comboEnergy?.takeIf { it.isNotBlank() }?.let { add(CardFact("Combo-Energie", it)) }
        features?.takeIf { it.isNotBlank() }?.let { add(CardFact("Merkmale", it)) }
        rarity?.takeIf { it.isNotBlank() }?.let { add(CardFact("Seltenheit", it)) }
    }
    return TcgCard(
        id = "DRAGONBALL:$cardId",
        game = TcgGame.DRAGONBALL,
        name = name,
        subtitle = listOfNotNull(cardType, color).joinToString(" · ").takeIf { it.isNotBlank() },
        setName = listOfNotNull(set?.name, code).joinToString(" · ").takeIf { it.isNotBlank() },
        collectorNumber = code,
        rarity = rarity,
        imageSmall = images?.small ?: images?.large,
        imageLarge = images?.large ?: images?.small,
        text = effect,
        facts = facts,
        prices = prices,
        externalUrl = tcgplayerUrl,
        typeLine = cardType,
        colors = color?.split("/", ",")?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() } ?: emptyList()
    )
}
