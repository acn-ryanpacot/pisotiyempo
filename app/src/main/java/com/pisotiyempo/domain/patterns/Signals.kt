package com.pisotiyempo.domain.patterns

import com.pisotiyempo.domain.indicators.Indicators
import com.pisotiyempo.domain.model.Bar
import com.pisotiyempo.domain.model.Direction
import com.pisotiyempo.domain.model.FibLevel
import com.pisotiyempo.domain.model.PatternCategory
import com.pisotiyempo.domain.model.PatternHit
import com.pisotiyempo.domain.model.PriceLevel
import kotlin.math.abs

/** Precomputed indicator series so detectors and the backtester don't recompute them per bar. */
class IndicatorSet(val bars: List<Bar>) {
    val closes = Indicators.closes(bars)
    val sma20 = Indicators.sma(closes, 20)
    val sma50 = Indicators.sma(closes, 50)
    val sma200 = Indicators.sma(closes, 200)
    val ema20 = Indicators.ema(closes, 20)
    val rsi = Indicators.rsi(closes, 14)
    val macd = Indicators.macd(closes)
    val bands = Indicators.bollinger(closes)
    val atr = Indicators.atr(bars)
    val adx = Indicators.adx(bars)
    val stoch = Indicators.stochastic(bars)
    val obv = Indicators.obv(bars)
    val ichimoku = Indicators.ichimoku(bars)
}

/** Indicator-based signals and the "advanced" studies: divergences, Ichimoku, Fibonacci, Wyckoff, levels. */
object Signals {

    /** Signals that fire on bar [i] (crossovers, extremes, breakouts). Safe for backtesting. */
    fun indicatorSignalsAt(s: IndicatorSet, i: Int): List<PatternHit> {
        if (i < 1) return emptyList()
        val bars = s.bars
        val out = mutableListOf<PatternHit>()
        fun hit(id: String, name: String, dir: Direction, conf: Double, detail: String) {
            out += PatternHit(id, name, PatternCategory.INDICATOR, dir, conf, i, i, detail)
        }
        fun crossedAbove(a: DoubleArray, b: DoubleArray) =
            !a[i].isNaN() && !b[i].isNaN() && !a[i - 1].isNaN() && !b[i - 1].isNaN() && a[i - 1] <= b[i - 1] && a[i] > b[i]
        fun crossedBelow(a: DoubleArray, b: DoubleArray) =
            !a[i].isNaN() && !b[i].isNaN() && !a[i - 1].isNaN() && !b[i - 1].isNaN() && a[i - 1] >= b[i - 1] && a[i] < b[i]

        if (crossedAbove(s.sma50, s.sma200)) hit("golden_cross", "Golden cross", Direction.BULLISH, 0.6, "50-day average crossed above the 200-day average.")
        if (crossedBelow(s.sma50, s.sma200)) hit("death_cross", "Death cross", Direction.BEARISH, 0.6, "50-day average crossed below the 200-day average.")
        if (crossedAbove(s.macd.macd, s.macd.signal)) hit("macd_bull_cross", "MACD bullish crossover", Direction.BULLISH, if (s.macd.macd[i] < 0) 0.5 else 0.4, "MACD line crossed above its signal line.")
        if (crossedBelow(s.macd.macd, s.macd.signal)) hit("macd_bear_cross", "MACD bearish crossover", Direction.BEARISH, if (s.macd.macd[i] > 0) 0.5 else 0.4, "MACD line crossed below its signal line.")

        val r = s.rsi[i]
        val rp = s.rsi[i - 1]
        if (!r.isNaN() && !rp.isNaN()) {
            if (rp < 30 && r >= 30) hit("rsi_oversold_exit", "RSI leaves oversold", Direction.BULLISH, 0.5, "RSI rose back above 30 after being oversold.")
            if (rp > 70 && r <= 70) hit("rsi_overbought_exit", "RSI leaves overbought", Direction.BEARISH, 0.5, "RSI fell back below 70 after being overbought.")
        }

        val avgVol = Indicators.averageVolume(bars, i - 1, 20)
        val change = (bars[i].close - bars[i - 1].close) / bars[i - 1].close
        if (avgVol > 0 && bars[i].volume > 2.0 * avgVol && abs(change) >= 0.02) {
            val dir = if (change > 0) Direction.BULLISH else Direction.BEARISH
            hit(if (change > 0) "volume_surge_up" else "volume_surge_down", "Volume surge", dir, 0.45,
                "Volume ${fmt(bars[i].volume / avgVol)}x the 20-day average on a ${fmt(change * 100)}% move.")
        }

        if (i >= 252) {
            val priorHigh = (i - 252 until i).maxOf { bars[it].high }
            val priorLow = (i - 252 until i).minOf { bars[it].low }
            if (bars[i].close > priorHigh && bars[i - 1].close <= priorHigh) hit("52w_high_breakout", "52-week high breakout", Direction.BULLISH, 0.55, "Closed at a new 52-week high above ₱${fmt(priorHigh)}.")
            if (bars[i].close < priorLow && bars[i - 1].close >= priorLow) hit("52w_low_breakdown", "52-week low breakdown", Direction.BEARISH, 0.55, "Closed at a new 52-week low below ₱${fmt(priorLow)}.")
        }

        // Bollinger squeeze release: bandwidth was in its lowest 10% of the last 120 bars, then price closed outside a band.
        if (i >= 140 && !s.bands.middle[i].isNaN()) {
            val widths = (i - 120 until i).map { s.bands.bandwidth(it) }.filter { !it.isNaN() }.sorted()
            if (widths.size > 50) {
                val threshold = widths[widths.size / 10]
                val squeezed = (i - 5 until i).any { s.bands.bandwidth(it) <= threshold }
                if (squeezed && bars[i].close > s.bands.upper[i]) hit("squeeze_breakout_up", "Bollinger squeeze breakout", Direction.BULLISH, 0.55, "Volatility expanded upward out of a tight range.")
                if (squeezed && bars[i].close < s.bands.lower[i]) hit("squeeze_breakout_down", "Bollinger squeeze breakdown", Direction.BEARISH, 0.55, "Volatility expanded downward out of a tight range.")
            }
        }
        return out
    }

    /** Signals that describe the current state rather than a one-bar event. */
    fun stateSignals(s: IndicatorSet): List<PatternHit> {
        val i = s.bars.lastIndex
        if (i < 30) return emptyList()
        val out = mutableListOf<PatternHit>()
        val r = s.rsi[i]
        if (!r.isNaN() && r < 30) out += PatternHit("rsi_oversold", "RSI oversold", PatternCategory.INDICATOR, Direction.BULLISH, 0.35, i, i, "RSI at ${fmt(r)}: selling may be overdone.")
        if (!r.isNaN() && r > 70) out += PatternHit("rsi_overbought", "RSI overbought", PatternCategory.INDICATOR, Direction.BEARISH, 0.35, i, i, "RSI at ${fmt(r)}: the rally may be stretched.")
        divergence(s, i)?.let(out::add)
        ichimokuState(s, i)?.let(out::add)
        wyckoff(s.bars, i)?.let(out::add)
        return out
    }

    /** Regular RSI divergence between the last two swing lows (bullish) or highs (bearish) within 60 bars. */
    fun divergence(s: IndicatorSet, end: Int): PatternHit? {
        val pivots = Pivots.find(s.bars, window = 3, start = maxOf(0, end - 60), end = end)
        val lows = pivots.filter { !it.isHigh }.takeLast(2)
        val highs = pivots.filter { it.isHigh }.takeLast(2)
        if (lows.size == 2 && end - lows[1].index <= 10) {
            val (a, b) = lows
            if (b.price < a.price && !s.rsi[a.index].isNaN() && s.rsi[b.index] > s.rsi[a.index] + 2) {
                return PatternHit("bullish_rsi_divergence", "Bullish RSI divergence", PatternCategory.ADVANCED, Direction.BULLISH, 0.55, a.index, end,
                    "Price made a lower low but RSI made a higher low: downside momentum is fading.", invalidation = b.price)
            }
        }
        if (highs.size == 2 && end - highs[1].index <= 10) {
            val (a, b) = highs
            if (b.price > a.price && !s.rsi[a.index].isNaN() && s.rsi[b.index] < s.rsi[a.index] - 2) {
                return PatternHit("bearish_rsi_divergence", "Bearish RSI divergence", PatternCategory.ADVANCED, Direction.BEARISH, 0.55, a.index, end,
                    "Price made a higher high but RSI made a lower high: upside momentum is fading.", invalidation = b.price)
            }
        }
        return null
    }

    fun ichimokuState(s: IndicatorSet, i: Int): PatternHit? {
        val cloud = s.ichimoku.cloudAt(i) ?: return null
        val t = s.ichimoku.tenkan[i]
        val k = s.ichimoku.kijun[i]
        if (t.isNaN() || k.isNaN()) return null
        val close = s.bars[i].close
        return when {
            close > cloud.second && t > k -> PatternHit("ichimoku_bullish", "Ichimoku bullish", PatternCategory.ADVANCED, Direction.BULLISH, 0.5, i, i,
                "Price above the cloud with conversion line over base line.")
            close < cloud.first && t < k -> PatternHit("ichimoku_bearish", "Ichimoku bearish", PatternCategory.ADVANCED, Direction.BEARISH, 0.5, i, i,
                "Price below the cloud with conversion line under base line.")
            else -> null
        }
    }

    /**
     * Wyckoff accumulation/distribution, approximated: a 40-bar trading range after a 20%+ trend, with a
     * spring (or upthrust) that undercut (overshot) the range and closed back inside, and volume favouring
     * the expected side. This is a heuristic, not a full Wyckoff phase analysis.
     */
    fun wyckoff(bars: List<Bar>, end: Int): PatternHit? {
        if (end < 100) return null
        val rangeStart = end - 40
        val rangeHigh = (rangeStart..end - 5).maxOf { bars[it].high }
        val rangeLow = (rangeStart..end - 5).minOf { bars[it].low }
        val mid = (rangeHigh + rangeLow) / 2
        if ((rangeHigh - rangeLow) / mid > 0.20) return null
        val trend = (bars[rangeStart].close - bars[rangeStart - 60].close) / bars[rangeStart - 60].close
        var upVol = 0.0
        var downVol = 0.0
        for (i in rangeStart + 1..end) {
            if (bars[i].close > bars[i - 1].close) upVol += bars[i].volume else if (bars[i].close < bars[i - 1].close) downVol += bars[i].volume
        }
        val recent = end - 4..end
        if (trend < -0.20) {
            val spring = recent.any { bars[it].low < rangeLow && bars[it].close > rangeLow }
            if (spring && upVol > downVol * 1.1) {
                return PatternHit("wyckoff_accumulation", "Wyckoff accumulation (heuristic)", PatternCategory.ADVANCED, Direction.BULLISH, 0.45, rangeStart, end,
                    "Base after a ${fmt(-trend * 100)}% decline, with a spring below ₱${fmt(rangeLow)} and stronger volume on up days.", invalidation = recent.minOf { bars[it].low })
            }
        }
        if (trend > 0.20) {
            val upthrust = recent.any { bars[it].high > rangeHigh && bars[it].close < rangeHigh }
            if (upthrust && downVol > upVol * 1.1) {
                return PatternHit("wyckoff_distribution", "Wyckoff distribution (heuristic)", PatternCategory.ADVANCED, Direction.BEARISH, 0.45, rangeStart, end,
                    "Top range after a ${fmt(trend * 100)}% rally, with an upthrust above ₱${fmt(rangeHigh)} and heavier volume on down days.", invalidation = recent.maxOf { bars[it].high })
            }
        }
        return null
    }

    /** Support and resistance from clustered swing pivots over the last [lookback] bars. */
    fun levels(bars: List<Bar>, lookback: Int = 250, tolerance: Double = 0.015): Pair<List<PriceLevel>, List<PriceLevel>> {
        if (bars.size < 20) return emptyList<PriceLevel>() to emptyList()
        val close = bars.last().close
        val pivots = Pivots.find(bars, window = 4, start = maxOf(0, bars.size - lookback))
        val clusters = mutableListOf<MutableList<Double>>()
        for (p in pivots.map { it.price }.sorted()) {
            val c = clusters.lastOrNull()
            if (c != null && (p - c.average()) / c.average() <= tolerance) c += p else clusters += mutableListOf(p)
        }
        val levels = clusters.map { it.average() to it.size }
        val supports = levels.filter { it.first < close * 0.995 }.sortedByDescending { it.first }.take(3)
            .map { PriceLevel(it.first, it.second, PriceLevel.Kind.SUPPORT) }
        val resistances = levels.filter { it.first > close * 1.005 }.sortedBy { it.first }.take(3)
            .map { PriceLevel(it.first, it.second, PriceLevel.Kind.RESISTANCE) }
        return supports to resistances
    }

    /** Retracement levels of the dominant swing in the last [lookback] bars. */
    fun fibonacci(bars: List<Bar>, lookback: Int = 120): List<FibLevel> {
        if (bars.size < 30) return emptyList()
        val from = maxOf(0, bars.size - lookback)
        val hi = (from until bars.size).maxByOrNull { bars[it].high }!!
        val lo = (from until bars.size).minByOrNull { bars[it].low }!!
        val high = bars[hi].high
        val low = bars[lo].low
        if (high <= low) return emptyList()
        val upswing = lo < hi
        return listOf(0.236, 0.382, 0.5, 0.618, 0.786).map { r ->
            FibLevel(r, if (upswing) high - (high - low) * r else low + (high - low) * r)
        }
    }

    fun fibonacciTouch(bars: List<Bar>, fibs: List<FibLevel>): PatternHit? {
        val close = bars.last().close
        val near = fibs.filter { it.ratio in listOf(0.382, 0.5, 0.618) }.minByOrNull { abs(it.price - close) } ?: return null
        if (abs(near.price - close) / close > 0.01) return null
        val i = bars.lastIndex
        val bouncing = bars[i].close > bars[i - 1].close
        return PatternHit("fib_${(near.ratio * 1000).toInt()}", "At ${fmt(near.ratio * 100)}% Fibonacci retracement", PatternCategory.ADVANCED,
            if (bouncing) Direction.BULLISH else Direction.NEUTRAL, 0.35, i, i,
            "Price is testing the ${fmt(near.ratio * 100)}% retracement at ₱${fmt(near.price)}, a common reaction zone.")
    }
}
