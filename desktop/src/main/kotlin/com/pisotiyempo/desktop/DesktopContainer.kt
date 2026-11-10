package com.pisotiyempo.desktop

import com.pisotiyempo.data.remote.PisoApi
import com.pisotiyempo.desktop.data.Db
import com.pisotiyempo.desktop.data.DesktopAi
import com.pisotiyempo.desktop.data.DesktopConfig
import com.pisotiyempo.desktop.data.DesktopMarket
import com.pisotiyempo.desktop.data.DesktopPaths
import com.pisotiyempo.desktop.data.DesktopPortfolio
import com.pisotiyempo.desktop.data.DesktopSession
import com.pisotiyempo.desktop.data.DesktopSync
import com.pisotiyempo.desktop.data.LinkStore
import com.pisotiyempo.desktop.data.MANILA
import com.pisotiyempo.desktop.feed.DashboardFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalTime
import java.time.ZonedDateTime

/** Wires the desktop app together and runs its background work: device sync, market sync, the dashboard feed. */
@OptIn(FlowPreview::class)
class DesktopContainer {
    val config = DesktopConfig()
    val db = Db(DesktopPaths.database)
    private val links = LinkStore(db)
    val session = DesktopSession(db, links)
    val api = PisoApi(config.apiBaseUrl, config.appKey, sessions = session)
    val sync = DesktopSync(db, api, links, session)
    private val localChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val portfolio = DesktopPortfolio(db) { localChanges.tryEmit(Unit) }
    val market = DesktopMarket(db, api)
    val ai = DesktopAi(api, config)
    val feed = DashboardFeed(db, market, portfolio, config)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val marketMutex = Mutex()
    private val _progress = MutableStateFlow<String?>(null)
    /** Non-null while a market sync runs: what it's doing. */
    val progress: StateFlow<String?> = _progress
    private val _lastMessage = MutableStateFlow<String?>(null)
    val lastMessage: StateFlow<String?> = _lastMessage
    private val _dataVersion = MutableStateFlow(0L)
    /** Changes whenever synced or market data changes, so screens reload. */
    val dataVersion: StateFlow<Long> = _dataVersion

    fun start() {
        runCatching { feed.start() }.onFailure { _lastMessage.value = "Dashboard feed couldn't start on port ${config.feedPort}: ${it.message}" }
        // Local edits reach the phone a few seconds later.
        scope.launch { localChanges.debounce(3_000).collect { syncDevices() } }
        scope.launch {
            while (isActive) {
                syncDevices()
                if (marketIsStale()) syncMarket()
                delay(15 * 60_000L)
            }
        }
    }

    fun touch() {
        _dataVersion.value = System.currentTimeMillis()
    }

    suspend fun syncDevices() {
        sync.syncNow()
        touch()
        exportQuietly()
    }

    fun syncMarketAsync() = scope.launch { syncMarket() }

    suspend fun syncMarket() {
        if (!marketMutex.tryLock()) return
        try {
            val mine = portfolio.summary(emptyMap()).holdings.map { it.symbol }.toSet() + portfolio.watchlist()
            _lastMessage.value = market.sync(mine) { _progress.value = it }
            db.kvPut("market-synced-at", System.currentTimeMillis().toString())
        } catch (e: Exception) {
            _lastMessage.value = "Market sync failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            _progress.value = null
            marketMutex.unlock()
            touch()
            exportQuietly()
        }
    }

    /** New prices exist after 6 PM Manila time on weekdays; otherwise refresh at most every 6 hours. */
    private fun marketIsStale(): Boolean {
        val last = db.kvGet("market-synced-at")?.toLongOrNull() ?: return true
        val now = ZonedDateTime.now(MANILA)
        val closeToday = now.with(LocalTime.of(18, 0))
        val lastAt = java.time.Instant.ofEpochMilli(last).atZone(MANILA)
        return (now.isAfter(closeToday) && lastAt.isBefore(closeToday)) || now.toInstant().toEpochMilli() - last > 6 * 3600_000L
    }

    private val exportMutex = Mutex()

    private suspend fun exportQuietly() = exportMutex.withLock {
        if (config.exportEnabled) runCatching { feed.export() }
    }

    fun close() {
        feed.stop()
    }
}
