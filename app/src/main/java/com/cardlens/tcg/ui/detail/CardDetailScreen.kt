package com.cardlens.tcg.ui.detail

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cardlens.tcg.CardLensApp
import com.cardlens.tcg.data.CardRepository
import com.cardlens.tcg.data.SettingsStore
import com.cardlens.tcg.data.local.CollectionDao
import com.cardlens.tcg.data.local.CollectionEntry
import com.cardlens.tcg.data.local.Deck
import com.cardlens.tcg.data.local.DeckCard
import com.cardlens.tcg.data.local.DeckDao
import com.cardlens.tcg.data.local.Favorite
import com.cardlens.tcg.data.local.FavoriteDao
import com.cardlens.tcg.model.TcgCard
import com.cardlens.tcg.model.TcgGame
import com.cardlens.tcg.model.formatPrice
import com.cardlens.tcg.model.primaryPrice
import com.cardlens.tcg.ui.components.CardImage
import com.cardlens.tcg.ui.components.EmptyState
import com.cardlens.tcg.ui.components.GameBadge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class DetailViewModel(
    private val repository: CardRepository,
    private val dao: CollectionDao,
    private val deckDao: DeckDao,
    private val favoriteDao: FavoriteDao,
    private val json: Json,
    val settings: SettingsStore,
    private val cardId: String,
    initialCard: TcgCard?
) : ViewModel() {

    /** Aktuell angezeigter Druck — wechselbar ueber die Editions-Auswahl. */
    val card = MutableStateFlow(initialCard)
    val restoring = MutableStateFlow(initialCard == null)

    /** Alle Drucke derselben Karte (aktuell fuer Magic & Yu-Gi-Oh!). */
    val printings = MutableStateFlow<List<TcgCard>>(emptyList())

    /** Offizielle Rulings (nur Magic). */
    val rulings = MutableStateFlow<List<Pair<String, String>>>(emptyList())

    val decks: kotlinx.coroutines.flow.StateFlow<List<Deck>> = deckDao.observeDecks()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val entries = card
        .flatMapLatest { c -> dao.observeCard(c?.id ?: "") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val collectionQuantity = entries
        .map { list -> list.filter { !it.wishlist }.sumOf { it.quantity } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val onWishlist = entries
        .map { list -> list.any { it.wishlist } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val isFavorite = card
        .flatMapLatest { c -> favoriteDao.observeIsFavorite(c?.id ?: "") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        if (initialCard != null) {
            loadCardExtras(initialCard)
        } else {
            // Navigation stores only the stable card ID. If Android restores a
            // detail route after process death, rebuild the card from the full
            // offline snapshot saved with collection/wishlist entries.
            viewModelScope.launch {
                val entry = dao.byCard(cardId, wishlist = false).firstOrNull()
                    ?: dao.byCard(cardId, wishlist = true).firstOrNull()
                val savedJson = entry?.cardJson
                    ?: deckDao.usagesOf(cardId).firstOrNull()?.cardJson
                val restored = savedJson?.let { raw ->
                    runCatching { json.decodeFromString(TcgCard.serializer(), raw) }.getOrNull()
                }
                if (restored != null) {
                    repository.remember(restored)
                    card.value = restored
                    loadCardExtras(restored)
                }
                restoring.value = false
            }
        }
    }

    private fun loadCardExtras(c: TcgCard) {
        when (c.game) {
            TcgGame.MAGIC -> viewModelScope.launch {
                printings.value = runCatching { repository.magicPrintings(c.name) }
                    .getOrDefault(emptyList())
                rulings.value = runCatching { repository.magicRulings(c.apiId) }
                    .getOrDefault(emptyList())
            }
            TcgGame.YUGIOH -> viewModelScope.launch {
                val passcode = c.id.removePrefix("YUGIOH:").substringBefore(":")
                printings.value = runCatching { repository.ygoPrintings(passcode) }
                    .getOrDefault(emptyList())
            }
            TcgGame.RIFTBOUND -> viewModelScope.launch {
                // Suchtreffer sind nur Typeahead — vollstaendige Karte nachladen.
                runCatching { repository.riftboundDetail(c.apiId) }.getOrNull()
                    ?.let { card.value = it }
            }
            else -> Unit
        }
    }

    fun selectPrinting(printing: TcgCard) {
        repository.remember(printing)
        card.value = printing
    }

    /** Menge in der Sammlung anpassen (wirkt auf den ersten Eintrag der Karte). */
    fun changeCollectionQuantity(delta: Int) {
        val c = card.value ?: return
        viewModelScope.launch {
            val owned = dao.byCard(c.id, wishlist = false)
            val first = owned.firstOrNull()
            when {
                first == null && delta > 0 -> dao.upsert(newEntry(c, wishlist = false, quantity = delta))
                first != null -> {
                    val newQty = first.quantity + delta
                    if (newQty <= 0) dao.delete(first.id)
                    else dao.update(first.copy(quantity = newQty))
                }
            }
        }
    }

    fun toggleWishlist() {
        val c = card.value ?: return
        viewModelScope.launch {
            val wished = dao.byCard(c.id, wishlist = true)
            if (wished.isNotEmpty()) {
                dao.deleteAll(wished.map { it.id })
            } else {
                dao.upsert(newEntry(c, wishlist = true, quantity = 1))
            }
        }
    }

    fun toggleFavorite() {
        val c = card.value ?: return
        viewModelScope.launch {
            if (isFavorite.value) {
                favoriteDao.remove(c.id)
            } else {
                favoriteDao.add(
                    Favorite(
                        cardId = c.id,
                        name = c.name,
                        game = c.game.name,
                        imageUrl = c.imageSmall ?: c.imageLarge,
                        cardJson = json.encodeToString(TcgCard.serializer(), c)
                    )
                )
            }
        }
    }

    fun addToDeck(deck: Deck) {
        val c = card.value ?: return
        viewModelScope.launch {
            val existing = deckDao.findCard(deck.id, c.id, "MAIN")
            deckDao.upsertCard(
                existing?.copy(quantity = existing.quantity + 1)
                    ?: DeckCard(
                        deckId = deck.id,
                        cardId = c.id,
                        name = c.name,
                        quantity = 1,
                        board = "MAIN",
                        imageUrl = c.imageSmall ?: c.imageLarge,
                        cardJson = json.encodeToString(TcgCard.serializer(), c)
                    )
            )
            deckDao.touch(deck.id)
        }
    }

    private fun newEntry(card: TcgCard, wishlist: Boolean, quantity: Int) = CollectionEntry(
        cardId = card.id,
        wishlist = wishlist,
        quantity = quantity,
        name = card.name,
        setName = card.setName,
        setCode = card.setCode,
        collectorNumber = card.collectorNumber,
        rarity = card.rarity,
        game = card.game.name,
        imageUrl = card.imageSmall ?: card.imageLarge,
        cardJson = json.encodeToString(TcgCard.serializer(), card),
        addedAt = System.currentTimeMillis(),
        condition = settings.defaultCondition.value,
        language = settings.defaultLanguage.value
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailScreen(cardId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as CardLensApp
    val viewModel: DetailViewModel = viewModel(key = cardId) {
        DetailViewModel(
            app.container.repository,
            app.container.collectionDao,
            app.container.deckDao,
            app.container.favoriteDao,
            app.container.cardJson,
            app.container.settings,
            cardId,
            app.container.repository.cached(cardId)
        )
    }
    val card by viewModel.card.collectAsState()
    val restoring by viewModel.restoring.collectAsState()
    var showDeckPicker by remember { mutableStateOf(false) }

    fun openUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(card?.name ?: "Karte", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    card?.let { c ->
                        val isFavorite by viewModel.isFavorite.collectAsState()
                        IconButton(onClick = { viewModel.toggleFavorite() }) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = "Favorit",
                                tint = if (isFavorite) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            val text = buildString {
                                append("${c.name} (${c.game.label})")
                                c.setName?.let { append(" – $it") }
                                c.externalUrl?.let { append("\n$it") }
                            }
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(intent, "Karte teilen"))
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = "Teilen")
                        }
                        c.externalUrl?.let { url ->
                            IconButton(onClick = { openUrl(url) }) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Im Browser öffnen")
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        val c = card
        if (c == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (restoring) {
                    CircularProgressIndicator()
                } else {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.HelpOutline,
                        title = "Karte nicht gefunden",
                        body = "Der gespeicherte Karteneintrag ist unvollständig."
                    )
                }
            }
            return@Scaffold
        }

        val quantity by viewModel.collectionQuantity.collectAsState()
        val onWishlist by viewModel.onWishlist.collectAsState()
        val printings by viewModel.printings.collectAsState()
        val rulings by viewModel.rulings.collectAsState()
        val decks by viewModel.decks.collectAsState()
        val currency by viewModel.settings.currency.collectAsState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Hero-Bereich: Kartenbild auf Farbverlauf mit Schattenwurf
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.verticalGradient(
                            0f to MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                            1f to MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.08f)
                        )
                    )
                    .padding(vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                CardImage(
                    url = c.imageLarge ?: c.imageSmall,
                    contentDescription = c.name,
                    modifier = Modifier
                        .fillMaxWidth(0.62f)
                        .shadow(16.dp, RoundedCornerShape(10.dp))
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(c.name, style = MaterialTheme.typography.headlineSmall)
            c.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GameBadge(c.game)
                c.setName?.let {
                    AssistChip(onClick = {}, label = { Text(it, maxLines = 1) })
                }
            }

            // Editions-Auswahl (alle Drucke derselben Karte)
            if (printings.size > 1) {
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "Editionen (${printings.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Tippe eine Edition an, um deren Preis zu sehen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(printings, key = { it.id }) { printing ->
                            val selected = printing.id == c.id
                            Column(
                                modifier = Modifier
                                    .width(116.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .then(
                                        if (selected) Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.primary,
                                            RoundedCornerShape(12.dp)
                                        ) else Modifier
                                    )
                                    .clickable { viewModel.selectPrinting(printing) }
                                    .padding(8.dp)
                            ) {
                                CardImage(
                                    url = printing.imageSmall ?: printing.imageLarge,
                                    contentDescription = printing.setName,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    printing.setName ?: "–",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                printing.rarity?.let { rarity ->
                                    Text(
                                        rarity,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                // Auflagen-spezifischer Preis vor dem Karten-Durchschnitt
                                val price = printing.prices
                                    .firstOrNull { it.label.startsWith("Auflage") }
                                    ?: printing.primaryPrice(currency)
                                Text(
                                    price?.let { formatPrice(it.amount, it.currency) } ?: "kein Preis",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            }

            // Sammlung, Wunschliste & Decks
            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("In Sammlung", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (quantity > 0) "$quantity× vorhanden" else "Noch nicht in der Sammlung",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (quantity > 0) {
                        IconButton(onClick = { viewModel.changeCollectionQuantity(-1) }) {
                            Icon(Icons.Filled.Remove, contentDescription = "Weniger")
                        }
                        Text("$quantity", style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { viewModel.changeCollectionQuantity(1) }) {
                            Icon(Icons.Filled.Add, contentDescription = "Mehr")
                        }
                    } else {
                        Button(onClick = {
                            viewModel.changeCollectionQuantity(1)
                            Toast.makeText(context, "Zur Sammlung hinzugefügt", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Hinzufügen")
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Wunschliste", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (onWishlist) "Auf der Wunschliste" else "Nicht auf der Wunschliste",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = {
                        val adding = !onWishlist
                        viewModel.toggleWishlist()
                        Toast.makeText(
                            context,
                            if (adding) "Auf die Wunschliste gesetzt" else "Von der Wunschliste entfernt",
                            Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Icon(
                            imageVector = if (onWishlist) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (onWishlist) "Entfernen" else "Merken")
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Deck", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Karte einem Deck hinzufügen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = { showDeckPicker = true }) {
                        Text("Zu Deck …")
                    }
                }
            }

            // Legalitaeten (Magic)
            if (c.legalities.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                SectionCard(title = "Format-Legalität") {
                    val interesting = listOf(
                        "standard", "pioneer", "modern", "legacy", "vintage", "commander", "pauper"
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            interesting.chunked(2).forEach { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    pair.forEach { format ->
                                        val status = c.legalities[format] ?: "unknown"
                                        val legal = status == "legal" || status == "restricted"
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (legal)
                                                MaterialTheme.colorScheme.secondaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                "${format.replaceFirstChar(Char::uppercase)}: " +
                                                    when (status) {
                                                        "legal" -> "✓"
                                                        "restricted" -> "1×"
                                                        "banned" -> "✕"
                                                        else -> "–"
                                                    },
                                                style = MaterialTheme.typography.labelMedium,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Preise — Zeilen mit Link zum Anbieter
            Spacer(Modifier.height(16.dp))
            SectionCard(title = "Aktuelle Marktpreise") {
                if (c.prices.isEmpty()) {
                    Text(
                        "Für diese Karte liegen keine Preisdaten vor.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    c.prices.forEachIndexed { index, price ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = price.url != null) {
                                    price.url?.let(::openUrl)
                                }
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(price.source, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (price.url != null) "${price.label} · antippen zum Prüfen"
                                    else price.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    formatPrice(price.amount, price.currency),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                if (price.url != null) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = "Beim Anbieter öffnen",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Karteninfos
            if (c.facts.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                SectionCard(title = "Karteninfos") {
                    c.facts.forEachIndexed { index, fact ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                fact.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(fact.value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // Kartentext
            c.text?.let { text ->
                Spacer(Modifier.height(16.dp))
                SectionCard(title = "Kartentext") {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic
                    )
                }
            }

            // Rulings (Magic)
            if (rulings.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                SectionCard(title = "Rulings (${rulings.size})") {
                    rulings.take(12).forEachIndexed { index, (date, comment) ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Column {
                            if (date.isNotBlank()) {
                                Text(
                                    date,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(comment, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Preise sind Marktdurchschnitte der jeweiligen Quelle und können abweichen.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }

        if (showDeckPicker) {
            AlertDialog(
                onDismissRequest = { showDeckPicker = false },
                title = { Text("Zu Deck hinzufügen") },
                text = {
                    if (decks.isEmpty()) {
                        Text("Noch keine Decks vorhanden. Erstelle zuerst ein Deck im Decks-Tab.")
                    } else {
                        Column {
                            decks.forEach { deck ->
                                TextButton(onClick = {
                                    viewModel.addToDeck(deck)
                                    showDeckPicker = false
                                    Toast.makeText(context, "Zu \"${deck.name}\" hinzugefügt", Toast.LENGTH_SHORT).show()
                                }) { Text(deck.name) }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showDeckPicker = false }) { Text("Abbrechen") }
                }
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
