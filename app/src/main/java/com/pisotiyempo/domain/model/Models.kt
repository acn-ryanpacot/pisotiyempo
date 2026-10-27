package com.pisotiyempo.domain.model

import java.time.LocalDate

/** One trading day. When a source has no open/high/low, they equal the close and [hasRange] is false. */
data class Bar(
    val date: LocalDate,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
) {
    val hasRange: Boolean get() = !(open == close && high == close && low == close)
    val body: Double get() = kotlin.math.abs(close - open)
    val range: Double get() = high - low
    val upperShadow: Double get() = high - maxOf(open, close)
    val lowerShadow: Double get() = minOf(open, close) - low
    val isUp: Boolean get() = close > open
    val isDown: Boolean get() = close < open
}

enum class Direction { BULLISH, BEARISH, NEUTRAL;

    val sign: Int get() = when (this) { BULLISH -> 1; BEARISH -> -1; NEUTRAL -> 0 }
}

enum class PatternCategory(val label: String) {
    CANDLESTICK("Candlestick"),
    CHART("Chart pattern"),
    INDICATOR("Indicator signal"),
    ADVANCED("Advanced"),
}

data class PatternHit(
    /** Stable id used to group backtest results, e.g. "bullish_engulfing". */
    val id: String,
    val name: String,
    val category: PatternCategory,
    val direction: Direction,
    /** 0..1 */
    val confidence: Double,
    val startIndex: Int,
    val endIndex: Int,
    val detail: String,
    val confirmed: Boolean = true,
    val target: Double? = null,
    val invalidation: Double? = null,
)

data class PriceLevel(val price: Double, val touches: Int, val kind: Kind) {
    enum class Kind { SUPPORT, RESISTANCE }
}

data class FibLevel(val ratio: Double, val price: Double)

data class BacktestStat(
    val signalId: String,
    val signalName: String,
    val direction: Direction,
    val samples: Int,
    /** Share of signals where price moved the expected way over the horizon. 0..1 */
    val winRate: Double,
    val avgReturnPct: Double,
    val horizonDays: Int,
)

enum class Stance(val label: String) {
    STRONG_BUY("Strong buy"),
    BUY("Buy"),
    HOLD("Hold"),
    REDUCE("Reduce"),
    SELL("Sell");

    companion object {
        fun fromScore(score: Int): Stance = when {
            score >= 50 -> STRONG_BUY
            score >= 20 -> BUY
            score > -20 -> HOLD
            score > -50 -> REDUCE
            else -> SELL
        }
    }
}

data class TradePlan(
    val entry: Double,
    val stop: Double,
    val target: Double,
    val riskPerShare: Double,
    val rewardToRisk: Double,
)

data class StockAnalysis(
    val symbol: String,
    val name: String,
    val asOf: LocalDate,
    val close: Double,
    val changePct: Double?,
    val barsUsed: Int,
    val hasOhlc: Boolean,
    /** Latest indicator readings keyed by short names (rsi14, sma50, ...). Null when there isn't enough history. */
    val indicators: Map<String, Double?>,
    val patterns: List<PatternHit>,
    val supports: List<PriceLevel>,
    val resistances: List<PriceLevel>,
    val fibLevels: List<FibLevel>,
    /** -100 (strong sell) .. +100 (strong buy) */
    val score: Int,
    /** Score components, each -1..1, keyed by name, for the "why" breakdown. */
    val components: Map<String, Double>,
    val signals: List<String>,
    val plan: TradePlan?,
    val avgDailyValue: Double,
    val backtest: List<BacktestStat> = emptyList(),
) {
    val stance: Stance get() = Stance.fromScore(score)
    val isLiquid: Boolean get() = avgDailyValue >= MIN_LIQUID_VALUE

    companion object {
        /** ₱2M average daily turnover: below this, getting in and out at quoted prices is unreliable. */
        const val MIN_LIQUID_VALUE = 2_000_000.0
    }
}
