package com.pisotiyempo.domain

import com.pisotiyempo.domain.analysis.PatternFilter
import com.pisotiyempo.domain.analysis.ScreenCriteria
import com.pisotiyempo.domain.analysis.ScreenPreset
import com.pisotiyempo.domain.analysis.ScreenRow
import com.pisotiyempo.domain.analysis.ScreenSort
import com.pisotiyempo.domain.analysis.Screener
import com.pisotiyempo.domain.model.Stance
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenerTest {
    private fun row(
        symbol: String,
        score: Int = 0,
        rsi: Double? = 50.0,
        above200: Boolean? = true,
        above50: Boolean? = true,
        patterns: List<String> = emptyList(),
        bullish: Int = 0,
        bearish: Int = 0,
        vol: Double? = 1.0,
        liquid: Boolean = true,
        sector: String = "Financials",
        change: Double? = 0.0,
    ) = ScreenRow(symbol, symbol, sector, 100.0, change, score, Stance.fromScore(score), rsi, above50, above200, patterns, bullish, bearish, vol, 5.0, 1e7, liquid)

    private val rows = listOf(
        row("UP", score = 45, rsi = 62.0, patterns = listOf("Ascending triangle breakout"), bullish = 1),
        row("DIP", score = 5, rsi = 28.0),
        row("DOWN", score = -55, rsi = 40.0, above200 = false, above50 = false, patterns = listOf("Death cross"), bearish = 1),
        row("HOT", score = 25, rsi = 71.0, vol = 3.2, sector = "Property"),
        row("THIN", score = 60, liquid = false),
        row("NEW", score = 30, rsi = null, above200 = null),
    )

    @Test
    fun presetsSelectTheIntendedSetups() {
        assertEquals(listOf("UP", "HOT"), Screener.run(rows, ScreenPreset.STRONG_UPTREND.criteria).map { it.symbol })
        assertEquals(listOf("DIP"), Screener.run(rows, ScreenPreset.OVERSOLD.criteria).map { it.symbol })
        assertEquals(listOf("UP"), Screener.run(rows, ScreenPreset.BREAKOUTS.criteria).map { it.symbol })
        assertEquals(listOf("HOT"), Screener.run(rows, ScreenPreset.VOLUME_SURGE.criteria).map { it.symbol })
        assertEquals(listOf("DOWN"), Screener.run(rows, ScreenPreset.WEAKEST.criteria).map { it.symbol })
    }

    @Test
    fun liquidityFilterCanBeTurnedOff() {
        assertEquals("THIN", Screener.run(rows, ScreenCriteria(liquidOnly = false)).first().symbol)
        assertEquals("UP", Screener.run(rows, ScreenCriteria()).first().symbol)
    }

    @Test
    fun missingReadingsFailFiltersAndSortLast() {
        assertEquals(false, Screener.matches(rows.last(), ScreenCriteria(minRsi = 10.0)))
        val byRsi = Screener.run(rows, ScreenCriteria(sort = ScreenSort.RSI, descending = false)).map { it.symbol }
        assertEquals("NEW", byRsi.last())
        assertEquals("DIP", byRsi.first())
    }

    @Test
    fun sectorAndPatternFilters() {
        assertEquals(listOf("HOT"), Screener.run(rows, ScreenCriteria(sectors = setOf("Property"))).map { it.symbol })
        assertEquals(listOf("DOWN"), Screener.run(rows, ScreenCriteria(patternFilter = PatternFilter.BEARISH)).map { it.symbol })
        assertEquals(listOf("DOWN"), Screener.run(rows, ScreenCriteria(patternName = "death")).map { it.symbol })
    }
}
