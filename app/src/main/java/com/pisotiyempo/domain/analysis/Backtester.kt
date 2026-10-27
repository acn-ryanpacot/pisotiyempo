package com.pisotiyempo.domain.analysis

import com.pisotiyempo.domain.model.Bar
import com.pisotiyempo.domain.model.BacktestStat
import com.pisotiyempo.domain.model.Direction
import com.pisotiyempo.domain.model.PatternHit
import com.pisotiyempo.domain.patterns.Candlesticks
import com.pisotiyempo.domain.patterns.ChartPatterns
import com.pisotiyempo.domain.patterns.IndicatorSet
import com.pisotiyempo.domain.patterns.Signals

/**
 * Measures how each signal has actually played out on this stock: for every past occurrence, did price
 * move the expected way over the next [horizon] trading days? Detection at bar i only sees bars up to i,
 * so there is no look-ahead.
 */
object Backtester {

    fun run(bars: List<Bar>, horizon: Int = 10, maxBars: Int = 500, indicators: IndicatorSet = IndicatorSet(bars)): List<BacktestStat> {
        if (bars.size < 60 + horizon) return emptyList()
        val first = maxOf(30, bars.size - maxBars)
        val last = bars.size - 1 - horizon
        val outcomes = mutableMapOf<String, MutableList<Double>>()
        val meta = mutableMapOf<String, PatternHit>()
        val lastSeen = mutableMapOf<String, Int>()

        fun record(hit: PatternHit, i: Int, minGap: Int = 5) {
            if (hit.direction == Direction.NEUTRAL) return
            // Ignore repeats of the same signal close together: they are one event, not several.
            val prev = lastSeen[hit.id]
            if (prev != null && i - prev < minGap) return
            lastSeen[hit.id] = i
            val ret = (bars[i + horizon].close - bars[i].close) / bars[i].close * 100
            outcomes.getOrPut(hit.id) { mutableListOf() } += ret * hit.direction.sign
            meta.putIfAbsent(hit.id, hit)
        }

        for (i in first..last) {
            Candlesticks.detectAt(bars, i).forEach { record(it, i) }
            Signals.indicatorSignalsAt(indicators, i).forEach { record(it, i) }
        }
        // Chart patterns are costlier to detect, so sample every 3 bars and count only confirmed breakouts.
        // A breakout stays "fresh" for 15 bars, so use that as the gap to count each pattern once.
        for (i in first..last step 3) {
            ChartPatterns.detect(bars, end = i, lookback = 120)
                .filter { it.confirmed }
                .forEach { record(it, i, minGap = 20) }
        }

        return outcomes.map { (id, rets) ->
            val hit = meta.getValue(id)
            BacktestStat(
                signalId = id,
                signalName = hit.name.removeSuffix(" breakout").removeSuffix(" breakdown"),
                direction = hit.direction,
                samples = rets.size,
                winRate = rets.count { it > 0 }.toDouble() / rets.size,
                // Stored as the raw move in the signal's direction, so positive is always "worked".
                avgReturnPct = rets.average(),
                horizonDays = horizon,
            )
        }.sortedByDescending { it.samples }
    }
}
