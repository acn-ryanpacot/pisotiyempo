package com.pisotiyempo.domain.patterns

import com.pisotiyempo.domain.model.Bar
import com.pisotiyempo.domain.model.Direction
import com.pisotiyempo.domain.model.PatternCategory
import com.pisotiyempo.domain.model.PatternHit

/**
 * Classic Japanese candlestick patterns. Reversal patterns only count when they appear after a move
 * in the opposite direction, which is what gives them meaning.
 */
object Candlesticks {

    /** Patterns completing within the last [lookback] bars. */
    fun detectRecent(bars: List<Bar>, lookback: Int = 3): List<PatternHit> =
        (maxOf(0, bars.size - lookback) until bars.size).flatMap { detectAt(bars, it) }

    /** Patterns whose last candle is bar [i]. Uses only bars up to [i], so it is safe for backtests. */
    fun detectAt(bars: List<Bar>, i: Int): List<PatternHit> {
        if (i < 5 || i >= bars.size) return emptyList()
        val c = bars[i]
        val p = bars[i - 1]
        val pp = bars[i - 2]
        if (!c.hasRange || !p.hasRange || !pp.hasRange || c.range <= 0) return emptyList()

        val avgBody = (i - 10..i - 1).filter { it >= 0 }.map { bars[it].body }.average().takeIf { it > 0 } ?: c.range
        val down = priorTrend(bars, i - 1) < -0.02
        val up = priorTrend(bars, i - 1) > 0.02
        val out = mutableListOf<PatternHit>()

        fun hit(id: String, name: String, dir: Direction, conf: Double, span: Int, detail: String) {
            out += PatternHit(id, name, PatternCategory.CANDLESTICK, dir, conf.coerceIn(0.0, 1.0), i - span + 1, i, detail)
        }

        val isDoji = c.body <= 0.1 * c.range
        val smallBody = c.body <= 0.35 * c.range

        // Single-candle patterns
        if (isDoji) {
            when {
                c.lowerShadow >= 0.6 * c.range && down -> hit("dragonfly_doji", "Dragonfly doji", Direction.BULLISH, 0.55, 1, "Sellers pushed price down but buyers closed it back at the open.")
                c.upperShadow >= 0.6 * c.range && up -> hit("gravestone_doji", "Gravestone doji", Direction.BEARISH, 0.55, 1, "Buyers pushed price up but sellers closed it back at the open.")
                down || up -> hit("doji", "Doji", Direction.NEUTRAL, 0.35, 1, "Indecision after a ${if (down) "decline" else "rally"}; watch the next candle.")
            }
        } else if (smallBody && c.lowerShadow >= 2 * c.body && c.upperShadow <= 0.3 * c.body.coerceAtLeast(c.range * 0.1)) {
            if (down) hit("hammer", "Hammer", Direction.BULLISH, 0.6, 1, "Long lower wick after a decline: buyers rejected lower prices.")
            else if (up) hit("hanging_man", "Hanging man", Direction.BEARISH, 0.5, 1, "Hammer-shaped candle after a rally hints at selling pressure.")
        } else if (smallBody && c.upperShadow >= 2 * c.body && c.lowerShadow <= 0.3 * c.body.coerceAtLeast(c.range * 0.1)) {
            if (up) hit("shooting_star", "Shooting star", Direction.BEARISH, 0.6, 1, "Long upper wick after a rally: sellers rejected higher prices.")
            else if (down) hit("inverted_hammer", "Inverted hammer", Direction.BULLISH, 0.45, 1, "Buyers tested higher after a decline; needs confirmation.")
        }

        if (c.body >= 0.9 * c.range && c.body >= 1.5 * avgBody) {
            if (c.isUp) hit("bullish_marubozu", "Bullish marubozu", Direction.BULLISH, 0.5, 1, "Strong full-bodied up day with almost no wicks.")
            else hit("bearish_marubozu", "Bearish marubozu", Direction.BEARISH, 0.5, 1, "Strong full-bodied down day with almost no wicks.")
        }

        // Two-candle patterns
        if (p.isDown && c.isUp && c.open <= p.close && c.close >= p.open && c.body > p.body && down) {
            hit("bullish_engulfing", "Bullish engulfing", Direction.BULLISH, 0.65, 2, "Today's up candle fully covers yesterday's down candle.")
        }
        if (p.isUp && c.isDown && c.open >= p.close && c.close <= p.open && c.body > p.body && up) {
            hit("bearish_engulfing", "Bearish engulfing", Direction.BEARISH, 0.65, 2, "Today's down candle fully covers yesterday's up candle.")
        }
        if (p.isDown && p.body >= avgBody && c.isUp && c.open < p.low && c.close > (p.open + p.close) / 2 && c.close < p.open && down) {
            hit("piercing_line", "Piercing line", Direction.BULLISH, 0.55, 2, "Opened below yesterday's low and closed above its midpoint.")
        }
        if (p.isUp && p.body >= avgBody && c.isDown && c.open > p.high && c.close < (p.open + p.close) / 2 && c.close > p.open && up) {
            hit("dark_cloud_cover", "Dark cloud cover", Direction.BEARISH, 0.55, 2, "Opened above yesterday's high and closed below its midpoint.")
        }
        if (p.body >= avgBody && c.body <= 0.5 * p.body && maxOf(c.open, c.close) <= maxOf(p.open, p.close) && minOf(c.open, c.close) >= minOf(p.open, p.close)) {
            if (p.isDown && down) hit("bullish_harami", "Bullish harami", Direction.BULLISH, 0.45, 2, "Small candle inside a large down candle: selling is losing steam.")
            if (p.isUp && up) hit("bearish_harami", "Bearish harami", Direction.BEARISH, 0.45, 2, "Small candle inside a large up candle: buying is losing steam.")
        }

        // Three-candle patterns
        val midSmall = p.body <= 0.3 * pp.body
        if (pp.isDown && pp.body >= avgBody && midSmall && c.isUp && c.close > (pp.open + pp.close) / 2 && down) {
            hit("morning_star", "Morning star", Direction.BULLISH, 0.7, 3, "Big down day, small pause, then a strong up day: a classic bottom reversal.")
        }
        if (pp.isUp && pp.body >= avgBody && midSmall && c.isDown && c.close < (pp.open + pp.close) / 2 && up) {
            hit("evening_star", "Evening star", Direction.BEARISH, 0.7, 3, "Big up day, small pause, then a strong down day: a classic top reversal.")
        }
        if (listOf(pp, p, c).all { it.isUp && it.body >= 0.6 * it.range } && p.close > pp.close && c.close > p.close && p.open > pp.open && c.open > p.open) {
            hit("three_white_soldiers", "Three white soldiers", Direction.BULLISH, 0.6, 3, "Three strong up days in a row with higher closes.")
        }
        if (listOf(pp, p, c).all { it.isDown && it.body >= 0.6 * it.range } && p.close < pp.close && c.close < p.close && p.open < pp.open && c.open < p.open) {
            hit("three_black_crows", "Three black crows", Direction.BEARISH, 0.6, 3, "Three strong down days in a row with lower closes.")
        }
        return out
    }

    /** Fractional price change over the 5 bars ending at [end]. */
    private fun priorTrend(bars: List<Bar>, end: Int): Double {
        val start = maxOf(0, end - 5)
        val from = bars[start].close
        return if (from > 0) (bars[end].close - from) / from else 0.0
    }
}
