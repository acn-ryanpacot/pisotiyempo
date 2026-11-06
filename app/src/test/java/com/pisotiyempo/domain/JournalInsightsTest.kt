package com.pisotiyempo.domain

import com.pisotiyempo.domain.journal.JournalInsights
import com.pisotiyempo.domain.journal.JournalNote
import com.pisotiyempo.domain.portfolio.ClosedTrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class JournalInsightsTest {
    private fun d(day: Int) = LocalDate.of(2026, 3, day)

    @Test
    fun matchesTradesToNotesAndFlagsWeakTags() {
        val notes = listOf(
            JournalNote("A", d(1), "Breakout", "Calm", emptyList(), 5),
            JournalNote("B", d(1), "Tip from someone", "FOMO", listOf("Chased the price"), 2),
            JournalNote("C", d(1), "Tip from someone", "FOMO", listOf("Chased the price", "No stop loss"), 1),
            JournalNote("D", d(1), "Tip from someone", "FOMO", listOf("Chased the price"), 2),
            JournalNote("E", d(1), "Breakout", "Calm", emptyList(), 4),
            // Written after the sell: must not be matched to E's trade.
            JournalNote("E", d(20), "Other", "Greedy", emptyList(), 3),
        )
        val closed = listOf(
            ClosedTrade("A", d(10), 100, 500.0, 10.0, 9),
            ClosedTrade("B", d(10), 100, -300.0, -6.0, 30),
            ClosedTrade("C", d(10), 100, -400.0, -8.0, 40),
            ClosedTrade("D", d(10), 100, -200.0, -4.0, 35),
            ClosedTrade("E", d(10), 100, 600.0, 12.0, 8),
        )
        val i = JournalInsights.compute(notes, closed)
        assertEquals(0.4, i.winRate!!, 1e-9)
        val fomo = i.byEmotion.first { it.label == "FOMO" }
        assertEquals(3, fomo.trades)
        assertEquals(0.0, fomo.winRate, 1e-9)
        assertEquals(2, i.byEmotion.first { it.label == "Calm" }.trades)
        assertTrue(i.byEmotion.none { it.label == "Greedy" })
        assertEquals("Chased the price" to 3, i.topMistakes.first())
        assertTrue(i.observations.any { it.contains("\"FOMO\" won 0%") })
        assertTrue(i.observations.any { it.startsWith("You hold losers") })
    }

    @Test
    fun handlesNoTrades() {
        val i = JournalInsights.compute(emptyList(), emptyList())
        assertEquals(null, i.winRate)
        assertTrue(i.observations.first().startsWith("Fewer than 5"))
    }
}
