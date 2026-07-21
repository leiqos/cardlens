package com.cardlens.tcg.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cardlens.tcg.CardLensApp
import com.cardlens.tcg.data.ThemeMode
import com.cardlens.tcg.model.CardCondition
import com.cardlens.tcg.model.CardLanguage
import com.cardlens.tcg.ui.components.ScreenHeader

private data class DataSource(val name: String, val games: String, val url: String)

private val dataSources = listOf(
    DataSource("Scryfall", "Magic: The Gathering", "https://scryfall.com"),
    DataSource("Pokémon TCG API", "Pokémon", "https://pokemontcg.io"),
    DataSource("YGOPRODeck", "Yu-Gi-Oh!", "https://ygoprodeck.com"),
    DataSource("OPTCG API", "One Piece Card Game", "https://optcgapi.com"),
    DataSource("Lorcast", "Disney Lorcana", "https://lorcast.com"),
    DataSource("SWU-DB", "Star Wars: Unlimited", "https://www.swu-db.com"),
    DataSource("apitcg.com", "Dragon Ball Fusion World (Key nötig)", "https://apitcg.com"),
    DataSource("RiftScribe", "Riftbound (LoL)", "https://riftscribe.gg")
)

@Composable
fun SettingsScreen(onOpenTrade: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as CardLensApp
    val settings = app.container.settings
    val currency by settings.currency.collectAsState()
    val themeMode by settings.themeMode.collectAsState()
    val dynamicColor by settings.dynamicColor.collectAsState()
    val defaultCondition by settings.defaultCondition.collectAsState()
    val defaultLanguage by settings.defaultLanguage.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ScreenHeader(
            eyebrow = "CardLens",
            title = "Mehr",
            subtitle = "Werkzeuge und persönliche Standards"
        )

        // Werkzeuge
        SectionCard(title = "Werkzeuge") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenTrade)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Trade-Rechner", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Zwei Kartenstapel vergleichen — fair tauschen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Darstellung
        SectionCard(title = "Darstellung") {
            Text("Design", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { settings.setThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)
                    ) {
                        Text(
                            when (mode) {
                                ThemeMode.SYSTEM -> "System"
                                ThemeMode.DARK -> "Dunkel"
                                ThemeMode.LIGHT -> "Hell"
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Material You", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Systemfarben statt Markenfarben (Android 12+)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = dynamicColor, onCheckedChange = { settings.setDynamicColor(it) })
            }
        }

        // Preise
        SectionCard(title = "Preise") {
            Text("Bevorzugte Währung", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("EUR" to "€ Cardmarket", "USD" to "$ TCGplayer").forEachIndexed { index, (code, label) ->
                    SegmentedButton(
                        selected = currency == code,
                        onClick = { settings.setCurrency(code) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2)
                    ) { Text(label) }
                }
            }
        }

        // Scan-Standards
        SectionCard(title = "Standard beim Erfassen") {
            Text("Zustand neuer Karten", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(CardCondition.entries.size) { i ->
                    val c = CardCondition.entries[i]
                    FilterChip(
                        selected = defaultCondition == c.code,
                        onClick = { settings.setDefaultCondition(c.code) },
                        label = { Text(c.code) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Sprache neuer Karten", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(CardLanguage.entries.size) { i ->
                    val l = CardLanguage.entries[i]
                    FilterChip(
                        selected = defaultLanguage == l.code,
                        onClick = { settings.setDefaultLanguage(l.code) },
                        label = { Text(l.code.uppercase()) }
                    )
                }
            }
        }

        // Datenquellen
        SectionCard(title = "Datenquellen") {
            dataSources.forEachIndexed { index, source ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url)))
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(source.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            source.games,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Text(
            "CardLens 3.0.1 · Alle Kartennamen und -bilder gehören den jeweiligen Rechteinhabern. " +
                "Preise sind Marktdurchschnitte der jeweiligen Quelle.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
