package com.pisotiyempo.data.repo

import com.pisotiyempo.data.local.AppDatabase
import com.pisotiyempo.data.local.TradeEntity
import com.pisotiyempo.data.local.WatchEntity
import com.pisotiyempo.domain.portfolio.CashDividend
import com.pisotiyempo.domain.portfolio.PortfolioMath
import com.pisotiyempo.domain.portfolio.PortfolioSummary
import com.pisotiyempo.domain.portfolio.Side
import com.pisotiyempo.domain.portfolio.Trade
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class PortfolioRepository(private val db: AppDatabase) {

    fun trades(): Flow<List<TradeEntity>> = db.trades().observeAll()

    fun summary(): Flow<PortfolioSummary> = combine(
        db.trades().observeAll(),
        db.stocks().observeAll(),
        db.dividends().observeAll(),
    ) { trades, stocks, divs ->
        PortfolioMath.summarize(
            trades = trades.map { it.toTrade() },
            prices = stocks.associate { it.symbol to (it.close to it.changePct) },
            dividends = divs.filter { it.type == "CASH" && it.amount != null }
                .map { CashDividend(it.symbol, LocalDate.parse(it.exDate), it.amount!!) },
        )
    }

    suspend fun holdingSymbols(): Set<String> =
        PortfolioMath.summarize(db.trades().all().map { it.toTrade() }, emptyMap()).holdings.map { it.symbol }.toSet()

    suspend fun addTrade(trade: TradeEntity) = db.trades().insert(trade)

    suspend fun deleteTrade(id: Long) = db.trades().delete(id)

    fun watchlist(): Flow<List<String>> = db.watchlist().observeAll().map { rows -> rows.map { it.symbol } }

    fun isWatched(symbol: String): Flow<Boolean> = db.watchlist().observeContains(symbol)

    suspend fun watchSymbols(): Set<String> = db.watchlist().symbols().toSet()

    suspend fun setWatched(symbol: String, watched: Boolean) {
        if (watched) db.watchlist().add(WatchEntity(symbol, System.currentTimeMillis())) else db.watchlist().remove(symbol)
    }
}

fun TradeEntity.toTrade() = Trade(id, symbol, Side.valueOf(side), shares, price, fees, LocalDate.parse(date))
