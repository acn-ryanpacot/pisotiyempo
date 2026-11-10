package com.pisotiyempo.domain

import com.pisotiyempo.domain.ph.FeeCalculator
import com.pisotiyempo.domain.ph.FeeSchedule
import com.pisotiyempo.domain.portfolio.PaperTrading
import com.pisotiyempo.domain.portfolio.PortfolioMath
import com.pisotiyempo.domain.portfolio.Side
import com.pisotiyempo.domain.portfolio.Trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PaperTradingTest {
    private val fees = FeeSchedule()

    @Test
    fun buysNeedWholeBoardLotsAndEnoughCash() {
        assertTrue(PaperTrading.order(Side.BUY, 15, 143.5, 100_000.0, 0, fees).isFailure) // board lot is 10
        assertTrue(PaperTrading.order(Side.BUY, 1_000, 143.5, 100_000.0, 0, fees).isFailure) // ₱143,500 > cash
        val ok = PaperTrading.order(Side.BUY, 100, 143.5, 100_000.0, 0, fees).getOrThrow()
        assertEquals(-FeeCalculator.buyCost(100, 143.5, fees), ok.cashChange, 1e-9)
    }

    @Test
    fun sellsCannotExceedHoldings() {
        assertTrue(PaperTrading.order(Side.SELL, 20, 143.5, 0.0, 10, fees).isFailure)
        val ok = PaperTrading.order(Side.SELL, 10, 143.5, 0.0, 10, fees).getOrThrow()
        assertEquals(FeeCalculator.sellProceeds(10, 143.5, fees), ok.cashChange, 1e-9)
    }

    @Test
    fun cashTracksTradesAndClosedTradesAreRecorded() {
        val d = LocalDate.of(2026, 1, 5)
        val trades = listOf(
            Trade(1, "JFC", Side.BUY, 100, 200.0, 50.0, d),
            Trade(2, "JFC", Side.SELL, 100, 220.0, 70.0, d.plusDays(10)),
        )
        val s = PortfolioMath.summarize(trades, emptyMap())
        assertEquals(100_000.0 - 20_050.0 + 21_930.0, PaperTrading.cash(100_000.0, s), 1e-9)
        val closed = s.closedTrades.single()
        assertEquals(1_880.0, closed.pnl, 1e-9)
        assertEquals(10L, closed.holdingDays)
    }
}
