package com.pisotiyempo.data.repo

import android.util.Log
import com.pisotiyempo.data.local.AppDatabase
import com.pisotiyempo.data.local.BarEntity
import com.pisotiyempo.data.local.DisclosureEntity
import com.pisotiyempo.data.local.DividendEntity
import com.pisotiyempo.data.local.KvEntity
import com.pisotiyempo.data.local.StockEntity
import com.pisotiyempo.data.remote.ApiJson
import com.pisotiyempo.data.remote.BarDto
import com.pisotiyempo.data.remote.ForeignFlowDto
import com.pisotiyempo.data.remote.PhisixClient
import com.pisotiyempo.data.remote.PisoApi
import com.pisotiyempo.data.remote.QuoteDto
import com.pisotiyempo.domain.model.Bar
import com.pisotiyempo.domain.ph.Sectors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

val MANILA: ZoneId = ZoneId.of("Asia/Manila")

data class SnapshotResult(val date: String, val source: String, val count: Int, val notes: List<String>)

/**
 * Market data with a local-first design: everything is stored in Room so the app works offline, and
 * sync pulls from the backend (which itself falls back from EODHD to free sources). If the backend is
 * down, the latest prices come straight from the phisix community feed.
 */
class MarketRepository(
    private val db: AppDatabase,
    private val api: PisoApi,
    private val phisix: PhisixClient,
) {
    fun stocks(): Flow<List<StockEntity>> = db.stocks().observeAll()

    fun stock(symbol: String): Flow<StockEntity?> = db.stocks().observe(symbol)

    fun bars(symbol: String): Flow<List<Bar>> = db.bars().observe(symbol).map { rows -> rows.map { it.toBar() } }

    suspend fun loadBars(symbol: String): List<Bar> = db.bars().forSymbol(symbol).map { it.toBar() }

    suspend fun search(query: String): List<StockEntity> = db.stocks().search(query.trim().uppercase())

    suspend fun mostActive(limit: Int): List<String> = db.stocks().mostActive(limit)

    /** Pulls the latest trading day for all stocks and appends it to local history. */
    suspend fun syncSnapshot(): SnapshotResult {
        val (quotes, source, notes) = try {
            val res = api.latest()
            Triple(res.data.quotes, res.source, res.notes)
        } catch (e: Exception) {
            Log.w(TAG, "backend snapshot failed, using phisix directly", e)
            val res = phisix.snapshot()
            val date = lastWeekdayOnOrBefore(LocalDate.parse(res.as_of.take(10))).toString()
            Triple(
                res.stocks.filter { it.price.amount > 0 }.map {
                    QuoteDto(it.symbol.uppercase(), it.name, date, close = it.price.amount, volume = it.volume, changePct = it.percentChange)
                },
                "phisix (direct)",
                listOf("Backend unreachable; prices came straight from the community feed."),
            )
        }

        val names = db.stocks().all().associate { it.symbol to it.name }
        db.stocks().upsert(quotes.map { q ->
            StockEntity(
                symbol = q.symbol,
                name = q.name ?: names[q.symbol] ?: q.symbol,
                sector = Sectors.of(q.symbol),
                close = q.close,
                changePct = q.changePct,
                volume = q.volume ?: 0,
                date = q.date,
                source = source,
            )
        })
        val bars = quotes.map { it.toBarEntity() }
        // Full OHLC replaces what we have; close-only fallback rows never overwrite better data.
        if (quotes.all { it.open != null }) db.bars().insert(bars) else db.bars().insertMissing(bars)
        val date = quotes.groupingBy { it.date }.eachCount().maxByOrNull { it.value }?.key ?: LocalDate.now(MANILA).toString()
        return SnapshotResult(date, source, quotes.size, notes)
    }

    /**
     * Makes sure [symbol] has about two years of history. Backfills at most once a day per symbol so a
     * recent IPO with a short history doesn't trigger a download on every sync.
     */
    suspend fun ensureHistory(symbol: String, minBars: Int = 200): Boolean {
        val today = LocalDate.now(MANILA).toString()
        val count = db.bars().count(symbol)
        val lastDate = db.bars().lastDate(symbol)
        val gap = lastDate?.let { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(it), LocalDate.now(MANILA)) } ?: Long.MAX_VALUE
        if (count >= minBars && gap <= 7) return true
        val key = "backfill:$symbol"
        if (db.kv().get(key)?.value == today) return count >= MIN_ANALYSIS_BARS
        return try {
            val from = LocalDate.now(MANILA).minusYears(2).toString()
            val res = api.history(symbol, from)
            db.bars().insert(res.data.map { it.toBarEntity(symbol) })
            db.kv().put(KvEntity(key, today, System.currentTimeMillis()))
            true
        } catch (e: Exception) {
            Log.w(TAG, "history backfill failed for $symbol", e)
            false
        }
    }

    suspend fun syncPsei() {
        try {
            val from = (db.bars().lastDate(BarEntity.PSEI_SYMBOL)?.let { LocalDate.parse(it).minusDays(5) } ?: LocalDate.now(MANILA).minusYears(2)).toString()
            val res = api.psei(from)
            db.bars().insert(res.data.map { it.toBarEntity(BarEntity.PSEI_SYMBOL) })
        } catch (e: Exception) {
            Log.w(TAG, "PSEi sync failed", e)
        }
    }

    fun psei(): Flow<List<Bar>> = bars(BarEntity.PSEI_SYMBOL)

    suspend fun pseiBars(): List<Bar> = loadBars(BarEntity.PSEI_SYMBOL)

    suspend fun refreshDividends(symbol: String) {
        try {
            val res = api.dividends(symbol)
            db.dividends().upsert(res.data.filter { it.exDate != null }.map {
                DividendEntity(symbol, it.exDate!!, it.type, it.amount, it.rateText, it.recordDate, it.paymentDate)
            })
        } catch (e: Exception) {
            Log.w(TAG, "dividends failed for $symbol", e)
        }
    }

    fun dividends(symbol: String): Flow<List<DividendEntity>> = db.dividends().observe(symbol)

    fun allDividends(): Flow<List<DividendEntity>> = db.dividends().observeAll()

    suspend fun refreshDisclosures(symbol: String) {
        try {
            val res = api.disclosures(symbol)
            db.disclosures().insertNew(res.data.map { DisclosureEntity(it.id, symbol, it.title, it.announcedAt, it.url) })
        } catch (e: Exception) {
            Log.w(TAG, "disclosures failed for $symbol", e)
        }
    }

    fun disclosures(symbol: String): Flow<List<DisclosureEntity>> = db.disclosures().observe(symbol, 20)

    fun disclosuresFor(symbols: List<String>): Flow<List<DisclosureEntity>> = db.disclosures().observeFor(symbols)

    /** Foreign flow needs a licensed feed on the backend; returns null when none is configured. */
    suspend fun foreignFlow(): ForeignFlowDto? {
        val key = "foreign-flow"
        return try {
            val res = api.foreignFlow().data
            db.kv().put(KvEntity(key, ApiJson.encodeToString(ForeignFlowDto.serializer(), res), System.currentTimeMillis()))
            res
        } catch (e: Exception) {
            db.kv().get(key)?.let { runCatching { ApiJson.decodeFromString(ForeignFlowDto.serializer(), it.value) }.getOrNull() }
        }
    }

    /** Keep about three years of bars; older data adds storage without changing any signal. */
    suspend fun prune() = db.bars().pruneBefore(LocalDate.now(MANILA).minusYears(3).toString())

    companion object {
        private const val TAG = "MarketRepository"
        private const val MIN_ANALYSIS_BARS = 30

        fun lastWeekdayOnOrBefore(date: LocalDate): LocalDate {
            var d = date
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.minusDays(1)
            return d
        }
    }
}

fun BarEntity.toBar() = Bar(LocalDate.parse(date), open, high, low, close, volume)

private fun QuoteDto.toBarEntity() = BarEntity(
    symbol = symbol,
    date = date,
    open = open ?: close,
    high = high ?: close,
    low = low ?: close,
    close = close,
    volume = volume ?: 0,
)

private fun BarDto.toBarEntity(symbol: String) = BarEntity(
    symbol = symbol,
    date = date,
    open = open ?: close,
    high = high ?: close,
    low = low ?: close,
    close = close,
    volume = volume ?: 0,
)
