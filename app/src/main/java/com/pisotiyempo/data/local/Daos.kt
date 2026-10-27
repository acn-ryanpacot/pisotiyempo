package com.pisotiyempo.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface StockDao {
    @Upsert
    suspend fun upsert(stocks: List<StockEntity>)

    @Query("SELECT * FROM stocks ORDER BY symbol")
    fun observeAll(): Flow<List<StockEntity>>

    @Query("SELECT * FROM stocks")
    suspend fun all(): List<StockEntity>

    @Query("SELECT * FROM stocks WHERE symbol = :symbol")
    fun observe(symbol: String): Flow<StockEntity?>

    @Query("SELECT * FROM stocks WHERE symbol = :symbol")
    suspend fun get(symbol: String): StockEntity?

    @Query("SELECT * FROM stocks WHERE symbol LIKE :q || '%' OR name LIKE '%' || :q || '%' ORDER BY CASE WHEN symbol = :q THEN 0 WHEN symbol LIKE :q || '%' THEN 1 ELSE 2 END, symbol LIMIT 40")
    suspend fun search(q: String): List<StockEntity>

    /** Most traded by peso value on the latest date: the analysis universe. */
    @Query("SELECT symbol FROM stocks WHERE date = (SELECT MAX(date) FROM stocks) ORDER BY close * volume DESC LIMIT :limit")
    suspend fun mostActive(limit: Int): List<String>
}

@Dao
interface BarDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bars: List<BarEntity>)

    /** Inserts only bars we don't have yet, so a close-only fallback never overwrites full OHLC data. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissing(bars: List<BarEntity>)

    @Query("SELECT * FROM bars WHERE symbol = :symbol ORDER BY date")
    suspend fun forSymbol(symbol: String): List<BarEntity>

    @Query("SELECT * FROM bars WHERE symbol = :symbol ORDER BY date")
    fun observe(symbol: String): Flow<List<BarEntity>>

    @Query("SELECT COUNT(*) FROM bars WHERE symbol = :symbol")
    suspend fun count(symbol: String): Int

    @Query("SELECT MAX(date) FROM bars WHERE symbol = :symbol")
    suspend fun lastDate(symbol: String): String?

    @Query("DELETE FROM bars WHERE date < :before")
    suspend fun pruneBefore(before: String)
}

@Dao
interface AnalysisDao {
    @Upsert
    suspend fun upsert(rows: List<AnalysisEntity>)

    @Query("SELECT * FROM analyses")
    fun observeAll(): Flow<List<AnalysisEntity>>

    @Query("SELECT * FROM analyses")
    suspend fun all(): List<AnalysisEntity>

    @Query("SELECT * FROM analyses WHERE symbol = :symbol")
    fun observe(symbol: String): Flow<AnalysisEntity?>
}

@Dao
interface TradeDao {
    @Insert
    suspend fun insert(trade: TradeEntity): Long

    @Query("DELETE FROM trades WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM trades ORDER BY date DESC, id DESC")
    fun observeAll(): Flow<List<TradeEntity>>

    @Query("SELECT * FROM trades")
    suspend fun all(): List<TradeEntity>
}

@Dao
interface WatchDao {
    @Upsert
    suspend fun add(item: WatchEntity)

    @Query("DELETE FROM watchlist WHERE symbol = :symbol")
    suspend fun remove(symbol: String)

    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<WatchEntity>>

    @Query("SELECT symbol FROM watchlist")
    suspend fun symbols(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE symbol = :symbol)")
    fun observeContains(symbol: String): Flow<Boolean>
}

@Dao
interface AlertDao {
    @Insert
    suspend fun insert(alert: AlertEntity): Long

    @Upsert
    suspend fun update(alerts: List<AlertEntity>)

    @Query("DELETE FROM alerts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM alerts ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts WHERE active = 1")
    suspend fun active(): List<AlertEntity>
}

@Dao
interface DividendDao {
    @Upsert
    suspend fun upsert(rows: List<DividendEntity>)

    @Query("SELECT * FROM dividends WHERE symbol = :symbol ORDER BY exDate DESC")
    fun observe(symbol: String): Flow<List<DividendEntity>>

    @Query("SELECT * FROM dividends ORDER BY exDate DESC")
    fun observeAll(): Flow<List<DividendEntity>>
}

@Dao
interface DisclosureDao {
    /** Keeps any AI summary we already have for an existing disclosure. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(rows: List<DisclosureEntity>)

    @Query("UPDATE disclosures SET summary = :summary, impact = :impact, keyPoints = :keyPoints WHERE id = :id")
    suspend fun setSummary(id: String, summary: String, impact: String, keyPoints: String)

    @Query("SELECT * FROM disclosures WHERE symbol = :symbol ORDER BY announcedAt DESC LIMIT :limit")
    fun observe(symbol: String, limit: Int): Flow<List<DisclosureEntity>>

    @Query("SELECT * FROM disclosures WHERE symbol IN (:symbols) ORDER BY announcedAt DESC LIMIT 100")
    fun observeFor(symbols: List<String>): Flow<List<DisclosureEntity>>
}

@Dao
interface KvDao {
    @Upsert
    suspend fun put(entry: KvEntity)

    @Query("SELECT * FROM kv WHERE `key` = :key")
    suspend fun get(key: String): KvEntity?

    @Query("SELECT * FROM kv WHERE `key` = :key")
    fun observe(key: String): Flow<KvEntity?>
}
