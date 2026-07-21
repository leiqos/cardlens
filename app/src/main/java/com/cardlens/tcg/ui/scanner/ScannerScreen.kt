package com.cardlens.tcg.ui.scanner

import android.Manifest
import android.content.Context
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.cardlens.tcg.model.TcgCard
import com.cardlens.tcg.model.TcgGame
import com.cardlens.tcg.model.formatPrice
import com.cardlens.tcg.scan.ScanGuide
import com.cardlens.tcg.ui.components.CardImage
import com.cardlens.tcg.ui.components.EmptyState
import com.cardlens.tcg.ui.components.GameFilterRow
import com.cardlens.tcg.ui.components.PriceTag
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private suspend fun Context.cameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(this))
    }

/**
 * Session-first-Scanner: Kamera bildschirmfuellend, erkannte Karten wandern
 * ohne Unterbrechung in den Stapel unten (ManaBox-Prinzip). Das Overlay
 * zeigt live die tatsaechlich erkannten Kartenkanten.
 */
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(onOpenCard: (TcgCard) -> Unit) {
    val app = LocalContext.current.applicationContext as CardLensApp
    val viewModel: ScannerViewModel = viewModel {
        ScannerViewModel(
            app.container.repository,
            app.container.collectionDao,
            app.container.cardJson,
            app.container.cardImageMatcher,
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
                    body = "CardLens erkennt Karten direkt über die Kamera und findet Edition und Marktpreis."
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
    val detectedGame by viewModel.detectedGame.collectAsState()
    val hint by viewModel.hint.collectAsState()
    val gameFilter by viewModel.gameFilter.collectAsState()
    val currency by viewModel.settings.currency.collectAsState()
    val session by viewModel.session.collectAsState()
    val showSessionSheet by viewModel.showSessionSheet.collectAsState()
    val lowLight by viewModel.lowLight.collectAsState()
    val quadOverlay by viewModel.quadOverlay.collectAsState()
    val cardInFrame by viewModel.cardInFrame.collectAsState()

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

    DisposableEffect(Unit) {
        onDispose {
            // Kamera freigeben, sobald der Scanner-Tab verlassen wird. Der
            // Analyzer (OCR, Executor) lebt im ViewModel weiter.
            cameraProvider?.unbindAll()
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
                            // Footer microprint is the primary key. Prefer a
                            // 3 MP analysis stream; CameraX falls back when a
                            // device cannot provide it.
                            Size(1920, 1440),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .build()
        analysis.setAnalyzer(viewModel.analyzer.executor) { proxy ->
            viewModel.analyzer.analyze(proxy, previewView.width, previewView.height)
        }
        provider.unbindAll()
        camera = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis
        )
    }

    // Fokus regelmaessig auf die Kartenmitte ziehen — scharfer Kleindruck
    // ist die Grundlage der Exakt-Erkennung.
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

    // Haptik, wenn der Editions-Waehler aufgeht
    LaunchedEffect(state) {
        if (state is ScanState.ConfirmPick) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // Haptik + gruener Rahmen-Blitz bei jeder Erfassung
    val successFlash = remember { Animatable(0f) }
    var lastCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(session) {
        val count = session.sumOf { it.quantity }
        if (count > lastCount) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            successFlash.snapTo(1f)
            successFlash.animateTo(0f, animationSpec = tween(750))
        }
        lastCount = count
    }

    val accent = MaterialTheme.colorScheme.secondary
    val isScanning = state is ScanState.Scanning
    val isResolving = state is ScanState.Resolving
    val hasLock = detectedLabel != null || isResolving

    // Farbe kommuniziert den Zustand: weiss beim Suchen, Akzent bei Erkennung.
    val bracketColor by animateColorAsState(
        targetValue = if (hasLock) accent else Color.White.copy(alpha = 0.9f),
        animationSpec = tween(350),
        label = "bracketColor"
    )
    // Ruhiges "Atmen" der Ecken, solange keine Karte im Rahmen liegt
    val breath by rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathAlpha"
    )
    val bracketAlpha = if (hasLock || cardInFrame) 1f else breath

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Abdunkelung mit Karten-Ausschnitt, live erkannten Kartenkanten
        // und Ecken-Markierungen. Rahmen-Geometrie = ScanGuide (identisch
        // mit der Analyse — was markiert ist, wird auch analysiert).
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        ) {
            val guide = ScanGuide.rect(size.width, size.height)
            val left = guide[0]
            val top = guide[1]
            val frameW = guide[2] - guide[0]
            val frameH = guide[3] - guide[1]
            val corner = 20.dp.toPx()

            drawRect(Color.Black.copy(alpha = 0.6f))
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(frameW, frameH),
                cornerRadius = CornerRadius(corner),
                blendMode = BlendMode.Clear
            )

            // Live erkannte Kartenkanten — direktes Feedback der Erkennung
            quadOverlay?.let { q ->
                val path = Path().apply {
                    moveTo(q[0] * size.width, q[1] * size.height)
                    lineTo(q[2] * size.width, q[3] * size.height)
                    lineTo(q[4] * size.width, q[5] * size.height)
                    lineTo(q[6] * size.width, q[7] * size.height)
                    close()
                }
                drawPath(
                    path,
                    color = bracketColor.copy(alpha = 0.85f),
                    style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round)
                )
            }

            // Gruener Bestaetigungs-Blitz bei jeder Erfassung
            if (successFlash.value > 0.01f) {
                drawRoundRect(
                    color = Color(0xFF3DDC84).copy(alpha = successFlash.value),
                    topLeft = Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(frameW, frameH),
                    cornerRadius = CornerRadius(corner),
                    style = Stroke(width = 4.dp.toPx())
                )
            }

            // Ecken-Markierungen: atmen im Leerlauf, leuchten bei Erkennung
            val len = 30.dp.toPx()
            val stroke = 4.dp.toPx()
            val r = left + frameW
            val b = top + frameH
            val cap = StrokeCap.Round
            val cornerColor = bracketColor.copy(alpha = bracketColor.alpha * bracketAlpha)
            fun cornerLines(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(cornerColor, Offset(x, y + dy * corner), Offset(x, y + dy * len), stroke, cap)
                drawLine(cornerColor, Offset(x + dx * corner, y), Offset(x + dx * len, y), stroke, cap)
            }
            cornerLines(left, top, 1f, 1f)
            cornerLines(r, top, -1f, 1f)
            cornerLines(left, b, 1f, -1f)
            cornerLines(r, b, -1f, -1f)
        }

        // Spiel-Filter + Taschenlampe oben, auf Verlaufs-Scrim fuer Lesbarkeit
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                    )
                )
                .statusBarsPadding()
                .padding(top = 8.dp, bottom = 24.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GameFilterRow(
                selected = gameFilter,
                onSelect = { viewModel.gameFilter.value = it },
                modifier = Modifier.weight(1f)
            )
            FilledTonalIconButton(onClick = {
                torchOn = !torchOn
                camera?.cameraControl?.enableTorch(torchOn)
            }) {
                Icon(
                    imageVector = if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    contentDescription = "Taschenlampe",
                    tint = if (lowLight && !torchOn) MaterialTheme.colorScheme.tertiary
                    else LocalContentColor.current
                )
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 72.dp),
            shape = RoundedCornerShape(100.dp),
            color = Color.Black.copy(alpha = 0.56f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(7.dp),
                    shape = RoundedCornerShape(100.dp),
                    color = accent
                ) {}
                Spacer(Modifier.width(7.dp))
                Text(
                    "LIVE · SMART SCAN",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
        }

        // Session-Tray unten (ManaBox-Prinzip): Status, letzte Karte, Summe
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 3.dp,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                )
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    val last = session.lastOrNull()
                    // Status-Zeile
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isResolving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                        } else if (last != null) {
                            CardImage(
                                url = last.card.imageSmall ?: last.card.imageLarge,
                                contentDescription = last.card.name,
                                modifier = Modifier
                                    .width(34.dp)
                                    .clickable { onOpenCard(last.card) }
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    isResolving -> "Suche ${(state as ScanState.Resolving).label} …"
                                    detectedLabel != null -> "Erkannt: $detectedLabel"
                                    detectedGame != null -> "${detectedGame?.shortLabel} erkannt …"
                                    cardInFrame -> "Karte im Rahmen – lese …"
                                    else -> "Karte in den Rahmen halten"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = hint
                                    ?: if (lowLight && !torchOn) {
                                        "Wenig Licht – Taschenlampe einschalten."
                                    } else {
                                        scannerInstruction(detectedGame)
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hint != null || (lowLight && !torchOn)) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isResolving) {
                            IconButton(onClick = { viewModel.cancelSearch() }) {
                                Icon(Icons.Filled.Close, contentDescription = "Suche abbrechen")
                            }
                        } else {
                            IconButton(
                                onClick = { viewModel.searchNow() },
                                enabled = detectedLabel != null && isScanning
                            ) {
                                Icon(Icons.Filled.Search, contentDescription = "Jetzt suchen")
                            }
                        }
                    }

                    // Compact session controls: do not grow over the camera
                    // preview as more cards are captured.
                    if (last != null) {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${viewModel.sessionCount()} Karten · " +
                                    formatPrice(viewModel.sessionTotal(currency), currency),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(onClick = { viewModel.undoLast() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Undo,
                                    contentDescription = "Letzte Erfassung zurücknehmen"
                                )
                            }
                            TextButton(onClick = { viewModel.showSessionSheet.value = true }) {
                                Text("Liste")
                            }
                            Button(
                                onClick = { viewModel.commitSession() },
                                enabled = viewModel.canCommitSession()
                            ) {
                                Text(if (viewModel.canCommitSession()) "Speichern" else "Prüfen")
                            }
                        }
                    }
                }
            }
        }
    }

    // Editions-Waehler: unsicherer Treffer → Nutzer tippt die richtige Version
    val confirm = state as? ScanState.ConfirmPick
    if (confirm != null) {
        val confirmSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.resumeScanning() },
            sheetState = confirmSheet
        ) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = "Treffer kurz prüfen",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Text(
                    text = confirm.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Text(
                    text = "Gelesen: ${confirm.label} · ${confirm.cards.size} mögliche Treffer",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
                Spacer(Modifier.height(12.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(confirm.cards, key = { it.id }) { card ->
                        Column(
                            modifier = Modifier
                                .width(150.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { viewModel.addFromConfirm(card) }
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(8.dp)
                        ) {
                            CardImage(
                                url = card.imageSmall ?: card.imageLarge,
                                contentDescription = card.name,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = card.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            card.setName?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = listOfNotNull(
                                    card.game.shortLabel,
                                    card.setCode?.uppercase(),
                                    card.collectorNumber?.let { "#$it" },
                                    card.rarity
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            PriceTag(card, currency)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { viewModel.resumeScanning() },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Keine davon – weiter scannen")
                }
            }
        }
    }

    // Session-Liste als Bottom-Sheet: Mengen, Foil, Zustand, Uebernahme
    if (showSessionSheet) {
        val sessionSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.showSessionSheet.value = false },
            sheetState = sessionSheet
        ) {
            // One scroll container for header, cards and actions. Nested fixed
            // heights made previews and the save button disappear on shorter
            // screens once a session contained several cards.
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Column(Modifier.padding(horizontal = 4.dp)) {
                        Text(
                            text = "Gescannte Karten (${viewModel.sessionCount()})",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Gesamtwert ≈ ${formatPrice(viewModel.sessionTotal(currency), currency)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                itemsIndexed(session) { index, entry ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.shapes.medium
                            )
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CardImage(
                                url = entry.card.imageSmall ?: entry.card.imageLarge,
                                contentDescription = entry.card.name,
                                modifier = Modifier
                                    .width(42.dp)
                                    .clickable { onOpenCard(entry.card) }
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
                                viewModel.setQuantity(index, entry.quantity - 1)
                            }) {
                                Icon(Icons.Filled.Remove, contentDescription = "Weniger")
                            }
                            Text("${entry.quantity}", style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = {
                                viewModel.setQuantity(index, entry.quantity + 1)
                            }) {
                                Icon(Icons.Filled.Add, contentDescription = "Mehr")
                            }
                        }
                        Text(
                            if (entry.finish == null) "Finish bestätigen" else "Finish",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (entry.finish == null) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(finishesFor(entry.card.game)) { (code, label) ->
                                FilterChip(
                                    selected = entry.finish == code,
                                    onClick = { viewModel.setFinish(index, code) },
                                    label = { Text(label) }
                                )
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf("NM", "EX", "GD", "PL")) { code ->
                                FilterChip(
                                    selected = entry.condition == code,
                                    onClick = { viewModel.setCondition(index, code) },
                                    label = { Text(code) }
                                )
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = { viewModel.commitSession() },
                        enabled = viewModel.canCommitSession(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (viewModel.canCommitSession()) {
                                "Alle ${viewModel.sessionCount()} zur Sammlung hinzufügen"
                            } else {
                                "Zuerst Finish jeder Karte bestätigen"
                            }
                        )
                    }
                }
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { viewModel.clearSession() }) {
                            Text("Session verwerfen")
                        }
                    }
                }
            }
        }
    }
}

private fun scannerInstruction(game: TcgGame?): String = when (game) {
    TcgGame.MAGIC -> "Unterkante scharf halten: Setcode · Sammlernummer · Sprache."
    TcgGame.POKEMON -> "Unterkante scharf halten: Setcode + Nummer/Setgröße."
    TcgGame.YUGIOH -> "Set-Code unter dem Artwork und 8-stelligen Passcode lesbar halten."
    TcgGame.ONEPIECE -> "Kartennummer wird gelesen; Parallel-Art wird zusätzlich per Bild geprüft."
    TcgGame.LORCANA -> "Unterkante scharf halten: Nummer · Sprache · Set."
    TcgGame.STARWARS -> "Unterkante scharf halten: Setcode + Variantennummer."
    TcgGame.DRAGONBALL -> "Kartencode und ★-Markierung sichtbar halten."
    TcgGame.RIFTBOUND -> "Unterkante scharf halten: Set + Nummer/Total + Variantensuffix."
    null -> "Setcode, Sammlernummer, Name und Artwork werden gemeinsam geprüft."
}

private fun finishesFor(game: TcgGame): List<Pair<String, String>> = when (game) {
    TcgGame.POKEMON -> listOf(
        "normal" to "Normal",
        "foil" to "Holofoil ✦",
        "reverse_holo" to "Reverse Holo"
    )
    TcgGame.MAGIC -> listOf(
        "normal" to "Normal",
        "foil" to "Foil ✦",
        "etched" to "Etched",
        "textured" to "Textured"
    )
    else -> listOf("normal" to "Normal", "foil" to "Foil ✦", "other" to "Spezial")
}
