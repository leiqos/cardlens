package com.cardlens.tcg.ui.trade

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cardlens.tcg.CardLensApp
import com.cardlens.tcg.data.SettingsStore
import com.cardlens.tcg.model.TcgCard
import com.cardlens.tcg.model.formatPrice
import com.cardlens.tcg.model.variantPrice
import com.cardlens.tcg.ui.components.CardImage
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlin.math.abs

data class TradeItem(val card: TcgCard, val quantity: Int)

class TradeViewModel(val settings: SettingsStore) : ViewModel() {
    val mySide = MutableStateFlow<List<TradeItem>>(emptyList())
    val theirSide = MutableStateFlow<List<TradeItem>>(emptyList())

    fun add(side: MutableStateFlow<List<TradeItem>>, card: TcgCard) {
        val list = side.value
        val existing = list.indexOfFirst { it.card.id == card.id }
        side.value = if (existing >= 0) {
            list.toMutableList().also { it[existing] = it[existing].copy(quantity = it[existing].quantity + 1) }
        } else {
            list + TradeItem(card, 1)
        }
    }

    fun setQuantity(side: MutableStateFlow<List<TradeItem>>, index: Int, quantity: Int) {
        val list = side.value.toMutableList()
        if (index !in list.indices) return
        if (quantity <= 0) list.removeAt(index) else list[index] = list[index].copy(quantity = quantity)
        side.value = list
    }

    fun total(items: List<TradeItem>, currency: String): Double =
        items.sumOf { (it.card.variantPrice(currency, foil = false)?.amount ?: 0.0) * it.quantity }

    fun reset() {
        mySide.value = emptyList()
        theirSide.value = emptyList()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeScreen(onBack: () -> Unit, onOpenCard: (TcgCard) -> Unit) {
    val app = LocalContext.current.applicationContext as CardLensApp
    val viewModel: TradeViewModel = viewModel { TradeViewModel(app.container.settings) }
    val mySide by viewModel.mySide.collectAsState()
    val theirSide by viewModel.theirSide.collectAsState()
    val currency by viewModel.settings.currency.collectAsState()

    var addTarget by remember { mutableStateOf<String?>(null) } // "mine" | "theirs"

    val myTotal = viewModel.total(mySide, currency)
    val theirTotal = viewModel.total(theirSide, currency)
    val delta = theirTotal - myTotal

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trade-Rechner") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.reset() }) { Text("Zurücksetzen") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Fairness-Anzeige
            Surface(
                shape = MaterialTheme.shapes.large,
                color = when {
                    abs(delta) < 0.01 -> MaterialTheme.colorScheme.surfaceVariant
                    delta > 0 -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.errorContainer
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.SwapHoriz, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            when {
                                mySide.isEmpty() && theirSide.isEmpty() -> "Karten auf beiden Seiten hinzufügen"
                                abs(delta) < 0.01 -> "Ausgeglichener Trade"
                                delta > 0 -> "Du erhältst ${formatPrice(delta, currency)} mehr"
                                else -> "Du gibst ${formatPrice(-delta, currency)} mehr"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Ich gebe: ${formatPrice(myTotal, currency)} · Ich erhalte: ${formatPrice(theirTotal, currency)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                TradeColumn(
                    title = "Ich gebe",
                    items = mySide,
                    currency = currency,
                    onAdd = { addTarget = "mine" },
                    onQuantity = { index, qty -> viewModel.setQuantity(viewModel.mySide, index, qty) },
                    onOpenCard = onOpenCard,
                    modifier = Modifier.weight(1f)
                )
                TradeColumn(
                    title = "Ich erhalte",
                    items = theirSide,
                    currency = currency,
                    onAdd = { addTarget = "theirs" },
                    onQuantity = { index, qty -> viewModel.setQuantity(viewModel.theirSide, index, qty) },
                    onOpenCard = onOpenCard,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    addTarget?.let { target ->
        TradeSearchSheet(
            onDismiss = { addTarget = null },
            onAdd = { card ->
                viewModel.add(
                    if (target == "mine") viewModel.mySide else viewModel.theirSide,
                    card
                )
            }
        )
    }
}

@Composable
private fun TradeColumn(
    title: String,
    items: List<TradeItem>,
    currency: String,
    onAdd: () -> Unit,
    onQuantity: (Int, Int) -> Unit,
    onOpenCard: (TcgCard) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "Karte hinzufügen")
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items.size) { index ->
                val item = items[index]
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                        .padding(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CardImage(
                            url = item.card.imageSmall ?: item.card.imageLarge,
                            contentDescription = item.card.name,
                            modifier = Modifier.width(36.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.card.name,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                formatPrice(
                                    (item.card.variantPrice(currency, false)?.amount ?: 0.0) * item.quantity,
                                    currency
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onQuantity(index, item.quantity - 1) }) {
                            Icon(Icons.Filled.Remove, contentDescription = "Weniger", Modifier.size(16.dp))
                        }
                        Text("${item.quantity}", style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = { onQuantity(index, item.quantity + 1) }) {
                            Icon(Icons.Filled.Add, contentDescription = "Mehr", Modifier.size(16.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { onQuantity(index, 0) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Entfernen", Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
private fun TradeSearchSheet(
    onDismiss: () -> Unit,
    onAdd: (TcgCard) -> Unit
) {
    val app = LocalContext.current.applicationContext as CardLensApp
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<TcgCard>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var addedCount by remember { mutableStateOf(0) }
    val queryFlow = remember { MutableStateFlow("") }

    LaunchedEffect(Unit) {
        queryFlow.debounce(500).collect { q ->
            if (q.length < 3) { results = emptyList(); return@collect }
            searching = true
            results = runCatching { app.container.repository.search(q, null) }
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
                modifier = Modifier.heightIn(max = 440.dp),
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
                        OutlinedButton(onClick = { onAdd(card); addedCount++ }) {
                            Text("+")
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) { Text("Fertig") }
        }
    }
}
