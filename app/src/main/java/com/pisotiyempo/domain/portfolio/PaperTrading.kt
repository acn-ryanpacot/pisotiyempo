package com.pisotiyempo.domain.portfolio

import com.pisotiyempo.domain.ph.FeeCalculator
import com.pisotiyempo.domain.ph.FeeSchedule
import com.pisotiyempo.domain.ph.PseRules

/** A practice order, filled at the last close with real PSE fees, board lots and the user's cash. */
data class PaperOrder(val side: Side, val shares: Long, val price: Double, val fees: Double, val cashChange: Double)

object PaperTrading {
    const val DEFAULT_STARTING_CASH = 100_000.0

    /** Cash left in the practice account: starting cash plus the net cash flow of every practice trade. */
    fun cash(startingCash: Double, summary: PortfolioSummary): Double = startingCash + summary.netCashFlow

    /**
     * Builds the order or explains why it can't be placed. Practice orders must be whole board lots,
     * like on the real exchange, so the habit carries over.
     */
    fun order(side: Side, shares: Long, lastClose: Double?, cash: Double, heldShares: Long, fees: FeeSchedule): Result<PaperOrder> {
        if (lastClose == null || lastClose <= 0) return Result.failure(IllegalArgumentException("No recent price for this stock yet."))
        if (shares <= 0) return Result.failure(IllegalArgumentException("Enter the number of shares."))
        val lot = PseRules.boardLot(lastClose)
        if (shares % lot != 0L) {
            return Result.failure(IllegalArgumentException("At ₱$lastClose the board lot is $lot shares. Use a multiple of $lot."))
        }
        return when (side) {
            Side.BUY -> {
                val f = FeeCalculator.buy(shares, lastClose, fees)
                val cost = f.gross + f.totalFees
                if (cost > cash + 1e-6) Result.failure(IllegalArgumentException("Not enough practice cash: this costs ₱%,.2f.".format(cost)))
                else Result.success(PaperOrder(side, shares, lastClose, f.totalFees, -cost))
            }
            Side.SELL -> {
                if (shares > heldShares) Result.failure(IllegalArgumentException("You only hold $heldShares shares."))
                else {
                    val f = FeeCalculator.sell(shares, lastClose, fees)
                    Result.success(PaperOrder(side, shares, lastClose, f.totalFees, f.gross - f.totalFees))
                }
            }
        }
    }
}
