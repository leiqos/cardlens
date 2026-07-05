package com.cardlens.tcg.ui.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cardlens.tcg.data.CsvPort
import com.cardlens.tcg.data.CardRepository
import com.cardlens.tcg.data.SettingsStore
import com.cardlens.tcg.data.local.Binder
import com.cardlens.tcg.data.local.BinderDao
import com.cardlens.tcg.data.local.CollectionDao
import com.cardlens.tcg.data.local.CollectionEntry
import com.cardlens.tcg.data.local.ValueSnapshot
import com.cardlens.tcg.data.local.ValueSnapshotDao
import com.cardlens.tcg.model.TcgCard
import com.cardlens.tcg.model.TcgGame
import com.cardlens.tcg.model.variantPrice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

enum class CollectionSort(val label: String) {
    NEWEST("Neueste zuerst"),
    NAME("Name A–Z"),
    PRICE_DESC("Wert absteigend"),
    PRICE_ASC("Wert aufsteigend"),
    SET("Set"),
    QUANTITY("Anzahl")
}

enum class CollectionTab(val label: String) { COLLECTION("Sammlung"), WISHLIST("Wunschliste") }

/** Sammlungs-Eintrag inklusive geparster Karte und Einzelwert. */
data class CollectionRow(
    val entry: CollectionEntry,
    val card: TcgCard?,
    val unitPrice: Double
) {
    val totalPrice: Double get() = unitPrice * entry.quantity
}

data class CollectionStatistics(
    val cardCount: Int = 0,
    val uniqueCount: Int = 0,
    val totalValue: Double = 0.0,
    val byGame: Map<TcgGame, Pair<Int, Double>> = emptyMap(),  // Anzahl, Wert
    val byRarity: Map<String, Int> = emptyMap()
)

class CollectionViewModel(
    private val dao: CollectionDao,
    private val binderDao: BinderDao,
    private val snapshotDao: ValueSnapshotDao,
    private val repository: CardRepository,
    private val json: Json,
    val settings: SettingsStore
) : ViewModel() {

    val tab = MutableStateFlow(CollectionTab.COLLECTION)
    val sort = MutableStateFlow(CollectionSort.NEWEST)
    val gameFilter = MutableStateFlow<TcgGame?>(null)
    val binderFilter = MutableStateFlow<Long?>(null)   // null = alle Binder
    val searchQuery = MutableStateFlow("")
    val selectedIds = MutableStateFlow<Set<Long>>(emptySet())

    val binders: StateFlow<List<Binder>> = binderDao.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val valueHistory: StateFlow<List<ValueSnapshot>> = snapshotDao.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val allRows: StateFlow<List<CollectionRow>> = combine(
        dao.observe(wishlist = false),
        dao.observe(wishlist = true),
        tab,
        settings.currency
    ) { owned, wishlist, currentTab, currency ->
        val source = if (currentTab == CollectionTab.COLLECTION) owned else wishlist
        source.map { entry ->
            val card = runCatching {
                json.decodeFromString(TcgCard.serializer(), entry.cardJson)
            }.getOrNull()
            CollectionRow(
                entry = entry,
                card = card,
                unitPrice = card?.variantPrice(currency, entry.foil)?.amount ?: 0.0
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rows: StateFlow<List<CollectionRow>> = combine(
        allRows, sort, gameFilter, binderFilter, searchQuery
    ) { list, currentSort, game, binder, query ->
        var filtered = list
        if (game != null) filtered = filtered.filter { it.entry.game == game.name }
        if (binder != null) filtered = filtered.filter { it.entry.binderId == binder }
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            filtered = filtered.filter {
                it.entry.name.lowercase().contains(q) ||
                    it.entry.setName?.lowercase()?.contains(q) == true
            }
        }
        when (currentSort) {
            CollectionSort.NEWEST -> filtered.sortedByDescending { it.entry.addedAt }
            CollectionSort.NAME -> filtered.sortedBy { it.entry.name.lowercase() }
            CollectionSort.PRICE_DESC -> filtered.sortedByDescending { it.unitPrice }
            CollectionSort.PRICE_ASC -> filtered.sortedBy { it.unitPrice }
            CollectionSort.SET -> filtered.sortedBy { it.entry.setName ?: "" }
            CollectionSort.QUANTITY -> filtered.sortedByDescending { it.entry.quantity }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Statistiken beziehen sich immer auf die gesamte Sammlung (ohne Filter). */
    val statistics: StateFlow<CollectionStatistics> = combine(
        allRows, tab
    ) { list, currentTab ->
        if (currentTab != CollectionTab.COLLECTION) return@combine CollectionStatistics()
        val byGame = linkedMapOf<TcgGame, Pair<Int, Double>>()
        val byRarity = linkedMapOf<String, Int>()
        var count = 0
        var value = 0.0
        for (row in list) {
            count += row.entry.quantity
            value += row.totalPrice
            val game = runCatching { TcgGame.valueOf(row.entry.game) }.getOrNull() ?: continue
            val (c, v) = byGame[game] ?: (0 to 0.0)
            byGame[game] = (c + row.entry.quantity) to (v + row.totalPrice)
            row.entry.rarity?.let { r ->
                val key = r.replaceFirstChar(Char::uppercase)
                byRarity[key] = (byRarity[key] ?: 0) + row.entry.quantity
            }
        }
        CollectionStatistics(
            cardCount = count,
            uniqueCount = list.size,
            totalValue = value,
            byGame = byGame,
            byRarity = byRarity
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CollectionStatistics())

    // ---- Einzel-Aktionen -----------------------------------------------------

    fun setQuantity(entry: CollectionEntry, quantity: Int) {
        viewModelScope.launch {
            if (quantity <= 0) dao.delete(entry.id)
            else dao.update(entry.copy(quantity = quantity))
        }
    }

    fun updateEntry(entry: CollectionEntry) {
        viewModelScope.launch { dao.update(entry) }
    }

    fun delete(entry: CollectionEntry) {
        viewModelScope.launch { dao.delete(entry.id) }
    }

    // ---- Mehrfachauswahl / Bulk-Edit ------------------------------------------

    fun toggleSelected(id: Long) {
        selectedIds.value = selectedIds.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    fun selectAllVisible() {
        selectedIds.value = rows.value.map { it.entry.id }.toSet()
    }

    fun bulkDelete() {
        viewModelScope.launch {
            dao.deleteAll(selectedIds.value.toList())
            clearSelection()
        }
    }

    fun bulkMoveToBinder(binderId: Long?) {
        viewModelScope.launch {
            dao.moveToBinder(selectedIds.value.toList(), binderId)
            clearSelection()
        }
    }

    fun bulkUpdate(transform: (CollectionEntry) -> CollectionEntry) {
        viewModelScope.launch {
            val entries = selectedIds.value.mapNotNull { dao.byId(it) }.map(transform)
            dao.updateAll(entries)
            clearSelection()
        }
    }

    // ---- Binder ---------------------------------------------------------------

    fun createBinder(name: String) {
        viewModelScope.launch {
            binderDao.insert(Binder(name = name.trim(), colorIndex = binders.value.size % 6))
        }
    }

    fun deleteBinder(binder: Binder) {
        viewModelScope.launch {
            dao.clearBinder(binder.id)
            binderDao.delete(binder.id)
            if (binderFilter.value == binder.id) binderFilter.value = null
        }
    }

    // ---- CSV-Export / -Import ---------------------------------------------------

    fun exportCsv(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val owned = dao.allOwned()
            onReady(CsvPort.exportCsv(owned))
        }
    }

    val importProgress = MutableStateFlow<String?>(null)

    /**
     * CSV importieren. Zeilen mit eigener Card-ID + Spiel werden direkt
     * uebernommen (Re-Import); Magic-Zeilen werden ueber Scryfall aufgeloest
     * (Scryfall-ID → Set+Nummer → Name).
     */
    fun importCsv(text: String, onDone: (Int, List<String>) -> Unit) {
        viewModelScope.launch {
            val rows = CsvPort.parseCsv(text)
            var imported = 0
            val failed = mutableListOf<String>()
            rows.forEachIndexed { index, row ->
                importProgress.value = "Importiere ${index + 1}/${rows.size} …"
                val card: TcgCard? = when {
                    row.cardId != null && row.game != null ->
                        repository.cached(row.cardId) ?: resolveMagic(row)
                    else -> resolveMagic(row)
                }
                if (card == null) {
                    failed += row.name
                    return@forEachIndexed
                }
                val existing = dao.findVariant(
                    cardId = card.id,
                    wishlist = row.wishlist,
                    condition = row.condition,
                    language = row.language,
                    foil = row.foil,
                    binderId = null
                )
                dao.upsert(
                    existing?.copy(quantity = existing.quantity + row.quantity)
                        ?: CollectionEntry(
                            cardId = card.id,
                            wishlist = row.wishlist,
                            quantity = row.quantity,
                            name = card.name,
                            setName = card.setName,
                            setCode = card.setCode,
                            collectorNumber = card.collectorNumber,
                            rarity = card.rarity,
                            game = card.game.name,
                            imageUrl = card.imageSmall ?: card.imageLarge,
                            cardJson = json.encodeToString(TcgCard.serializer(), card),
                            addedAt = System.currentTimeMillis(),
                            condition = row.condition,
                            language = row.language,
                            foil = row.foil,
                            altered = row.altered,
                            misprint = row.misprint,
                            purchasePrice = row.purchasePrice,
                            purchaseCurrency = row.purchaseCurrency
                        )
                )
                imported += row.quantity
            }
            importProgress.value = null
            onDone(imported, failed)
        }
    }

    private suspend fun resolveMagic(row: CsvPort.CsvRow): TcgCard? = runCatching {
        row.scryfallId?.let { repository.magicById(it) }
            ?: row.setCode?.let { set ->
                row.collectorNumber?.let { num ->
                    repository.resolve(
                        com.cardlens.tcg.model.CardIdentifier.Magic(set, num)
                    ).firstOrNull()
                }
            }
            ?: repository.magicByName(row.name)
    }.getOrNull()
}
