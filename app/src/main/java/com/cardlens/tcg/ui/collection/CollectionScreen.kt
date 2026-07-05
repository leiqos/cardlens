package com.cardlens.tcg.ui.collection

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cardlens.tcg.CardLensApp
import com.cardlens.tcg.data.local.Binder
import com.cardlens.tcg.data.local.CollectionEntry
import com.cardlens.tcg.data.local.ValueSnapshot
import com.cardlens.tcg.model.CardCondition
import com.cardlens.tcg.model.CardLanguage
import com.cardlens.tcg.model.TcgCard
import com.cardlens.tcg.model.formatPrice
import com.cardlens.tcg.ui.components.CardImage
import com.cardlens.tcg.ui.components.EmptyState
import com.cardlens.tcg.ui.components.GameFilterRow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CollectionScreen(onOpenCard: (TcgCard) -> Unit) {
    val app = LocalContext.current.applicationContext as CardLensApp
    val viewModel: CollectionViewModel = viewModel {
        CollectionViewModel(
            app.container.collectionDao,
            app.container.binderDao,
            app.container.valueSnapshotDao,
            app.container.repository,
            app.container.cardJson,
            app.container.settings
        )
    }
    val context = LocalContext.current
    val tab by viewModel.tab.collectAsState()
    val rows by viewModel.rows.collectAsState()
    val stats by viewModel.statistics.collectAsState()
    val binders by viewModel.binders.collectAsState()
    val history by viewModel.valueHistory.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val gameFilter by viewModel.gameFilter.collectAsState()
    val binderFilter by viewModel.binderFilter.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val selected by viewModel.selectedIds.collectAsState()
    val currency by viewModel.settings.currency.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()

    val selectionMode = selected.isNotEmpty()
    var sortMenu by remember { mutableStateOf(false) }
    var editEntry by remember { mutableStateOf<CollectionEntry?>(null) }
    var newBinderDialog by remember { mutableStateOf(false) }
    var binderPickerForBulk by remember { mutableStateOf(false) }
    var importReport by remember { mutableStateOf<String?>(null) }
    var pendingCsv by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val csv = pendingCsv
        if (uri != null && csv != null) {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(csv.toByteArray(Charsets.UTF_8))
            }
        }
        pendingCsv = null
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            viewModel.importCsv(text) { imported, failed ->
                importReport = buildString {
                    append("$imported Karten importiert.")
                    if (failed.isNotEmpty()) {
                        append("\nNicht gefunden (${failed.size}): ${failed.take(10).joinToString(", ")}")
                        if (failed.size > 10) append(" …")
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selected.size} ausgewählt") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Auswahl aufheben")
                        }
                    },
                    actions = {
                        TextButton(onClick = { viewModel.selectAllVisible() }) { Text("Alle") }
                        IconButton(onClick = { binderPickerForBulk = true }) {
                            Icon(Icons.Filled.Folder, contentDescription = "In Binder verschieben")
                        }
                        IconButton(onClick = { viewModel.bulkDelete() }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Löschen")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("Meine Sammlung") },
                    actions = {
                        IconButton(onClick = {
                            viewModel.exportCsv { csv ->
                                pendingCsv = csv
                                exportLauncher.launch("cardlens_collection.csv")
                            }
                        }) {
                            Icon(Icons.Outlined.FileUpload, contentDescription = "CSV exportieren")
                        }
                        IconButton(onClick = {
                            importLauncher.launch(
                                arrayOf("text/*", "text/csv", "application/csv", "application/vnd.ms-excel")
                            )
                        }) {
                            Icon(Icons.Outlined.FileDownload, contentDescription = "CSV importieren")
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(Icons.Filled.Sort, contentDescription = "Sortieren")
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                CollectionSort.entries.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(s.label) },
                                        leadingIcon = {
                                            if (s == sort) Icon(Icons.Filled.Check, contentDescription = null)
                                        },
                                        onClick = { viewModel.sort.value = s; sortMenu = false }
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                CollectionTab.entries.forEachIndexed { index, t ->
                    SegmentedButton(
                        selected = tab == t,
                        onClick = { viewModel.tab.value = t; viewModel.clearSelection() },
                        shape = SegmentedButtonDefaults.itemShape(index, CollectionTab.entries.size)
                    ) { Text(t.label) }
                }
            }
            Spacer(Modifier.height(8.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (tab == CollectionTab.COLLECTION) {
                    item(span = { GridItemSpan(2) }) {
                        DashboardHeader(stats, history, currency)
                    }
                }
                item(span = { GridItemSpan(2) }) {
                    Column {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { viewModel.searchQuery.value = it },
                            placeholder = { Text("In Sammlung suchen …") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        GameFilterRow(
                            selected = gameFilter,
                            onSelect = { viewModel.gameFilter.value = it }
                        )
                    }
                }
                if (tab == CollectionTab.COLLECTION) {
                    item(span = { GridItemSpan(2) }) {
                        BinderRow(
                            binders = binders,
                            selected = binderFilter,
                            onSelect = { viewModel.binderFilter.value = it },
                            onCreate = { newBinderDialog = true },
                            onDelete = { viewModel.deleteBinder(it) }
                        )
                    }
                }
                if (rows.isEmpty()) {
                    item(span = { GridItemSpan(2) }) {
                        EmptyState(
                            icon = Icons.Outlined.Style,
                            title = if (tab == CollectionTab.COLLECTION) "Noch keine Karten" else "Wunschliste ist leer",
                            body = if (tab == CollectionTab.COLLECTION)
                                "Scanne Karten oder füge sie über die Suche hinzu."
                            else "Markiere Karten mit dem Herz, um sie hier zu sammeln."
                        )
                    }
                }
                items(rows, key = { it.entry.id }) { row ->
                    CollectionGridItem(
                        row = row,
                        currency = currency,
                        selected = row.entry.id in selected,
                        selectionMode = selectionMode,
                        onClick = {
                            if (selectionMode) viewModel.toggleSelected(row.entry.id)
                            else row.card?.let(onOpenCard)
                        },
                        onLongClick = { viewModel.toggleSelected(row.entry.id) },
                        onEdit = { editEntry = row.entry }
                    )
                }
            }
        }
    }

    // ---- Dialoge / Sheets ------------------------------------------------------

    editEntry?.let { entry ->
        EntryEditSheet(
            entry = entry,
            binders = binders,
            onDismiss = { editEntry = null },
            onSave = { viewModel.updateEntry(it); editEntry = null },
            onDelete = { viewModel.delete(entry); editEntry = null }
        )
    }

    if (newBinderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newBinderDialog = false },
            title = { Text("Neuer Binder") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(enabled = name.isNotBlank(), onClick = {
                    viewModel.createBinder(name)
                    newBinderDialog = false
                }) { Text("Erstellen") }
            },
            dismissButton = {
                TextButton(onClick = { newBinderDialog = false }) { Text("Abbrechen") }
            }
        )
    }

    if (binderPickerForBulk) {
        AlertDialog(
            onDismissRequest = { binderPickerForBulk = false },
            title = { Text("In Binder verschieben") },
            text = {
                Column {
                    TextButton(onClick = {
                        viewModel.bulkMoveToBinder(null); binderPickerForBulk = false
                    }) { Text("Kein Binder") }
                    binders.forEach { binder ->
                        TextButton(onClick = {
                            viewModel.bulkMoveToBinder(binder.id); binderPickerForBulk = false
                        }) { Text(binder.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { binderPickerForBulk = false }) { Text("Abbrechen") }
            }
        )
    }

    importProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("CSV-Import") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(progress)
                }
            },
            confirmButton = {}
        )
    }

    importReport?.let { report ->
        AlertDialog(
            onDismissRequest = { importReport = null },
            title = { Text("Import abgeschlossen") },
            text = { Text(report) },
            confirmButton = { TextButton(onClick = { importReport = null }) { Text("OK") } }
        )
    }
}

/** Dashboard: Gesamtwert, Kartenzahl, Wertverlauf, Spiel-Aufschluesselung. */
@Composable
private fun DashboardHeader(
    stats: CollectionStatistics,
    history: List<ValueSnapshot>,
    currency: String
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Sammlungswert",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                formatPrice(stats.totalValue, currency),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                "${stats.cardCount} Karten · ${stats.uniqueCount} verschiedene",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (history.size >= 2) {
                Spacer(Modifier.height(10.dp))
                ValueSparkline(history, Modifier.fillMaxWidth().height(48.dp))
            }
            if (stats.byGame.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                stats.byGame.forEach { (game, pair) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(game.shortLabel, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${pair.first} · ${formatPrice(pair.second, currency)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    }
}

/** Wertverlauf als einfache Linien-Sparkline. */
@Composable
private fun ValueSparkline(
    history: List<ValueSnapshot>,
    modifier: Modifier = Modifier
) {
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val values = history.map { it.value }
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height * (1f - ((v - min) / span).toFloat()) * 0.9f + size.height * 0.05f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height * (1f - ((v - min) / span).toFloat()) * 0.9f + size.height * 0.05f
            drawCircle(lineColor, radius = 2.5.dp.toPx(), center = Offset(x, y))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BinderRow(
    binders: List<Binder>,
    selected: Long?,
    onSelect: (Long?) -> Unit,
    onCreate: () -> Unit,
    onDelete: (Binder) -> Unit
) {
    var deleteCandidate by remember { mutableStateOf<Binder?>(null) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("Alle") }
            )
        }
        items(binders.size) { i ->
            val binder = binders[i]
            Box(
                Modifier.combinedClickable(
                    onClick = { onSelect(binder.id) },
                    onLongClick = { deleteCandidate = binder }
                )
            ) {
                FilterChip(
                    selected = selected == binder.id,
                    onClick = { onSelect(binder.id) },
                    label = { Text(binder.name) },
                    leadingIcon = {
                        Icon(Icons.Filled.Folder, contentDescription = null, Modifier.size(16.dp))
                    }
                )
            }
        }
        item {
            FilterChip(
                selected = false,
                onClick = onCreate,
                label = { Text("Binder +") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(16.dp)) }
            )
        }
    }
    deleteCandidate?.let { binder ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Binder löschen?") },
            text = { Text("\"${binder.name}\" wird gelöscht. Karten bleiben in der Sammlung.") },
            confirmButton = {
                TextButton(onClick = { onDelete(binder); deleteCandidate = null }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Abbrechen") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionGridItem(
    row: CollectionRow,
    currency: String,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEdit: () -> Unit
) {
    val entry = row.entry
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
            .then(
                if (selected) Modifier.border(
                    2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium
                ) else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp)
    ) {
        Box {
            CardImage(entry.imageUrl, entry.name, Modifier.fillMaxWidth())
            if (entry.quantity > 1) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                ) {
                    Text(
                        "×${entry.quantity}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            if (entry.foil) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                ) {
                    Text(
                        "✦",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            entry.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${CardCondition.fromCode(entry.condition).code} · ${entry.language.uppercase()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                formatPrice(row.totalPrice, currency),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        if (!selectionMode) {
            TextButton(
                onClick = onEdit,
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Bearbeiten", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Detail-Editor fuer einen Sammlungs-Eintrag (Zustand, Sprache, Foil …). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryEditSheet(
    entry: CollectionEntry,
    binders: List<Binder>,
    onDismiss: () -> Unit,
    onSave: (CollectionEntry) -> Unit,
    onDelete: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var condition by remember { mutableStateOf(entry.condition) }
    var language by remember { mutableStateOf(entry.language) }
    var foil by remember { mutableStateOf(entry.foil) }
    var altered by remember { mutableStateOf(entry.altered) }
    var misprint by remember { mutableStateOf(entry.misprint) }
    var quantity by remember { mutableIntStateOf(entry.quantity) }
    var binderId by remember { mutableStateOf(entry.binderId) }
    var purchasePrice by remember { mutableStateOf(entry.purchasePrice?.toString().orEmpty()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(entry.name, style = MaterialTheme.typography.titleLarge)
            entry.setName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Anzahl", Modifier.weight(1f))
                IconButton(onClick = { if (quantity > 1) quantity-- }) {
                    Icon(Icons.Filled.Remove, contentDescription = "Weniger")
                }
                Text("$quantity", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { quantity++ }) {
                    Icon(Icons.Filled.Add, contentDescription = "Mehr")
                }
            }

            Text("Zustand", style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(CardCondition.entries.size) { i ->
                    val c = CardCondition.entries[i]
                    FilterChip(
                        selected = condition == c.code,
                        onClick = { condition = c.code },
                        label = { Text(c.code) }
                    )
                }
            }

            Text("Sprache", style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(CardLanguage.entries.size) { i ->
                    val l = CardLanguage.entries[i]
                    FilterChip(
                        selected = language == l.code,
                        onClick = { language = l.code },
                        label = { Text(l.code.uppercase()) }
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Foil ✦", Modifier.weight(1f))
                Switch(checked = foil, onCheckedChange = { foil = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Altered Art", Modifier.weight(1f))
                Switch(checked = altered, onCheckedChange = { altered = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Fehldruck", Modifier.weight(1f))
                Switch(checked = misprint, onCheckedChange = { misprint = it })
            }

            OutlinedTextField(
                value = purchasePrice,
                onValueChange = { purchasePrice = it },
                label = { Text("Kaufpreis (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (binders.isNotEmpty()) {
                Text("Binder", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = binderId == null,
                            onClick = { binderId = null },
                            label = { Text("Keiner") }
                        )
                    }
                    items(binders.size) { i ->
                        val b = binders[i]
                        FilterChip(
                            selected = binderId == b.id,
                            onClick = { binderId = b.id },
                            label = { Text(b.name) }
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onSave(
                            entry.copy(
                                quantity = quantity,
                                condition = condition,
                                language = language,
                                foil = foil,
                                altered = altered,
                                misprint = misprint,
                                binderId = binderId,
                                purchasePrice = purchasePrice.replace(',', '.').toDoubleOrNull()
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Speichern") }
                TextButton(onClick = onDelete) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
