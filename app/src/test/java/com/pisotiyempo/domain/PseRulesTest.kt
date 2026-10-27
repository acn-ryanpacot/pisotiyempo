package com.pisotiyempo.domain

import com.pisotiyempo.domain.ph.FeeCalculator
import com.pisotiyempo.domain.ph.FeeSchedule
import com.pisotiyempo.domain.ph.PseRules
import com.pisotiyempo.domain.ph.RiskTools
import com.pisotiyempo.domain.ph.Sectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PseRulesTest {

    @Test
    fun boardLotsAndTicksFollowPriceBands() {
        assertEquals(1_000L, PseRules.boardLot(2.50))
        assertEquals(0.01, PseRules.tickSize(2.50), 1e-12)
        assertEquals(100L, PseRules.boardLot(15.0))
        assertEquals(0.02, PseRules.tickSize(15.0), 1e-12)
        assertEquals(10L, PseRules.boardLot(143.5))
        assertEquals(0.10, PseRules.tickSize(143.5), 1e-12)
        assertEquals(5L, PseRules.boardLot(1900.0))
        assertEquals(10_000L, PseRules.boardLot(0.30))
    }

    @Test
    fun roundsToValidTicks() {
        assertEquals(143.5, PseRules.roundToTick(143.47), 1e-9)
        assertEquals(15.04, PseRules.roundToTick(15.035), 1e-9)
        assertEquals(1902.0, PseRules.roundToTick(1901.6), 1e-9)
    }

    @Test
    fun validatesBoardLotQuantities() {
        assertTrue(PseRules.isValidQuantity(20, 143.5))
        assertFalse(PseRules.isValidQuantity(15, 143.5))
        assertEquals(1_000L, PseRules.roundDownToBoardLot(1_999, 2.5))
    }

    @Test
    fun buyFeesApplyMinimumCommission() {
        // 10 shares at ₱143.50 = ₱1,435 gross; 0.25% would be ₱3.59, so the ₱20 minimum applies.
        val f = FeeCalculator.buy(10, 143.5)
        assertEquals(1435.0, f.gross, 1e-9)
        assertEquals(20.0, f.commission, 1e-9)
        assertEquals(2.4, f.vat, 1e-9)
        assertEquals(0.07, f.pseFee, 1e-9)
        assertEquals(0.14, f.sccpFee, 1e-9)
        assertEquals(0.0, f.salesTax, 1e-9)
    }

    @Test
    fun sellFeesIncludeTransactionTax() {
        val f = FeeCalculator.sell(1_000, 100.0)
        assertEquals(250.0, f.commission, 1e-9)
        assertEquals(30.0, f.vat, 1e-9)
        assertEquals(5.0, f.pseFee, 1e-9)
        assertEquals(10.0, f.sccpFee, 1e-9)
        assertEquals(100.0, f.salesTax, 1e-9)
        assertEquals(100_000.0 - 395.0, FeeCalculator.sellProceeds(1_000, 100.0), 1e-9)
    }

    @Test
    fun breakEvenCoversRoundTripCosts() {
        val be = FeeCalculator.breakEvenPrice(1_000, 100.0)
        assertTrue(FeeCalculator.sellProceeds(1_000, be) >= FeeCalculator.buyCost(1_000, 100.0))
        val oneTickLower = be - PseRules.tickSize(be)
        assertTrue(FeeCalculator.sellProceeds(1_000, oneTickLower) < FeeCalculator.buyCost(1_000, 100.0))
        assertEquals(be, PseRules.roundToTick(be), 1e-9)
    }

    @Test
    fun customScheduleIsRespected() {
        val cheap = FeeSchedule(commissionRate = 0.001, minCommission = 0.0)
        assertEquals(100.0, FeeCalculator.buy(1_000, 100.0, cheap).commission, 1e-9)
    }

    @Test
    fun positionSizeRespectsRiskBoardLotsAndCapital() {
        // Risk 1% of ₱100,000 = ₱1,000; ₱5 risk per share -> 200 shares; board lot at ₱100 is 10.
        val p = RiskTools.positionSize(100_000.0, 1.0, entry = 100.0, stop = 95.0)
        assertNotNull(p)
        assertEquals(200L, p!!.shares)
        assertEquals(1_000.0, p.riskAmount, 1e-9)
        assertFalse(p.limitedByCapital)

        // A very tight stop would want more shares than the capital can buy.
        val tight = RiskTools.positionSize(10_000.0, 2.0, entry = 100.0, stop = 99.9)!!
        assertTrue(tight.limitedByCapital)
        assertTrue(tight.cost <= 10_000.0)
        assertEquals(0L, tight.shares % 10)
    }

    @Test
    fun sectorsMapPreferredSharesToTheirParent() {
        assertEquals(Sectors.INDUSTRIAL, Sectors.of("JFC"))
        assertEquals(Sectors.INDUSTRIAL, Sectors.of("JFCPB"))
        assertEquals(Sectors.HOLDING, Sectors.of("SMC2F"))
        // ACR is not an AC derivative.
        assertEquals(Sectors.OTHER, Sectors.of("ACR"))
    }
}
