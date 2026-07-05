package com.cardlens.tcg.ui.scanner

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cardlens.tcg.CardLensApp
import com.cardlens.tcg.model.CardIdentifierDetector
import com.cardlens.tcg.model.TcgCard
import com.cardlens.tcg.model.formatPrice
import com.cardlens.tcg.ui.components.CardGridItem
import com.cardlens.tcg.ui.components.CardImage
import com.cardlens.tcg.ui.components.EmptyState
import com.cardlens.tcg.ui.components.GameFilterRow
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private suspend fun Context.cameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(this))
    }

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(onOpenCard: (TcgCard) -> Unit) {
    val app = LocalContext.current.applicationContext as CardLensApp
    val viewModel: ScannerViewModel = viewModel {
        ScannerViewModel(
            app.container.repository,
            app.container.collectionDao,
            app.container.cardJson,
            app.container.settings
        )
    }
    val permission = rememberPermissionState(Manifest.permission.CAMERA)

    if (!permission.status.isGranted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(
                    icon = Icons.Filled.CameraAlt,
                    title = "Kamerazugriff benötigt",
                    body = "CardLens liest den Kartennamen direkt über die Kamera und findet die Karte mit aktuellem Marktpreis."
                )
                Button(onClick = { permission.launchPermissionRequest() }) {
                    Text("Kamera erlauben")
                }
            }
        }
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val state by viewModel.state.collectAsState()
    val detectedLabel by viewModel.detectedLabel.collectAsState()
    val hint by viewModel.hint.collectAsState()
    val gameFilter by viewModel.gameFilter.collectAsState()
    val currency by viewModel.settings.currency.collectAsState()
    val batchMode by viewModel.batchMode.collectAsState()
    val batch by viewModel.batch.collectAsState()
    val showBatchReview by viewModel.showBatchReview.collectAsState()

    val previewView = remember {
        PreviewView(context).apply {
            // COMPATIBLE (TextureView) statt SurfaceView: verhindert die schwarze
            // Vorschau, die SurfaceView in Compose-Layouts auf vielen Geräten zeigt.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var torchOn by remember { mutableStateOf(false) }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val analysisBusy = remember { AtomicBoolean(false) }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    DisposableEffect(Unit) {
        onDispose {
            // Kamera freigeben, sobald der Scanner-Tab verlassen wird —
            // bindToLifecycle allein wuerde sie bis zum Activity-Stop weiterlaufen lassen.
            cameraProvider?.unbindAll()
            analysisExecutor.shutdown()
            recognizer.close()
        }
    }

    LaunchedEffect(Unit) {
        val provider = context.cameraProvider()
        cameraProvider = provider
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // Hoehere Analyse-Aufloesung = deutlich zuverlaessigere Texterkennung.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 960),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .build()
        analysis.setAnalyzer(analysisExecutor) { proxy ->
            // Eigenes Throttling: naechstes Bild erst, wenn beide OCR-Paesse durch sind.
            if (!analysisBusy.compareAndSet(false, true)) {
                proxy.close()
                return@setAnalyzer
            }
            val rotation = proxy.imageInfo.rotationDegrees
            val raw = proxy.toBitmap()
            proxy.close()
            val upright = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            } else {
                raw
            }

            // Pass 1: Vollbild — Kartenname + gut lesbare Kennungen
            recognizer.process(InputImage.fromBitmap(upright, 0))
                .addOnSuccessListener { text ->
                    viewModel.onFrame(
                        CardNameExtractor.extract(text, upright.width, upright.height)
                    )
                }
                .addOnCompleteListener {
                    if (viewModel.needsIdentifierBoost()) {
                        // Pass 2 (Ecken-Zoom): unteres Drittel 2x hochskaliert —
                        // dort stehen die klein gedruckten Druck-Kennungen.
                        val top = (upright.height * 0.60f).toInt()
                        val band = Bitmap.createBitmap(
                            upright, 0, top, upright.width, upright.height - top
                        )
                        val zoomed = Bitmap.createScaledBitmap(
                            band, band.width * 2, band.height * 2, true
                        )
                        recognizer.process(InputImage.fromBitmap(zoomed, 0))
                            .addOnSuccessListener { bandText ->
                                viewModel.offerIdentifiers(
                                    CardIdentifierDetector.detect(
                                        bandText.textBlocks.flatMap { it.lines }.map { it.text }
                                    )
                                )
                            }
                            .addOnCompleteListener { analysisBusy.set(false) }
                    } else {
                        analysisBusy.set(false)
                    }
                }
        }
        provider.unbindAll()
        camera = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis
        )
    }

    // Fokus regelmaessig auf die Kartenmitte ziehen — scharfe Kennungen
    // sind die Grundlage fuer die Exakt-Erkennung.
    LaunchedEffect(camera) {
        val cam = camera ?: return@LaunchedEffect
        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        val center = factory.createPoint(0.5f, 0.45f)
        while (isActive) {
            runCatching {
                cam.cameraControl.startFocusAndMetering(
                    FocusMeteringAction.Builder(center, FocusMeteringAction.FLAG_AF).build()
                )
            }
            delay(2500)
        }
    }

    // Vibration, sobald Treffer da sind
    LaunchedEffect(state) {
        if (state is ScanState.Results) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // Vibration bei jeder Stapel-Erfassung
    var lastBatchCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(batch) {
        val count = batch.sumOf { it.quantity }
        if (count > lastBatchCount) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        lastBatchCount = count
    }

    val accent = MaterialTheme.colorScheme.secondary
    val isScanning = state is ScanState.Scanning
    val scanLine by rememberInfiniteTransition(label = "scan")
        .animateFloat(
            initialValue = 0.08f,
            targetValue = 0.92f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scanLine"
        )

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Abdunkelung mit Karten-Ausschnitt, Ecken-Markierungen und Scanlinie
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        ) {
            val frameW = size.width * 0.74f
            val frameH = frameW * 88f / 63f
            val left = (size.width - frameW) / 2f
            val top = ((size.height - frameH) / 2f - size.height * 0.05f).coerceAtLeast(0f)
            val corner = 20.dp.toPx()

            drawRect(Color.Black.copy(alpha = 0.55f))
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(frameW, frameH),
                cornerRadius = CornerRadius(corner),
                blendMode = BlendMode.Clear
            )

            // Ecken-Markierungen
            val len = 30.dp.toPx()
            val stroke = 4.dp.toPx()
            val r = left + frameW
            val b = top + frameH
            val cap = StrokeCap.Round
            fun cornerLines(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(accent, Offset(x, y + dy * corner), Offset(x, y + dy * len), stroke, cap)
                drawLine(accent, Offset(x + dx * corner, y), Offset(x + dx * len, y), stroke, cap)
            }
            cornerLines(left, top, 1f, 1f)
            cornerLines(r, top, -1f, 1f)
            cornerLines(left, b, 1f, -1f)
            cornerLines(r, b, -1f, -1f)

            // Scanlinie, solange gescannt wird
            if (isScanning) {
                val y = top + frameH * scanLine
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, accent.copy(alpha = 0.85f), Color.Transparent)
                    ),
                    start = Offset(left + 10.dp.toPx(), y),
                    end = Offset(left + frameW - 10.dp.toPx(), y),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }

        // Spiel-Filter oben, auf Verlaufs-Scrim fuer Lesbarkeit
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                    )
                )
                .statusBarsPadding()
                .padding(top = 8.dp, bottom = 24.dp)
        ) {
            GameFilterRow(
                selected = gameFilter,
                onSelect = { viewModel.gameFilter.value = it }
            )
        }

        // Statuskarte unten
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 3.dp
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = when (val s = state) {
                                is ScanState.Searching -> "Suche \"${s.label}\" …"
                                else -> detectedLabel?.let { "Erkannt: $it" }
                                    ?: "Karte in den Rahmen halten"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = hint
                                ?: "Kennung oder Name wird automatisch erkannt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hint != null) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalIconButton(onClick = {
                        torchOn = !torchOn
                        camera?.cameraControl?.enableTorch(torchOn)
                    }) {
                        Icon(
                            imageVector = if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = "Taschenlampe"
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = batchMode,
                        onClick = { viewModel.batchMode.value = !batchMode },
                        label = { Text("Stapel-Scan") },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Layers,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                    Spacer(Modifier.width(10.dp))
                    if (batchMode && batch.isNotEmpty()) {
                        Text(
                            text = "${viewModel.batchCount()} Karten · ≈ " +
                                formatPrice(viewModel.batchTotal(currency), currency),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = { viewModel.showBatchReview.value = true }) {
                            Text("Prüfen")
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (state is ScanState.Searching) {
                    OutlinedButton(
                        onClick = { viewModel.cancelSearch() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Abbrechen")
                    }
                } else {
                    Button(
                        onClick = { viewModel.searchNow() },
                        enabled = detectedLabel != null && isScanning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Jetzt suchen")
                    }
                }
            }
        }
    }

    // Stapel-Review als Bottom-Sheet
    if (showBatchReview) {
        val reviewSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.showBatchReview.value = false },
            sheetState = reviewSheet
        ) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = "Gescannte Karten (${viewModel.batchCount()})",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Text(
                    text = "Gesamtwert ≈ ${formatPrice(viewModel.batchTotal(currency), currency)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(batch) { index, entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.shapes.medium
                                )
                                .padding(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CardImage(
                                    url = entry.card.imageSmall ?: entry.card.imageLarge,
                                    contentDescription = entry.card.name,
                                    modifier = Modifier.width(42.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.card.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    entry.card.setName?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                IconButton(onClick = {
                                    viewModel.setBatchQuantity(index, entry.quantity - 1)
                                }) {
                                    Icon(Icons.Filled.Remove, contentDescription = "Weniger")
                                }
                                Text("${entry.quantity}", style = MaterialTheme.typography.titleSmall)
                                IconButton(onClick = {
                                    viewModel.setBatchQuantity(index, entry.quantity + 1)
                                }) {
                                    Icon(Icons.Filled.Add, contentDescription = "Mehr")
                                }
                            }
                            // Varianten-Schnelleinstellung: Foil + Zustand
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = entry.foil,
                                    onClick = { viewModel.toggleBatchFoil(index) },
                                    label = { Text("Foil ✦") }
                                )
                                listOf("NM", "EX", "GD", "PL").forEach { code ->
                                    FilterChip(
                                        selected = entry.condition == code,
                                        onClick = { viewModel.setBatchCondition(index, code) },
                                        label = { Text(code) }
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.commitBatch() },
                    enabled = batch.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                ) {
                    Text("Alle ${viewModel.batchCount()} zur Sammlung hinzufügen")
                }
                TextButton(
                    onClick = { viewModel.clearBatch() },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Session verwerfen")
                }
            }
        }
    }

    // Treffer als Bottom-Sheet
    val results = state as? ScanState.Results
    if (results != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.resumeScanning() },
            sheetState = sheetState
        ) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = "Treffer für \"${results.label}\"",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Text(
                    text = "${results.cards.size} Karten gefunden – tippe für Details & Preise",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.heightIn(max = 460.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(results.cards, key = { it.id }) { card ->
                        CardGridItem(
                            card = card,
                            currency = currency,
                            onClick = { onOpenCard(card) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.resumeScanning() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                ) {
                    Text("Weiter scannen")
                }
            }
        }
    }
}
