package com.pisotiyempo.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pisotiyempo.desktop.DesktopContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Page(val label: String, val icon: ImageVector) {
    DASHBOARD("Home", Icons.Filled.Dashboard),
    MARKET("Market", Icons.Filled.ShowChart),
    SCREENER("Screener", Icons.Filled.FilterAlt),
    PORTFOLIO("Portfolio", Icons.Filled.AccountBalanceWallet),
    JOURNAL("Journal", Icons.Filled.EditNote),
    WATCHLIST("Watchlist", Icons.Filled.Star),
    SETTINGS("Settings", Icons.Filled.Settings),
}

/** Loads [load] off the UI thread, again whenever synced or market data changes. */
@Composable
fun <T> rememberData(c: DesktopContainer, initial: T, vararg keys: Any?, load: () -> T): State<T> {
    val version by c.dataVersion.collectAsState()
    return produceState(initial, version, *keys) { value = withContext(Dispatchers.IO) { runCatching(load).getOrDefault(initial) } }
}

@Composable
fun App(c: DesktopContainer) {
    var page by remember { mutableStateOf(Page.DASHBOARD) }
    var openStock by remember { mutableStateOf<String?>(null) }
    val open: (String) -> Unit = { openStock = it }

    PisoDesktopTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    Text("Piso\nTiyempo", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp))
                    Page.entries.forEach { p ->
                        NavigationRailItem(
                            selected = page == p && openStock == null,
                            onClick = { page = p; openStock = null },
                            icon = { Icon(p.icon, null) },
                            label = { Text(p.label) },
                        )
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    StatusBar(c)
                    HorizontalDivider()
                    Box(Modifier.fillMaxSize().padding(16.dp)) {
                        val symbol = openStock
                        if (symbol != null) {
                            StockScreen(c, symbol, onBack = { openStock = null })
                        } else {
                            when (page) {
                                Page.DASHBOARD -> DashboardScreen(c, open)
                                Page.MARKET -> MarketScreen(c, open)
                                Page.SCREENER -> ScreenerScreen(c, open)
                                Page.PORTFOLIO -> PortfolioScreen(c, open)
                                Page.JOURNAL -> JournalScreen(c)
                                Page.WATCHLIST -> WatchlistScreen(c, open)
                                Page.SETTINGS -> SettingsScreen(c)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBar(c: DesktopContainer) {
    val progress by c.progress.collectAsState()
    val message by c.lastMessage.collectAsState()
    val sync by c.sync.status.collectAsState()
    val scope = rememberCoroutineScope()
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Muted(
                progress ?: message ?: (c.market.lastSyncDate()?.let { "Prices as of $it" } ?: "Not synced yet"),
                Modifier.weight(1f),
            )
            Muted(if (sync.linked) (sync.message ?: if (sync.state.lastSyncAt > 0) "Phone synced" else "Phone linked") else "Phone not linked")
            TextButton(onClick = { scope.launch { c.syncDevices() } }, enabled = sync.linked && !sync.running) { Text("Sync phone") }
            TextButton(onClick = { c.syncMarketAsync() }, enabled = progress == null) { Text("Update prices") }
        }
        if (progress != null) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
