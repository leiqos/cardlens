package com.cardlens.tcg.data

import com.cardlens.tcg.data.remote.DragonBallService
import com.cardlens.tcg.data.remote.LorcastService
import com.cardlens.tcg.data.remote.OnePieceCatalog
import com.cardlens.tcg.data.remote.PokemonService
import com.cardlens.tcg.data.remote.RiftboundService
import com.cardlens.tcg.data.remote.ScryfallService
import com.cardlens.tcg.data.remote.SwuService
import com.cardlens.tcg.data.remote.YgoService
import com.cardlens.tcg.data.remote.toTcgCard
import com.cardlens.tcg.model.CardIdentifier
import com.cardlens.tcg.model.TcgCard
import com.cardlens.tcg.model.TcgGame
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import java.util.concurrent.ConcurrentHashMap

class CardRepository(
    private val scryfall: ScryfallService,
    private val pokemon: PokemonService,
    private val ygo: YgoService,
    private val lorcast: LorcastService,
    private val onePiece: OnePieceCatalog,
    private val swu: SwuService,
    private val dragonBall: DragonBallService,
    private val riftbound: RiftboundService
) {
    /** In-Memory-Cache, damit die Detailansicht ohne erneuten API-Call funktioniert. */
    private val cache = ConcurrentHashMap<String, TcgCard>()

    /** Session-Cache fuer Suchergebnisse (Query+Spiel → Treffer). */
    private val searchCache = ConcurrentHashMap<String, List<TcgCard>>()

    fun cached(id: String): TcgCard? = cache[id]

    fun remember(card: TcgCard) {
        cache[card.id] = card
    }

    /**
     * Exakter Lookup ueber eine aufgedruckte Druck-Kennung — ein einziger,
     * schneller API-Call, liefert die genaue Edition.
     */
    suspend fun resolve(identifier: CardIdentifier): List<TcgCard> {
        val cards = try {
            when (identifier) {
                is CardIdentifier.Magic ->
                    listOf(scryfall.byCollector(identifier.setCode.lowercase(), identifier.number).toTcgCard())
                is CardIdentifier.Pokemon ->
                    pokemon.search("number:${identifier.number} set.printedTotal:${identifier.printedTotal}")
                        .data.map { it.toTcgCard() }
                is CardIdentifier.YugiohPasscode ->
                    ygo.byPasscode(identifier.passcode).data.map { it.toTcgCard() }
                is CardIdentifier.YugiohSet -> {
                    // Set-Code → Auflagen-Info → Karte mit genau dieser Auflage
                    val setInfo = ygo.bySetCode(identifier.setCode)
                    ygo.byPasscode(setInfo.id.toString()).data.map { card ->
                        val set = card.cardSets?.firstOrNull {
                            it.setCode.equals(setInfo.setCode, ignoreCase = true)
                        } ?: com.cardlens.tcg.data.remote.YgoSet(
                            setName = setInfo.setName,
                            setCode = setInfo.setCode,
                            setRarity = setInfo.setRarity,
                            setPrice = setInfo.setPrice
                        )
                        card.toTcgCard(set)
                    }
                }
                is CardIdentifier.OnePiece ->
                    onePiece.byCardSetId(identifier.cardSetId)
                is CardIdentifier.Lorcana ->
                    listOf(lorcast.byNumber(identifier.setNumber, identifier.number).toTcgCard())
            }
        } catch (e: HttpException) {
            if (e.code() == 404 || e.code() == 400) emptyList() else throw e
        }
        cards.forEach(::remember)
        return cards
    }

    /**
     * Vollstaendige Riftbound-Karte (Werte, Text, grosses Bild). Die Suche
     * liefert nur Typeahead-Daten; das Detail wird hiermit nachgeladen.
     */
    suspend fun riftboundDetail(cardId: String): TcgCard? = try {
        riftbound.byId(cardId).toTcgCard().also(::remember)
    } catch (e: HttpException) {
        if (e.code() in intArrayOf(400, 404)) null else throw e
    }

    /** Offizielle Rulings einer Magic-Karte (Scryfall-UUID). */
    suspend fun magicRulings(scryfallId: String): List<Pair<String, String>> = try {
        scryfall.rulings(scryfallId).data.map { (it.publishedAt ?: "") to it.comment }
    } catch (e: HttpException) {
        if (e.code() == 404 || e.code() == 400) emptyList() else throw e
    }

    /** Namensvorschlaege fuer die Magic-Suche (Scryfall-Autocomplete). */
    suspend fun magicAutocomplete(query: String): List<String> = try {
        scryfall.autocomplete(query).data
    } catch (_: Exception) {
        emptyList()
    }

    /** Exakte Magic-Karte per Name (Decklisten-Import). */
    suspend fun magicByName(name: String): TcgCard? = try {
        scryfall.byName(name).toTcgCard().also(::remember)
    } catch (e: HttpException) {
        if (e.code() == 404 || e.code() == 400) null else throw e
    }

    /** Magic-Karte per Scryfall-UUID (CSV-Import). */
    suspend fun magicById(scryfallId: String): TcgCard? = try {
        scryfall.byId(scryfallId).toTcgCard().also(::remember)
    } catch (e: HttpException) {
        if (e.code() == 404 || e.code() == 400) null else throw e
    }

    /**
     * Alle Drucke/Editionen einer Magic-Karte (exakter Name), neueste zuerst.
     * Grundlage der Editions-Auswahl in der Detailansicht.
     */
    suspend fun magicPrintings(name: String): List<TcgCard> = try {
        scryfall.search("!\"$name\"", order = "released", unique = "prints")
            .data.map { it.toTcgCard() }
            .also { cards -> cards.forEach(::remember) }
    } catch (e: HttpException) {
        if (e.code() == 404 || e.code() == 400) emptyList() else throw e
    }

    /**
     * Alle Auflagen einer Yu-Gi-Oh!-Karte (per Passcode) mit Auflagen-Preisen.
     */
    suspend fun ygoPrintings(passcode: String): List<TcgCard> = try {
        val card = ygo.byPasscode(passcode).data.firstOrNull() ?: return emptyList()
        (card.cardSets ?: emptyList())
            .map { set -> card.toTcgCard(set) }
            .distinctBy { it.id }
            .also { cards -> cards.forEach(::remember) }
    } catch (e: HttpException) {
        if (e.code() == 404 || e.code() == 400) emptyList() else throw e
    }

    /**
     * Namenssuche. `game == null` durchsucht alle TCGs parallel; jede Quelle
     * hat ein eigenes Timeout, damit eine langsame API nicht alles blockiert.
     * Einzelne fehlschlagende Quellen werden ignoriert, solange mindestens
     * eine Quelle antwortet; schlagen alle fehl, wird der Fehler geworfen.
     */
    suspend fun search(query: String, game: TcgGame?): List<TcgCard> = coroutineScope {
        val cacheKey = "${game?.name ?: "ALL"}|${query.trim().lowercase()}"
        searchCache[cacheKey]?.let { return@coroutineScope it }

        val games = game?.let { listOf(it) } ?: TcgGame.entries.toList()
        val results = games.map { g ->
            async {
                runCatching {
                    withTimeoutOrNull(SOURCE_TIMEOUT_MS) { searchGame(g, query) } ?: emptyList()
                }
            }
        }.awaitAll()

        if (results.all { it.isFailure }) {
            throw results.first().exceptionOrNull() ?: IllegalStateException("Suche fehlgeschlagen")
        }
        results.flatMap { it.getOrDefault(emptyList()) }.also { cards ->
            cards.forEach(::remember)
            if (searchCache.size > 60) searchCache.clear()
            searchCache[cacheKey] = cards
        }
    }

    private suspend fun searchGame(game: TcgGame, query: String): List<TcgCard> = try {
        when (game) {
            TcgGame.MAGIC -> scryfall.search(query).data.map { it.toTcgCard() }
            TcgGame.POKEMON -> pokemon.search(PokemonService.nameQuery(query)).data.map { it.toTcgCard() }
            TcgGame.YUGIOH -> ygo.search(query).data.map { it.toTcgCard() }
            TcgGame.ONEPIECE -> onePiece.search(query)
            TcgGame.LORCANA -> lorcast.search(query).results.map { it.toTcgCard() }
            TcgGame.STARWARS -> swu.search(query).data.map { it.toTcgCard() }
            TcgGame.DRAGONBALL -> dragonBall.search(query).all.mapNotNull { it.toTcgCard() }
            TcgGame.RIFTBOUND -> riftbound.search(query).map { it.toTcgCard() }
        }
    } catch (e: HttpException) {
        // Viele der freien APIs liefern 400/404/401/403/422, wenn nichts gefunden
        // wurde, der optionale Key fehlt (apitcg) oder ein Parameter ausserhalb
        // des erlaubten Bereichs liegt — dann leeres Ergebnis statt Fehler.
        if (e.code() in intArrayOf(400, 401, 403, 404, 422)) emptyList() else throw e
    }

    private companion object {
        const val SOURCE_TIMEOUT_MS = 8_000L
    }
}
