package com.pisotiyempo.domain.portfolio

import com.pisotiyempo.domain.ph.Sectors
import java.time.LocalDate

enum class Side { BUY, SELL }

data class Trade(
    val id: Long,
    val symbol: String,
    val side: Side,
    val shares: Long,
    val price: Double,
    /** All fees and taxes paid on this trade. */
    val fees: Double,
    val date: LocalDate,
)

data class CashDividend(val symbol: String, val exDate: LocalDate, val amountPerShare: Double)

data class Holding(
    val symbol: String,
    val sector: String,
    val shares: Long,
    /** Average cost per share including buy fees. */
    val avgCost: Double,
    val costBasis: Double,
    val lastPrice: Double?,
    val changePct: Double?,
) {
    val marketValue: Double get() = shares * (lastPrice ?: avgCost)
    val unrealizedPnl: Double get() = marketValue - costBasis
    val unrealizedPct: Double get() = if (costBasis > 0) unrealizedPnl / costBasis * 100 else 0.0
    val dayChangeValue: Double get() = changePct?.let { pct -> marketValue - marketValue / (1 + pct / 100) } ?: 0.0
}

data class PortfolioSummary(
    val holdings: List<Holding>,
    val marketValue: Double,
    val costBasis: Double,
    val unrealizedPnl: Double,
    val realizedPnl: Double,
    val dayChange: Double,
    /** Net of the 10% final withholding tax on cash dividends. */
    val dividendsReceived: Double,
    val sectorWeights: Map<String, Double>,
    val warnings: List<String>,
) {
    val unrealizedPct: Double get() = if (costBasis > 0) unrealizedPnl / costBasis * 100 else 0.0
    val dayChangePct: Double get() = if (marketValue - dayChange > 0) dayChange / (marketValue - dayChange) * 100 else 0.0
}

/** Average-cost accounting, the method Philippine brokers use on statements. */
object PortfolioMath {

    const val DIVIDEND_TAX = 0.10

    fun summarize(
        trades: List<Trade>,
        prices: Map<String, Pair<Double, Double?>>,
        dividends: List<CashDividend> = emptyList(),
        sectorOf: (String) -> String = Sectors::of,
    ): PortfolioSummary {
        data class Position(var shares: Long = 0, var cost: Double = 0.0)
        val positions = mutableMapOf<String, Position>()
        var realized = 0.0
        val ordered = trades.sortedWith(compareBy<Trade> { it.date }.thenBy { if (it.side == Side.BUY) 0 else 1 }.thenBy { it.id })

        // Shares held at each ex-date determine dividend entitlement.
        var dividendIncome = 0.0
        val pendingDivs = dividends.sortedBy { it.exDate }.toMutableList()
        fun creditDividendsBefore(date: LocalDate?) {
            while (pendingDivs.isNotEmpty() && (date == null || pendingDivs.first().exDate <= date)) {
                val d = pendingDivs.removeAt(0)
                val held = positions[d.symbol]?.shares ?: 0
                dividendIncome += held * d.amountPerShare * (1 - DIVIDEND_TAX)
            }
        }

        for (t in ordered) {
            // Buying on or after the ex-date does not earn that dividend.
            creditDividendsBefore(t.date)
            val p = positions.getOrPut(t.symbol) { Position() }
            when (t.side) {
                Side.BUY -> {
                    p.shares += t.shares
                    p.cost += t.shares * t.price + t.fees
                }
                Side.SELL -> {
                    val qty = minOf(t.shares, p.shares)
                    if (qty <= 0) continue
                    val avg = p.cost / p.shares
                    realized += qty * t.price - t.fees - avg * qty
                    p.cost -= avg * qty
                    p.shares -= qty
                }
            }
        }
        creditDividendsBefore(LocalDate.now())

        val holdings = positions.filter { it.value.shares > 0 }.map { (symbol, p) ->
            val quote = prices[symbol]
            Holding(symbol, sectorOf(symbol), p.shares, p.cost / p.shares, p.cost, quote?.first, quote?.second)
        }.sortedByDescending { it.marketValue }

        val mv = holdings.sumOf { it.marketValue }
        val weights = holdings.groupBy { it.sector }.mapValues { (_, hs) -> if (mv > 0) hs.sumOf { it.marketValue } / mv else 0.0 }
        return PortfolioSummary(
            holdings = holdings,
            marketValue = mv,
            costBasis = holdings.sumOf { it.costBasis },
            unrealizedPnl = holdings.sumOf { it.unrealizedPnl },
            realizedPnl = realized,
            dayChange = holdings.sumOf { it.dayChangeValue },
            dividendsReceived = dividendIncome,
            sectorWeights = weights,
            warnings = concentrationWarnings(holdings, mv, weights),
        )
    }

    fun concentrationWarnings(holdings: List<Holding>, total: Double, weights: Map<String, Double>): List<String> {
        if (total <= 0) return emptyList()
        val out = mutableListOf<String>()
        holdings.filter { it.marketValue / total > 0.25 }.forEach {
            out += "${it.symbol} is ${pct(it.marketValue / total)} of your portfolio. Consider keeping any single stock under 25%."
        }
        weights.filter { it.value > 0.40 && it.key != Sectors.OTHER }.forEach {
            out += "${it.key} makes up ${pct(it.value)} of your portfolio. A sector shock would hit hard; consider diversifying."
        }
        if (holdings.size in 1..2) out += "With only ${holdings.size} stock${if (holdings.size == 1) "" else "s"}, your results depend heavily on one company."
        return out
    }

    private fun pct(v: Double) = "${"%.0f".format(v * 100)}%"
}
