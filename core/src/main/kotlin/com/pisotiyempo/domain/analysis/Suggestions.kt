package com.pisotiyempo.domain.analysis

import com.pisotiyempo.domain.model.Stance

/** A compact row of engine output, what the dashboard needs to rank and display a stock. */
data class ScoredStock(
    val symbol: String,
    val name: String,
    val close: Double,
    val changePct: Double?,
    val score: Int,
    val stance: Stance,
    val topSignal: String?,
    val avgDailyValue: Double,
    val isLiquid: Boolean,
)

object Suggestions {

    const val BUY_THRESHOLD = 20
    const val SELL_THRESHOLD = -20

    /** Highest-scoring liquid stocks across the analyzed universe. */
    fun topBuys(all: List<ScoredStock>, n: Int = 5): List<ScoredStock> =
        all.filter { it.isLiquid && it.score >= BUY_THRESHOLD }
            .sortedWith(compareByDescending<ScoredStock> { it.score }.thenByDescending { it.avgDailyValue })
            .take(n)

    /**
     * Sell candidates come first from what the user owns or watches, since that is where a sell call is
     * actionable (holdings rank ahead of watchlist names at the same score). Remaining slots are filled
     * with the weakest liquid stocks market-wide, which the UI labels as "avoid" rather than "sell".
     */
    fun topSells(all: List<ScoredStock>, holdings: Set<String>, watchlist: Set<String>, n: Int = 5): List<ScoredStock> {
        val mine = holdings + watchlist
        val own = all.filter { it.symbol in mine && it.score <= SELL_THRESHOLD }
            .sortedWith(compareBy<ScoredStock> { it.score }.thenBy { if (it.symbol in holdings) 0 else 1 })
            .take(n)
        if (own.size >= n) return own
        val market = all.filter { it.symbol !in mine && it.isLiquid && it.score <= SELL_THRESHOLD }
            .sortedWith(compareBy<ScoredStock> { it.score }.thenByDescending { it.avgDailyValue })
            .take(n - own.size)
        return own + market
    }
}
