package com.pisotiyempo.domain.patterns

import com.pisotiyempo.domain.model.Bar
import com.pisotiyempo.domain.model.Direction
import com.pisotiyempo.domain.model.PatternCategory
import com.pisotiyempo.domain.model.PatternHit
import kotlin.math.abs

/**
 * Classical chart patterns found from swing pivots: double/triple tops and bottoms, head and shoulders,
 * triangles, wedges, flags/pennants and cup and handle. Only patterns that are still forming or broke
 * out recently are reported; old completed patterns say little about what happens next.
 */
object ChartPatterns {

    /** Bars after a breakout during which the pattern is still considered "fresh". */
    private const val FRESH_BARS = 15

    fun detect(bars: List<Bar>, end: Int = bars.lastIndex, lookback: Int = 160): List<PatternHit> {
        if (end < 40 || end >= bars.size) return emptyList()
        val start = maxOf(0, end - lookback)
        val pivots = Pivots.find(bars, window = 3, start = start, end = end)
        val out = mutableListOf<PatternHit>()
        for (top in listOf(true, false)) {
            out += reversals(bars, pivots, end, top)
            flag(bars, end, bullish = !top)?.let(out::add)
        }
        triangleOrWedge(bars, pivots, end)?.let(out::add)
        cupAndHandle(bars, end)?.let(out::add)
        return out
    }

    // ---- Double/triple tops and bottoms, head and shoulders ------------------------------------------

    private fun reversals(bars: List<Bar>, pivots: List<Pivot>, end: Int, top: Boolean): List<PatternHit> {
        val out = mutableListOf<PatternHit>()
        val k = pivots.indexOfLast { it.isHigh == top }
        // The last peak must be one of the last two pivots, or the pattern is stale.
        if (k < 2 || k < pivots.size - 2) return out
        val dir = if (top) Direction.BEARISH else Direction.BULLISH
        val close = bars[end].close
        fun beyond(a: Double, b: Double) = if (top) a > b else a < b
        fun near(a: Double, b: Double, tol: Double) = abs(a - b) / maxOf(abs(a), abs(b)) <= tol
        fun broke(neck: (Int) -> Double, from: Int): Int? =
            (from..end).firstOrNull { i -> if (top) bars[i].close < neck(i) else bars[i].close > neck(i) }
        // After the last peak, price must not have made a new extreme beyond it.
        fun heldBelow(level: Double, from: Int): Boolean =
            (from + 1..end).none { i -> beyond(if (top) bars[i].high else bars[i].low, level * (if (top) 1.01 else 0.99)) }

        val p2 = pivots[k]
        val t1 = pivots[k - 1]
        val p1 = pivots[k - 2]

        // Head and shoulders: P1 (left shoulder), T1, P2 (head), T2, P3 (right shoulder)
        if (k >= 4) {
            val ls = pivots[k - 4]; val n1 = pivots[k - 3]; val head = pivots[k - 2]; val n2 = pivots[k - 1]; val rs = pivots[k]
            val headProminent = beyond(head.price, (if (top) maxOf(ls.price, rs.price) else minOf(ls.price, rs.price)) * (if (top) 1.03 else 0.97))
            if (headProminent && near(ls.price, rs.price, 0.06) && heldBelow(rs.price, rs.index) && end - rs.index <= 40) {
                val slope = (n2.price - n1.price) / (n2.index - n1.index)
                val neck = { i: Int -> n1.price + slope * (i - n1.index) }
                val breakAt = broke(neck, rs.index + 1)
                if (breakAt == null || end - breakAt <= FRESH_BARS) {
                    val height = abs(head.price - neck(head.index))
                    val neckNow = neck(end)
                    val name = if (top) "Head and shoulders" else "Inverse head and shoulders"
                    out += PatternHit(
                        id = if (top) "head_and_shoulders" else "inverse_head_and_shoulders",
                        name = name,
                        category = PatternCategory.CHART,
                        direction = dir,
                        confidence = if (breakAt != null) 0.75 else 0.5,
                        startIndex = ls.index,
                        endIndex = end,
                        detail = if (breakAt != null) "$name confirmed: price closed through the neckline near ₱${fmt(neckNow)}."
                        else "$name forming; confirmation needs a close ${if (top) "below" else "above"} the neckline near ₱${fmt(neckNow)}.",
                        confirmed = breakAt != null,
                        target = if (top) neckNow - height else neckNow + height,
                        invalidation = head.price,
                    )
                    return out
                }
            }
        }

        // Triple top/bottom: three peaks within 3%
        if (k >= 4) {
            val a = pivots[k - 4]; val b = pivots[k - 2]; val c = pivots[k]
            if (near(a.price, b.price, 0.03) && near(b.price, c.price, 0.03) && near(a.price, c.price, 0.03) && heldBelow(maxOrMin(top, a.price, b.price, c.price), c.index)) {
                val neckPrice = if (top) minOf(pivots[k - 3].price, pivots[k - 1].price) else maxOf(pivots[k - 3].price, pivots[k - 1].price)
                val peak = maxOrMin(top, a.price, b.price, c.price)
                if (abs(peak - neckPrice) / peak >= 0.04) {
                    val breakAt = broke({ neckPrice }, c.index + 1)
                    if (breakAt == null || end - breakAt <= FRESH_BARS) {
                        val name = if (top) "Triple top" else "Triple bottom"
                        out += PatternHit(
                            if (top) "triple_top" else "triple_bottom", name, PatternCategory.CHART, dir,
                            if (breakAt != null) 0.72 else 0.5, a.index, end,
                            "Price failed three times near ₱${fmt(peak)}. Key level: ₱${fmt(neckPrice)}.",
                            breakAt != null, if (top) neckPrice - abs(peak - neckPrice) else neckPrice + abs(peak - neckPrice), peak,
                        )
                        return out
                    }
                }
            }
        }

        // Double top/bottom
        val separation = p2.index - p1.index
        val peak = maxOrMin(top, p1.price, p2.price)
        val depth = abs(minOrMax(top, p1.price, p2.price) - t1.price) / peak
        if (near(p1.price, p2.price, 0.03) && depth >= 0.04 && separation >= 8 && heldBelow(peak, p2.index) && end - p2.index <= 40) {
            val breakAt = broke({ t1.price }, p2.index + 1)
            if (breakAt == null || end - breakAt <= FRESH_BARS) {
                // An unconfirmed double needs price already turning away from the second peak.
                val turning = if (top) close < p2.price * 0.98 else close > p2.price * 1.02
                if (breakAt != null || turning) {
                    val name = if (top) "Double top" else "Double bottom"
                    out += PatternHit(
                        if (top) "double_top" else "double_bottom", name, PatternCategory.CHART, dir,
                        if (breakAt != null) 0.7 else 0.45, p1.index, end,
                        if (breakAt != null) "$name confirmed through ₱${fmt(t1.price)}."
                        else "Two ${if (top) "peaks" else "lows"} near ₱${fmt(peak)}; confirmation needs a close ${if (top) "below" else "above"} ₱${fmt(t1.price)}.",
                        breakAt != null,
                        if (top) t1.price - abs(peak - t1.price) else t1.price + abs(peak - t1.price),
                        peak,
                    )
                }
            }
        }
        return out
    }

    private fun maxOrMin(top: Boolean, vararg v: Double) = if (top) v.max() else v.min()
    private fun minOrMax(top: Boolean, vararg v: Double) = if (top) v.min() else v.max()

    // ---- Triangles and wedges -----------------------------------------------------------------------

    private data class Line(val slope: Double, val intercept: Double, val meanAbsResidual: Double) {
        fun at(i: Int) = intercept + slope * i
    }

    private fun fit(points: List<Pivot>): Line {
        val n = points.size.toDouble()
        val mx = points.sumOf { it.index.toDouble() } / n
        val my = points.sumOf { it.price } / n
        var num = 0.0
        var den = 0.0
        for (p in points) {
            num += (p.index - mx) * (p.price - my)
            den += (p.index - mx) * (p.index - mx)
        }
        val slope = if (den == 0.0) 0.0 else num / den
        val intercept = my - slope * mx
        val resid = points.sumOf { abs(it.price - (intercept + slope * it.index)) } / n
        return Line(slope, intercept, resid)
    }

    private fun triangleOrWedge(bars: List<Bar>, pivots: List<Pivot>, end: Int): PatternHit? {
        val recent = pivots.filter { it.index >= end - 70 }
        val highs = recent.filter { it.isHigh }.takeLast(4)
        val lows = recent.filter { !it.isHigh }.takeLast(4)
        if (highs.size < 2 || lows.size < 2 || highs.size + lows.size < 5) return null
        val upper = fit(highs)
        val lower = fit(lows)
        val close = bars[end].close
        val first = minOf(highs.first().index, lows.first().index)
        // Both lines must actually fit their pivots.
        if (upper.meanAbsResidual / close > 0.02 || lower.meanAbsResidual / close > 0.02) return null
        val widthStart = upper.at(first) - lower.at(first)
        val widthEnd = upper.at(end) - lower.at(end)
        if (widthStart <= 0 || widthEnd <= 0 || widthEnd > widthStart * 0.8) return null

        val flat = 0.0006
        val su = upper.slope / close
        val sl = lower.slope / close
        val (id, name, dir) = when {
            abs(su) < flat && sl > flat -> Triple("ascending_triangle", "Ascending triangle", Direction.BULLISH)
            su < -flat && abs(sl) < flat -> Triple("descending_triangle", "Descending triangle", Direction.BEARISH)
            su < -flat && sl > flat -> Triple("symmetrical_triangle", "Symmetrical triangle", priorTrend(bars, first))
            su > flat && sl > flat -> Triple("rising_wedge", "Rising wedge", Direction.BEARISH)
            su < -flat && sl < -flat -> Triple("falling_wedge", "Falling wedge", Direction.BULLISH)
            else -> return null
        }
        val up = upper.at(end)
        val lo = lower.at(end)
        val brokeUp = close > up * 1.005
        val brokeDown = close < lo * 0.995
        // Too far past the lines means the move already happened.
        if (close > up * 1.08 || close < lo * 0.92) return null
        val height = widthStart
        return when {
            brokeUp -> PatternHit(
                id, "$name breakout", PatternCategory.CHART, Direction.BULLISH, if (dir == Direction.BULLISH) 0.7 else 0.55,
                first, end, "Closed above the upper trendline near ₱${fmt(up)}.", true, up + height, lo,
            )
            brokeDown -> PatternHit(
                id, "$name breakdown", PatternCategory.CHART, Direction.BEARISH, if (dir == Direction.BEARISH) 0.7 else 0.55,
                first, end, "Closed below the lower trendline near ₱${fmt(lo)}.", true, lo - height, up,
            )
            else -> PatternHit(
                id, name, PatternCategory.CHART, dir, 0.4, first, end,
                "Price is coiling between ₱${fmt(lo)} and ₱${fmt(up)}; watch for a close outside this range.", false,
                if (dir == Direction.BEARISH) lo - height else up + height, if (dir == Direction.BEARISH) up else lo,
            )
        }
    }

    private fun priorTrend(bars: List<Bar>, at: Int): Direction {
        val from = maxOf(0, at - 40)
        val change = (bars[at].close - bars[from].close) / bars[from].close
        return when {
            change > 0.08 -> Direction.BULLISH
            change < -0.08 -> Direction.BEARISH
            else -> Direction.NEUTRAL
        }
    }

    // ---- Flags and pennants -------------------------------------------------------------------------

    private fun flag(bars: List<Bar>, end: Int, bullish: Boolean): PatternHit? {
        val searchFrom = maxOf(0, end - 25)
        val poleEndRange = searchFrom..(end - 4)
        if (poleEndRange.isEmpty()) return null
        val b = if (bullish) poleEndRange.maxByOrNull { bars[it].high }!! else poleEndRange.minByOrNull { bars[it].low }!!
        val poleStartRange = maxOf(0, b - 12)..maxOf(0, b - 3)
        val a = if (bullish) poleStartRange.minByOrNull { bars[it].low } ?: return null else poleStartRange.maxByOrNull { bars[it].high } ?: return null
        val poleFrom = if (bullish) bars[a].low else bars[a].high
        val poleTo = if (bullish) bars[b].high else bars[b].low
        val pole = abs(poleTo - poleFrom)
        if (pole / poleFrom < 0.10) return null

        val consolidation = (b + 1)..end
        val len = consolidation.count()
        if (len !in 4..20) return null
        val hi = consolidation.maxOf { bars[it].high }
        val lo = consolidation.minOf { bars[it].low }
        if ((hi - lo) > 0.5 * pole) return null
        // Must not give back more than half the pole.
        if (bullish && lo < poleTo - 0.5 * pole) return null
        if (!bullish && hi > poleTo + 0.5 * pole) return null

        val half = len / 2
        val firstHalf = (b + 1) until (b + 1 + half)
        val secondHalf = (b + 1 + half)..end
        val r1 = firstHalf.maxOf { bars[it].high } - firstHalf.minOf { bars[it].low }
        val r2 = secondHalf.maxOf { bars[it].high } - secondHalf.minOf { bars[it].low }
        val pennant = r1 > r2 * 1.3
        val shape = if (pennant) "pennant" else "flag"

        val priorHi = (b + 1 until end).maxOfOrNull { bars[it].high } ?: hi
        val priorLo = (b + 1 until end).minOfOrNull { bars[it].low } ?: lo
        val close = bars[end].close
        val broke = if (bullish) close > priorHi else close < priorLo
        val level = if (bullish) priorHi else priorLo
        return PatternHit(
            id = if (bullish) "bull_$shape" else "bear_$shape",
            name = "${if (bullish) "Bull" else "Bear"} $shape",
            category = PatternCategory.CHART,
            direction = if (bullish) Direction.BULLISH else Direction.BEARISH,
            confidence = if (broke) 0.65 else 0.45,
            startIndex = a,
            endIndex = end,
            detail = "A ${fmt(pole / poleFrom * 100)}% ${if (bullish) "surge" else "drop"} followed by a tight $shape. " +
                if (broke) "Price just broke ${if (bullish) "above" else "below"} ₱${fmt(level)}." else "Breakout level: ₱${fmt(level)}.",
            confirmed = broke,
            target = if (bullish) level + pole else level - pole,
            invalidation = if (bullish) lo else hi,
        )
    }

    // ---- Cup and handle -----------------------------------------------------------------------------

    private fun cupAndHandle(bars: List<Bar>, end: Int): PatternHit? {
        val rightRange = maxOf(0, end - 25)..(end - 5)
        if (rightRange.isEmpty()) return null
        val r = rightRange.maxByOrNull { bars[it].high }!!
        val leftRange = maxOf(0, r - 150)..(r - 30)
        if (leftRange.isEmpty()) return null
        val l = leftRange.maxByOrNull { bars[it].high }!!
        val rimL = bars[l].high
        val rimR = bars[r].high
        if (abs(rimL - rimR) / maxOf(rimL, rimR) > 0.05) return null
        val bottom = (l..r).minByOrNull { bars[it].low }!!
        val low = bars[bottom].low
        val rim = minOf(rimL, rimR)
        val depth = (rim - low) / rim
        if (depth !in 0.12..0.40) return null
        val position = (bottom - l).toDouble() / (r - l)
        if (position !in 0.25..0.75) return null
        // U-shaped, not V-shaped: enough time spent in the lower third of the cup.
        val lowerThird = low + (rim - low) / 3
        val timeLow = (l..r).count { bars[it].close <= lowerThird }.toDouble() / (r - l + 1)
        if (timeLow < 0.15) return null

        val handleLow = (r + 1..end).minOf { bars[it].low }
        if (handleLow < rimR - 0.5 * (rimR - low)) return null
        val close = bars[end].close
        val broke = close > rimR
        return PatternHit(
            "cup_and_handle", "Cup and handle", PatternCategory.CHART, Direction.BULLISH,
            if (broke) 0.7 else 0.5, l, end,
            if (broke) "Broke out of a ${fmt(depth * 100)}%-deep cup above ₱${fmt(rimR)}."
            else "Rounded ${fmt(depth * 100)}%-deep base with a handle; breakout level ₱${fmt(rimR)}.",
            broke, rimR + (rimR - low), handleLow,
        )
    }
}

internal fun fmt(v: Double): String =
    if (abs(v) >= 100) String.format(java.util.Locale.US, "%,.2f", v)
    else if (abs(v) >= 1) String.format(java.util.Locale.US, "%.2f", v)
    else String.format(java.util.Locale.US, "%.4f", v)
