package com.pisotiyempo.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Latest known quote for every PSE security. Dates are ISO yyyy-MM-dd strings throughout. */
@Entity(tableName = "stocks")
data class StockEntity(
    @PrimaryKey val symbol: String,
    val name: String,
    val sector: String,
    val close: Double,
    val changePct: Double?,
    val volume: Long,
    val date: String,
    val source: String,
)

/** Daily bars. The PSEi is stored here too, under [PSEI_SYMBOL]. */
@Entity(tableName = "bars", primaryKeys = ["symbol", "date"])
data class BarEntity(
    val symbol: String,
    val date: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
) {
    companion object {
        const val PSEI_SYMBOL = "^PSEI"
    }
}

/** Compact engine output per stock, refreshed on each sync, used for rankings and alerts. */
@Entity(tableName = "analyses")
data class AnalysisEntity(
    @PrimaryKey val symbol: String,
    val name: String,
    val asOf: String,
    val close: Double,
    val changePct: Double?,
    val score: Int,
    val stance: String,
    val topSignal: String?,
    /** Names of confirmed, higher-confidence patterns found this sync, newline-separated (for pattern alerts). */
    val alertPatterns: String,
    val avgDailyValue: Double,
    val isLiquid: Boolean,
    val updatedAt: Long,
)

@Entity(tableName = "trades", indices = [Index("symbol")])
data class TradeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val side: String,
    val shares: Long,
    val price: Double,
    val fees: Double,
    val date: String,
    val note: String = "",
)

@Entity(tableName = "watchlist")
data class WatchEntity(
    @PrimaryKey val symbol: String,
    val addedAt: Long,
)

@Entity(tableName = "alerts", indices = [Index("symbol")])
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val kind: String,
    val threshold: Double?,
    val active: Boolean = true,
    val createdAt: Long,
    /** Trading date this alert last fired, so it fires at most once per day. */
    val lastTriggered: String? = null,
)

@Entity(tableName = "dividends", primaryKeys = ["symbol", "exDate", "type", "rateText"])
data class DividendEntity(
    val symbol: String,
    val exDate: String,
    val type: String,
    val amount: Double?,
    val rateText: String,
    val recordDate: String?,
    val paymentDate: String?,
)

@Entity(tableName = "disclosures", indices = [Index("symbol")])
data class DisclosureEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val title: String,
    val announcedAt: String,
    val url: String,
    val summary: String? = null,
    val impact: String? = null,
    /** Newline-separated. */
    val keyPoints: String? = null,
)

/** Small key/value cache for AI results, the market brief and bookkeeping like last backfill dates. */
@Entity(tableName = "kv")
data class KvEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)
