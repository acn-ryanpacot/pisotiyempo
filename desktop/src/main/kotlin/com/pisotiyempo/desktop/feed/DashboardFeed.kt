package com.pisotiyempo.desktop.feed

import com.pisotiyempo.desktop.data.Db
import com.pisotiyempo.desktop.data.DesktopConfig
import com.pisotiyempo.desktop.data.DesktopMarket
import com.pisotiyempo.desktop.data.DesktopPortfolio
import com.pisotiyempo.domain.csv.Csv
import com.pisotiyempo.domain.portfolio.PaperTrading
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

@Serializable
data class FeedHolding(
    val symbol: String, val sector: String, val shares: Long, val avgCost: Double, val costBasis: Double,
    val lastPrice: Double?, val changePct: Double?, val marketValue: Double, val unrealizedPnl: Double, val unrealizedPct: Double,
)

@Serializable
data class FeedSummary(
    val asOf: String?, val marketValue: Double, val costBasis: Double, val unrealizedPnl: Double, val unrealizedPct: Double,
    val realizedPnl: Double, val dayChange: Double, val dividendsReceived: Double, val holdings: Int,
    val sectorWeights: Map<String, Double>, val warnings: List<String>,
    val practiceValue: Double, val practiceCash: Double,
)

@Serializable
data class FeedTrade(val uid: String, val date: String, val side: String, val symbol: String, val shares: Long, val price: Double, val fees: Double, val account: String, val note: String)

@Serializable
data class FeedClosedTrade(val symbol: String, val date: String, val shares: Long, val pnl: Double, val pnlPct: Double, val holdingDays: Long)

@Serializable
data class FeedJournal(val uid: String, val date: String, val symbol: String, val setup: String, val emotion: String, val mistakes: List<String>, val rating: Int?, val reason: String, val lesson: String)

@Serializable
data class FeedAnalysis(
    val symbol: String, val name: String, val sector: String, val close: Double, val changePct: Double?, val score: Int, val stance: String,
    val rsi14: Double?, val aboveSma50: Boolean?, val aboveSma200: Boolean?, val patterns: List<String>, val return3mPct: Double?, val isLiquid: Boolean,
)

@Serializable
data class FeedPrice(val symbol: String, val name: String, val sector: String, val close: Double, val changePct: Double?, val volume: Long, val date: String)

/**
 * Builds the dashboard data once and serves it two ways: a read-only JSON API on 127.0.0.1 (never reachable
 * from other machines), and JSON/CSV files written to the export folder after every sync. The SQLite file
 * itself is a third option for tools that read databases.
 */
class DashboardFeed(private val db: Db, private val market: DesktopMarket, private val portfolio: DesktopPortfolio, private val config: DesktopConfig) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private var server: HttpServer? = null

    fun prices(): Map<String, Pair<Double, Double?>> = market.stocks().associate { it.symbol to (it.close to it.changePct) }

    fun summary(): FeedSummary {
        val p = prices()
        val s = portfolio.summary(p)
        val practice = portfolio.summary(p, "PAPER")
        val start = db.kvGet("paper-start")?.toDoubleOrNull() ?: PaperTrading.DEFAULT_STARTING_CASH
        val cash = PaperTrading.cash(start, practice)
        return FeedSummary(
            market.lastSyncDate(), s.marketValue, s.costBasis, s.unrealizedPnl, s.unrealizedPct, s.realizedPnl, s.dayChange,
            s.dividendsReceived, s.holdings.size, s.sectorWeights, s.warnings, cash + practice.marketValue, cash,
        )
    }

    fun holdings(account: String = "REAL") = portfolio.summary(prices(), account).holdings.map {
        FeedHolding(it.symbol, it.sector, it.shares, it.avgCost, it.costBasis, it.lastPrice, it.changePct, it.marketValue, it.unrealizedPnl, it.unrealizedPct)
    }

    fun trades(account: String) = portfolio.trades(account).map { FeedTrade(it.uid, it.date, it.side, it.symbol, it.shares, it.price, it.fees, it.account, it.note) }

    fun closedTrades(account: String = "REAL") = portfolio.summary(prices(), account).closedTrades.map {
        FeedClosedTrade(it.symbol, it.date.toString(), it.shares, it.pnl, it.pnlPct, it.holdingDays)
    }

    fun journal() = portfolio.journal().map { FeedJournal(it.uid, it.date, it.symbol, it.setup, it.emotion, it.mistakes, it.rating, it.reason, it.lesson) }

    fun analyses() = market.screenRows().map {
        FeedAnalysis(it.symbol, it.name, it.sector, it.close, it.changePct, it.score, it.stance.name, it.rsi14, it.aboveSma50, it.aboveSma200, it.patterns, it.return3mPct, it.isLiquid)
    }

    fun pricesList() = market.stocks().map { FeedPrice(it.symbol, it.name, it.sector, it.close, it.changePct, it.volume, it.date) }

    fun watchlist() = portfolio.watchlist()

    // ---- Local API ------------------------------------------------------------------------------------

    fun start() {
        stop()
        if (!config.feedEnabled) return
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), config.feedPort), 0)
        s.executor = Executors.newFixedThreadPool(2)
        s.createContext("/") { ex -> handle(ex) }
        s.start()
        server = s
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    val url: String get() = "http://127.0.0.1:${config.feedPort}"

    private fun handle(ex: HttpExchange) {
        try {
            if (ex.requestMethod == "OPTIONS") return respond(ex, 204, "")
            if (ex.requestMethod != "GET") return respond(ex, 405, """{"error":"read-only"}""")
            val query = ex.requestURI.query.orEmpty().split('&').mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val account = if (query["account"]?.uppercase() == "PAPER") "PAPER" else "REAL"
            val body = when (ex.requestURI.path.trimEnd('/')) {
                "", "/api" -> json.encodeToString(Index.serializer(), Index())
                "/api/summary" -> json.encodeToString(FeedSummary.serializer(), summary())
                "/api/holdings" -> json.encodeToString(ListSerializer(FeedHolding.serializer()), holdings(account))
                "/api/trades" -> json.encodeToString(ListSerializer(FeedTrade.serializer()), trades(account))
                "/api/closed-trades" -> json.encodeToString(ListSerializer(FeedClosedTrade.serializer()), closedTrades(account))
                "/api/journal" -> json.encodeToString(ListSerializer(FeedJournal.serializer()), journal())
                "/api/watchlist" -> json.encodeToString(ListSerializer(String.serializer()), watchlist())
                "/api/analyses" -> json.encodeToString(ListSerializer(FeedAnalysis.serializer()), analyses())
                "/api/prices" -> json.encodeToString(ListSerializer(FeedPrice.serializer()), pricesList())
                else -> return respond(ex, 404, """{"error":"not found"}""")
            }
            respond(ex, 200, body)
        } catch (e: Exception) {
            respond(ex, 500, """{"error":"${(e.message ?: "error").replace("\"", "'")}"}""")
        }
    }

    @Serializable
    private data class Index(
        val endpoints: List<String> = listOf(
            "/api/summary", "/api/holdings?account=REAL|PAPER", "/api/trades?account=REAL|PAPER", "/api/closed-trades?account=REAL|PAPER",
            "/api/journal", "/api/watchlist", "/api/analyses", "/api/prices",
        ),
    )

    private fun respond(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        // Browsers may only read the feed from origins the user listed; other local apps (curl, Power BI, Python) don't need CORS.
        val origin = ex.requestHeaders.getFirst("Origin")
        val allowed = config.feedAllowOrigins.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (origin != null && origin in allowed) {
            ex.responseHeaders.add("Access-Control-Allow-Origin", origin)
            ex.responseHeaders.add("Access-Control-Allow-Methods", "GET, OPTIONS")
        }
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
        ex.close()
    }

    // ---- File exports ---------------------------------------------------------------------------------

    /** Writes summary.json plus a CSV for each dataset to the export folder. Returns the folder. */
    fun export(): File {
        val dir = config.exportDir.also { it.mkdirs() }
        File(dir, "summary.json").writeText(json.encodeToString(FeedSummary.serializer(), summary()))
        writeCsv(dir, "holdings.csv", listOf("symbol", "sector", "shares", "avgCost", "costBasis", "lastPrice", "changePct", "marketValue", "unrealizedPnl", "unrealizedPct"),
            holdings().map { listOf(it.symbol, it.sector, it.shares, it.avgCost, it.costBasis, it.lastPrice, it.changePct, it.marketValue, it.unrealizedPnl, it.unrealizedPct) })
        for (account in listOf("REAL", "PAPER")) {
            val suffix = if (account == "REAL") "" else "_practice"
            writeCsv(dir, "trades$suffix.csv", listOf("uid", "date", "side", "symbol", "shares", "price", "fees", "note"),
                trades(account).map { listOf(it.uid, it.date, it.side, it.symbol, it.shares, it.price, it.fees, it.note) })
            writeCsv(dir, "closed_trades$suffix.csv", listOf("symbol", "date", "shares", "pnl", "pnlPct", "holdingDays"),
                closedTrades(account).map { listOf(it.symbol, it.date, it.shares, it.pnl, it.pnlPct, it.holdingDays) })
        }
        writeCsv(dir, "journal.csv", listOf("uid", "date", "symbol", "setup", "emotion", "mistakes", "rating", "reason", "lesson"),
            journal().map { listOf(it.uid, it.date, it.symbol, it.setup, it.emotion, it.mistakes.joinToString("; "), it.rating, it.reason, it.lesson) })
        writeCsv(dir, "analyses.csv", listOf("symbol", "name", "sector", "close", "changePct", "score", "stance", "rsi14", "aboveSma50", "aboveSma200", "patterns", "return3mPct", "isLiquid"),
            analyses().map { listOf(it.symbol, it.name, it.sector, it.close, it.changePct, it.score, it.stance, it.rsi14, it.aboveSma50, it.aboveSma200, it.patterns.joinToString("; "), it.return3mPct, it.isLiquid) })
        writeCsv(dir, "prices.csv", listOf("symbol", "name", "sector", "close", "changePct", "volume", "date"),
            pricesList().map { listOf(it.symbol, it.name, it.sector, it.close, it.changePct, it.volume, it.date) })
        writeCsv(dir, "watchlist.csv", listOf("symbol"), watchlist().map { listOf(it) })
        return dir
    }

    private fun writeCsv(dir: File, name: String, header: List<String>, rows: List<List<Any?>>) {
        // Write to a temp file and rename, so a dashboard reading mid-export never sees half a file.
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(Csv.write(listOf(header) + rows.map { r -> r.map { it?.toString().orEmpty() } }))
        val target = File(dir, name)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
