package com.pisotiyempo.domain

import com.pisotiyempo.domain.model.Bar
import java.time.LocalDate

/** Builds synthetic bar series for tests. */
object TestBars {
    private val START: LocalDate = LocalDate.of(2024, 1, 1)

    fun fromCloses(closes: List<Double>, volume: Long = 100_000, spread: Double = 0.01): List<Bar> =
        closes.mapIndexed { i, c ->
            val open = if (i == 0) c else closes[i - 1]
            Bar(START.plusDays(i.toLong()), open, maxOf(open, c) * (1 + spread), minOf(open, c) * (1 - spread), c, volume)
        }

    fun bar(i: Int, open: Double, high: Double, low: Double, close: Double, volume: Long = 100_000) =
        Bar(START.plusDays(i.toLong()), open, high, low, close, volume)

    /** Linear path from [from] to [to] over [n] points (exclusive of [to]). */
    fun ramp(from: Double, to: Double, n: Int): List<Double> = List(n) { from + (to - from) * it / n }
}
