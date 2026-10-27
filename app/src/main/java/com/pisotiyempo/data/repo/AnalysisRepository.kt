package com.pisotiyempo.data.repo

import com.pisotiyempo.data.local.AnalysisEntity
import com.pisotiyempo.data.local.AppDatabase
import com.pisotiyempo.domain.analysis.ScoredStock
import com.pisotiyempo.domain.analysis.StockAnalyzer
import com.pisotiyempo.domain.model.PatternCategory
import com.pisotiyempo.domain.model.Stance
import com.pisotiyempo.domain.model.StockAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class AnalysisRepository(
    private val db: AppDatabase,
    private val market: MarketRepository,
) {
    /** Full analysis including the backtest, for the stock detail screen. */
    suspend fun fullAnalysis(symbol: String): StockAnalysis? = withContext(Dispatchers.Default) {
        val bars = market.loadBars(symbol)
        val name = db.stocks().get(symbol)?.name ?: symbol
        StockAnalyzer.analyze(symbol, name, bars, market.pseiBars(), withBacktest = true)
            ?.also { db.analyses().upsert(listOf(it.toEntity())) }
    }

    /** Analysis without the backtest, for list rows that only need the narrative. */
    suspend fun quickAnalysis(symbol: String): StockAnalysis? = withContext(Dispatchers.Default) {
        val name = db.stocks().get(symbol)?.name ?: symbol
        StockAnalyzer.analyze(symbol, name, market.loadBars(symbol), market.pseiBars())
    }

    /** Re-scores every symbol in [symbols]; called by the daily sync. Backtests are skipped here for speed. */
    suspend fun refresh(symbols: Collection<String>): Int = withContext(Dispatchers.Default) {
        val index = market.pseiBars()
        val names = db.stocks().all().associate { it.symbol to it.name }
        val rows = symbols.mapNotNull { symbol ->
            StockAnalyzer.analyze(symbol, names[symbol] ?: symbol, market.loadBars(symbol), index)?.toEntity()
        }
        db.analyses().upsert(rows)
        rows.size
    }

    fun scored(): Flow<List<ScoredStock>> = db.analyses().observeAll().map { rows -> rows.map { it.toScored() } }

    suspend fun scoredNow(): List<ScoredStock> = db.analyses().all().map { it.toScored() }

    fun observe(symbol: String): Flow<AnalysisEntity?> = db.analyses().observe(symbol)
}

private fun StockAnalysis.toEntity() = AnalysisEntity(
    symbol = symbol,
    name = name,
    asOf = asOf.toString(),
    close = close,
    changePct = changePct,
    score = score,
    stance = stance.name,
    topSignal = patterns.firstOrNull { it.direction.sign != 0 }?.name,
    alertPatterns = patterns
        .filter { it.confirmed && it.confidence >= 0.6 && it.category != PatternCategory.INDICATOR }
        .joinToString("\n") { it.name },
    avgDailyValue = avgDailyValue,
    isLiquid = isLiquid,
    updatedAt = System.currentTimeMillis(),
)

fun AnalysisEntity.toScored() = ScoredStock(
    symbol = symbol,
    name = name,
    close = close,
    changePct = changePct,
    score = score,
    stance = runCatching { Stance.valueOf(stance) }.getOrDefault(Stance.fromScore(score)),
    topSignal = topSignal,
    avgDailyValue = avgDailyValue,
    isLiquid = isLiquid,
)
