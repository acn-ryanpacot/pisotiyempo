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
import com.pisotiyempo.data.remote.CalendarDto
import com.pisotiyempo.data.remote.EodhdDirect
import com.pisotiyempo.data.remote.ForeignFlowDto
import com.pisotiyempo.data.remote.PhisixClient
import com.pisotiyempo.data.remote.PisoApi
import com.pisotiyempo.data.remote.QuoteDto
import com.pisotiyempo.data.remote.StatusDto
import com.pisotiyempo.data.settings.SecretStore
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
 * Market data with a local-first design: everything is stored in Room so the app works offline. Sync order:
 * the user's own EODHD key (if they added one), then the backend (which falls back from EODHD to free
 * sources), then the phisix community feed directly.
 */
class MarketRepository(
    private val db: AppDatabase,
    private val api: PisoApi,
    private val phisix: PhisixClient,
    private val secrets: SecretStore,
) {
    fun stocks(): Flow<List<StockEntity>> = db.stocks().observeAll()

    fun stock(symbol: String): Flow<StockEntity?> = db.stocks().observe(symbol)

    fun bars(symbol: String): Flow<List<Bar>> = db.bars().observe(symbol).map { rows -> rows.map { it.toBar() } }

    suspend fun loadBars(symbol: String): List<Bar> = db.bars().forSymbol(symbol).map { it.toBar() }

    suspend fun search(query: String): List<StockEntity> = db.stocks().search(query.trim().uppercase())

    suspend fun mostActive(limit: Int): List<String> = db.stocks().mostActive(limit)

    private suspend fun userEodhd(): EodhdDirect? = secrets.get(SecretStore.EODHD_KEY)?.let { EodhdDirect(it) }

    suspend fun hasUserEodhdKey(): Boolean = secrets.get(SecretStore.EODHD_KEY) != null

    /** Saves the user's own EODHD key after checking it works for PSE data. Pass null to remove it. */
    suspend fun setUserEodhdKey(key: String?): Boolean {
        if (key.isNullOrBlank()) {
            secrets.put(SecretStore.EODHD_KEY, null)
            return true
        }
        if (!EodhdDirect(key.trim()).check(LocalDate.now(MANILA).minusDays(14).toString())) return false
        secrets.put(SecretStore.EODHD_KEY, key.trim())
        return true
    }

    /** EODHD's bulk feed has no % change, so derive it from the last close we stored for each symbol. */
    private suspend fun withChange(quotes: List<QuoteDto>): List<QuoteDto> {
        val date = quotes.groupingBy { it.date }.eachCount().maxByOrNull { it.value }?.key ?: return quotes
        val prev = db.bars().previousCloses(date).associate { it.symbol to it.close }
        return quotes.map { q ->
            val p = prev[q.symbol]
            if (q.changePct != null || p == null || p <= 0) q else q.copy(changePct = (q.close - p) / p * 100)
        }
    }

    /** Pulls the latest trading day for all stocks and appends it to local history. */
    suspend fun syncSnapshot(): SnapshotResult {
        val own = userEodhd()?.let { direct ->
            try {
                Triple(withChange(direct.latestDay()), "eodhd (your key)", emptyList<String>())
            } catch (e: Exception) {
                Log.w(TAG, "user EODHD key failed, falling back to the backend", e)
                null
            }
        }
        val (quotes, source, notes) = own ?: try {
            val res = api.latest()
            Triple(res.data.quotes, res.source, res.notes)
        } catch (e: Exception) {
            Log.w(TAG, "backend snapshot failed, using phisix directly", e)
            val res = phisix.snapshot()
            // phisix stamps today's date even when the market is closed, so roll back to the last trading day.
            val date = lastTradingDayOnOrBefore(LocalDate.parse(res.as_of.take(10)), holidays()).toString()
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
            val own = userEodhd()?.let { runCatching { it.history(symbol, from) }.getOrNull()?.takeIf { bars -> bars.isNotEmpty() } }
            val bars = own ?: api.history(symbol, from).data
            db.bars().insert(bars.map { it.toBarEntity(symbol) })
            db.kv().put(KvEntity(key, today, System.currentTimeMillis()))
            true
        } catch (e: Exception) {
            Log.w(TAG, "history backfill failed for $symbol", e)
            false
        }
    }

    /** Downloads PSE holidays and ad-hoc closures; kept locally so the offline fallback can use them. */
    suspend fun syncCalendar() {
        try {
            val from = LocalDate.now(MANILA).minusYears(1).withDayOfYear(1).toString()
            val res = api.calendar(from)
            db.kv().put(KvEntity(CALENDAR_KEY, ApiJson.encodeToString(CalendarDto.serializer(), res), System.currentTimeMillis()))
        } catch (e: Exception) {
            Log.w(TAG, "calendar sync failed", e)
        }
    }

    private suspend fun calendar(): Map<String, String> =
        db.kv().get(CALENDAR_KEY)
            ?.let { runCatching { ApiJson.decodeFromString(CalendarDto.serializer(), it.value) }.getOrNull() }
            ?.holidays?.associate { it.date to it.name }
            .orEmpty()

    /** Known PSE holidays as ISO dates; empty until the first successful calendar sync. */
    suspend fun holidays(): Set<String> = calendar().keys

    /** Why the PSE is closed today ("Weekend" or the holiday's name), or null on a trading day. */
    suspend fun closedToday(): String? {
        val today = LocalDate.now(MANILA)
        if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) return "Weekend"
        return calendar()[today.toString()]
    }

    /** Backend health and configuration, for the Settings screen. Throws when the backend can't be reached. */
    suspend fun backendStatus(): StatusDto = api.status()

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
        private const val CALENDAR_KEY = "calendar"

        fun lastTradingDayOnOrBefore(date: LocalDate, holidays: Set<String> = emptySet()): LocalDate {
            var d = date
            var guard = 0
            while ((d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY || d.toString() in holidays) && guard++ < 30) {
                d = d.minusDays(1)
            }
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
