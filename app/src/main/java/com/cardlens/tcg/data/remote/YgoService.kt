package com.cardlens.tcg.data.remote

import com.cardlens.tcg.model.CardFact
import com.cardlens.tcg.model.PriceEntry
import com.cardlens.tcg.model.TcgCard
import com.cardlens.tcg.model.TcgGame
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query
import java.net.URLEncoder

/** Yu-Gi-Oh! — https://ygoprodeck.com/api-guide (kein API-Key noetig) */
interface YgoService {
    @GET("cardinfo.php")
    suspend fun search(
        @Query("fname") nameFragment: String,
        @Query("num") num: Int = 24,
        @Query("offset") offset: Int = 0
    ): YgoList

    /** Exakte Karte ueber den 8-stelligen Passcode (unten links auf der Karte). */
    @GET("cardinfo.php")
    suspend fun byPasscode(@Query("id") passcode: String): YgoList

    /** Auflagen-Info ueber den Set-Code (z. B. "LOB-EN001") inkl. Auflagen-Preis. */
    @GET("cardsetsinfo.php")
    suspend fun bySetCode(@Query("setcode") setCode: String): YgoSetInfo

    companion object {
        const val BASE_URL = "https://db.ygoprodeck.com/api/v7/"
    }
}

@Serializable
data class YgoSetInfo(
    val id: Long,
    val name: String? = null,
    @SerialName("set_name") val setName: String? = null,
    @SerialName("set_code") val setCode: String? = null,
    @SerialName("set_rarity") val setRarity: String? = null,
    @SerialName("set_price") val setPrice: String? = null
)

@Serializable
data class YgoList(val data: List<YgoCard> = emptyList())

@Serializable
data class YgoSet(
    @SerialName("set_name") val setName: String? = null,
    @SerialName("set_code") val setCode: String? = null,
    @SerialName("set_rarity") val setRarity: String? = null,
    @SerialName("set_price") val setPrice: String? = null
)

@Serializable
data class YgoImage(
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("image_url_small") val imageUrlSmall: String? = null
)

@Serializable
data class YgoPrices(
    @SerialName("cardmarket_price") val cardmarket: String? = null,
    @SerialName("tcgplayer_price") val tcgplayer: String? = null,
    @SerialName("ebay_price") val ebay: String? = null,
    @SerialName("amazon_price") val amazon: String? = null,
    @SerialName("coolstuffinc_price") val coolstuffinc: String? = null
)

@Serializable
data class YgoCard(
    val id: Long,
    val name: String,
    val type: String? = null,
    val desc: String? = null,
    val atk: Int? = null,
    val def: Int? = null,
    val level: Int? = null,
    val race: String? = null,
    val attribute: String? = null,
    val archetype: String? = null,
    @SerialName("card_sets") val cardSets: List<YgoSet>? = null,
    @SerialName("card_images") val cardImages: List<YgoImage>? = null,
    @SerialName("card_prices") val cardPrices: List<YgoPrices>? = null,
    @SerialName("ygoprodeck_url") val ygoprodeckUrl: String? = null
)

/**
 * @param selectedSet gewaehlte Auflage — bestimmt Set/Seltenheit und ergaenzt
 * deren Auflagen-Preis. Ohne Angabe wird die erste bekannte Auflage genutzt.
 */
fun YgoCard.toTcgCard(selectedSet: YgoSet? = null): TcgCard {
    val image = cardImages?.firstOrNull()
    val p = cardPrices?.firstOrNull()
    val encodedName = URLEncoder.encode(name, "UTF-8")
    val cardmarketUrl = "https://www.cardmarket.com/de/YuGiOh/Products/Search?searchString=$encodedName"
    val tcgplayerUrl = "https://www.tcgplayer.com/search/yugioh/product?q=$encodedName"
    val priceList = buildList {
        selectedSet?.setPrice?.toDoubleOrNull()?.takeIf { it > 0 }?.let {
            add(PriceEntry("TCGplayer", "Auflage ${selectedSet.setCode}", it, "USD", tcgplayerUrl))
        }
        p?.cardmarket?.toDoubleOrNull()?.takeIf { it > 0 }?.let { add(PriceEntry("Cardmarket", "Ø alle Auflagen", it, "EUR", cardmarketUrl)) }
        p?.tcgplayer?.toDoubleOrNull()?.takeIf { it > 0 }?.let { add(PriceEntry("TCGplayer", "Ø alle Auflagen", it, "USD", tcgplayerUrl)) }
        p?.ebay?.toDoubleOrNull()?.takeIf { it > 0 }?.let { add(PriceEntry("eBay", "Ø", it, "USD", "https://www.ebay.de/sch/i.html?_nkw=$encodedName+yugioh")) }
        p?.amazon?.toDoubleOrNull()?.takeIf { it > 0 }?.let { add(PriceEntry("Amazon", "Ø", it, "USD")) }
    }
    val firstSet = selectedSet ?: cardSets?.firstOrNull()
    val facts = buildList {
        attribute?.let { add(CardFact("Attribut", it)) }
        race?.let { add(CardFact("Typ", it)) }
        level?.let { add(CardFact("Stufe/Rang", it.toString())) }
        if (atk != null) add(CardFact("ATK", atk.toString()))
        if (def != null) add(CardFact("DEF", def.toString()))
        archetype?.let { add(CardFact("Archetyp", it)) }
        cardSets?.takeIf { it.size > 1 }?.let { add(CardFact("Auflagen", it.size.toString())) }
    }
    // Set-Code allein ist nicht eindeutig: dieselbe Karte existiert im selben
    // Set oft in mehreren Seltenheiten — Rarity gehoert mit in die ID.
    val variantSuffix = selectedSet?.let {
        listOfNotNull(it.setCode, it.setRarity).joinToString(":", prefix = ":")
    } ?: ""
    return TcgCard(
        id = "YUGIOH:$id$variantSuffix",
        game = TcgGame.YUGIOH,
        name = name,
        subtitle = type,
        setName = listOfNotNull(firstSet?.setName, firstSet?.setCode).joinToString(" · ").takeIf { it.isNotBlank() },
        collectorNumber = firstSet?.setCode,
        rarity = firstSet?.setRarity,
        imageSmall = image?.imageUrlSmall ?: image?.imageUrl,
        imageLarge = image?.imageUrl,
        text = desc,
        facts = facts,
        prices = priceList,
        externalUrl = ygoprodeckUrl
    )
}
