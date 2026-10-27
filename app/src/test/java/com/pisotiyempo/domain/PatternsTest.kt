package com.pisotiyempo.domain

import com.pisotiyempo.domain.analysis.Backtester
import com.pisotiyempo.domain.analysis.RuleBasedAnalyst
import com.pisotiyempo.domain.analysis.ScoredStock
import com.pisotiyempo.domain.analysis.StockAnalyzer
import com.pisotiyempo.domain.analysis.Suggestions
import com.pisotiyempo.domain.model.Direction
import com.pisotiyempo.domain.model.Stance
import com.pisotiyempo.domain.patterns.Candlesticks
import com.pisotiyempo.domain.patterns.ChartPatterns
import com.pisotiyempo.domain.patterns.Pivots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class PatternsTest {

    @Test
    fun detectsBullishEngulfingAfterDecline() {
        val decline = TestBars.fromCloses(TestBars.ramp(120.0, 100.0, 10))
        val bars = decline + TestBars.bar(10, 101.0, 101.5, 98.8, 99.0) + TestBars.bar(11, 98.5, 102.3, 98.2, 102.0)
        val hits = Candlesticks.detectAt(bars, 11)
        assertTrue(hits.map { it.id }.toString(), hits.any { it.id == "bullish_engulfing" && it.direction == Direction.BULLISH })
    }

    @Test
    fun ignoresCandlesWithoutIntradayRange() {
        val flat = (0 until 12).map { TestBars.bar(it, 100.0, 100.0, 100.0, 100.0) }
        assertTrue(Candlesticks.detectAt(flat, 11).isEmpty())
    }

    @Test
    fun pivotsAlternateHighsAndLows() {
        val closes = (0 until 120).map { 100 + 10 * sin(it / 6.0) }
        val pivots = Pivots.find(TestBars.fromCloses(closes))
        assertTrue(pivots.size >= 4)
        pivots.zipWithNext().forEach { (a, b) -> assertTrue(a.isHigh != b.isHigh) }
    }

    @Test
    fun detectsConfirmedDoubleBottom() {
        val closes = TestBars.ramp(120.0, 100.0, 20) + TestBars.ramp(100.0, 110.0, 10) +
            TestBars.ramp(110.0, 100.5, 10) + TestBars.ramp(100.5, 115.0, 6) + listOf(115.0)
        val hits = ChartPatterns.detect(TestBars.fromCloses(closes))
        val db = hits.firstOrNull { it.id == "double_bottom" }
        assertNotNull(hits.map { it.id }.toString(), db)
        assertEquals(Direction.BULLISH, db!!.direction)
        assertTrue(db.confirmed)
        assertTrue(db.target!! > 111.0)
    }

    private fun trending(slopePerBar: Double, n: Int = 300, noise: Double = 0.5): List<Double> =
        (0 until n).map { 100.0 * (1 + slopePerBar * it) + noise * sin(it / 4.0) }

    @Test
    fun uptrendScoresPositiveAndDowntrendNegative() {
        val up = StockAnalyzer.analyze("UP", "Up Corp", TestBars.fromCloses(trending(0.004)))!!
        val down = StockAnalyzer.analyze("DN", "Down Corp", TestBars.fromCloses(trending(-0.002)))!!
        assertTrue("up score ${up.score} ${up.components}", up.score > 20)
        // Oversold momentum partly offsets a steady decline, so the bar is lower than for the uptrend.
        assertTrue("down score ${down.score} ${down.components}", down.score < -10)
        assertNotNull(up.plan)
        assertTrue(up.plan!!.stop < up.close && up.plan!!.target > up.close)
    }

    @Test
    fun analyzerNeedsMinimumHistory() {
        assertNull(StockAnalyzer.analyze("X", "X", TestBars.fromCloses(List(10) { 100.0 })))
    }

    @Test
    fun backtestProducesValidStatistics() {
        val closes = (0 until 400).map { 100 + 15 * sin(it / 9.0) + it * 0.02 }
        val stats = Backtester.run(TestBars.fromCloses(closes))
        assertTrue(stats.isNotEmpty())
        stats.forEach {
            assertTrue(it.samples > 0)
            assertTrue(it.winRate in 0.0..1.0)
        }
    }

    @Test
    fun ruleBasedNarrativeMatchesStance() {
        val a = StockAnalyzer.analyze("UP", "Up Corp", TestBars.fromCloses(trending(0.004)))!!
        val n = RuleBasedAnalyst.stock(a)
        assertEquals(a.stance, n.stance)
        assertTrue(n.summary.contains("Up Corp"))
    }

    @Test
    fun suggestionsRankAndFilter() {
        fun s(sym: String, score: Int, liquid: Boolean = true) =
            ScoredStock(sym, sym, 10.0, 0.0, score, Stance.fromScore(score), null, if (liquid) 5e6 else 1e5, liquid)
        val all = listOf(s("A", 80), s("B", 60), s("C", 90, liquid = false), s("D", 10), s("E", -70), s("F", -40), s("G", -90))
        assertEquals(listOf("A", "B"), Suggestions.topBuys(all).map { it.symbol })
        // Holdings first (F), then the weakest liquid market names.
        assertEquals(listOf("F", "G", "E"), Suggestions.topSells(all, holdings = setOf("F"), watchlist = emptySet(), n = 3).map { it.symbol })
    }
}
