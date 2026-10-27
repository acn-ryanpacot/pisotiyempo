package com.pisotiyempo.domain.ph

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor

/**
 * Philippine Stock Exchange trading rules: tick sizes, board lots and transaction costs.
 *
 * The tick/board-lot table follows the PSE's published schedule. Fees default to the common retail
 * schedule and are all editable in Settings because brokers differ (some charge less than 0.25%).
 */
object PseRules {

    private data class Band(val from: Double, val tick: Double, val boardLot: Long)

    // Lower bound of each price band, its tick size and its board lot.
    private val BANDS = listOf(
        Band(0.0001, 0.0001, 1_000_000),
        Band(0.0100, 0.0010, 100_000),
        Band(0.0500, 0.0010, 10_000),
        Band(0.2500, 0.0050, 10_000),
        Band(0.5000, 0.0100, 1_000),
        Band(5.0000, 0.0100, 100),
        Band(10.000, 0.0200, 100),
        Band(20.000, 0.0500, 100),
        Band(50.000, 0.0500, 10),
        Band(100.00, 0.1000, 10),
        Band(200.00, 0.2000, 10),
        Band(500.00, 0.5000, 10),
        Band(1000.0, 1.0000, 5),
        Band(2000.0, 2.0000, 5),
        Band(5000.0, 5.0000, 5),
    )

    private fun band(price: Double) = BANDS.lastOrNull { price >= it.from - 1e-9 } ?: BANDS.first()

    fun tickSize(price: Double): Double = band(price).tick

    fun boardLot(price: Double): Long = band(price).boardLot

    /** Rounds to the nearest valid tick for the price's band. */
    fun roundToTick(price: Double, mode: RoundingMode = RoundingMode.HALF_UP): Double {
        if (price <= 0) return price
        val tick = BigDecimal.valueOf(tickSize(price))
        return BigDecimal.valueOf(price).divide(tick, 0, mode).multiply(tick).toDouble()
    }

    /** Largest whole number of board lots not exceeding [shares]. */
    fun roundDownToBoardLot(shares: Long, price: Double): Long {
        val lot = boardLot(price)
        return (shares / lot) * lot
    }

    fun isValidQuantity(shares: Long, price: Double): Boolean = shares > 0 && shares % boardLot(price) == 0L
}

data class FeeSchedule(
    /** Broker commission rate, e.g. 0.0025 for 0.25%. */
    val commissionRate: Double = 0.0025,
    val minCommission: Double = 20.0,
    /** VAT on the broker commission. */
    val vatRate: Double = 0.12,
    /** PSE transaction fee. */
    val pseFeeRate: Double = 0.00005,
    /** Securities Clearing Corporation of the Philippines fee. */
    val sccpRate: Double = 0.0001,
    /** Stock transaction tax on sells: 0.1% under the Capital Markets Efficiency Promotion Act (RA 12214, 2025). */
    val salesTaxRate: Double = 0.001,
)

data class FeeBreakdown(
    val gross: Double,
    val commission: Double,
    val vat: Double,
    val pseFee: Double,
    val sccpFee: Double,
    val salesTax: Double,
) {
    val totalFees: Double get() = commission + vat + pseFee + sccpFee + salesTax
}

object FeeCalculator {

    fun buy(shares: Long, price: Double, fees: FeeSchedule = FeeSchedule()): FeeBreakdown = breakdown(shares, price, fees, sell = false)

    fun sell(shares: Long, price: Double, fees: FeeSchedule = FeeSchedule()): FeeBreakdown = breakdown(shares, price, fees, sell = true)

    /** Total cash out for a buy. */
    fun buyCost(shares: Long, price: Double, fees: FeeSchedule = FeeSchedule()): Double = buy(shares, price, fees).let { it.gross + it.totalFees }

    /** Cash received from a sell. */
    fun sellProceeds(shares: Long, price: Double, fees: FeeSchedule = FeeSchedule()): Double = sell(shares, price, fees).let { it.gross - it.totalFees }

    private fun breakdown(shares: Long, price: Double, f: FeeSchedule, sell: Boolean): FeeBreakdown {
        val gross = shares * price
        val commission = maxOf(gross * f.commissionRate, f.minCommission)
        return FeeBreakdown(
            gross = round2(gross),
            commission = round2(commission),
            vat = round2(commission * f.vatRate),
            pseFee = round2(gross * f.pseFeeRate),
            sccpFee = round2(gross * f.sccpRate),
            salesTax = if (sell) round2(gross * f.salesTaxRate) else 0.0,
        )
    }

    /**
     * Lowest valid PSE price at which selling [shares] returns at least what buying them cost.
     * Starts from the analytic estimate and steps up tick by tick to absorb rounding and minimum commission.
     */
    fun breakEvenPrice(shares: Long, buyPrice: Double, fees: FeeSchedule = FeeSchedule()): Double {
        val cost = buyCost(shares, buyPrice, fees)
        val variableRate = fees.commissionRate * (1 + fees.vatRate) + fees.pseFeeRate + fees.sccpRate + fees.salesTaxRate
        var price = PseRules.roundToTick(cost / (shares * (1 - variableRate)), RoundingMode.DOWN)
        var guard = 0
        while (sellProceeds(shares, price, fees) < cost && guard++ < 10_000) {
            price = PseRules.roundToTick(price + PseRules.tickSize(price), RoundingMode.HALF_UP)
        }
        return price
    }

    private fun round2(v: Double): Double = BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toDouble()
}

object RiskTools {

    data class PositionSize(
        val shares: Long,
        val boardLot: Long,
        val cost: Double,
        val riskAmount: Double,
        val limitedByCapital: Boolean,
    )

    /**
     * Shares to buy so that hitting [stop] loses about [riskPct] of [capital], rounded down to whole board
     * lots and capped so the total cost (with fees) fits in [capital].
     */
    fun positionSize(capital: Double, riskPct: Double, entry: Double, stop: Double, fees: FeeSchedule = FeeSchedule()): PositionSize? {
        if (capital <= 0 || entry <= 0 || stop <= 0 || stop >= entry) return null
        val lot = PseRules.boardLot(entry)
        val riskBudget = capital * riskPct / 100
        var shares = PseRules.roundDownToBoardLot(floor(riskBudget / (entry - stop)).toLong(), entry)
        var limited = false
        // Cap by capital up front (a stop very close to entry asks for millions of shares); the loop
        // below then only has to absorb the minimum commission and fee rounding.
        val costPerShare = entry * (1 + fees.commissionRate * (1 + fees.vatRate) + fees.pseFeeRate + fees.sccpRate)
        val maxByCapital = PseRules.roundDownToBoardLot(floor(capital / costPerShare).toLong(), entry)
        if (shares > maxByCapital) {
            shares = maxByCapital
            limited = true
        }
        while (shares > 0 && FeeCalculator.buyCost(shares, entry, fees) > capital) {
            shares -= lot
            limited = true
        }
        if (shares <= 0) return PositionSize(0, lot, 0.0, 0.0, limited)
        return PositionSize(shares, lot, FeeCalculator.buyCost(shares, entry, fees), shares * (entry - stop), limited)
    }

    /** Minimum cash needed for one board lot at [price], including fees. */
    fun minimumInvestment(price: Double, fees: FeeSchedule = FeeSchedule()): Double =
        FeeCalculator.buyCost(PseRules.boardLot(price), price, fees)

    fun lotsFor(shares: Long, price: Double): Double = shares.toDouble() / PseRules.boardLot(price)
}
