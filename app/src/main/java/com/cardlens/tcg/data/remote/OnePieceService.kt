package com.cardlens.tcg.data.remote

import com.cardlens.tcg.model.CardFact
import com.cardlens.tcg.model.PriceEntry
import com.cardlens.tcg.model.TcgCard
import com.cardlens.tcg.model.TcgGame
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import java.net.URLEncoder

/**
 * One Piece Card Game — https://optcgapi.com (kein API-Key noetig).
 * Die API bietet keine Namenssuche, nur Set-/Deck-Listen. Deshalb wird beim
 * ersten Zugriff ein lokaler Suchindex ueber alle Sets und Starter-Decks
 * aufgebaut (einmalig pro App-Sitzung) und danach lokal gesucht.
 */
interface OnePieceApi {
    @GET("api/allSets/")
    suspend fun sets(): List<OpSet>

    @GET("api/allDecks/")
    suspend fun decks(): List<OpDeck>

    @GET("api/sets/{id}/")
    suspend fun setCards(@Path("id") id: String): List<OpCard>

    @GET("api/decks/{id}/")
    suspend fun deckCards(@Path("id") id: String): List<OpCard>

    companion object {
        const val BASE_URL = "https://optcgapi.com/"
    }
}

@Serializable
data class OpSet(@SerialName("set_id") val setId: String)

@Serializable
data class OpDeck(@SerialName("structure_deck_id") val deckId: String)

@Serializable
data class OpCard(
    @SerialName("card_set_id") val cardSetId: String? = null,
    @SerialName("card_image_id") val cardImageId: String? = null,
    @SerialName("card_name") val cardName: String? = null,
    @SerialName("set_name") val setName: String? = null,
    @SerialName("set_id") val setId: String? = null,
    @SerialName("card_text") val cardText: String? = null,
    val rarity: String? = null,
    @SerialName("card_color") val cardColor: String? = null,
    @SerialName("card_type") val cardType: String? = null,
    // Zahlen/Strings sind in der API gemischt — lenient-Parsing macht beides zu String
    val life: String? = null,
    @SerialName("card_cost") val cardCost: String? = null,
    @SerialName("card_power") val cardPower: String? = null,
    @SerialName("counter_amount") val counterAmount: String? = null,
    @SerialName("sub_types") val subTypes: String? = null,
    val attribute: String? = null,
    @SerialName("inventory_price") val inventoryPrice: Double? = null,
    @SerialName("market_price") val marketPrice: Double? = null,
    @SerialName("card_image") val cardImage: String? = null
)

fun OpCard.toTcgCard(): TcgCard? {
    val name = cardName ?: return null
    val id = cardImageId ?: cardSetId ?: return null
    val tcgplayerUrl = "https://www.tcgplayer.com/search/one-piece-card-game/product?q=" +
        URLEncoder.encode(name, "UTF-8")
    val priceList = buildList {
        marketPrice?.takeIf { it > 0 }?.let { add(PriceEntry("TCGplayer", "Markt", it, "USD", tcgplayerUrl)) }
        inventoryPrice?.takeIf { it > 0 }?.let { add(PriceEntry("TCGplayer", "Niedrigster Preis", it, "USD", tcgplayerUrl)) }
    }
    val facts = buildList {
        cardColor?.let { add(CardFact("Farbe", it)) }
        cardCost?.takeIf { it.isNotBlank() && it != "null" }?.let { add(CardFact("Kosten", it)) }
        life?.takeIf { it.isNotBlank() && it != "null" }?.let { add(CardFact("Leben", it)) }
        cardPower?.takeIf { it.isNotBlank() && it != "null" }?.let { add(CardFact("Power", it)) }
        counterAmount?.takeIf { it.isNotBlank() && it != "null" && it != "0" }
            ?.let { add(CardFact("Counter", it)) }
        attribute?.let { add(CardFact("Attribut", it)) }
        subTypes?.let { add(CardFact("Typen", it)) }
        rarity?.let { add(CardFact("Seltenheit", it)) }
    }
    return TcgCard(
        id = "ONEPIECE:$id",
        game = TcgGame.ONEPIECE,
        name = name,
        subtitle = listOfNotNull(cardType, cardColor).joinToString(" · ").takeIf { it.isNotBlank() },
        setName = listOfNotNull(setName, cardSetId).joinToString(" · ").takeIf { it.isNotBlank() },
        collectorNumber = cardSetId,
        rarity = rarity,
        imageSmall = cardImage,
        imageLarge = cardImage,
        text = cardText,
        facts = facts,
        prices = priceList
    )
}

class OnePieceCatalog(private val api: OnePieceApi) {

    private data class Indexed(val card: TcgCard, val normName: String)

    private val mutex = Mutex()
    @Volatile
    private var index: List<Indexed>? = null

    suspend fun search(query: String): List<TcgCard> {
        val q = normalize(query)
        if (q.length < 2) return emptyList()
        return loadIndex()
            .filter { it.normName.contains(q) }
            .sortedBy { indexed ->
                when {
                    indexed.normName == q -> 0
                    indexed.normName.startsWith(q) -> 1
                    else -> 2
                }
            }
            .take(40)
            .map { it.card }
    }

    /** Exakte Karte(n) zu einer Karten-ID wie "OP01-025" (inkl. Alt-Arts). */
    suspend fun byCardSetId(cardSetId: String): List<TcgCard> =
        loadIndex()
            .filter { it.card.collectorNumber.equals(cardSetId, ignoreCase = true) }
            .map { it.card }

    /** Index im Hintergrund vorladen, damit der erste Scan nicht wartet. */
    suspend fun prefetch() {
        runCatching { loadIndex() }
    }

    private suspend fun loadIndex(): List<Indexed> =
        index ?: mutex.withLock {
            index ?: buildIndex().also { index = it }
        }

    private suspend fun buildIndex(): List<Indexed> = coroutineScope {
        val setIds = runCatching { api.sets().map { it.setId } }.getOrDefault(emptyList())
        val deckIds = runCatching { api.decks().map { it.deckId } }.getOrDefault(emptyList())
        if (setIds.isEmpty() && deckIds.isEmpty()) {
            throw IllegalStateException("One-Piece-Katalog nicht erreichbar")
        }
        // Begrenzte Parallelitaet, um die kostenlose API nicht zu ueberlasten.
        val semaphore = Semaphore(6)
        val cards = (
            setIds.map { id ->
                async { semaphore.withPermit { runCatching { api.setCards(id) }.getOrDefault(emptyList()) } }
            } + deckIds.map { id ->
                async { semaphore.withPermit { runCatching { api.deckCards(id) }.getOrDefault(emptyList()) } }
            }
        ).awaitAll().flatten()
        cards.mapNotNull { it.toTcgCard() }
            .distinctBy { it.id }
            .map { Indexed(it, normalize(it.name)) }
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
}
