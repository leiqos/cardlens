package com.cardlens.tcg.ui.decks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Anzeigefarben je Magic-Farbe / Ink-Farbe. */
fun colorFor(symbol: String): Color = when (symbol.uppercase()) {
    "W" -> Color(0xFFF5EFCE)
    "U" -> Color(0xFF64A8E0)
    "B" -> Color(0xFF6E6478)
    "R" -> Color(0xFFE06A5A)
    "G" -> Color(0xFF5FAE71)
    "C" -> Color(0xFFB9B2AE)
    // Lorcana-Inks
    "AMBER" -> Color(0xFFF2B347)
    "AMETHYST" -> Color(0xFF9D5CC3)
    "EMERALD" -> Color(0xFF3E9C5C)
    "RUBY" -> Color(0xFFD3405B)
    "SAPPHIRE" -> Color(0xFF3A7CC6)
    "STEEL" -> Color(0xFF8D99A6)
    else -> Color(0xFF8D99A6)
}

fun labelFor(symbol: String): String = when (symbol.uppercase()) {
    "W" -> "Weiß"; "U" -> "Blau"; "B" -> "Schwarz"; "R" -> "Rot"; "G" -> "Grün"; "C" -> "Farblos"
    else -> symbol.lowercase().replaceFirstChar(Char::uppercase)
}

/** Mana-Kurve als Balkendiagramm (Bucket 7 = "7+"). */
@Composable
fun ManaCurveChart(curve: Map<Int, Int>, modifier: Modifier = Modifier) {
    val barColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val max = (curve.values.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            val buckets = 8
            val gap = 8.dp.toPx()
            val barW = (size.width - gap * (buckets - 1)) / buckets
            // Hilfslinien
            for (frac in listOf(0.5f, 1f)) {
                val y = size.height * (1f - frac)
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            for (b in 0 until buckets) {
                val count = curve[b] ?: 0
                val h = size.height * count / max
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(b * (barW + gap), size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(4.dp.toPx())
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            for (b in 0..7) {
                Text(
                    text = if (b == 7) "7+" else "$b",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

/** Farbverteilung als Donut-Diagramm mit Legende. */
@Composable
fun ColorPieChart(colorCounts: Map<String, Int>, modifier: Modifier = Modifier) {
    val total = colorCounts.values.sum().coerceAtLeast(1)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(120.dp)) {
            Canvas(Modifier.size(120.dp)) {
                val stroke = Stroke(width = 22.dp.toPx())
                var start = -90f
                for ((symbol, count) in colorCounts) {
                    val sweep = 360f * count / total
                    drawArc(
                        color = colorFor(symbol),
                        startAngle = start,
                        sweepAngle = sweep - 2f,
                        useCenter = false,
                        style = stroke,
                        topLeft = Offset(stroke.width / 2, stroke.width / 2),
                        size = Size(size.width - stroke.width, size.height - stroke.width)
                    )
                    start += sweep
                }
            }
            Text(
                text = "$total",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        Spacer(Modifier.width(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((symbol, count) in colorCounts.entries.sortedByDescending { it.value }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = colorFor(symbol)) {
                        Box(Modifier.size(10.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${labelFor(symbol)} · $count",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/** Kartentyp-Verteilung als horizontale Balken. */
@Composable
fun TypeBreakdown(typeCounts: Map<String, Int>, modifier: Modifier = Modifier) {
    val max = (typeCounts.values.maxOrNull() ?: 0).coerceAtLeast(1)
    val barColor = MaterialTheme.colorScheme.secondary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((type, count) in typeCounts) {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(type, style = MaterialTheme.typography.bodySmall)
                    Text("$count", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(2.dp))
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                ) {
                    drawRoundRect(
                        color = barColor.copy(alpha = 0.2f),
                        cornerRadius = CornerRadius(4.dp.toPx())
                    )
                    drawRoundRect(
                        color = barColor,
                        size = Size(size.width * count / max, size.height),
                        cornerRadius = CornerRadius(4.dp.toPx())
                    )
                }
            }
        }
    }
}

/** Kleine Farbpunkte (Farbidentitaet) fuer Deck-Listen. */
@Composable
fun ColorDots(colors: List<String>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        colors.take(6).forEach { symbol ->
            Surface(
                shape = CircleShape,
                color = colorFor(symbol),
                border = androidx.compose.foundation.BorderStroke(
                    0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                )
            ) {
                Box(Modifier.size(12.dp))
            }
        }
    }
}
