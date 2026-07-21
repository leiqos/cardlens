package com.cardlens.tcg.ui.decks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Splitscreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.cardlens.tcg.model.DeckFormat
import com.cardlens.tcg.model.TcgGame
import com.cardlens.tcg.model.formatPrice
import com.cardlens.tcg.ui.components.CardImage
import com.cardlens.tcg.ui.components.EmptyState
import com.cardlens.tcg.ui.components.GameBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecksScreen(onOpenDeck: (Long) -> Unit) {
    val app = LocalContext.current.applicationContext as CardLensApp
    val viewModel: DecksViewModel = viewModel {
        DecksViewModel(app.container.deckDao, app.container.cardJson, app.container.settings)
    }
    val summaries by viewModel.summaries.collectAsState()
    val currency by viewModel.settings.currency.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<DeckSummary?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "BUILD & PLAY",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("Decks", style = MaterialTheme.typography.headlineSmall)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Neues Deck") }
            )
        }
    ) { padding ->
        if (summaries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.Splitscreen,
                    title = "Noch keine Decks",
                    body = "Erstelle dein erstes Deck und füge Karten aus Suche, Scanner oder Sammlung hinzu."
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(summaries, key = { it.deck.id }) { summary ->
                    DeckRow(
                        summary = summary,
                        currency = currency,
                        onClick = { onOpenDeck(summary.deck.id) },
                        onDelete = { deleteCandidate = summary }
                    )
                }
            }
        }
    }

    if (showCreate) {
        CreateDeckDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, game, format ->
                showCreate = false
                viewModel.createDeck(name, game, format) { onOpenDeck(it) }
            }
        )
    }

    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Deck löschen?") },
            text = { Text("\"${candidate.deck.name}\" mit ${candidate.cardCount} Karten wird endgültig gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDeck(candidate.deck.id)
                    deleteCandidate = null
                }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Abbrechen") }
            }
        )
    }
}

@Composable
private fun DeckRow(
    summary: DeckSummary,
    currency: String,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val format = DeckFormat.fromName(summary.deck.format)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                MaterialTheme.shapes.large
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CardImage(
            url = summary.coverImage,
            contentDescription = summary.deck.name,
            modifier = Modifier.width(52.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                summary.deck.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameBadge(TcgGame.valueOf(summary.deck.game))
                Spacer(Modifier.width(6.dp))
                Text(
                    format.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorDots(summary.colors)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${summary.cardCount} Karten · ≈ ${formatPrice(summary.value, currency)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Deck löschen",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CreateDeckDialog(
    onDismiss: () -> Unit,
    onCreate: (String, TcgGame, DeckFormat) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var game by remember { mutableStateOf(TcgGame.MAGIC) }
    var format by remember { mutableStateOf(DeckFormat.COMMANDER) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neues Deck") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Deck-Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("Spiel", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(TcgGame.entries.size) { i ->
                        val g = TcgGame.entries[i]
                        FilterChip(
                            selected = game == g,
                            onClick = {
                                game = g
                                format = DeckFormat.forGame(g).first()
                            },
                            label = { Text(g.shortLabel) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Format", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val formats = DeckFormat.forGame(game)
                    items(formats.size) { i ->
                        val f = formats[i]
                        FilterChip(
                            selected = format == f,
                            onClick = { format = f },
                            label = { Text(f.label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name.ifBlank { "Neues Deck" }, game, format) },
                enabled = name.isNotBlank()
            ) { Text("Erstellen") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}
