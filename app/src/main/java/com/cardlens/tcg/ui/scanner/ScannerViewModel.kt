package com.cardlens.tcg.ui.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cardlens.tcg.data.CardRepository
import com.cardlens.tcg.data.SettingsStore
import com.cardlens.tcg.data.local.CollectionDao
import com.cardlens.tcg.data.local.CollectionEntry
import com.cardlens.tcg.model.CardIdentifier
import com.cardlens.tcg.model.TcgCard
import com.cardlens.tcg.model.TcgGame
import com.cardlens.tcg.model.primaryPrice
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

sealed interface ScanState {
    data object Scanning : ScanState
    data class Searching(val label: String) : ScanState
    data class Results(val label: String, val cards: List<TcgCard>) : ScanState
}

/** Eintrag der Stapel-Scan-Session (mit Varianten-Merkmalen). */
data class BatchEntry(
    val card: TcgCard,
    val quantity: Int,
    val foil: Boolean = false,
    val condition: String = "NM",
    val language: String = "en"
)

class ScannerViewModel(
    private val repository: CardRepository,
    private val dao: CollectionDao,
    private val json: Json,
    val settings: SettingsStore
) : ViewModel() {

    // --- Stapel-Scan (Session wie bei ManaBox) ---
    val batchMode = MutableStateFlow(false)
    val batch = MutableStateFlow<List<BatchEntry>>(emptyList())
    val showBatchReview = MutableStateFlow(false)

    fun batchTotal(currency: String): Double = batch.value.sumOf { entry ->
        (entry.card.primaryPrice(currency)?.amount ?: 0.0) * entry.quantity
    }

    fun batchCount(): Int = batch.value.sumOf { it.quantity }

    private fun addToBatch(card: TcgCard) {
        val current = batch.value
        val last = current.lastOrNull()
        batch.value = if (last?.card?.id == card.id) {
            current.dropLast(1) + last.copy(quantity = last.quantity + 1)
        } else {
            current + BatchEntry(
                card = card,
                quantity = 1,
                condition = settings.defaultCondition.value,
                language = settings.defaultLanguage.value
            )
        }
    }

    fun toggleBatchFoil(index: Int) {
        val current = batch.value.toMutableList()
        if (index !in current.indices) return
        current[index] = current[index].copy(foil = !current[index].foil)
        batch.value = current
    }

    fun setBatchCondition(index: Int, condition: String) {
        val current = batch.value.toMutableList()
        if (index !in current.indices) return
        current[index] = current[index].copy(condition = condition)
        batch.value = current
    }

    fun setBatchQuantity(index: Int, quantity: Int) {
        val current = batch.value.toMutableList()
        if (index !in current.indices) return
        if (quantity <= 0) current.removeAt(index) else {
            current[index] = current[index].copy(quantity = quantity)
        }
        batch.value = current
    }

    fun clearBatch() {
        batch.value = emptyList()
        showBatchReview.value = false
    }

    /** Gesamte Session in die Sammlung uebernehmen (Varianten-Mengen addieren). */
    fun commitBatch() {
        val entries = batch.value
        if (entries.isEmpty()) return
        viewModelScope.launch {
            for (entry in entries) {
                val existing = dao.findVariant(
                    cardId = entry.card.id,
                    wishlist = false,
                    condition = entry.condition,
                    language = entry.language,
                    foil = entry.foil,
                    binderId = null
                )
                dao.upsert(
                    existing?.copy(quantity = existing.quantity + entry.quantity)
                        ?: CollectionEntry(
                            cardId = entry.card.id,
                            wishlist = false,
                            quantity = entry.quantity,
                            name = entry.card.name,
                            setName = entry.card.setName,
                            setCode = entry.card.setCode,
                            collectorNumber = entry.card.collectorNumber,
                            rarity = entry.card.rarity,
                            game = entry.card.game.name,
                            imageUrl = entry.card.imageSmall ?: entry.card.imageLarge,
                            cardJson = json.encodeToString(TcgCard.serializer(), entry.card),
                            addedAt = System.currentTimeMillis(),
                            condition = entry.condition,
                            language = entry.language,
                            foil = entry.foil
                        )
                )
            }
            val count = entries.sumOf { it.quantity }
            batch.value = emptyList()
            showBatchReview.value = false
            hint.value = "✓ $count Karten zur Sammlung hinzugefügt."
        }
    }

    val gameFilter = MutableStateFlow<TcgGame?>(null)
    val state = MutableStateFlow<ScanState>(ScanState.Scanning)

    /** Stabil erkannter Kandidat (Kennung oder Name) fuer die Live-Anzeige. */
    val detectedLabel = MutableStateFlow<String?>(null)

    /** Kurzlebige Hinweise, z. B. "keine Treffer". */
    val hint = MutableStateFlow<String?>(null)

    // --- Namens-Voting (Fallback-Pfad) ---
    private val recentNames = ArrayDeque<String>()
    private val displayText = HashMap<String, String>()
    private val nameCandidate = MutableStateFlow<String?>(null)
    private var lastSearched: String? = null

    // --- Kennungs-Voting (Schnellpfad) ---
    // Fenster statt "in Folge": einzelne OCR-Aussetzer setzen nichts zurueck.
    private val recentIdentifiers = ArrayDeque<CardIdentifier>()
    private var stableIdentifier: CardIdentifier? = null
    private var failedIdentifier: CardIdentifier? = null

    private var searchJob: Job? = null

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            nameCandidate.filterNotNull().debounce(700).collect { name ->
                val norm = normalize(name)
                val alreadySearched = lastSearched?.let { fuzzyEquals(it, norm) } == true
                if (state.value is ScanState.Scanning && !alreadySearched) {
                    searchByName(name)
                }
            }
        }
    }

    /** Wird pro analysiertem Kamerabild aufgerufen (Vollbild-Durchgang). */
    fun onFrame(reading: ScanReading) {
        if (state.value !is ScanState.Scanning) return

        offerIdentifiers(reading.identifiers)
        if (state.value !is ScanState.Scanning) return // Kennung hat Suche gestartet

        // Fallback: Kartenname per Mehrheitsvotum ueber die letzten Frames.
        val candidate = reading.nameCandidate ?: return
        val norm = normalize(candidate)
        if (norm.length < 3) return
        displayText[norm] = candidate
        recentNames.addLast(norm)
        if (recentNames.size > VOTE_WINDOW) recentNames.removeFirst()
        val votes = recentNames.count { fuzzyEquals(it, norm) }
        if (votes >= MIN_VOTES) {
            nameCandidate.value = displayText[norm]
            if (recentIdentifiers.isEmpty()) detectedLabel.value = displayText[norm]
            hint.value = null
        }
    }

    /**
     * Kennungen einspeisen — aus dem Vollbild- oder dem Ecken-Zoom-Durchgang.
     * Loest die Suche aus, sobald dieselbe Kennung 2x im Fenster auftaucht.
     */
    fun offerIdentifiers(identifiers: List<CardIdentifier>) {
        if (state.value !is ScanState.Scanning) return
        val id = identifiers.firstOrNull { it != failedIdentifier } ?: return
        recentIdentifiers.addLast(id)
        if (recentIdentifiers.size > ID_WINDOW) recentIdentifiers.removeFirst()
        detectedLabel.value = id.display
        hint.value = null
        if (recentIdentifiers.count { it == id } >= ID_VOTES) {
            stableIdentifier = id
            searchByIdentifiers(
                listOf(id) + identifiers.filter { it != id && it != failedIdentifier }
            )
        }
    }

    /** Lohnt sich der Ecken-Zoom-Durchgang noch? */
    fun needsIdentifierBoost(): Boolean = state.value is ScanState.Scanning

    /** Sofortsuche per Button, ohne auf das Debounce zu warten. */
    fun searchNow() {
        if (state.value !is ScanState.Scanning) return
        val identifier = (stableIdentifier ?: recentIdentifiers.lastOrNull())
            ?.takeIf { it != failedIdentifier }
        val name = nameCandidate.value
        when {
            identifier != null -> searchByIdentifiers(listOf(identifier))
            name != null -> searchByName(name)
        }
    }

    /** Laufende Suche abbrechen und weiterscannen. */
    fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
        state.value = ScanState.Scanning
        hint.value = null
        lastSearched = null
        recentIdentifiers.clear()
        stableIdentifier = null
    }

    private fun searchByIdentifiers(identifiers: List<CardIdentifier>) {
        val first = identifiers.firstOrNull() ?: return
        state.value = ScanState.Searching(first.display)
        searchJob = viewModelScope.launch {
            for (identifier in identifiers) {
                val cards = runCatching { repository.resolve(identifier) }.getOrDefault(emptyList())
                if (cards.isNotEmpty()) {
                    onCardsFound(identifier.display, cards)
                    return@launch
                }
                failedIdentifier = identifier
            }
            // Kennung nicht aufloesbar (z. B. OCR-Zahlendreher) → Namens-Pfad weiter.
            hint.value = "Kennung nicht eindeutig – suche per Name weiter."
            recentIdentifiers.clear()
            stableIdentifier = null
            state.value = ScanState.Scanning
        }
    }

    private fun searchByName(name: String) {
        lastSearched = normalize(name)
        state.value = ScanState.Searching(name)
        searchJob = viewModelScope.launch {
            val result = runCatching { repository.search(name, gameFilter.value) }
            val cards = rankByRelevance(result.getOrDefault(emptyList()), name)
            if (cards.isEmpty()) {
                hint.value = if (result.isFailure) {
                    "Netzwerkfehler – bitte erneut versuchen."
                } else {
                    "Keine Treffer für \"$name\" – Karte neu ausrichten."
                }
                state.value = ScanState.Scanning
            } else {
                onCardsFound(name, cards)
            }
        }
    }

    /**
     * Trefferbehandlung: im Stapel-Modus wird der beste Treffer sofort erfasst
     * und weitergescannt (ManaBox-Workflow), sonst oeffnet das Ergebnis-Sheet.
     */
    private fun onCardsFound(label: String, cards: List<TcgCard>) {
        if (batchMode.value) {
            val best = cards.first()
            addToBatch(best)
            hint.value = "✓ ${best.name} erfasst – nächste Karte"
            resumeForNextScan()
        } else {
            state.value = ScanState.Results(label, cards)
        }
    }

    /** Wie resumeScanning, behaelt aber Hinweis und Stapel. */
    private fun resumeForNextScan() {
        state.value = ScanState.Scanning
        detectedLabel.value = null
        nameCandidate.value = null
        recentNames.clear()
        displayText.clear()
        lastSearched = null
        recentIdentifiers.clear()
        stableIdentifier = null
        failedIdentifier = null
    }

    /** Nach Schliessen des Ergebnis-Sheets weiterscannen. */
    fun resumeScanning() {
        searchJob?.cancel()
        searchJob = null
        state.value = ScanState.Scanning
        detectedLabel.value = null
        nameCandidate.value = null
        hint.value = null
        recentNames.clear()
        displayText.clear()
        lastSearched = null
        recentIdentifiers.clear()
        stableIdentifier = null
        failedIdentifier = null
    }

    private companion object {
        const val VOTE_WINDOW = 10
        const val MIN_VOTES = 3
        const val ID_WINDOW = 8
        const val ID_VOTES = 2
    }
}

// ---- Fuzzy-Matching-Helfer -------------------------------------------------

private fun normalize(s: String): String = s.lowercase()
    .replace(Regex("[^\\p{L}\\p{N}' ]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
        }
        val tmp = prev; prev = curr; curr = tmp
    }
    return prev[b.length]
}

/** Tippfehlertoleranter Vergleich (erlaubt ~1 Fehler pro 6 Zeichen). */
private fun fuzzyEquals(a: String, b: String): Boolean =
    a == b || levenshtein(a, b) <= maxOf(1, minOf(a.length, b.length) / 6)

/** Sortiert Treffer nach Namensaehnlichkeit zur OCR-Anfrage (stabil). */
private fun rankByRelevance(cards: List<TcgCard>, query: String): List<TcgCard> {
    val q = normalize(query)
    return cards.sortedBy { card ->
        val n = normalize(card.name)
        when {
            n == q -> 0
            n.startsWith(q) || q.startsWith(n) -> 1
            n.contains(q) -> 2
            else -> 3 + levenshtein(n.take(24), q.take(24)).coerceAtMost(20)
        }
    }
}
