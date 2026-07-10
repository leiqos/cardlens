package com.cardlens.tcg.ui.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cardlens.tcg.data.CardRepository
import com.cardlens.tcg.data.SettingsStore
import com.cardlens.tcg.data.local.CollectionDao
import com.cardlens.tcg.data.local.CollectionEntry
import com.cardlens.tcg.model.CardIdentifier
import com.cardlens.tcg.model.TcgCard
import com.cardlens.tcg.model.TcgGame
import com.cardlens.tcg.model.game
import com.cardlens.tcg.model.isStrong
import com.cardlens.tcg.model.primaryPrice
import com.cardlens.tcg.scan.CardImageMatcher
import com.cardlens.tcg.scan.PerceptualHash
import com.cardlens.tcg.scan.ScanAnalyzer
import com.cardlens.tcg.scan.ScanReading
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

sealed interface ScanState {
    /** Kamera laeuft, Pipeline sammelt Evidenz. */
    data object Scanning : ScanState

    /** Kennung/Name wird gerade aufgeloest — nicht blockierend. */
    data class Resolving(val label: String) : ScanState

    /**
     * Treffer ist mehrdeutig (Signale widersprechen sich oder mehrere
     * Editionen gleichauf) — der Nutzer waehlt die richtige Version.
     */
    data class ConfirmPick(val label: String, val cards: List<TcgCard>) : ScanState
}

/** Eintrag der Scan-Session (mit Varianten-Merkmalen). */
data class SessionEntry(
    val card: TcgCard,
    val quantity: Int,
    val foil: Boolean = false,
    val condition: String = "NM",
    val language: String = "en"
)

/**
 * Session-first-Scanner nach ManaBox-Vorbild: jede sicher erkannte Karte
 * landet sofort im Session-Stapel (Haptik + Blitz), ohne den Scan-Fluss zu
 * unterbrechen. Korrekturen (Menge, Foil, Zustand, Edition) passieren im
 * Stapel — nicht in blockierenden Dialogen. Erst "Übernehmen" schreibt die
 * Session in die Sammlung.
 */
class ScannerViewModel(
    private val repository: CardRepository,
    private val dao: CollectionDao,
    private val json: Json,
    private val matcher: CardImageMatcher,
    val settings: SettingsStore
) : ViewModel() {

    // --- Kamera-Pipeline ------------------------------------------------------

    /**
     * Der Analyzer lebt im ViewModel (ueberdauert Tab-Wechsel-Recompositions);
     * der Screen bindet nur die Kamera an ihn.
     */
    val analyzer = ScanAnalyzer(
        onFrame = { quad, detected, fingerprint ->
            quadOverlay.value = quad
            cardInFrame.value = detected
            submitFingerprint(fingerprint)
        },
        onReading = ::onReading,
        needsIdentifierBoost = { state.value is ScanState.Scanning }
    )

    override fun onCleared() {
        analyzer.close()
    }

    /** Erkanntes Karten-Quad (View-normiert) fuers Live-Overlay. */
    val quadOverlay = MutableStateFlow<FloatArray?>(null)

    /** Liegt gerade eine Karte im Rahmen? */
    val cardInFrame = MutableStateFlow(false)

    /**
     * Fingerabdruck der zuletzt im Rahmen gesehenen Karte (Perceptual Hash des
     * entzerrten Kartenbilds). Beim Start einer Suche wird der aktuelle Wert
     * eingefroren, damit der visuelle Abgleich die gescannte Karte vergleicht.
     */
    @Volatile
    private var lastFingerprint: PerceptualHash.Fingerprint? = null

    /** Schwachlicht-Erkennung: mehrere dunkle Frames in Folge → Hinweis. */
    val lowLight = MutableStateFlow(false)
    private var darkFrames = 0

    private fun submitFingerprint(fingerprint: PerceptualHash.Fingerprint) {
        lastFingerprint = fingerprint
        darkFrames = if (fingerprint.meanLuma < 55) darkFrames + 1 else 0
        lowLight.value = darkFrames >= 6
    }

    // --- Scan-Session ---------------------------------------------------------

    val session = MutableStateFlow<List<SessionEntry>>(emptyList())
    val showSessionSheet = MutableStateFlow(false)

    fun sessionTotal(currency: String): Double = session.value.sumOf { entry ->
        (entry.card.primaryPrice(currency)?.amount ?: 0.0) * entry.quantity
    }

    fun sessionCount(): Int = session.value.sumOf { it.quantity }

    /** Duplikat-Bremse: dieselbe Karte feuert fruehestens alle 2 s erneut. */
    private var lastAddedCardId: String? = null
    private var lastAddedAt = 0L

    private fun addToSession(card: TcgCard): Boolean {
        val now = System.currentTimeMillis()
        if (card.id == lastAddedCardId && now - lastAddedAt < DUPLICATE_COOLDOWN_MS) return false
        lastAddedCardId = card.id
        lastAddedAt = now
        val current = session.value
        val last = current.lastOrNull()
        session.value = if (last?.card?.id == card.id && !last.foil) {
            current.dropLast(1) + last.copy(quantity = last.quantity + 1)
        } else {
            current + SessionEntry(
                card = card,
                quantity = 1,
                condition = settings.defaultCondition.value,
                language = settings.defaultLanguage.value
            )
        }
        return true
    }

    /** Letzte Erfassung zuruecknehmen (Menge −1 bzw. Eintrag entfernen). */
    fun undoLast() {
        val current = session.value
        val last = current.lastOrNull() ?: return
        session.value = if (last.quantity > 1) {
            current.dropLast(1) + last.copy(quantity = last.quantity - 1)
        } else {
            current.dropLast(1)
        }
        hint.value = "Erfassung zurückgenommen."
        lastAddedCardId = null
    }

    fun toggleFoil(index: Int) {
        val current = session.value.toMutableList()
        if (index !in current.indices) return
        current[index] = current[index].copy(foil = !current[index].foil)
        session.value = current
    }

    fun setCondition(index: Int, condition: String) {
        val current = session.value.toMutableList()
        if (index !in current.indices) return
        current[index] = current[index].copy(condition = condition)
        session.value = current
    }

    fun setQuantity(index: Int, quantity: Int) {
        val current = session.value.toMutableList()
        if (index !in current.indices) return
        if (quantity <= 0) current.removeAt(index) else {
            current[index] = current[index].copy(quantity = quantity)
        }
        session.value = current
    }

    fun clearSession() {
        session.value = emptyList()
        showSessionSheet.value = false
        lastAddedCardId = null
    }

    /** Gesamte Session in die Sammlung uebernehmen (Varianten-Mengen addieren). */
    fun commitSession() {
        val entries = session.value
        if (entries.isEmpty()) return
        viewModelScope.launch {
            for (entry in entries) {
                val existing = dao.findVariant(
                    cardId = entry.card.id,
                    wishlist = false,
                    condition = entry.condition,
                    language = entry.language,
                    foil = entry.foil,
                    binderId = null
                )
                dao.upsert(
                    existing?.copy(quantity = existing.quantity + entry.quantity)
                        ?: CollectionEntry(
                            cardId = entry.card.id,
                            wishlist = false,
                            quantity = entry.quantity,
                            name = entry.card.name,
                            setName = entry.card.setName,
                            setCode = entry.card.setCode,
                            collectorNumber = entry.card.collectorNumber,
                            rarity = entry.card.rarity,
                            game = entry.card.game.name,
                            imageUrl = entry.card.imageSmall ?: entry.card.imageLarge,
                            cardJson = json.encodeToString(TcgCard.serializer(), entry.card),
                            addedAt = System.currentTimeMillis(),
                            condition = entry.condition,
                            language = entry.language,
                            foil = entry.foil
                        )
                )
            }
            val count = entries.sumOf { it.quantity }
            session.value = emptyList()
            showSessionSheet.value = false
            lastAddedCardId = null
            hint.value = "✓ $count Karten zur Sammlung hinzugefügt."
        }
    }

    // --- Erkennungs-Zustand -----------------------------------------------------

    val gameFilter = MutableStateFlow<TcgGame?>(null)
    val state = MutableStateFlow<ScanState>(ScanState.Scanning)

    /** Stabil erkannter Kandidat (Kennung oder Name) fuer die Live-Anzeige. */
    val detectedLabel = MutableStateFlow<String?>(null)

    /**
     * Aus den gedruckten Merkmalen (Typzeile, KP/HP, ATK/DEF, Copyright …)
     * erkanntes Spiel — per Mehrheitsvotum ueber die letzten Frames.
     */
    val detectedGame = MutableStateFlow<TcgGame?>(null)
    private val recentGames = ArrayDeque<TcgGame>()

    /** Kurzlebige Hinweise, z. B. "keine Treffer". */
    val hint = MutableStateFlow<String?>(null)

    // --- Namens-Voting (Fallback-Pfad) ---
    private val recentNames = ArrayDeque<String>()
    private val displayText = HashMap<String, String>()
    private val nameCandidate = MutableStateFlow<String?>(null)
    private var lastSearched: String? = null

    // --- Kennungs-Voting (Schnellpfad) ---
    // Fenster statt "in Folge": einzelne OCR-Aussetzer setzen nichts zurueck.
    private val recentIdentifiers = ArrayDeque<CardIdentifier>()
    private var stableIdentifier: CardIdentifier? = null
    private val failedIdentifiers = mutableSetOf<CardIdentifier>()

    /** Whitelist gueltiger Magic-Set-Codes — verwirft OCR-Fehllesungen sofort. */
    private var magicSetCodes: Set<String>? = null

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            magicSetCodes = runCatching { repository.magicSetCodes() }.getOrNull()
        }
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            nameCandidate.filterNotNull().debounce(700).collect { name ->
                val norm = normalize(name)
                val alreadySearched = lastSearched?.let { fuzzyEquals(it, norm) } == true
                if (state.value is ScanState.Scanning && !alreadySearched) {
                    searchByName(name)
                }
            }
        }
    }

    /**
     * Plausibilitaets-Filter vor jedem Lookup: bereits gescheiterte Kennungen
     * und offensichtliche Fehllesungen (unbekannter Set-Code, Nummer ausser
     * Bereich) fliegen raus, bevor sie einen API-Call kosten.
     */
    private fun validated(identifiers: List<CardIdentifier>): List<CardIdentifier> =
        identifiers.filter { id ->
            id !in failedIdentifiers &&
                gameFilter.value.let { it == null || id.game == it } &&
                when (id) {
                    is CardIdentifier.Magic -> {
                        val sets = magicSetCodes
                        sets == null || id.setCode.uppercase() in sets
                    }
                    is CardIdentifier.Pokemon ->
                        // Das nackte Nummer/Total-Paar steht auch auf Magic- und
                        // Lorcana-Karten. Es zaehlt nur, wenn die gedruckten
                        // Merkmale nicht klar auf ein ANDERES Spiel zeigen.
                        detectedGame.value.let { it == null || it == TcgGame.POKEMON } &&
                            (id.printedTotal.toIntOrNull() ?: 0) in 20..999 &&
                            (id.number.toIntOrNull() ?: 0) in 1..999
                    is CardIdentifier.Lorcana ->
                        (id.setNumber.toIntOrNull() ?: 0) in 1..15
                    is CardIdentifier.YugiohPasscode ->
                        id.passcode.toSet().size > 1 // "00000000" u. ae. sind OCR-Muell
                    else -> true
                }
        }

    /** Wird pro OCR-Durchgang des Analyzers aufgerufen. */
    fun onReading(reading: ScanReading) {
        if (state.value !is ScanState.Scanning) return

        // Spiel-Erkennung per Mehrheitsvotum ueber die letzten Frames.
        reading.gameHint?.let { hintGame ->
            recentGames.addLast(hintGame)
            if (recentGames.size > GAME_WINDOW) recentGames.removeFirst()
            detectedGame.value = recentGames
                .groupingBy { it }.eachCount()
                .maxByOrNull { it.value }
                ?.takeIf { it.value >= GAME_VOTES }
                ?.key
        }

        // Kartenname per Mehrheitsvotum ueber die letzten Frames aktualisieren.
        val candidate = reading.nameCandidate
        if (candidate != null) {
            val norm = normalize(candidate)
            if (norm.length >= 3) {
                displayText[norm] = candidate
                recentNames.addLast(norm)
                if (recentNames.size > VOTE_WINDOW) recentNames.removeFirst()
                if (recentNames.count { fuzzyEquals(it, norm) } >= MIN_VOTES) {
                    nameCandidate.value = displayText[norm]
                    if (recentIdentifiers.isEmpty()) detectedLabel.value = displayText[norm]
                    hint.value = null
                }
            }
        }

        // Blitzstart nur fuer STARKE Kennungen (Spiel strukturell eindeutig):
        // Kennung + Name im selben Frame sind zwei unabhaengige Signale.
        // Das schwache Nummer/Total-Paar (Pokémon-Muster steht auch auf Magic-
        // und Lorcana-Karten!) muss dagegen immer das Voting durchlaufen.
        offerIdentifiers(reading.identifiers, instant = candidate != null)
    }

    /**
     * Kennungen einspeisen — aus dem Karten- oder dem Zoom-Durchgang.
     * Starke Kennungen loesen die Suche nach 2 Sichtungen aus (oder sofort
     * bei [instant]-Evidenz); das schwache Pokémon-Muster braucht 3 und nie
     * sofort — sonst kapert es Karten anderer Spiele.
     */
    private fun offerIdentifiers(identifiers: List<CardIdentifier>, instant: Boolean = false) {
        if (state.value !is ScanState.Scanning) return
        val valid = validated(identifiers)
        val id = valid.firstOrNull() ?: return
        recentIdentifiers.addLast(id)
        if (recentIdentifiers.size > ID_WINDOW) recentIdentifiers.removeFirst()
        detectedLabel.value = id.display
        hint.value = null
        val needed = if (id.isStrong) ID_VOTES else ID_VOTES + 1
        val fire = (instant && id.isStrong) ||
            recentIdentifiers.count { it == id } >= needed
        if (fire) {
            stableIdentifier = id
            searchByIdentifiers(listOf(id) + valid.filter { it != id })
        }
    }

    /** Sofortsuche per Button, ohne auf das Debounce zu warten. */
    fun searchNow() {
        if (state.value !is ScanState.Scanning) return
        val identifier = (stableIdentifier ?: recentIdentifiers.lastOrNull())
            ?.takeIf { it !in failedIdentifiers }
        val name = nameCandidate.value
        when {
            identifier != null -> searchByIdentifiers(listOf(identifier))
            name != null -> searchByName(name)
        }
    }

    /** Laufende Suche abbrechen und weiterscannen. */
    fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
        state.value = ScanState.Scanning
        hint.value = null
        lastSearched = null
        recentIdentifiers.clear()
        stableIdentifier = null
    }

    private fun searchByIdentifiers(identifiers: List<CardIdentifier>) {
        val first = identifiers.firstOrNull() ?: return
        // Momentaufnahmen fuer die Trefferpruefung: das Kartenbild und der
        // OCR-Name JETZT — nicht das, was waehrend der Suche im Rahmen liegt.
        val fingerprint = lastFingerprint
        val ocrName = nameCandidate.value
            ?: recentNames.lastOrNull()?.let { displayText[it] }
        state.value = ScanState.Resolving(first.display)
        searchJob = viewModelScope.launch {
            for (identifier in identifiers) {
                val cards = runCatching { repository.resolve(identifier) }.getOrDefault(emptyList())
                if (cards.isNotEmpty()) {
                    // Namens-Gegenprobe: eine falsch gelesene Kennung loest sich
                    // fast nie zu einer Karte auf, deren Name auch noch passt.
                    val nameAgrees = ocrName == null || cards.any { nameMatches(it.name, ocrName) }
                    if (nameAgrees) {
                        onCardsFound(identifier.display, cards, fingerprint, true, exactEdition = true)
                    } else {
                        // Kennung und gelesener Name widersprechen sich —
                        // vermutlich eine Fehllesung der Nummer. Beide
                        // Deutungen anbieten: der visuelle Abgleich sortiert
                        // die tatsaechlich gehaltene Karte nach vorn, statt
                        // nur den falschen Kennungs-Treffer zu zeigen.
                        val byName = ocrName?.let { name ->
                            runCatching {
                                repository.search(name, gameFilter.value ?: detectedGame.value)
                            }.getOrDefault(emptyList()).let { rankByRelevance(it, name) }
                        }.orEmpty()
                        val merged = (cards + byName).distinctBy { it.id }
                        onCardsFound(
                            identifier.display, merged, fingerprint,
                            nameAgrees = false, exactEdition = false
                        )
                    }
                    return@launch
                }
                failedIdentifiers += identifier
            }
            // Kennung nicht aufloesbar (z. B. OCR-Zahlendreher) → Namens-Pfad weiter.
            hint.value = "Kennung nicht eindeutig – suche per Name weiter."
            recentIdentifiers.clear()
            stableIdentifier = null
            state.value = ScanState.Scanning
        }
    }

    private fun searchByName(name: String) {
        lastSearched = normalize(name)
        val fingerprint = lastFingerprint
        // Suche aufs erkannte Spiel begrenzen: das haelt Treffer anderer
        // Spiele komplett raus. Ohne Treffer weitet ein zweiter Versuch
        // die Suche auf alle Spiele aus (falls die Erkennung daneben lag).
        val game = gameFilter.value ?: detectedGame.value
        state.value = ScanState.Resolving(name)
        searchJob = viewModelScope.launch {
            var result = runCatching { repository.search(name, game) }
            var cards = rankByRelevance(result.getOrDefault(emptyList()), name)
            if (cards.isEmpty() && game != null && gameFilter.value == null) {
                result = runCatching { repository.search(name, null) }
                cards = rankByRelevance(result.getOrDefault(emptyList()), name)
            }
            if (cards.isEmpty()) {
                hint.value = if (result.isFailure) {
                    "Netzwerkfehler – bitte erneut versuchen."
                } else {
                    "Keine Treffer für \"$name\" – Karte neu ausrichten."
                }
                state.value = ScanState.Scanning
            } else {
                onCardsFound(
                    name, cards, fingerprint,
                    nameMatches(cards.first().name, name),
                    exactEdition = false
                )
            }
        }
    }

    /**
     * Trefferbehandlung mit Zwei-Signale-Regel: automatisch in die Session
     * wandert eine Karte nur, wenn neben der Kennung mindestens ein zweites
     * unabhaengiges Signal zustimmt — der OCR-Name ODER der visuelle Abgleich
     * (Perceptual Hash des entzerrten Kartenbilds, sprachunabhaengig).
     * Andernfalls oeffnet der Editions-Waehler, statt still eine falsche
     * Karte zu erfassen.
     */
    private suspend fun onCardsFound(
        label: String,
        cards: List<TcgCard>,
        fingerprint: PerceptualHash.Fingerprint?,
        nameAgrees: Boolean,
        exactEdition: Boolean
    ) {
        val (ranked, confidence) = rankVisually(cards, fingerprint)

        // Editions-Mehrdeutigkeit: bei einem reinen Namens-Treffer mit
        // mehreren gleichnamigen Drucken (One Piece druckt z. B. dutzende
        // "Monkey.D.Luffy") sagt der Name NICHTS ueber die Edition. Dann
        // darf nur ein klarer visueller Treffer automatisch erfassen —
        // sonst waehlt der Nutzer im Editions-Waehler.
        val topName = normalize(ranked.first().name)
        val sameNamePrints = ranked.count { normalize(it.name) == topName }
        val trusted = when {
            exactEdition -> nameAgrees || confidence >= 0.5f
            sameNamePrints > 1 -> confidence >= 0.75f
            else -> nameAgrees || confidence >= 0.5f
        }
        if (trusted) {
            val best = ranked.first()
            if (addToSession(best)) {
                hint.value = if (confidence >= 0.75f || ranked.size == 1) {
                    "✓ ${best.name} erfasst"
                } else {
                    "✓ ${best.name} erfasst – Edition im Stapel prüfbar"
                }
            }
            resumeForNextScan()
        } else {
            state.value = ScanState.ConfirmPick(label, ranked)
        }
    }

    /** Auswahl aus dem Editions-Waehler in die Session uebernehmen. */
    fun addFromConfirm(card: TcgCard) {
        addToSession(card)
        hint.value = "✓ ${card.name} erfasst"
        resumeScanning(keepHint = true)
    }

    /**
     * Sortiert die Kandidaten nach visueller Aehnlichkeit zum gescannten Bild.
     * Liefert die (ggf. neu geordnete) Liste plus ein Vertrauensmass 0..1.
     * Auch bei nur einem Kandidaten wird verglichen — als Bestaetigung.
     */
    private suspend fun rankVisually(
        cards: List<TcgCard>,
        fingerprint: PerceptualHash.Fingerprint?
    ): Pair<List<TcgCard>, Float> {
        if (fingerprint == null || cards.isEmpty()) return cards to 0f
        // Nur die textlich relevantesten Kandidaten visuell pruefen — begrenzt
        // die Zahl der Bild-Downloads und damit die Latenz.
        val head = cards.take(VISUAL_MATCH_LIMIT)
        val tail = cards.drop(VISUAL_MATCH_LIMIT)
        val ranked = runCatching { matcher.rank(fingerprint, head) }.getOrNull()
            ?: return cards to 0f
        return (ranked.cards + tail) to ranked.confidence
    }

    /** Erkennungs-Evidenz zuruecksetzen, Hinweis und Session behalten. */
    private fun resumeForNextScan() {
        state.value = ScanState.Scanning
        detectedLabel.value = null
        detectedGame.value = null
        nameCandidate.value = null
        recentNames.clear()
        recentGames.clear()
        displayText.clear()
        lastSearched = null
        recentIdentifiers.clear()
        stableIdentifier = null
        failedIdentifiers.clear()
    }

    /** Nach Schliessen des Editions-Waehlers weiterscannen. */
    fun resumeScanning(keepHint: Boolean = false) {
        searchJob?.cancel()
        searchJob = null
        if (!keepHint) hint.value = null
        resumeForNextScan()
    }

    private companion object {
        const val VOTE_WINDOW = 10
        const val MIN_VOTES = 3
        const val ID_WINDOW = 8
        const val ID_VOTES = 2
        const val GAME_WINDOW = 8
        const val GAME_VOTES = 2
        const val VISUAL_MATCH_LIMIT = 12
        const val DUPLICATE_COOLDOWN_MS = 2000L
    }
}

// ---- Fuzzy-Matching-Helfer -------------------------------------------------

private fun normalize(s: String): String = s.lowercase()
    .replace(Regex("[^\\p{L}\\p{N}' ]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
        }
        val tmp = prev; prev = curr; curr = tmp
    }
    return prev[b.length]
}

/** Tippfehlertoleranter Vergleich (erlaubt ~1 Fehler pro 6 Zeichen). */
private fun fuzzyEquals(a: String, b: String): Boolean =
    a == b || levenshtein(a, b) <= maxOf(1, minOf(a.length, b.length) / 6)

/**
 * Namens-Gegenprobe zwischen aufgeloester Karte und OCR-Lesung: tolerant
 * gegenueber Zusaetzen ("Charizard ex" vs. "Charizard", Untertitel) und
 * kleinen OCR-Fehlern, aber ein komplett anderer Name faellt durch.
 */
private fun nameMatches(cardName: String, ocrName: String): Boolean {
    val a = normalize(cardName)
    val b = normalize(ocrName)
    if (a.isEmpty() || b.isEmpty()) return false
    return a.contains(b) || b.contains(a) || fuzzyEquals(a, b) ||
        fuzzyEquals(a.take(12), b.take(12))
}

/** Sortiert Treffer nach Namensaehnlichkeit zur OCR-Anfrage (stabil). */
private fun rankByRelevance(cards: List<TcgCard>, query: String): List<TcgCard> {
    val q = normalize(query)
    return cards.sortedBy { card ->
        val n = normalize(card.name)
        when {
            n == q -> 0
            n.startsWith(q) || q.startsWith(n) -> 1
            n.contains(q) -> 2
            else -> 3 + levenshtein(n.take(24), q.take(24)).coerceAtMost(20)
        }
    }
}
