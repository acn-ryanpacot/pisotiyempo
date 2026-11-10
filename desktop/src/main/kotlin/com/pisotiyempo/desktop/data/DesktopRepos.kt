package com.pisotiyempo.desktop.data

import com.pisotiyempo.data.remote.AnalyzeRequest
import com.pisotiyempo.data.remote.BacktestFeatureDto
import com.pisotiyempo.data.remote.LevelsDto
import com.pisotiyempo.data.remote.PatternFeatureDto
import com.pisotiyempo.data.remote.PhisixClient
import com.pisotiyempo.data.remote.PisoApi
import com.pisotiyempo.data.remote.QuoteDto
import com.pisotiyempo.domain.analysis.MarketBrief
import com.pisotiyempo.domain.analysis.MarketStats
import com.pisotiyempo.domain.analysis.Narrative
import com.pisotiyempo.domain.analysis.NarrativeSource
import com.pisotiyempo.domain.analysis.RuleBasedAnalyst
import com.pisotiyempo.domain.analysis.ScreenRow
import com.pisotiyempo.domain.analysis.StockAnalyzer
import com.pisotiyempo.domain.model.Bar
import com.pisotiyempo.domain.model.Direction
import com.pisotiyempo.domain.model.Stance
import com.pisotiyempo.domain.model.StockAnalysis
import com.pisotiyempo.domain.ph.Sectors
import com.pisotiyempo.domain.portfolio.PortfolioMath
import com.pisotiyempo.domain.portfolio.PortfolioSummary
import com.pisotiyempo.domain.portfolio.Side
import com.pisotiyempo.domain.portfolio.Trade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

val MANILA: ZoneId = ZoneId.of("Asia/Manila")

data class StockRowData(val symbol: String, val name: String, val sector: String, val close: Double, val changePct: Double?, val volume: Long, val date: String)

data class TradeRow(val uid: String, val symbol: String, val side: String, val shares: Long, val price: Double, val fees: Double, val date: String, val note: String, val account: String)

data class JournalRow(
    val uid: String, val symbol: String, val date: String, val setup: String, val reason: String, val emotion: String,
    val mistakes: List<String>, val lesson: String, val rating: Int?, val createdAt: Long,
)

/**
 * Market data on the desktop: the same backend and fallbacks as the phone (backend → phisix directly),
 * stored in the local SQLite file, and scored with the shared analysis engine.
 */
class DesktopMarket(private val db: Db, private val api: PisoApi, private val phisix: PhisixClient = PhisixClient()) {
    private val _version = MutableStateFlow(0L)
    /** Bumped after every data change so screens know to reload. */
    val version: StateFlow<Long> = _version

    fun stocks(): List<StockRowData> = db.query("SELECT * FROM stocks ORDER BY symbol") { it.toStock() }

    fun stock(symbol: String): StockRowData? = db.one("SELECT * FROM stocks WHERE symbol = ?", symbol) { it.toStock() }

    fun bars(symbol: String): List<Bar> = db.query("SELECT * FROM bars WHERE symbol = ? ORDER BY date", symbol) {
        Bar(LocalDate.parse(it.getString("date")), it.getDouble("open"), it.getDouble("high"), it.getDouble("low"), it.getDouble("close"), it.getLong("volume"))
    }

    fun psei(): List<Bar> = bars(PSEI)

    fun lastSyncDate(): String? = db.kvGet("market-date")

    /** Latest prices, PSEi, two years of history for the analysed universe, then scores. Returns a status line. */
    suspend fun sync(mine: Set<String>, onProgress: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        onProgress("Fetching latest prices")
        val (quotes, source) = try {
            api.latest().let { it.data.quotes to it.source }
        } catch (e: Exception) {
            val res = phisix.snapshot()
            var date = LocalDate.parse(res.as_of.take(10))
            while (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) date = date.minusDays(1)
            res.stocks.filter { it.price.amount > 0 }.map {
                QuoteDto(it.symbol.uppercase(), it.name, date.toString(), close = it.price.amount, volume = it.volume, changePct = it.percentChange)
            } to "phisix (direct)"
        }
        val names = db.query("SELECT symbol, name FROM stocks") { it.getString(1) to it.getString(2) }.toMap()
        db.batch(
            "INSERT OR REPLACE INTO stocks (symbol, name, sector, close, changePct, volume, date, source) VALUES (?,?,?,?,?,?,?,?)",
            quotes.map { q -> arrayOf(q.symbol, q.name ?: names[q.symbol] ?: q.symbol, Sectors.of(q.symbol), q.close, q.changePct, q.volume ?: 0L, q.date, source) },
        )
        val full = quotes.all { it.open != null }
        db.batch(
            "INSERT OR ${if (full) "REPLACE" else "IGNORE"} INTO bars (symbol, date, open, high, low, close, volume) VALUES (?,?,?,?,?,?,?)",
            quotes.map { q -> arrayOf(q.symbol, q.date, q.open ?: q.close, q.high ?: q.close, q.low ?: q.close, q.close, q.volume ?: 0L) },
        )
        runCatching {
            val from = (db.one("SELECT MAX(date) FROM bars WHERE symbol = ?", PSEI) { it.getString(1) }?.let { LocalDate.parse(it).minusDays(5) }
                ?: LocalDate.now(MANILA).minusYears(2)).toString()
            val res = api.psei(from)
            db.batch(
                "INSERT OR REPLACE INTO bars (symbol, date, open, high, low, close, volume) VALUES (?,?,?,?,?,?,?)",
                res.data.map { b -> arrayOf(PSEI, b.date, b.open ?: b.close, b.high ?: b.close, b.low ?: b.close, b.close, b.volume ?: 0L) },
            )
        }
        val universe = (db.query("SELECT symbol FROM stocks WHERE date = (SELECT MAX(date) FROM stocks) ORDER BY close * volume DESC LIMIT 120") { it.getString(1) } + mine).distinct()
        onProgress("Updating history for ${universe.size} stocks")
        universe.forEach { ensureHistory(it) }
        onProgress("Scanning patterns")
        val scored = refreshAnalyses(universe)
        val date = quotes.groupingBy { it.date }.eachCount().maxByOrNull { it.value }?.key
        date?.let { db.kvPut("market-date", it) }
        db.kvPut("market-source", source)
        _version.value = System.currentTimeMillis()
        "${quotes.size} prices as of ${date ?: "?"} from $source; scored $scored stocks"
    }

    suspend fun ensureHistory(symbol: String): Boolean {
        val count = db.one("SELECT COUNT(*) FROM bars WHERE symbol = ?", symbol) { it.getInt(1) } ?: 0
        val today = LocalDate.now(MANILA).toString()
        if (count >= 200) return true
        if (db.kvGet("backfill:$symbol") == today) return count >= StockAnalyzer.MIN_BARS
        return runCatching {
            val res = api.history(symbol, LocalDate.now(MANILA).minusYears(2).toString())
            db.batch(
                "INSERT OR REPLACE INTO bars (symbol, date, open, high, low, close, volume) VALUES (?,?,?,?,?,?,?)",
                res.data.map { b -> arrayOf(symbol, b.date, b.open ?: b.close, b.high ?: b.close, b.low ?: b.close, b.close, b.volume ?: 0L) },
            )
            db.kvPut("backfill:$symbol", today)
            true
        }.getOrDefault(false)
    }

    fun analyze(symbol: String, withBacktest: Boolean): StockAnalysis? {
        val name = stock(symbol)?.name ?: symbol
        return StockAnalyzer.analyze(symbol, name, bars(symbol), psei(), withBacktest)
    }

    private fun refreshAnalyses(symbols: List<String>): Int {
        val index = psei()
        val rows = symbols.mapNotNull { s ->
            val a = StockAnalyzer.analyze(s, stock(s)?.name ?: s, bars(s), index) ?: return@mapNotNull null
            arrayOf<Any?>(
                a.symbol, a.name, Sectors.of(a.symbol), a.asOf.toString(), a.close, a.changePct, a.score, a.stance.name,
                a.patterns.firstOrNull { it.direction != Direction.NEUTRAL }?.name, a.indicators["rsi14"],
                a.indicators["sma50"]?.let { a.close > it }, a.indicators["sma200"]?.let { a.close > it },
                a.patterns.filter { it.direction != Direction.NEUTRAL }.joinToString("\n") { it.name },
                a.patterns.count { it.direction == Direction.BULLISH }, a.patterns.count { it.direction == Direction.BEARISH },
                a.volumeRatio, a.return3mPct, a.avgDailyValue, a.isLiquid, System.currentTimeMillis(),
            )
        }
        db.batch(
            "INSERT OR REPLACE INTO analyses (symbol, name, sector, asOf, close, changePct, score, stance, topSignal, rsi14, aboveSma50, aboveSma200, " +
                "patternNames, bullishCount, bearishCount, volumeRatio, return3mPct, avgDailyValue, isLiquid, updatedAt) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            rows,
        )
        return rows.size
    }

    fun screenRows(): List<ScreenRow> = db.query("SELECT * FROM analyses") {
        ScreenRow(
            symbol = it.getString("symbol"), name = it.getString("name"), sector = it.getString("sector"), close = it.getDouble("close"),
            changePct = it.doubleOrNull("changePct"), score = it.getInt("score"),
            stance = runCatching { Stance.valueOf(it.getString("stance")) }.getOrDefault(Stance.HOLD),
            rsi14 = it.doubleOrNull("rsi14"), aboveSma50 = it.boolOrNull("aboveSma50"), aboveSma200 = it.boolOrNull("aboveSma200"),
            patterns = it.getString("patternNames").lines().filter { p -> p.isNotBlank() },
            bullishCount = it.getInt("bullishCount"), bearishCount = it.getInt("bearishCount"), volumeRatio = it.doubleOrNull("volumeRatio"),
            return3mPct = it.doubleOrNull("return3mPct"), avgDailyValue = it.getDouble("avgDailyValue"), isLiquid = it.getInt("isLiquid") == 1,
        )
    }

    fun marketStats(): MarketStats? {
        val all = stocks()
        val date = all.groupingBy { it.date }.eachCount().maxByOrNull { it.value }?.key ?: return null
        val day = all.filter { it.date == date }
        val liquid = day.filter { it.close * it.volume >= 5_000_000 && it.changePct != null }.sortedByDescending { it.changePct }
        val p = psei()
        val last = p.lastOrNull()
        val prev = p.getOrNull(p.size - 2)
        return MarketStats(
            date, last?.close, if (last != null && prev != null) (last.close - prev.close) / prev.close * 100 else null,
            day.count { (it.changePct ?: 0.0) > 0 }, day.count { (it.changePct ?: 0.0) < 0 }, day.count { it.changePct == 0.0 },
            day.sumOf { it.close * it.volume },
            liquid.filter { it.changePct!! > 0 }.take(5).map { it.symbol to it.changePct!! },
            liquid.reversed().filter { it.changePct!! < 0 }.take(5).map { it.symbol to it.changePct!! },
        )
    }

    private fun java.sql.ResultSet.toStock() = StockRowData(
        getString("symbol"), getString("name"), getString("sector"), getDouble("close"), doubleOrNull("changePct"), getLong("volume"), getString("date"),
    )

    companion object {
        const val PSEI = "^PSEI"
    }
}

/** Trades, journal, watchlist and alerts on the desktop. Every write stamps updatedAt (and tombstones deletes) for sync. */
class DesktopPortfolio(private val db: Db, private val onChange: () -> Unit) {
    fun trades(account: String = "REAL"): List<TradeRow> =
        db.query("SELECT * FROM trades WHERE account = ? ORDER BY date DESC", account) {
            TradeRow(it.getString("uid"), it.getString("symbol"), it.getString("side"), it.getLong("shares"), it.getDouble("price"),
                it.getDouble("fees"), it.getString("date"), it.getString("note"), it.getString("account"))
        }

    fun summary(prices: Map<String, Pair<Double, Double?>>, account: String = "REAL"): PortfolioSummary =
        PortfolioMath.summarize(
            trades(account).mapIndexed { i, t -> Trade(i.toLong(), t.symbol, Side.valueOf(t.side), t.shares, t.price, t.fees, LocalDate.parse(t.date)) },
            prices,
        )

    fun addTrade(symbol: String, side: Side, shares: Long, price: Double, fees: Double, date: LocalDate, account: String = "REAL", note: String = "") {
        db.exec(
            "INSERT INTO trades (uid, symbol, side, shares, price, fees, date, note, account, updatedAt) VALUES (?,?,?,?,?,?,?,?,?,?)",
            newUid(), symbol, side.name, shares, price, fees, date.toString(), note, account, System.currentTimeMillis(),
        )
        onChange()
    }

    fun deleteTrade(uid: String) = delete("trades", "uid", uid, uid, "trade")

    fun journal(): List<JournalRow> = db.query("SELECT * FROM journal ORDER BY date DESC") {
        JournalRow(it.getString("uid"), it.getString("symbol"), it.getString("date"), it.getString("setup"), it.getString("reason"),
            it.getString("emotion"), it.getString("mistakes").lines().filter { m -> m.isNotBlank() }, it.getString("lesson"), it.intOrNull("rating"), it.getLong("createdAt"))
    }

    fun addJournal(symbol: String, date: LocalDate, setup: String, emotion: String, mistakes: List<String>, rating: Int?, reason: String, lesson: String) {
        val now = System.currentTimeMillis()
        db.exec(
            "INSERT INTO journal (uid, symbol, date, setup, reason, emotion, mistakes, lesson, rating, createdAt, updatedAt) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            newUid(), symbol, date.toString(), setup, reason, emotion, mistakes.joinToString("\n"), lesson, rating, now, now,
        )
        onChange()
    }

    fun deleteJournal(uid: String) = delete("journal", "uid", uid, uid, "journal")

    fun watchlist(): List<String> = db.query("SELECT symbol FROM watchlist ORDER BY addedAt DESC") { it.getString(1) }

    fun setWatched(symbol: String, watched: Boolean) {
        val now = System.currentTimeMillis()
        if (watched) {
            db.exec("INSERT OR REPLACE INTO watchlist (symbol, addedAt, updatedAt) VALUES (?,?,?)", symbol, now, now)
            db.exec("DELETE FROM tombstones WHERE uid = ?", watchUid(symbol))
            onChange()
        } else {
            delete("watchlist", "symbol", symbol, watchUid(symbol), "watch")
        }
    }

    private fun delete(table: String, keyCol: String, key: String, uid: String, type: String) {
        db.exec("DELETE FROM $table WHERE $keyCol = ?", key)
        db.exec("INSERT OR REPLACE INTO tombstones (uid, type, deletedAt) VALUES (?,?,?)", uid, type, System.currentTimeMillis())
        onChange()
    }

    private fun newUid() = UUID.randomUUID().toString().replace("-", "")
}

/** Claude text with the rules-engine fallback, same as the phone. */
class DesktopAi(private val api: PisoApi, private val config: DesktopConfig) {
    suspend fun stock(a: StockAnalysis): Narrative {
        if (!config.aiEnabled) return RuleBasedAnalyst.stock(a)
        return try {
            val r = api.analyze(
                AnalyzeRequest(
                    a.symbol, a.name, a.asOf.toString(), a.close, a.changePct, a.score, a.indicators,
                    a.patterns.take(12).map { PatternFeatureDto(it.name, it.direction.name, it.confidence, it.detail.take(200)) },
                    LevelsDto(a.supports.map { it.price }, a.resistances.map { it.price }),
                    a.backtest.take(12).map { BacktestFeatureDto(it.signalName, it.winRate, it.samples, it.avgReturnPct) },
                    a.signals.take(15),
                ),
            ).analysis
            Narrative(
                r.headline, r.oneLiner, r.summary, r.bullCase, r.bearCase, r.keyLevels, r.riskNote,
                runCatching { Stance.valueOf(r.stance) }.getOrDefault(Stance.HOLD), r.confidence, NarrativeSource.CLAUDE_HAIKU,
            )
        } catch (e: Exception) {
            RuleBasedAnalyst.stock(a)
        }
    }

    suspend fun brief(stats: MarketStats): MarketBrief {
        if (config.aiEnabled) {
            runCatching { api.brief() }.getOrNull()?.takeIf { it.date == stats.date }?.let { r ->
                return MarketBrief(r.date, r.brief.headline, r.brief.mood, r.brief.overview, r.brief.highlights, r.brief.risks, r.brief.watchTomorrow, NarrativeSource.CLAUDE_OPUS)
            }
        }
        return RuleBasedAnalyst.market(stats)
    }
}
