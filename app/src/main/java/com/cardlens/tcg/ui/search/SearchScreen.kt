package com.cardlens.tcg.ui.search

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cardlens.tcg.CardLensApp
import com.cardlens.tcg.data.CardRepository
import com.cardlens.tcg.data.SettingsStore
import com.cardlens.tcg.model.TcgCard
import com.cardlens.tcg.model.TcgGame
import com.cardlens.tcg.model.primaryPrice
import com.cardlens.tcg.ui.components.CardGridItem
import com.cardlens.tcg.ui.components.EmptyState
import com.cardlens.tcg.ui.components.GameFilterRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class SearchSort(val label: String) {
    RELEVANCE("Relevanz"),
    PRICE_DESC("Preis absteigend"),
    PRICE_ASC("Preis aufsteigend"),
    NAME("Name A–Z")
}

/** Erweiterte Filter — MTG-Filter werden in Scryfall-Syntax uebersetzt. */
data class SearchFilters(
    val colors: Set<String> = emptySet(),      // W/U/B/R/G
    val types: Set<String> = emptySet(),       // Creature, Instant …
    val rarities: Set<String> = emptySet(),    // common … mythic
    val format: String? = null,                // legal:<format>
    val maxPrice: Double? = null
) {
    val active: Boolean get() = colors.isNotEmpty() || types.isNotEmpty() ||
        rarities.isNotEmpty() || format != null || maxPrice != null

    /** Scryfall-Query-Anteile fuer die Magic-Suche. */
    fun scryfallSuffix(): String = buildString {
        if (colors.isNotEmpty()) append(" c:${colors.joinToString("")}")
        types.forEach { append(" t:$it") }
        if (rarities.isNotEmpty()) append(" (${rarities.joinToString(" OR ") { "r:$it" }})")
        format?.let { append(" legal:$it") }
        maxPrice?.let { append(" usd<=$it") }
    }
}

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Results(val cards: List<TcgCard>) : SearchState
    data object NoResults : SearchState
    data object Error : SearchState
}

class SearchViewModel(
    private val repository: CardRepository,
    val settings: SettingsStore
) : ViewModel() {

    val query = MutableStateFlow("")
    val game = MutableStateFlow<TcgGame?>(null)
    val sort = MutableStateFlow(SearchSort.RELEVANCE)
    val filters = MutableStateFlow(SearchFilters())
    val state = MutableStateFlow<SearchState>(SearchState.Idle)
    val suggestions = MutableStateFlow<List<String>>(emptyList())
    val recentSearches = MutableStateFlow<List<String>>(emptyList())

    private var searchJob: Job? = null
    private var rawResults: List<TcgCard> = emptyList()

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            query.debounce(650).collect { q ->
                if (q.trim().length >= 3) runSearch(q)
            }
        }
        // Namensvorschlaege (Scryfall) parallel zum Tippen
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            query.debounce(300).collect { q ->
                suggestions.value = if (q.trim().length >= 3 && game.value in listOf(null, TcgGame.MAGIC)) {
                    repository.magicAutocomplete(q.trim()).take(6)
                } else emptyList()
            }
        }
    }

    fun searchNow(text: String = query.value) {
        query.value = text
        if (text.trim().length >= 2) runSearch(text)
    }

    fun setGame(value: TcgGame?) {
        game.value = value
        if (query.value.trim().length >= 3) runSearch(query.value)
    }

    fun setSort(value: SearchSort) {
        sort.value = value
        applySort()
    }

    fun setFilters(value: SearchFilters) {
        filters.value = value
        if (query.value.trim().length >= 3) runSearch(query.value)
    }

    fun clear() {
        query.value = ""
        suggestions.value = emptyList()
        state.value = SearchState.Idle
    }

    private fun runSearch(rawQuery: String) {
        val q = rawQuery.trim()
        searchJob?.cancel()
        state.value = SearchState.Loading
        searchJob = viewModelScope.launch {
            val f = filters.value
            // Magic bekommt die Filter als Scryfall-Syntax; andere Spiele suchen roh.
            val magicQuery = if (f.active) "$q${f.scryfallSuffix()}" else q
            val result = runCatching {
                if (f.active && game.value in listOf(null, TcgGame.MAGIC)) {
                    // Bei aktiven Filtern nur Magic durchsuchen — die Filter
                    // sind Scryfall-spezifisch.
                    repository.search(magicQuery, TcgGame.MAGIC)
                } else {
                    repository.search(q, game.value)
                }
            }
            // Wurde diese Suche durch eine neuere abgeloest (Tab-/Query-Wechsel),
            // darf sie den State nicht mehr ueberschreiben — sonst erscheint ein
            // falscher "Netzwerkfehler" aus der geschluckten CancellationException.
            if (!isActive || result.exceptionOrNull() is CancellationException) return@launch
            rawResults = result.getOrDefault(emptyList())
            recentSearches.value = (listOf(q) + recentSearches.value.filter { it != q }).take(8)
            state.value = when {
                result.isFailure -> SearchState.Error
                rawResults.isEmpty() -> SearchState.NoResults
                else -> SearchState.Results(sorted(rawResults))
            }
        }
    }

    private fun applySort() {
        val current = state.value
        if (current is SearchState.Results) {
            state.value = SearchState.Results(sorted(rawResults))
        }
    }

    private fun sorted(cards: List<TcgCard>): List<TcgCard> {
        val currency = settings.currency.value
        return when (sort.value) {
            SearchSort.RELEVANCE -> cards
            SearchSort.NAME -> cards.sortedBy { it.name.lowercase() }
            SearchSort.PRICE_DESC -> cards.sortedByDescending { it.primaryPrice(currency)?.amount ?: -1.0 }
            SearchSort.PRICE_ASC -> cards.sortedBy { it.primaryPrice(currency)?.amount ?: Double.MAX_VALUE }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onOpenCard: (TcgCard) -> Unit) {
    val app = LocalContext.current.applicationContext as CardLensApp
    val viewModel: SearchViewModel = viewModel {
        SearchViewModel(app.container.repository, app.container.settings)
    }
    val query by viewModel.query.collectAsState()
    val game by viewModel.game.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val filters by viewModel.filters.collectAsState()
    val state by viewModel.state.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val recent by viewModel.recentSearches.collectAsState()
    val currency by viewModel.settings.currency.collectAsState()

    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    var showFilters by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // Suchfeld
        TextField(
            value = query,
            onValueChange = { viewModel.query.value = it },
            placeholder = { Text("Karte suchen …") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                Row {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clear() }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Leeren")
                        }
                    }
                    IconButton(onClick = { showFilters = true }) {
                        Icon(
                            Icons.Filled.FilterList,
                            contentDescription = "Filter",
                            tint = if (filters.active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focusManager.clearFocus()
                viewModel.searchNow()
            }),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .focusRequester(focusRequester)
        )

        // Namensvorschlaege
        if (suggestions.isNotEmpty() && state !is SearchState.Results) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                items(suggestions.size) { i ->
                    AssistChip(
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.searchNow(suggestions[i])
                        },
                        label = { Text(suggestions[i]) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        GameFilterRow(selected = game, onSelect = { viewModel.setGame(it) })
        Spacer(Modifier.height(6.dp))

        // Sortierung
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            items(SearchSort.entries.size) { i ->
                val s = SearchSort.entries[i]
                FilterChip(
                    selected = sort == s,
                    onClick = { viewModel.setSort(s) },
                    label = { Text(s.label) },
                    leadingIcon = when (s) {
                        SearchSort.PRICE_DESC -> ({
                            Icon(Icons.AutoMirrored.Filled.TrendingDown, contentDescription = null, Modifier.size(16.dp))
                        })
                        SearchSort.PRICE_ASC -> ({
                            Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = null, Modifier.size(16.dp))
                        })
                        else -> null
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        Crossfade(targetState = state, label = "searchState") { s ->
            when (s) {
                SearchState.Idle -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp)
                ) {
                    if (recent.isNotEmpty()) {
                        Text(
                            "Zuletzt gesucht",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        recent.forEach { term ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.searchNow(term) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.padding(4.dp))
                                Text(term, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyState(
                                icon = Icons.Filled.Search,
                                title = "Alle TCGs durchsuchen",
                                body = "Suche Karten aus Magic, Pokémon, Yu-Gi-Oh!, One Piece und Lorcana — mit Filtern für Farbe, Typ, Seltenheit und Format."
                            )
                        }
                    }
                }
                SearchState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                SearchState.NoResults -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Filled.SearchOff,
                        title = "Keine Treffer",
                        body = "Anderen Suchbegriff versuchen oder Filter lockern."
                    )
                }
                SearchState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyState(
                            icon = Icons.Filled.CloudOff,
                            title = "Netzwerkfehler",
                            body = "Die Suche konnte nicht ausgeführt werden."
                        )
                        Button(onClick = { viewModel.searchNow() }) { Text("Erneut versuchen") }
                    }
                }
                is SearchState.Results -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(s.cards, key = { it.id }) { card ->
                        CardGridItem(
                            card = card,
                            currency = currency,
                            onClick = {
                                focusManager.clearFocus()
                                onOpenCard(card)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showFilters) {
        FilterSheet(
            filters = filters,
            onDismiss = { showFilters = false },
            onApply = { viewModel.setFilters(it); showFilters = false }
        )
    }
}

/** Erweiterte Filter (Scryfall-basiert, gilt fuer die Magic-Suche). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    filters: SearchFilters,
    onDismiss: () -> Unit,
    onApply: (SearchFilters) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var colors by remember { mutableStateOf(filters.colors) }
    var types by remember { mutableStateOf(filters.types) }
    var rarities by remember { mutableStateOf(filters.rarities) }
    var format by remember { mutableStateOf(filters.format) }
    var maxPrice by remember { mutableStateOf(filters.maxPrice) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Erweiterte Filter", style = MaterialTheme.typography.titleLarge)
            Text(
                "Gilt für die Magic-Suche (Scryfall-Filter).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Farben", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("W", "U", "B", "R", "G").forEach { c ->
                    FilterChip(
                        selected = c in colors,
                        onClick = { colors = if (c in colors) colors - c else colors + c },
                        label = { Text(c) }
                    )
                }
            }

            Text("Kartentyp", style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val allTypes = listOf(
                    "creature", "instant", "sorcery", "enchantment",
                    "artifact", "planeswalker", "land"
                )
                items(allTypes.size) { i ->
                    val t = allTypes[i]
                    FilterChip(
                        selected = t in types,
                        onClick = { types = if (t in types) types - t else types + t },
                        label = { Text(t.replaceFirstChar(Char::uppercase)) }
                    )
                }
            }

            Text("Seltenheit", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("common", "uncommon", "rare", "mythic").forEach { r ->
                    FilterChip(
                        selected = r in rarities,
                        onClick = { rarities = if (r in rarities) rarities - r else rarities + r },
                        label = { Text(r.take(1).uppercase()) }
                    )
                }
            }

            Text("Format-legal", style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val formats = listOf("standard", "pioneer", "modern", "legacy", "commander", "pauper")
                items(formats.size) { i ->
                    val f = formats[i]
                    FilterChip(
                        selected = format == f,
                        onClick = { format = if (format == f) null else f },
                        label = { Text(f.replaceFirstChar(Char::uppercase)) }
                    )
                }
            }

            Text("Max. Preis (USD)", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1.0, 5.0, 20.0, 100.0).forEach { p ->
                    FilterChip(
                        selected = maxPrice == p,
                        onClick = { maxPrice = if (maxPrice == p) null else p },
                        label = { Text("≤ $${p.toInt()}") }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onApply(SearchFilters(colors, types, rarities, format, maxPrice))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Anwenden") }
                Button(
                    onClick = { onApply(SearchFilters()) },
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                ) { Text("Zurücksetzen") }
            }
        }
    }
}
