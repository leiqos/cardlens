package com.cardlens.tcg

import android.app.Application
import android.content.Context
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.cardlens.tcg.data.CardRepository
import com.cardlens.tcg.data.SettingsStore
import com.cardlens.tcg.data.local.AppDatabase
import com.cardlens.tcg.data.local.BinderDao
import com.cardlens.tcg.data.local.CollectionDao
import com.cardlens.tcg.data.local.DeckDao
import com.cardlens.tcg.data.local.FavoriteDao
import com.cardlens.tcg.data.local.ValueSnapshot
import com.cardlens.tcg.data.local.ValueSnapshotDao
import com.cardlens.tcg.data.remote.DragonBallService
import com.cardlens.tcg.data.remote.LorcastService
import com.cardlens.tcg.data.remote.OnePieceApi
import com.cardlens.tcg.data.remote.OnePieceCatalog
import com.cardlens.tcg.data.remote.PokemonService
import com.cardlens.tcg.data.remote.RiftboundService
import com.cardlens.tcg.data.remote.ScryfallService
import com.cardlens.tcg.data.remote.SwuService
import com.cardlens.tcg.data.remote.YgoService
import com.cardlens.tcg.model.TcgCard
import com.cardlens.tcg.model.variantPrice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

class CardLensApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.recordValueSnapshot()
    }

    /**
     * Coil global mit demselben OkHttp-Client wie die APIs versorgen:
     * einige Bild-CDNs (v. a. Scryfall) lehnen Requests ohne aussagekraeftigen
     * User-Agent ab — Coils Standard-Client haette keinen.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(container.okHttp)
        .crossfade(true)
        .build()
}

/** Manuelle Dependency Injection — bewusst ohne Hilt gehalten. */
class AppContainer(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val host = chain.request().url.host
            val builder = chain.request().newBuilder()
                .header("User-Agent", "CardLens/2.0 (Android)")
            // Accept-Header nur fuer die JSON-APIs, nicht fuer Bild-CDNs
            if (host.startsWith("api.") || host == "db.ygoprodeck.com") {
                builder.header("Accept", "application/json")
            }
            if (BuildConfig.POKEMON_API_KEY.isNotBlank() && host == "api.pokemontcg.io") {
                builder.header("X-Api-Key", BuildConfig.POKEMON_API_KEY)
            }
            // apitcg.com (Dragon Ball Fusion World) verlangt einen optionalen Key.
            if (BuildConfig.DRAGONBALL_API_KEY.isNotBlank() && host == "apitcg.com") {
                builder.header("x-api-key", BuildConfig.DRAGONBALL_API_KEY)
            }
            chain.proceed(builder.build())
        }
        .build()

    private fun retrofit(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttp)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val onePieceCatalog = OnePieceCatalog(
        retrofit(OnePieceApi.BASE_URL).create(OnePieceApi::class.java)
    )

    init {
        // One-Piece-Suchindex im Hintergrund vorladen,
        // damit der erste Scan/die erste Suche nicht darauf wartet.
        appScope.launch { onePieceCatalog.prefetch() }
    }

    val repository = CardRepository(
        scryfall = retrofit(ScryfallService.BASE_URL).create(ScryfallService::class.java),
        pokemon = retrofit(PokemonService.BASE_URL).create(PokemonService::class.java),
        ygo = retrofit(YgoService.BASE_URL).create(YgoService::class.java),
        lorcast = retrofit(LorcastService.BASE_URL).create(LorcastService::class.java),
        onePiece = onePieceCatalog,
        swu = retrofit(SwuService.BASE_URL).create(SwuService::class.java),
        dragonBall = retrofit(DragonBallService.BASE_URL).create(DragonBallService::class.java),
        riftbound = retrofit(RiftboundService.BASE_URL).create(RiftboundService::class.java)
    )

    private val database: AppDatabase = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "cardlens.db"
    )
        .addMigrations(AppDatabase.MIGRATION_1_2)
        .build()

    val collectionDao: CollectionDao = database.collectionDao()
    val binderDao: BinderDao = database.binderDao()
    val deckDao: DeckDao = database.deckDao()
    val favoriteDao: FavoriteDao = database.favoriteDao()
    val valueSnapshotDao: ValueSnapshotDao = database.valueSnapshotDao()

    val settings = SettingsStore(context)

    val cardJson = json

    /**
     * Aktuellen Sammlungswert als Tages-Schnappschuss festhalten —
     * Grundlage des Wertverlauf-Diagramms auf dem Sammlungs-Dashboard.
     */
    fun recordValueSnapshot() {
        appScope.launch {
            runCatching {
                val currency = settings.currency.value
                val owned = collectionDao.allOwned()
                var total = 0.0
                var count = 0
                for (entry in owned) {
                    val card = runCatching {
                        json.decodeFromString(TcgCard.serializer(), entry.cardJson)
                    }.getOrNull() ?: continue
                    val price = card.variantPrice(currency, entry.foil)?.amount ?: 0.0
                    total += price * entry.quantity
                    count += entry.quantity
                }
                valueSnapshotDao.upsert(
                    ValueSnapshot(
                        epochDay = System.currentTimeMillis() / 86_400_000L,
                        value = total,
                        currency = currency,
                        cardCount = count
                    )
                )
            }
        }
    }
}
