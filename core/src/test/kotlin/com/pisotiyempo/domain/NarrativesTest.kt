package com.pisotiyempo.domain

import com.pisotiyempo.domain.analysis.MarketStats
import com.pisotiyempo.domain.analysis.RuleBasedAnalyst
import com.pisotiyempo.domain.analysis.StockAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class NarrativesTest {
    private val analysis = StockAnalyzer.analyze(
        "UP", "Up Corp",
        TestBars.fromCloses((0 until 300).map { 100.0 * (1 + 0.004 * it) + 0.5 * sin(it / 4.0) }),
    )!!

    @Test
    fun writesEachLanguage() {
        val en = RuleBasedAnalyst.stock(analysis, "en")
        val fil = RuleBasedAnalyst.stock(analysis, "fil")
        val tag = RuleBasedAnalyst.stock(analysis, "taglish")
        assertTrue(fil.summary.startsWith("Nagsara ang Up Corp"))
        assertTrue(tag.summary.startsWith("Nag-close ang Up Corp"))
        assertNotEquals(en.headline, fil.headline)
        // Stance and confidence don't depend on language.
        assertEquals(en.stance, fil.stance)
        assertEquals(en.confidence, tag.confidence)
    }

    @Test
    fun unknownLanguageFallsBackToEnglish() {
        assertEquals(RuleBasedAnalyst.stock(analysis, "en"), RuleBasedAnalyst.stock(analysis, "xx"))
    }

    @Test
    fun marketBriefInFilipino() {
        val stats = MarketStats("2026-11-05", 6000.0, 1.2, 120, 60, 20, 5e9, listOf("JFC" to 3.0), listOf("BDO" to -2.0))
        val b = RuleBasedAnalyst.market(stats, "fil")
        assertEquals("BULLISH", b.mood)
        assertTrue(b.overview.startsWith("Ang PSEi ay tumaas ng 1.20%"))
        assertEquals(listOf("JFC tumaas ng 3.00%", "BDO bumaba ng 2.00%"), b.highlights)
    }
}
