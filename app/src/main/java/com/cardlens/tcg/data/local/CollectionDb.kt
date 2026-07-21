package com.cardlens.tcg.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Ein Sammlungs-Eintrag. Seit v2 kann dieselbe Karte mehrfach vorkommen —
 * je Variante (Zustand, Sprache, Foil, Binder) ein eigener Eintrag.
 * `cardJson` haelt den kompletten Karten-Snapshot fuer Offline-Details.
 */
@Entity(
    tableName = "collection_entries",
    indices = [Index("cardId"), Index("binderId"), Index("wishlist")]
)
data class CollectionEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: String,
    val wishlist: Boolean,
    val quantity: Int,
    val name: String,
    val setName: String?,
    val setCode: String? = null,
    val collectorNumber: String? = null,
    val rarity: String? = null,
    val game: String,
    val imageUrl: String?,
    val cardJson: String,
    val addedAt: Long,
    // Varianten-Merkmale (ManaBox-Feature-Paritaet)
    val condition: String = "NM",     // CardCondition.code
    val language: String = "en",      // CardLanguage.code
    val foil: Boolean = false,
    /** normal, foil, reverse_holo, etched, textured, serialized, other */
    val finish: String = "normal",
    val altered: Boolean = false,
    val misprint: Boolean = false,
    val purchasePrice: Double? = null,
    val purchaseCurrency: String? = null,
    val binderId: Long? = null,
    val notes: String? = null
)

/** Binder/Ordner zum Organisieren der Sammlung. */
@Entity(tableName = "binders")
data class Binder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "decks")
data class Deck(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val game: String,                 // TcgGame.name
    val format: String,               // DeckFormat.name
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "deck_cards",
    indices = [Index("deckId"), Index("cardId")]
)
data class DeckCard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deckId: Long,
    val cardId: String,
    val name: String,
    val quantity: Int,
    val board: String = "MAIN",       // DeckBoard.name
    val imageUrl: String?,
    val cardJson: String
)

@Entity(tableName = "favorites")
data class Favorite(
    @PrimaryKey val cardId: String,
    val name: String,
    val game: String,
    val imageUrl: String?,
    val cardJson: String,
    val addedAt: Long = System.currentTimeMillis()
)

/** Taeglicher Schnappschuss des Sammlungswerts (Wertverlauf-Diagramm). */
@Entity(tableName = "value_snapshots")
data class ValueSnapshot(
    @PrimaryKey val epochDay: Long,
    val value: Double,
    val currency: String,
    val cardCount: Int
)

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collection_entries WHERE wishlist = :wishlist ORDER BY addedAt DESC")
    fun observe(wishlist: Boolean): Flow<List<CollectionEntry>>

    @Query("SELECT * FROM collection_entries WHERE binderId = :binderId ORDER BY addedAt DESC")
    fun observeBinder(binderId: Long): Flow<List<CollectionEntry>>

    @Query("SELECT * FROM collection_entries WHERE cardId = :cardId")
    fun observeCard(cardId: String): Flow<List<CollectionEntry>>

    @Query("SELECT * FROM collection_entries WHERE id = :id")
    suspend fun byId(id: Long): CollectionEntry?

    @Query(
        """SELECT * FROM collection_entries
           WHERE cardId = :cardId AND wishlist = :wishlist AND condition = :condition
             AND language = :language AND foil = :foil AND finish = :finish
             AND ((:binderId IS NULL AND binderId IS NULL) OR binderId = :binderId)
           LIMIT 1"""
    )
    suspend fun findVariant(
        cardId: String,
        wishlist: Boolean,
        condition: String,
        language: String,
        foil: Boolean,
        finish: String,
        binderId: Long?
    ): CollectionEntry?

    @Query("SELECT * FROM collection_entries WHERE cardId = :cardId AND wishlist = :wishlist")
    suspend fun byCard(cardId: String, wishlist: Boolean): List<CollectionEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CollectionEntry): Long

    @Update
    suspend fun update(entry: CollectionEntry)

    @Update
    suspend fun updateAll(entries: List<CollectionEntry>)

    @Query("DELETE FROM collection_entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM collection_entries WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>)

    @Query("UPDATE collection_entries SET binderId = :binderId WHERE id IN (:ids)")
    suspend fun moveToBinder(ids: List<Long>, binderId: Long?)

    @Query("UPDATE collection_entries SET binderId = NULL WHERE binderId = :binderId")
    suspend fun clearBinder(binderId: Long)

    @Query("SELECT * FROM collection_entries WHERE wishlist = 0")
    suspend fun allOwned(): List<CollectionEntry>

    @Query("SELECT COALESCE(SUM(quantity), 0) FROM collection_entries WHERE cardId = :cardId AND wishlist = 0")
    fun observeOwnedQuantity(cardId: String): Flow<Int>
}

@Dao
interface BinderDao {
    @Query("SELECT * FROM binders ORDER BY createdAt ASC")
    fun observe(): Flow<List<Binder>>

    @Insert
    suspend fun insert(binder: Binder): Long

    @Update
    suspend fun update(binder: Binder)

    @Query("DELETE FROM binders WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DeckDao {
    @Query("SELECT * FROM decks ORDER BY updatedAt DESC")
    fun observeDecks(): Flow<List<Deck>>

    @Query("SELECT * FROM decks WHERE id = :id")
    fun observeDeck(id: Long): Flow<Deck?>

    @Query("SELECT * FROM deck_cards WHERE deckId = :deckId ORDER BY board, name")
    fun observeCards(deckId: Long): Flow<List<DeckCard>>

    @Query("SELECT * FROM deck_cards ORDER BY deckId")
    suspend fun allCards(): List<DeckCard>

    @Query("SELECT * FROM deck_cards")
    fun observeAllCards(): Flow<List<DeckCard>>

    @Query("SELECT * FROM deck_cards WHERE cardId = :cardId")
    suspend fun usagesOf(cardId: String): List<DeckCard>

    @Insert
    suspend fun insertDeck(deck: Deck): Long

    @Update
    suspend fun updateDeck(deck: Deck)

    @Query("DELETE FROM decks WHERE id = :id")
    suspend fun deleteDeck(id: Long)

    @Query("DELETE FROM deck_cards WHERE deckId = :deckId")
    suspend fun deleteDeckCards(deckId: Long)

    @Query(
        """SELECT * FROM deck_cards
           WHERE deckId = :deckId AND cardId = :cardId AND board = :board LIMIT 1"""
    )
    suspend fun findCard(deckId: Long, cardId: String, board: String): DeckCard?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCard(card: DeckCard): Long

    @Query("DELETE FROM deck_cards WHERE id = :id")
    suspend fun deleteCard(id: Long)

    @Query("UPDATE decks SET updatedAt = :now WHERE id = :deckId")
    suspend fun touch(deckId: Long, now: Long = System.currentTimeMillis())
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observe(): Flow<List<Favorite>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE cardId = :cardId)")
    fun observeIsFavorite(cardId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(favorite: Favorite)

    @Query("DELETE FROM favorites WHERE cardId = :cardId")
    suspend fun remove(cardId: String)
}

@Dao
interface ValueSnapshotDao {
    @Query("SELECT * FROM value_snapshots ORDER BY epochDay ASC")
    fun observe(): Flow<List<ValueSnapshot>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: ValueSnapshot)
}

@Database(
    entities = [
        CollectionEntry::class, Binder::class, Deck::class,
        DeckCard::class, Favorite::class, ValueSnapshot::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun collectionDao(): CollectionDao
    abstract fun binderDao(): BinderDao
    abstract fun deckDao(): DeckDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun valueSnapshotDao(): ValueSnapshotDao

    companion object {
        /** v1 → v2: Varianten-Spalten, Binder/Decks/Favoriten/Wertverlauf. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS collection_entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        cardId TEXT NOT NULL,
                        wishlist INTEGER NOT NULL,
                        quantity INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        setName TEXT,
                        setCode TEXT,
                        collectorNumber TEXT,
                        rarity TEXT,
                        game TEXT NOT NULL,
                        imageUrl TEXT,
                        cardJson TEXT NOT NULL,
                        addedAt INTEGER NOT NULL,
                        condition TEXT NOT NULL DEFAULT 'NM',
                        language TEXT NOT NULL DEFAULT 'en',
                        foil INTEGER NOT NULL DEFAULT 0,
                        altered INTEGER NOT NULL DEFAULT 0,
                        misprint INTEGER NOT NULL DEFAULT 0,
                        purchasePrice REAL,
                        purchaseCurrency TEXT,
                        binderId INTEGER,
                        notes TEXT
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_collection_entries_cardId ON collection_entries(cardId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_collection_entries_binderId ON collection_entries(binderId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_collection_entries_wishlist ON collection_entries(wishlist)")
                db.execSQL(
                    """INSERT INTO collection_entries
                        (cardId, wishlist, quantity, name, setName, game, imageUrl, cardJson, addedAt)
                       SELECT cardId, wishlist, quantity, name, setName, game, imageUrl, cardJson, addedAt
                       FROM collection"""
                )
                db.execSQL("DROP TABLE IF EXISTS collection")

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS binders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        colorIndex INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS decks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        game TEXT NOT NULL,
                        format TEXT NOT NULL,
                        notes TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS deck_cards (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        deckId INTEGER NOT NULL,
                        cardId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        quantity INTEGER NOT NULL,
                        board TEXT NOT NULL DEFAULT 'MAIN',
                        imageUrl TEXT,
                        cardJson TEXT NOT NULL
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_deck_cards_deckId ON deck_cards(deckId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_deck_cards_cardId ON deck_cards(cardId)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS favorites (
                        cardId TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        game TEXT NOT NULL,
                        imageUrl TEXT,
                        cardJson TEXT NOT NULL,
                        addedAt INTEGER NOT NULL
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS value_snapshots (
                        epochDay INTEGER PRIMARY KEY NOT NULL,
                        value REAL NOT NULL,
                        currency TEXT NOT NULL,
                        cardCount INTEGER NOT NULL
                    )"""
                )
            }
        }

        /** v2 → v3: preserve finishes that cannot be represented by a foil boolean. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE collection_entries ADD COLUMN finish TEXT NOT NULL DEFAULT 'normal'"
                )
                db.execSQL("UPDATE collection_entries SET finish = 'foil' WHERE foil = 1")
            }
        }
    }
}
