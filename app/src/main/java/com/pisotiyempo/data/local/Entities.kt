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
    // Screener fields (schema v2)
    val sector: String,
    val rsi14: Double?,
    val aboveSma50: Boolean?,
    val aboveSma200: Boolean?,
    /** Names of all directional patterns found, newline-separated. */
    val patternNames: String,
    val bullishCount: Int,
    val bearishCount: Int,
    /** Latest volume divided by the 20-day average. */
    val volumeRatio: Double?,
    val return3mPct: Double?,
)

@Entity(tableName = "trades", indices = [Index("symbol"), Index(value = ["uid"], unique = true)])
data class TradeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val side: String,
    val shares: Long,
    val price: Double,
    val fees: Double,
    val date: String,
    val note: String = "",
    /** [ACCOUNT_REAL] or [ACCOUNT_PAPER] (practice trading with virtual money). */
    val account: String = ACCOUNT_REAL,
    /** Stable id across devices for sync (schema v3). */
    val uid: String = newUid(),
    /** Last local change, for sync's last-write-wins. */
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val ACCOUNT_REAL = "REAL"
        const val ACCOUNT_PAPER = "PAPER"
    }
}

/** A trading-journal note: why a trade was taken, how it felt and what was learned. */
@Entity(tableName = "journal", indices = [Index("symbol"), Index(value = ["uid"], unique = true)])
data class JournalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val date: String,
    val tradeId: Long? = null,
    val setup: String,
    val reason: String,
    val emotion: String,
    /** Mistake tags, newline-separated. */
    val mistakes: String,
    val lesson: String,
    /** 1-5, how well the plan was followed. */
    val rating: Int? = null,
    val createdAt: Long,
    val uid: String = newUid(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "watchlist")
data class WatchEntity(
    @PrimaryKey val symbol: String,
    val addedAt: Long,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "alerts", indices = [Index("symbol"), Index(value = ["uid"], unique = true)])
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val kind: String,
    val threshold: Double?,
    val active: Boolean = true,
    val createdAt: Long,
    /** Trading date this alert last fired, so it fires at most once per day. Local only, not synced. */
    val lastTriggered: String? = null,
    val uid: String = newUid(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Remembers deletions so sync can tell other devices, and so an older copy from the server isn't revived. */
@Entity(tableName = "sync_tombstones")
data class TombstoneEntity(
    @PrimaryKey val uid: String,
    val type: String,
    val deletedAt: Long,
)

fun newUid(): String = java.util.UUID.randomUUID().toString().replace("-", "")

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
