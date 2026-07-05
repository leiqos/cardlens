package com.cardlens.tcg.ui.decks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cardlens.tcg.data.CardRepository
import com.cardlens.tcg.data.CsvPort
import com.cardlens.tcg.data.SettingsStore
import com.cardlens.tcg.data.local.CollectionDao
import com.cardlens.tcg.data.local.Deck
import com.cardlens.tcg.data.local.DeckCard
import com.cardlens.tcg.data.local.DeckDao
import com.cardlens.tcg.model.DeckBoard
import com.cardlens.tcg.model.DeckFormat
import com.cardlens.tcg.model.TcgCard
import com.cardlens.tcg.model.TcgGame
import com.cardlens.tcg.model.variantPrice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Zusammenfassung eines Decks fuer die Deck-Liste. */
data class DeckSummary(
    val deck: Deck,
    val cardCount: Int,
    val value: Double,
    val colors: List<String>,
    val coverImage: String?
)

class DecksViewModel(
    private val deckDao: DeckDao,
    private val json: Json,
    val settings: SettingsStore
) : ViewModel() {

    val summaries: StateFlow<List<DeckSummary>> = combine(
        deckDao.observeDecks(),
        deckDao.observeAllCards(),
        settings.currency
    ) { decks, allCards, currency ->
        val byDeck = allCards.groupBy { it.deckId }
        decks.map { deck ->
            val cards = byDeck[deck.id].orEmpty()
            var value = 0.0
            val colors = linkedSetOf<String>()
            for (dc in cards) {
                val card = dc.parsedCard(json) ?: continue
                value += (card.variantPrice(currency, foil = false)?.amount ?: 0.0) * dc.quantity
                colors += card.colorIdentity.ifEmpty { card.colors }
            }
            DeckSummary(
                deck = deck,
                cardCount = cards.sumOf { it.quantity },
                value = value,
                colors = colors.toList(),
                coverImage = cards.firstOrNull { it.board == DeckBoard.COMMANDER.name }?.imageUrl
                    ?: cards.firstOrNull()?.imageUrl
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createDeck(name: String, game: TcgGame, format: DeckFormat, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = deckDao.insertDeck(Deck(name = name.trim(), game = game.name, format = format.name))
            onCreated(id)
        }
    }

    fun deleteDeck(deckId: Long) {
        viewModelScope.launch {
            deckDao.deleteDeckCards(deckId)
            deckDao.deleteDeck(deckId)
        }
    }
}

private fun DeckCard.parsedCard(json: Json): TcgCard? =
    runCatching { json.decodeFromString(TcgCard.serializer(), cardJson) }.getOrNull()

/** Statistiken eines Decks (Mana-Kurve, Farben, Typen, Legalitaet). */
data class DeckStats(
    val manaCurve: Map<Int, Int>,          // Manawert (7 = 7+) -> Kartenzahl
    val colorCounts: Map<String, Int>,     // W/U/B/R/G/C -> Kartenzahl
    val typeCounts: Map<String, Int>,      // Kartentyp -> Kartenzahl
    val averageManaValue: Double,
    val illegalCards: List<String>         // im gewaehlten Format nicht legal
)

/** Karte + Besitz-Info fuer die Deck-Kartenliste. */
data class DeckCardRow(
    val deckCard: DeckCard,
    val card: TcgCard?,
    val ownedQuantity: Int,
    val price: Double
)

class DeckDetailViewModel(
    private val deckId: Long,
    private val deckDao: DeckDao,
    private val collectionDao: CollectionDao,
    private val repository: CardRepository,
    private val json: Json,
    val settings: SettingsStore
) : ViewModel() {

    val deck: StateFlow<Deck?> = deckDao.observeDeck(deckId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val ownedByCard: StateFlow<Map<String, Int>> = collectionDao.observe(wishlist = false)
        .map { entries -> entries.groupBy { it.cardId }.mapValues { (_, es) -> es.sumOf { it.quantity } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val rows: StateFlow<List<DeckCardRow>> = combine(
        deckDao.observeCards(deckId),
        ownedByCard,
        settings.currency
    ) { cards, owned, currency ->
        cards.map { dc ->
            val card = dc.parsedCard(json)
            DeckCardRow(
                deckCard = dc,
                card = card,
                ownedQuantity = owned[dc.cardId] ?: 0,
                price = card?.variantPrice(currency, foil = false)?.amount ?: 0.0
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stats: StateFlow<DeckStats> = combine(rows, deck) { rowList, deckEntity ->
        computeStats(rowList, deckEntity)
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000),
        DeckStats(emptyMap(), emptyMap(), emptyMap(), 0.0, emptyList())
    )

    private fun computeStats(rowList: List<DeckCardRow>, deckEntity: Deck?): DeckStats {
        val main = rowList.filter { it.deckCard.board != DeckBoard.SIDE.name }
        val curve = sortedMapOf<Int, Int>()
        val colors = linkedMapOf<String, Int>()
        val types = linkedMapOf<String, Int>()
        var mvSum = 0.0
        var mvCount = 0
        val legalityKey = DeckFormat.fromName(deckEntity?.format).legalityKey
        val illegal = mutableListOf<String>()
        for (row in main) {
            val card = row.card ?: continue
            val qty = row.deckCard.quantity
            val isLand = card.typeLine?.contains("Land", ignoreCase = true) == true
            card.manaValue?.let { mv ->
                if (!isLand) {
                    val bucket = mv.toInt().coerceIn(0, 7)
                    curve[bucket] = (curve[bucket] ?: 0) + qty
                    mvSum += mv * qty
                    mvCount += qty
                }
            }
            val cardColors = card.colors.ifEmpty {
                if (card.game == TcgGame.MAGIC && !isLand) listOf("C") else emptyList()
            }
            for (c in cardColors) colors[c] = (colors[c] ?: 0) + qty
            types[card.primaryType] = (types[card.primaryType] ?: 0) + qty
            if (legalityKey != null && card.legalities.isNotEmpty()) {
                val status = card.legalities[legalityKey]
                if (status != null && status != "legal" && status != "restricted") {
                    illegal += card.name
                }
            }
        }
        return DeckStats(
            manaCurve = curve,
            colorCounts = colors,
            typeCounts = types.entries.sortedByDescending { it.value }.associate { it.key to it.value },
            averageManaValue = if (mvCount > 0) mvSum / mvCount else 0.0,
            illegalCards = illegal.distinct()
        )
    }

    fun addCard(card: TcgCard, board: DeckBoard = DeckBoard.MAIN, quantity: Int = 1) {
        viewModelScope.launch {
            val existing = deckDao.findCard(deckId, card.id, board.name)
            deckDao.upsertCard(
                existing?.copy(quantity = existing.quantity + quantity)
                    ?: DeckCard(
                        deckId = deckId,
                        cardId = card.id,
                        name = card.name,
                        quantity = quantity,
                        board = board.name,
                        imageUrl = card.imageSmall ?: card.imageLarge,
                        cardJson = json.encodeToString(TcgCard.serializer(), card)
                    )
            )
            deckDao.touch(deckId)
        }
    }

    fun setQuantity(deckCard: DeckCard, quantity: Int) {
        viewModelScope.launch {
            if (quantity <= 0) deckDao.deleteCard(deckCard.id)
            else deckDao.upsertCard(deckCard.copy(quantity = quantity))
            deckDao.touch(deckId)
        }
    }

    fun moveToBoard(deckCard: DeckCard, board: DeckBoard) {
        viewModelScope.launch {
            val existing = deckDao.findCard(deckId, deckCard.cardId, board.name)
            if (existing != null) {
                deckDao.upsertCard(existing.copy(quantity = existing.quantity + deckCard.quantity))
                deckDao.deleteCard(deckCard.id)
            } else {
                deckDao.upsertCard(deckCard.copy(board = board.name))
            }
            deckDao.touch(deckId)
        }
    }

    fun rename(name: String) {
        viewModelScope.launch {
            deck.value?.let { deckDao.updateDeck(it.copy(name = name.trim(), updatedAt = System.currentTimeMillis())) }
        }
    }

    fun exportText(): String = CsvPort.exportDeckText(rows.value.map { it.deckCard })

    // ---- Deckliste importieren ---------------------------------------------

    val importProgress = MutableStateFlow<String?>(null)

    /** Text-Deckliste importieren; loest Namen (nur Magic) ueber Scryfall auf. */
    fun importDeckList(text: String, onDone: (Int, List<String>) -> Unit) {
        viewModelScope.launch {
            val lines = CsvPort.parseDeckList(text)
            val failed = mutableListOf<String>()
            var imported = 0
            lines.forEachIndexed { index, (qty, name, board) ->
                importProgress.value = "Importiere ${index + 1}/${lines.size} …"
                val card = runCatching { repository.magicByName(name) }.getOrNull()
                    ?: runCatching {
                        repository.search(name, deck.value?.let { TcgGame.valueOf(it.game) })
                            .firstOrNull { it.name.equals(name, ignoreCase = true) }
                    }.getOrNull()
                if (card == null) {
                    failed += name
                } else {
                    addCard(card, DeckBoard.valueOf(board), qty)
                    imported += qty
                }
            }
            importProgress.value = null
            onDone(imported, failed)
        }
    }

    // ---- Goldfish-Simulator ---------------------------------------------------

    data class SimState(
        val hand: List<TcgCard> = emptyList(),
        val library: List<TcgCard> = emptyList(),
        val mulligans: Int = 0,
        val turn: Int = 0
    )

    val sim = MutableStateFlow(SimState())

    /** Bibliothek aus dem Hauptdeck bauen (Mengen expandiert) und 7 ziehen. */
    fun simNewHand(keepMulliganCount: Boolean = false) {
        val cards = rows.value
            .filter { it.deckCard.board == DeckBoard.MAIN.name }
            .flatMap { row -> List(row.deckCard.quantity) { row.card } }
            .filterNotNull()
            .shuffled()
        val mulligans = if (keepMulliganCount) sim.value.mulligans + 1 else 0
        val handSize = (7 - mulligans).coerceAtLeast(1)
        sim.value = SimState(
            hand = cards.take(handSize),
            library = cards.drop(handSize),
            mulligans = mulligans,
            turn = 0
        )
    }

    fun simDraw() {
        val s = sim.value
        val next = s.library.firstOrNull() ?: return
        sim.value = s.copy(
            hand = s.hand + next,
            library = s.library.drop(1),
            turn = s.turn + 1
        )
    }
}
