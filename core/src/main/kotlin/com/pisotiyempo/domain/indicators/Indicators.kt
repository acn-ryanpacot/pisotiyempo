package com.pisotiyempo.domain.indicators

import com.pisotiyempo.domain.model.Bar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Technical indicators over arrays aligned with the bar list. Positions without enough history hold NaN.
 * Smoothing follows the standard definitions (Wilder for RSI/ATR/ADX, SMA-seeded EMA).
 */
object Indicators {

    fun closes(bars: List<Bar>): DoubleArray = DoubleArray(bars.size) { bars[it].close }

    fun sma(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        if (period <= 0 || values.size < period) return out
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= period) sum -= values[i - period]
            if (i >= period - 1) out[i] = sum / period
        }
        return out
    }

    fun ema(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        // Skip leading NaNs so EMAs of derived series (e.g. the MACD line) work.
        val start = values.indexOfFirst { !it.isNaN() }
        if (start < 0 || values.size - start < period) return out
        val k = 2.0 / (period + 1)
        var prev = (start until start + period).sumOf { values[it] } / period
        out[start + period - 1] = prev
        for (i in start + period until values.size) {
            prev = values[i] * k + prev * (1 - k)
            out[i] = prev
        }
        return out
    }

    /** Wilder's RSI. */
    fun rsi(values: DoubleArray, period: Int = 14): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        if (values.size <= period) return out
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val d = values[i] - values[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        gain /= period
        loss /= period
        out[period] = rsiFrom(gain, loss)
        for (i in period + 1 until values.size) {
            val d = values[i] - values[i - 1]
            gain = (gain * (period - 1) + max(d, 0.0)) / period
            loss = (loss * (period - 1) + max(-d, 0.0)) / period
            out[i] = rsiFrom(gain, loss)
        }
        return out
    }

    private fun rsiFrom(gain: Double, loss: Double): Double =
        if (loss == 0.0) (if (gain == 0.0) 50.0 else 100.0) else 100.0 - 100.0 / (1.0 + gain / loss)

    data class Macd(val macd: DoubleArray, val signal: DoubleArray, val histogram: DoubleArray)

    fun macd(values: DoubleArray, fast: Int = 12, slow: Int = 26, signalPeriod: Int = 9): Macd {
        val fastEma = ema(values, fast)
        val slowEma = ema(values, slow)
        val line = DoubleArray(values.size) { fastEma[it] - slowEma[it] }
        val signal = ema(line, signalPeriod)
        val hist = DoubleArray(values.size) { line[it] - signal[it] }
        return Macd(line, signal, hist)
    }

    data class Bands(val upper: DoubleArray, val middle: DoubleArray, val lower: DoubleArray) {
        /** (upper - lower) / middle */
        fun bandwidth(i: Int): Double = (upper[i] - lower[i]) / middle[i]

        /** 0 at the lower band, 1 at the upper band. */
        fun percentB(i: Int, price: Double): Double = (price - lower[i]) / (upper[i] - lower[i])
    }

    fun bollinger(values: DoubleArray, period: Int = 20, stdDevs: Double = 2.0): Bands {
        val middle = sma(values, period)
        val upper = DoubleArray(values.size) { Double.NaN }
        val lower = DoubleArray(values.size) { Double.NaN }
        for (i in period - 1 until values.size) {
            var sq = 0.0
            for (j in i - period + 1..i) {
                val d = values[j] - middle[i]
                sq += d * d
            }
            val sd = sqrt(sq / period)
            upper[i] = middle[i] + stdDevs * sd
            lower[i] = middle[i] - stdDevs * sd
        }
        return Bands(upper, middle, lower)
    }

    fun trueRange(bars: List<Bar>): DoubleArray = DoubleArray(bars.size) { i ->
        val b = bars[i]
        if (i == 0) b.high - b.low
        else {
            val pc = bars[i - 1].close
            maxOf(b.high - b.low, abs(b.high - pc), abs(b.low - pc))
        }
    }

    /** Wilder's ATR. */
    fun atr(bars: List<Bar>, period: Int = 14): DoubleArray = wilder(trueRange(bars), period, startAt = 1)

    /** Wilder smoothing (RMA): first value is the plain average of [period] items starting at [startAt]. */
    private fun wilder(values: DoubleArray, period: Int, startAt: Int = 0): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        val first = startAt + period - 1
        if (values.size <= first) return out
        var prev = (startAt..first).sumOf { values[it] } / period
        out[first] = prev
        for (i in first + 1 until values.size) {
            prev = (prev * (period - 1) + values[i]) / period
            out[i] = prev
        }
        return out
    }

    data class Adx(val adx: DoubleArray, val plusDi: DoubleArray, val minusDi: DoubleArray)

    fun adx(bars: List<Bar>, period: Int = 14): Adx {
        val n = bars.size
        val plusDm = DoubleArray(n)
        val minusDm = DoubleArray(n)
        for (i in 1 until n) {
            val up = bars[i].high - bars[i - 1].high
            val down = bars[i - 1].low - bars[i].low
            plusDm[i] = if (up > down && up > 0) up else 0.0
            minusDm[i] = if (down > up && down > 0) down else 0.0
        }
        val tr = wilder(trueRange(bars), period, startAt = 1)
        val pdm = wilder(plusDm, period, startAt = 1)
        val mdm = wilder(minusDm, period, startAt = 1)
        val plusDi = DoubleArray(n) { if (tr[it] > 0) 100 * pdm[it] / tr[it] else Double.NaN }
        val minusDi = DoubleArray(n) { if (tr[it] > 0) 100 * mdm[it] / tr[it] else Double.NaN }
        val dx = DoubleArray(n) {
            val s = plusDi[it] + minusDi[it]
            if (s > 0) 100 * abs(plusDi[it] - minusDi[it]) / s else Double.NaN
        }
        val firstDx = dx.indexOfFirst { !it.isNaN() }
        val adx = if (firstDx < 0) DoubleArray(n) { Double.NaN } else wilder(dx.map { if (it.isNaN()) 0.0 else it }.toDoubleArray(), period, firstDx)
        return Adx(adx, plusDi, minusDi)
    }

    data class Stochastic(val k: DoubleArray, val d: DoubleArray)

    fun stochastic(bars: List<Bar>, period: Int = 14, smooth: Int = 3): Stochastic {
        val raw = DoubleArray(bars.size) { Double.NaN }
        for (i in period - 1 until bars.size) {
            var hh = Double.NEGATIVE_INFINITY
            var ll = Double.POSITIVE_INFINITY
            for (j in i - period + 1..i) {
                hh = max(hh, bars[j].high)
                ll = minOf(ll, bars[j].low)
            }
            raw[i] = if (hh > ll) 100 * (bars[i].close - ll) / (hh - ll) else 50.0
        }
        val k = smaIgnoringNaN(raw, smooth)
        return Stochastic(k, smaIgnoringNaN(k, smooth))
    }

    private fun smaIgnoringNaN(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        for (i in period - 1 until values.size) {
            var sum = 0.0
            var ok = true
            for (j in i - period + 1..i) {
                if (values[j].isNaN()) { ok = false; break }
                sum += values[j]
            }
            if (ok) out[i] = sum / period
        }
        return out
    }

    /** On-balance volume. */
    fun obv(bars: List<Bar>): DoubleArray {
        val out = DoubleArray(bars.size)
        for (i in 1 until bars.size) {
            val v = bars[i].volume.toDouble()
            out[i] = out[i - 1] + when {
                bars[i].close > bars[i - 1].close -> v
                bars[i].close < bars[i - 1].close -> -v
                else -> 0.0
            }
        }
        return out
    }

    data class Ichimoku(
        val tenkan: DoubleArray,
        val kijun: DoubleArray,
        /** Leading spans as computed at each bar; the cloud *at* bar i is spanA[i - 26], spanB[i - 26]. */
        val spanA: DoubleArray,
        val spanB: DoubleArray,
    ) {
        fun cloudAt(i: Int, shift: Int = 26): Pair<Double, Double>? {
            val j = i - shift
            if (j < 0 || spanA[j].isNaN() || spanB[j].isNaN()) return null
            return minOf(spanA[j], spanB[j]) to maxOf(spanA[j], spanB[j])
        }
    }

    fun ichimoku(bars: List<Bar>, conversion: Int = 9, base: Int = 26, spanBPeriod: Int = 52): Ichimoku {
        fun midpoint(period: Int) = DoubleArray(bars.size) { i ->
            if (i < period - 1) Double.NaN
            else {
                var hh = Double.NEGATIVE_INFINITY
                var ll = Double.POSITIVE_INFINITY
                for (j in i - period + 1..i) {
                    hh = max(hh, bars[j].high)
                    ll = minOf(ll, bars[j].low)
                }
                (hh + ll) / 2
            }
        }
        val tenkan = midpoint(conversion)
        val kijun = midpoint(base)
        val spanA = DoubleArray(bars.size) { (tenkan[it] + kijun[it]) / 2 }
        return Ichimoku(tenkan, kijun, spanA, midpoint(spanBPeriod))
    }

    fun averageVolume(bars: List<Bar>, endInclusive: Int, period: Int = 20): Double {
        val from = max(0, endInclusive - period + 1)
        if (endInclusive < from) return 0.0
        return (from..endInclusive).sumOf { bars[it].volume.toDouble() } / (endInclusive - from + 1)
    }

    /** Average daily peso turnover over the last [period] bars. */
    fun averageValue(bars: List<Bar>, period: Int = 20): Double {
        if (bars.isEmpty()) return 0.0
        val from = max(0, bars.size - period)
        return (from until bars.size).sumOf { bars[it].close * bars[it].volume } / (bars.size - from)
    }

    /** Least-squares slope of the last [period] values, as a fraction of the latest value per bar. */
    fun normalizedSlope(values: DoubleArray, endInclusive: Int, period: Int): Double {
        val from = endInclusive - period + 1
        if (from < 0) return Double.NaN
        var sx = 0.0; var sy = 0.0; var sxy = 0.0; var sxx = 0.0
        for (k in 0 until period) {
            val y = values[from + k]
            if (y.isNaN()) return Double.NaN
            sx += k; sy += y; sxy += k * y; sxx += k * k.toDouble()
        }
        val slope = (period * sxy - sx * sy) / (period * sxx - sx * sx)
        val last = values[endInclusive]
        return if (last != 0.0) slope / last else Double.NaN
    }
}

fun DoubleArray.lastValid(): Double? = lastOrNull()?.takeUnless { it.isNaN() }
