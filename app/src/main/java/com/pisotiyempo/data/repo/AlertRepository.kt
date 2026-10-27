package com.pisotiyempo.data.repo

import com.pisotiyempo.data.local.AlertEntity
import com.pisotiyempo.data.local.AnalysisEntity
import com.pisotiyempo.data.local.AppDatabase
import com.pisotiyempo.data.local.StockEntity
import kotlinx.coroutines.flow.Flow

enum class AlertKind(val label: String, val needsThreshold: Boolean) {
    PRICE_ABOVE("Price rises above", true),
    PRICE_BELOW("Price falls below", true),
    BUY_SIGNAL("Score turns Buy (≥ 20)", false),
    SELL_SIGNAL("Score turns Sell (≤ -20)", false),
    PATTERN("A confirmed pattern appears", false),
}

data class TriggeredAlert(val alert: AlertEntity, val title: String, val message: String)

class AlertRepository(private val db: AppDatabase) {

    fun alerts(): Flow<List<AlertEntity>> = db.alerts().observeAll()

    suspend fun add(symbol: String, kind: AlertKind, threshold: Double?) {
        db.alerts().insert(AlertEntity(symbol = symbol, kind = kind.name, threshold = threshold, createdAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: Long) = db.alerts().delete(id)

    /** Checks active alerts against the latest data. Each alert fires at most once per trading day. */
    suspend fun evaluate(): List<TriggeredAlert> {
        val stocks = db.stocks().all().associateBy { it.symbol }
        val analyses = db.analyses().all().associateBy { it.symbol }
        val fired = mutableListOf<TriggeredAlert>()
        val updated = mutableListOf<AlertEntity>()
        for (alert in db.alerts().active()) {
            val stock = stocks[alert.symbol] ?: continue
            if (alert.lastTriggered == stock.date) continue
            val message = check(alert, stock, analyses[alert.symbol]) ?: continue
            fired += TriggeredAlert(alert, "${alert.symbol} alert", message)
            updated += alert.copy(lastTriggered = stock.date)
        }
        if (updated.isNotEmpty()) db.alerts().update(updated)
        return fired
    }

    private fun check(alert: AlertEntity, stock: StockEntity, analysis: AnalysisEntity?): String? {
        val kind = runCatching { AlertKind.valueOf(alert.kind) }.getOrNull() ?: return null
        val t = alert.threshold
        val price = "₱%,.2f".format(stock.close)
        return when (kind) {
            AlertKind.PRICE_ABOVE -> if (t != null && stock.close >= t) "Closed at $price, above your ₱%,.2f alert.".format(t) else null
            AlertKind.PRICE_BELOW -> if (t != null && stock.close <= t) "Closed at $price, below your ₱%,.2f alert.".format(t) else null
            AlertKind.BUY_SIGNAL -> analysis?.takeIf { it.asOf == stock.date && it.score >= 20 }?.let { "Score ${it.score}: technicals now rate it a buy." }
            AlertKind.SELL_SIGNAL -> analysis?.takeIf { it.asOf == stock.date && it.score <= -20 }?.let { "Score ${it.score}: technicals now rate it a sell." }
            AlertKind.PATTERN -> analysis?.takeIf { it.asOf == stock.date && it.alertPatterns.isNotBlank() }?.let { "New pattern: ${it.alertPatterns.lines().joinToString()}." }
        }
    }
}
