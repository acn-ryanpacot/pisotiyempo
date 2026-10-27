package com.pisotiyempo.domain

import com.pisotiyempo.domain.portfolio.CashDividend
import com.pisotiyempo.domain.portfolio.PortfolioMath
import com.pisotiyempo.domain.portfolio.Side
import com.pisotiyempo.domain.portfolio.Trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PortfolioMathTest {
    private fun d(day: Int) = LocalDate.of(2026, 1, day)

    @Test
    fun averageCostAndRealizedPnl() {
        val trades = listOf(
            Trade(1, "JFC", Side.BUY, 100, 200.0, 25.0, d(2)),
            Trade(2, "JFC", Side.BUY, 100, 180.0, 25.0, d(5)),
            Trade(3, "JFC", Side.SELL, 100, 210.0, 45.0, d(10)),
        )
        val s = PortfolioMath.summarize(trades, mapOf("JFC" to (220.0 to 1.0)))
        // Cost 38,050 for 200 shares -> 190.25 avg. Sell 100: 21,000 - 45 - 19,025 = 1,930 realized.
        assertEquals(1930.0, s.realizedPnl, 1e-6)
        val h = s.holdings.single()
        assertEquals(100L, h.shares)
        assertEquals(190.25, h.avgCost, 1e-9)
        assertEquals(22_000.0, h.marketValue, 1e-9)
        assertEquals(22_000.0 - 19_025.0, s.unrealizedPnl, 1e-6)
    }

    @Test
    fun dividendsUseSharesHeldBeforeExDateNetOfTax() {
        val trades = listOf(
            Trade(1, "BDO", Side.BUY, 100, 150.0, 20.0, d(2)),
            Trade(2, "BDO", Side.BUY, 100, 150.0, 20.0, d(10)), // bought on the ex-date: not entitled
        )
        val divs = listOf(CashDividend("BDO", d(10), 1.0))
        val s = PortfolioMath.summarize(trades, emptyMap(), divs)
        assertEquals(100 * 1.0 * 0.9, s.dividendsReceived, 1e-9)
    }

    @Test
    fun warnsOnConcentration() {
        val trades = listOf(
            Trade(1, "BDO", Side.BUY, 1000, 150.0, 0.0, d(2)),
            Trade(2, "BPI", Side.BUY, 100, 100.0, 0.0, d(2)),
        )
        val s = PortfolioMath.summarize(trades, emptyMap())
        assertTrue(s.warnings.any { it.startsWith("BDO is") })
        assertTrue(s.warnings.any { it.startsWith("Financials makes up") })
    }
}
