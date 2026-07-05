package com.cardlens.tcg.ui.decks

import android.content.Intent
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cardlens.tcg.CardLensApp
import com.cardlens.tcg.model.DeckBoard
import com.cardlens.tcg.model.DeckFormat
import com.cardlens.tcg.model.TcgCard
import com.cardlens.tcg.model.TcgGame
import com.cardlens.tcg.model.formatPrice
import com.cardlens.tcg.ui.components.CardImage
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce

private enum class DeckTab(val label: String) { CARDS("Karten"), STATS("Statistik") }

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun DeckDetailScreen(
    deckId: Long,
    onBack: () -> Unit,
    onOpenCard: (TcgCard) -> Unit
) {
    val app = LocalContext.current.applicationContext as CardLensApp
    val viewModel: DeckDetailViewModel = viewModel(key = "deck-$deckId") {
        DeckDetailViewModel(
            deckId,
            app.container.deckDao,
            app.container.collectionDao,
            app.container.repository,
            app.container.cardJson,
            app.container.settings
        )
    }
    val context = LocalContext.current
    val deck by viewModel.deck.collectAsState()
    val rows by viewModel.rows.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val currency by viewModel.settings.currency.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()

    var tab by remember { mutableStateOf(DeckTab.CARDS) }
    var showAddSheet by remember { mutableStateOf(false) }
    var showSim by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var importReport by remember { mutableStateOf<String?>(null) }

    val totalValue = rows.sumOf { it.price * it.deckCard.quantity }
    val totalCards = rows.filter { it.deckCard.board == DeckBoard.MAIN.name }.sumOf { it.deckCard.quantity }
    val missing = rows.count { it.ownedQuantity < it.deckCard.quantity }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(deck?.name ?: "Deck", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        deck?.let {
                            Text(
                                "${DeckFormat.fromName(it.format).label} · $totalCards Karten · ≈ ${formatPrice(totalValue, currency)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = { showSim = true; viewModel.simNewHand() }) {
                        Icon(Icons.Filled.Casino, contentDescription = "Starthand simulieren")
                    }
                    IconButton(onClick = { showImport = true }) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = "Deckliste importieren")
                    }
                    IconButton(onClick = {
                        val text = viewModel.exportText()
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, deck?.name)
                                    putExtra(Intent.EXTRA_TEXT, text)
                                },
                                "Deck teilen"
                            )
                        )
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Deck teilen")
                    }
                }
            )
        },
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { showAddSheet = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Karte") }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                DeckTab.entries.forEachIndexed { index, t ->
                    SegmentedButton(
                        selected = tab == t,
                        onClick = { tab = t },
                        shape = SegmentedButtonDefaults.itemShape(index, DeckTab.entries.size)
                    ) { Text(t.label) }
                }
            }

            if (stats.illegalCards.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        "⚠ Nicht format-legal: ${stats.illegalCards.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            when (tab) {
                DeckTab.CARDS -> DeckCardList(
                    rows = rows,
                    currency = currency,
                    onOpenCard = onOpenCard,
                    onSetQuantity = viewModel::setQuantity,
                    onMoveToBoard = viewModel::moveToBoard
                )
                DeckTab.STATS -> DeckStatsTab(stats)
            }
        }
    }

    if (showAddSheet) {
        AddCardSheet(
            game = deck?.let { runCatching { TcgGame.valueOf(it.game) }.getOrNull() },
            onDismiss = { showAddSheet = false },
            onAdd = { card, board -> viewModel.addCard(card, board) }
        )
    }

    if (showSim) {
        GoldfishSheet(
            viewModel = viewModel,
            onDismiss = { showSim = false }
        )
    }

    if (showImport) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (importProgress == null) showImport = false },
            title = { Text("Deckliste importieren") },
            text = {
                Column {
                    Text(
                        "Format: \"4 Lightning Bolt\" pro Zeile. \"Sideboard\" wechselt den Bereich.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                        placeholder = { Text("4 Lightning Bolt\n2 Counterspell\n…") }
                    )
                    importProgress?.let {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = text.isNotBlank() && importProgress == null,
                    onClick = {
                        viewModel.importDeckList(text) { imported, failed ->
                            showImport = false
                            importReport = buildString {
                                append("$imported Karten importiert.")
                                if (failed.isNotEmpty()) {
                                    append("\nNicht gefunden: ${failed.joinToString(", ")}")
                                }
                            }
                        }
                    }
                ) { Text("Importieren") }
            },
            dismissButton = {
                TextButton(
                    enabled = importProgress == null,
                    onClick = { showImport = false }
                ) { Text("Abbrechen") }
            }
        )
    }

    importReport?.let { report ->
        AlertDialog(
            onDismissRequest = { importReport = null },
            title = { Text("Import abgeschlossen") },
            text = { Text(report) },
            confirmButton = {
                TextButton(onClick = { importReport = null }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun DeckCardList(
    rows: List<DeckCardRow>,
    currency: String,
    onOpenCard: (TcgCard) -> Unit,
    onSetQuantity: (com.cardlens.tcg.data.local.DeckCard, Int) -> Unit,
    onMoveToBoard: (com.cardlens.tcg.data.local.DeckCard, DeckBoard) -> Unit
) {
    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            com.cardlens.tcg.ui.components.EmptyState(
                icon = Icons.Outlined.Style,
                title = "Deck ist leer",
                body = "Füge Karten über das Plus hinzu oder importiere eine Deckliste."
            )
        }
        return
    }
    val grouped = rows.groupBy { it.deckCard.board }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (board in listOf(DeckBoard.COMMANDER, DeckBoard.MAIN, DeckBoard.SIDE)) {
            val boardRows = grouped[board.name] ?: continue
            item(key = "header-${board.name}") {
                Text(
                    "${board.label} · ${boardRows.sumOf { it.deckCard.quantity }}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
            }
            items(boardRows, key = { it.deckCard.id }) { row ->
                DeckCardRowItem(row, currency, onOpenCard, onSetQuantity, onMoveToBoard)
            }
        }
    }
}

@Composable
private fun DeckCardRowItem(
    row: DeckCardRow,
    currency: String,
    onOpenCard: (TcgCard) -> Unit,
    onSetQuantity: (com.cardlens.tcg.data.local.DeckCard, Int) -> Unit,
    onMoveToBoard: (com.cardlens.tcg.data.local.DeckCard, DeckBoard) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val owned = row.ownedQuantity >= row.deckCard.quantity
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { row.card?.let(onOpenCard) }
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CardImage(
            url = row.deckCard.imageUrl,
            contentDescription = row.deckCard.name,
            modifier = Modifier.width(40.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.deckCard.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (owned) "✓ im Besitz" else "${row.ownedQuantity}/${row.deckCard.quantity} im Besitz",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (owned) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "≈ ${formatPrice(row.price * row.deckCard.quantity, currency)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = { onSetQuantity(row.deckCard, row.deckCard.quantity - 1) }) {
            Icon(Icons.Filled.Remove, contentDescription = "Weniger", Modifier.size(18.dp))
        }
        Text("${row.deckCard.quantity}", style = MaterialTheme.typography.titleSmall)
        IconButton(onClick = { onSetQuantity(row.deckCard, row.deckCard.quantity + 1) }) {
            Icon(Icons.Filled.Add, contentDescription = "Mehr", Modifier.size(18.dp))
        }
        Box {
            TextButton(onClick = { showMenu = true }) { Text("⋮") }
            androidx.compose.material3.DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DeckBoard.entries.filter { it.name != row.deckCard.board }.forEach { board ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Nach ${board.label}") },
                        onClick = {
                            showMenu = false
                            onMoveToBoard(row.deckCard, board)
                        }
                    )
                }
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Entfernen", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showMenu = false
                        onSetQuantity(row.deckCard, 0)
                    }
                )
            }
        }
    }
}

@Composable
private fun DeckStatsTab(stats: DeckStats) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Column {
                Text("Mana-Kurve", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Ø Manawert: ${"%.2f".format(stats.averageManaValue)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                ManaCurveChart(stats.manaCurve)
            }
        }
        item {
            Column {
                Text("Farbverteilung", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                if (stats.colorCounts.isEmpty()) {
                    Text(
                        "Keine Farbdaten vorhanden.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    ColorPieChart(stats.colorCounts)
                }
            }
        }
        item {
            Column {
                Text("Kartentypen", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                TypeBreakdown(stats.typeCounts)
            }
        }
    }
}

/** Karten-Suche als Bottom-Sheet zum direkten Hinzufuegen ins Deck. */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
private fun AddCardSheet(
    game: TcgGame?,
    onDismiss: () -> Unit,
    onAdd: (TcgCard, DeckBoard) -> Unit
) {
    val app = LocalContext.current.applicationContext as CardLensApp
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<TcgCard>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var board by remember { mutableStateOf(DeckBoard.MAIN) }
    var addedCount by remember { mutableIntStateOf(0) }
    val queryFlow = remember { MutableStateFlow("") }

    LaunchedEffect(Unit) {
        queryFlow.debounce(500).collect { q ->
            if (q.length < 3) { results = emptyList(); return@collect }
            searching = true
            results = runCatching { app.container.repository.search(q, game) }
                .getOrDefault(emptyList())
            searching = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(bottom = 24.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; queryFlow.value = it },
                label = { Text("Karte suchen") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 20.dp)
            ) {
                items(DeckBoard.entries) { b ->
                    FilterChip(
                        selected = board == b,
                        onClick = { board = b },
                        label = { Text(b.label) }
                    )
                }
            }
            if (addedCount > 0) {
                Text(
                    "✓ $addedCount hinzugefügt",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            if (searching) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(results, key = { it.id }) { card ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CardImage(
                            url = card.imageSmall ?: card.imageLarge,
                            contentDescription = card.name,
                            modifier = Modifier.width(36.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                card.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            card.setName?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        OutlinedButton(onClick = { onAdd(card, board); addedCount++ }) {
                            Text("Hinzufügen")
                        }
                    }
                }
            }
        }
    }
}

/** Starthand-Simulator ("Goldfishing"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoldfishSheet(
    viewModel: DeckDetailViewModel,
    onDismiss: () -> Unit
) {
    val sim by viewModel.sim.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Starthand-Simulator", style = MaterialTheme.typography.titleLarge)
            Text(
                "Hand: ${sim.hand.size} · Bibliothek: ${sim.library.size}" +
                    (if (sim.mulligans > 0) " · Mulligans: ${sim.mulligans}" else "") +
                    (if (sim.turn > 0) " · Zug ${sim.turn + 1}" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sim.hand.size) { i ->
                    CardImage(
                        url = sim.hand[i].imageSmall ?: sim.hand[i].imageLarge,
                        contentDescription = sim.hand[i].name,
                        modifier = Modifier.width(96.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.simDraw() }, enabled = sim.library.isNotEmpty()) {
                    Text("Karte ziehen")
                }
                OutlinedButton(onClick = { viewModel.simNewHand(keepMulliganCount = true) }) {
                    Text("Mulligan")
                }
                OutlinedButton(onClick = { viewModel.simNewHand() }) {
                    Text("Neu mischen")
                }
            }
        }
    }
}
