package com.pisotiyempo.domain.analysis

import com.pisotiyempo.domain.indicators.Indicators
import com.pisotiyempo.domain.model.Bar
import com.pisotiyempo.domain.model.BacktestStat
import com.pisotiyempo.domain.model.Direction
import com.pisotiyempo.domain.model.PatternHit
import com.pisotiyempo.domain.model.StockAnalysis
import com.pisotiyempo.domain.model.TradePlan
import com.pisotiyempo.domain.patterns.Candlesticks
import com.pisotiyempo.domain.patterns.ChartPatterns
import com.pisotiyempo.domain.patterns.IndicatorSet
import com.pisotiyempo.domain.patterns.Signals
import com.pisotiyempo.domain.ph.PseRules
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Runs every indicator and pattern detector on one stock and combines them into a score from -100 to +100.
 *
 * Weights: trend 25, momentum 20, patterns 25, volume 10, Ichimoku/ADX 10, relative strength 10.
 * Each component is scaled to -1..1 before weighting.
 */
object StockAnalyzer {

    const val MIN_BARS = 30

    private val WEIGHTS = mapOf(
        "Trend" to 25.0,
        "Momentum" to 20.0,
        "Patterns" to 25.0,
        "Volume" to 10.0,
        "Trend strength" to 10.0,
        "Relative strength" to 10.0,
    )

    fun analyze(
        symbol: String,
        name: String,
        bars: List<Bar>,
        index: List<Bar> = emptyList(),
        withBacktest: Boolean = false,
    ): StockAnalysis? {
        if (bars.size < MIN_BARS) return null
        val s = IndicatorSet(bars)
        val i = bars.lastIndex
        val close = bars[i].close
        val hasOhlc = bars.takeLast(20).count { it.hasRange } >= 15

        val backtest = if (withBacktest) Backtester.run(bars, indicators = s) else emptyList()
        val patterns = buildList {
            if (hasOhlc) addAll(Candlesticks.detectRecent(bars, lookback = 2))
            addAll(ChartPatterns.detect(bars))
            for (j in maxOf(1, i - 2)..i) addAll(Signals.indicatorSignalsAt(s, j))
            addAll(Signals.stateSignals(s))
        }.let { hits ->
            val fibs = Signals.fibonacci(bars)
            hits + listOfNotNull(Signals.fibonacciTouch(bars, fibs))
        }.distinctBy { it.id }
        val (supports, resistances) = Signals.levels(bars)
        val fibs = Signals.fibonacci(bars)

        val components = linkedMapOf(
            "Trend" to trend(s, i),
            "Momentum" to momentum(s, i),
            "Patterns" to patternScore(patterns, backtest),
            "Volume" to volume(s, i),
            "Trend strength" to trendStrength(s, i),
            "Relative strength" to relativeStrength(bars, index),
        )
        val raw = components.entries.sumOf { (k, v) -> v * WEIGHTS.getValue(k) }
        val score = raw.toInt().coerceIn(-100, 100)

        val indicators = linkedMapOf(
            "rsi14" to s.rsi[i].orNull(),
            "macd" to s.macd.macd[i].orNull(),
            "macdSignal" to s.macd.signal[i].orNull(),
            "macdHist" to s.macd.histogram[i].orNull(),
            "sma20" to s.sma20[i].orNull(),
            "sma50" to s.sma50[i].orNull(),
            "sma200" to s.sma200[i].orNull(),
            "bbUpper" to s.bands.upper[i].orNull(),
            "bbLower" to s.bands.lower[i].orNull(),
            "atr14" to s.atr[i].orNull(),
            "adx14" to s.adx.adx[i].orNull(),
            "stochK" to s.stoch.k[i].orNull(),
            "stochD" to s.stoch.d[i].orNull(),
        )

        val prev = bars.getOrNull(i - 1)?.close
        return StockAnalysis(
            symbol = symbol,
            name = name,
            asOf = bars[i].date,
            close = close,
            changePct = prev?.let { (close - it) / it * 100 },
            barsUsed = bars.size,
            hasOhlc = hasOhlc,
            indicators = indicators,
            patterns = patterns.sortedByDescending { it.confidence },
            supports = supports,
            resistances = resistances,
            fibLevels = fibs,
            score = score,
            components = components,
            signals = describe(components, s, i),
            plan = tradePlan(close, s.atr[i].orNull(), supports.firstOrNull()?.price, resistances.firstOrNull()?.price, patterns),
            avgDailyValue = Indicators.averageValue(bars),
            backtest = backtest,
        )
    }

    private fun Double.orNull(): Double? = takeUnless { it.isNaN() }

    private fun trend(s: IndicatorSet, i: Int): Double {
        val c = s.closes[i]
        var score = 0.0
        var parts = 0
        fun add(v: Double) { score += v; parts++ }
        if (!s.sma20[i].isNaN()) add(if (c > s.sma20[i]) 0.5 else -0.5)
        if (!s.sma50[i].isNaN()) add(if (c > s.sma50[i]) 1.0 else -1.0)
        if (!s.sma200[i].isNaN()) add(if (c > s.sma200[i]) 1.0 else -1.0)
        if (!s.sma50[i].isNaN() && !s.sma200[i].isNaN()) add(if (s.sma50[i] > s.sma200[i]) 1.0 else -1.0)
        val slope = Indicators.normalizedSlope(s.sma50.takeIf { !it[i].isNaN() } ?: s.sma20, i, 10)
        if (!slope.isNaN()) add(tanh(slope * 400))
        return if (parts == 0) 0.0 else (score / parts).coerceIn(-1.0, 1.0)
    }

    private fun momentum(s: IndicatorSet, i: Int): Double {
        var score = 0.0
        val r = s.rsi[i]
        if (!r.isNaN()) {
            score += when {
                r < 30 -> 0.3 // oversold: bounce potential
                r < 45 -> -0.4
                r <= 65 -> (r - 50) / 25
                r <= 75 -> 0.4
                else -> -0.2 // stretched
            }
        }
        val h = s.macd.histogram[i]
        val hp = s.macd.histogram.getOrNull(i - 1) ?: Double.NaN
        if (!h.isNaN() && !hp.isNaN()) {
            score += if (h > 0) 0.35 else -0.35
            score += if (h > hp) 0.25 else -0.25
        }
        return score.coerceIn(-1.0, 1.0)
    }

    /** Pattern votes weighted by confidence and, where we have history, by how often that signal worked on this stock. */
    private fun patternScore(patterns: List<PatternHit>, backtest: List<BacktestStat>): Double {
        val byId = backtest.associateBy { it.signalId }
        var sum = 0.0
        for (p in patterns) {
            if (p.direction == Direction.NEUTRAL) continue
            val stat = byId[p.id]
            val reliability = if (stat != null && stat.samples >= 5) (stat.winRate - 0.5) * 2 + 0.5 else 0.5
            val confirmBoost = if (p.confirmed) 1.0 else 0.6
            sum += p.direction.sign * p.confidence * reliability.coerceIn(0.0, 1.0) * confirmBoost
        }
        return tanh(sum * 1.2)
    }

    private fun volume(s: IndicatorSet, i: Int): Double {
        if (i < 25) return 0.0
        val obvSlope = Indicators.normalizedSlope(s.obv.map { it + 1e9 }.toDoubleArray(), i, 20)
        val priceSlope = Indicators.normalizedSlope(s.closes, i, 20)
        if (obvSlope.isNaN() || priceSlope.isNaN()) return 0.0
        // OBV rising with price confirms the move; OBV diverging warns against it.
        return when {
            obvSlope > 0 && priceSlope > 0 -> 0.6
            obvSlope < 0 && priceSlope < 0 -> -0.6
            obvSlope > 0 -> 0.3
            obvSlope < 0 -> -0.3
            else -> 0.0
        }
    }

    private fun trendStrength(s: IndicatorSet, i: Int): Double {
        var score = 0.0
        val cloud = s.ichimoku.cloudAt(i)
        val c = s.closes[i]
        if (cloud != null) score += if (c > cloud.second) 0.5 else if (c < cloud.first) -0.5 else 0.0
        val adx = s.adx.adx[i]
        val pdi = s.adx.plusDi[i]
        val mdi = s.adx.minusDi[i]
        if (!adx.isNaN() && !pdi.isNaN() && !mdi.isNaN() && adx > 20) {
            score += (if (pdi > mdi) 0.5 else -0.5) * ((adx - 20) / 20).coerceAtMost(1.0)
        }
        return score.coerceIn(-1.0, 1.0)
    }

    /** 3-month return against the PSEi (or against zero when the index isn't available). */
    private fun relativeStrength(bars: List<Bar>, index: List<Bar>): Double {
        val lookback = 63
        if (bars.size <= lookback) return 0.0
        val stockRet = bars.last().close / bars[bars.size - 1 - lookback].close - 1
        val indexRet = if (index.size > lookback) index.last().close / index[index.size - 1 - lookback].close - 1 else 0.0
        return tanh((stockRet - indexRet) * 5)
    }

    private fun describe(components: Map<String, Double>, s: IndicatorSet, i: Int): List<String> {
        val out = mutableListOf<String>()
        val c = s.closes[i]
        if (!s.sma200[i].isNaN()) out += "Price is ${if (c > s.sma200[i]) "above" else "below"} the 200-day average (long-term ${if (c > s.sma200[i]) "uptrend" else "downtrend"})."
        if (!s.sma50[i].isNaN()) out += "Price is ${if (c > s.sma50[i]) "above" else "below"} the 50-day average."
        val r = s.rsi[i]
        if (!r.isNaN()) out += "RSI(14) is ${"%.0f".format(r)}${when { r > 70 -> " (overbought)"; r < 30 -> " (oversold)"; else -> "" }}."
        val h = s.macd.histogram[i]
        if (!h.isNaN()) out += "MACD momentum is ${if (h > 0) "positive" else "negative"}."
        val strongest = components.maxByOrNull { abs(it.value) }
        if (strongest != null && abs(strongest.value) > 0.3) out += "${strongest.key} is the biggest ${if (strongest.value > 0) "positive" else "negative"} factor."
        return out
    }

    /**
     * Long-side plan: stop at the nearer of 2×ATR or just under support; target at the pattern target,
     * the next resistance, or 2R, whichever is most conservative but still at least 1.5R.
     */
    private fun tradePlan(close: Double, atr: Double?, support: Double?, resistance: Double?, patterns: List<PatternHit>): TradePlan? {
        if (atr == null || atr <= 0) return null
        val atrStop = close - 2 * atr
        val supportStop = support?.let { it - 0.5 * atr }
        val stop = PseRules.roundToTick(listOfNotNull(atrStop, supportStop).filter { it < close }.maxOrNull() ?: atrStop)
        val risk = close - stop
        if (risk <= 0) return null
        val patternTarget = patterns.filter { it.direction == Direction.BULLISH && it.target != null && it.target > close }.minOfOrNull { it.target!! }
        val candidates = listOfNotNull(patternTarget, resistance, close + 2 * risk).filter { it >= close + 1.5 * risk }
        val target = PseRules.roundToTick(candidates.minOrNull() ?: (close + 2 * risk))
        return TradePlan(entry = close, stop = stop, target = target, riskPerShare = risk, rewardToRisk = (target - close) / risk)
    }
}
