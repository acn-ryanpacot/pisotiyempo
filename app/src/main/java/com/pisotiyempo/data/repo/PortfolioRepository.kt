package com.pisotiyempo.data.repo

import com.pisotiyempo.data.local.AppDatabase
import com.pisotiyempo.data.local.JournalEntity
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

    fun trades(account: String = TradeEntity.ACCOUNT_REAL): Flow<List<TradeEntity>> = db.trades().observe(account)

    fun summary(account: String = TradeEntity.ACCOUNT_REAL): Flow<PortfolioSummary> = combine(
        db.trades().observe(account),
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

    /** Real holdings only: practice positions don't drive sell signals, alerts or disclosure syncing. */
    suspend fun holdingSymbols(): Set<String> =
        PortfolioMath.summarize(db.trades().all(TradeEntity.ACCOUNT_REAL).map { it.toTrade() }, emptyMap()).holdings.map { it.symbol }.toSet()

    suspend fun allTrades(account: String): List<TradeEntity> = db.trades().all(account)

    suspend fun addTrade(trade: TradeEntity) = db.trades().insert(trade)

    suspend fun addTrades(trades: List<TradeEntity>) = db.trades().insertAll(trades)

    /** Deletes every practice trade, returning the practice account to its starting cash. */
    suspend fun resetPractice() = db.trades().deleteAccount(TradeEntity.ACCOUNT_PAPER)

    suspend fun deleteTrade(id: Long) = db.trades().delete(id)

    fun journal(): Flow<List<JournalEntity>> = db.journal().observeAll()

    suspend fun journalNow(): List<JournalEntity> = db.journal().all()

    suspend fun addJournal(entry: JournalEntity) = db.journal().insert(entry)

    suspend fun addJournalEntries(entries: List<JournalEntity>) = db.journal().insertAll(entries)

    suspend fun deleteJournal(id: Long) = db.journal().delete(id)

    fun watchlist(): Flow<List<String>> = db.watchlist().observeAll().map { rows -> rows.map { it.symbol } }

    fun isWatched(symbol: String): Flow<Boolean> = db.watchlist().observeContains(symbol)

    suspend fun watchSymbols(): Set<String> = db.watchlist().symbols().toSet()

    suspend fun setWatched(symbol: String, watched: Boolean) {
        if (watched) db.watchlist().add(WatchEntity(symbol, System.currentTimeMillis())) else db.watchlist().remove(symbol)
    }
}

fun TradeEntity.toTrade() = Trade(id, symbol, Side.valueOf(side), shares, price, fees, LocalDate.parse(date))
