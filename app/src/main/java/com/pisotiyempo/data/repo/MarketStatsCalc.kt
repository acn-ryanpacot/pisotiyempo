package com.pisotiyempo.data.repo

import com.pisotiyempo.data.local.StockEntity
import com.pisotiyempo.domain.analysis.MarketStats
import com.pisotiyempo.domain.model.Bar
import com.pisotiyempo.domain.ph.Sectors

data class SectorMove(val sector: String, val changePct: Double, val value: Double, val count: Int)

object MarketStatsCalc {
    /** Gainers/losers only count stocks that traded at least this much, so tiny trades don't dominate. */
    const val LIQUID_VALUE = 5_000_000.0

    fun latestDate(stocks: List<StockEntity>): String? =
        stocks.groupingBy { it.date }.eachCount().maxByOrNull { it.value }?.key

    fun today(stocks: List<StockEntity>): List<StockEntity> {
        val date = latestDate(stocks) ?: return emptyList()
        return stocks.filter { it.date == date }
    }

    fun compute(stocks: List<StockEntity>, psei: List<Bar>): MarketStats? {
        val date = latestDate(stocks) ?: return null
        val day = stocks.filter { it.date == date }
        val liquid = day.filter { it.close * it.volume >= LIQUID_VALUE && it.changePct != null }
        val byChange = liquid.sortedByDescending { it.changePct }
        val last = psei.lastOrNull()
        val prev = psei.getOrNull(psei.size - 2)
        return MarketStats(
            date = date,
            pseiClose = last?.close,
            pseiChangePct = if (last != null && prev != null) (last.close - prev.close) / prev.close * 100 else null,
            advancers = day.count { (it.changePct ?: 0.0) > 0 },
            decliners = day.count { (it.changePct ?: 0.0) < 0 },
            unchanged = day.count { it.changePct == 0.0 },
            totalValue = day.sumOf { it.close * it.volume },
            topGainers = byChange.filter { it.changePct!! > 0 }.take(5).map { it.symbol to it.changePct!! },
            topLosers = byChange.reversed().filter { it.changePct!! < 0 }.take(5).map { it.symbol to it.changePct!! },
        )
    }

    /** Value-weighted average move per sector for the latest day. */
    fun sectors(stocks: List<StockEntity>): List<SectorMove> =
        today(stocks).filter { it.changePct != null && it.sector in Sectors.ALL }
            .groupBy { it.sector }
            .map { (sector, rows) ->
                val weights = rows.map { maxOf(it.close * it.volume, 1.0) }
                val total = weights.sum()
                SectorMove(sector, rows.indices.sumOf { rows[it].changePct!! * weights[it] } / total, rows.sumOf { it.close * it.volume }, rows.size)
            }
            .sortedByDescending { it.changePct }
}
