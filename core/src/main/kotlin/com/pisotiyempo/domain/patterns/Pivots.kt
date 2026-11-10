package com.pisotiyempo.domain.patterns

import com.pisotiyempo.domain.model.Bar

data class Pivot(val index: Int, val price: Double, val isHigh: Boolean)

object Pivots {
    /**
     * Swing highs and lows: a bar whose high (low) is the extreme within [window] bars on each side.
     * Consecutive pivots of the same kind are merged keeping the more extreme one, so the result alternates.
     * Bars within [window] of [end] can't be confirmed yet and are skipped.
     */
    fun find(bars: List<Bar>, window: Int = 3, start: Int = 0, end: Int = bars.size - 1): List<Pivot> {
        val raw = mutableListOf<Pivot>()
        for (i in maxOf(start + window, window)..(end - window)) {
            var isHigh = true
            var isLow = true
            for (j in i - window..i + window) {
                if (j == i) continue
                if (bars[j].high > bars[i].high) isHigh = false
                if (bars[j].low < bars[i].low) isLow = false
            }
            if (isHigh) raw += Pivot(i, bars[i].high, true)
            if (isLow) raw += Pivot(i, bars[i].low, false)
        }
        val merged = mutableListOf<Pivot>()
        for (p in raw) {
            val last = merged.lastOrNull()
            if (last != null && last.isHigh == p.isHigh) {
                val better = if (p.isHigh) p.price > last.price else p.price < last.price
                if (better) merged[merged.size - 1] = p
            } else {
                merged += p
            }
        }
        return merged
    }
}
