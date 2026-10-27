package com.pisotiyempo.ui.watchlist

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisotiyempo.data.repo.AlertKind
import com.pisotiyempo.ui.common.Fmt
import com.pisotiyempo.ui.common.MutedText
import com.pisotiyempo.ui.common.StanceBadge
import com.pisotiyempo.ui.common.StockRow
import com.pisotiyempo.ui.common.appContainer
import kotlinx.coroutines.launch

@Composable
fun WatchlistScreen(onOpenStock: (String) -> Unit, contentPadding: PaddingValues) {
    val c = appContainer()
    var tab by remember { mutableIntStateOf(0) }
    val watch by remember { c.portfolio.watchlist() }.collectAsStateWithLifecycle(emptyList())
    val stocks by remember { c.market.stocks() }.collectAsStateWithLifecycle(emptyList())
    val analyses by remember { c.analysis.scored() }.collectAsStateWithLifecycle(emptyList())
    val alerts by remember { c.alerts.alerts() }.collectAsStateWithLifecycle(emptyList())
    val summary by remember { c.portfolio.summary() }.collectAsStateWithLifecycle(null)
    val mine = (watch + summary?.holdings?.map { it.symbol }.orEmpty()).distinct()
    val disclosures by remember(mine) { c.market.disclosuresFor(mine) }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding())) {
        TabRow(selectedTabIndex = tab) {
            listOf("Watchlist", "Alerts", "Disclosures").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            when (tab) {
                0 -> {
                    if (watch.isEmpty()) item { MutedText("Tap the star on any stock to add it here. Search from the top bar.") }
                    val bySymbol = stocks.associateBy { it.symbol }
                    val scoreBy = analyses.associateBy { it.symbol }
                    items(watch, key = { it }) { symbol ->
                        val s = bySymbol[symbol]
                        StockRow(
                            symbol = symbol,
                            name = s?.name ?: symbol,
                            price = s?.close,
                            changePct = s?.changePct,
                            subtitle = scoreBy[symbol]?.topSignal ?: s?.name,
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    scoreBy[symbol]?.let { StanceBadge(it.stance, it.score) }
                                    IconButton(onClick = { scope.launch { c.portfolio.setWatched(symbol, false) } }) { Icon(Icons.Filled.Delete, "Remove") }
                                }
                            },
                            onClick = { onOpenStock(symbol) },
                        )
                        HorizontalDivider()
                    }
                }
                1 -> {
                    if (alerts.isEmpty()) item { MutedText("No alerts yet. Open a stock and tap the bell to add one. Alerts are checked after each daily sync.") }
                    items(alerts, key = { it.id }) { a ->
                        val kind = runCatching { AlertKind.valueOf(a.kind) }.getOrNull()
                        Row(Modifier.fillMaxWidth().clickable { onOpenStock(a.symbol) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.symbol, fontWeight = FontWeight.Bold)
                                Text("${kind?.label ?: a.kind}${a.threshold?.let { " ${Fmt.peso(it)}" } ?: ""}", style = MaterialTheme.typography.bodyMedium)
                                a.lastTriggered?.let { MutedText("Last fired $it") }
                            }
                            IconButton(onClick = { scope.launch { c.alerts.delete(a.id) } }) { Icon(Icons.Filled.Delete, "Delete alert") }
                        }
                        HorizontalDivider()
                    }
                }
                else -> {
                    if (disclosures.isEmpty()) item { MutedText("Disclosures for your holdings and watchlist appear here after a sync.") }
                    items(disclosures, key = { it.id }) { d ->
                        Column(Modifier.fillMaxWidth().clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.url))) }.padding(vertical = 8.dp)) {
                            Text("${d.symbol} · ${d.title}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            MutedText(d.announcedAt.take(16).replace('T', ' '))
                            d.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
fun AddAlertDialog(symbol: String, currentPrice: Double?, onDismiss: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(AlertKind.PRICE_ABOVE) }
    var threshold by remember { mutableStateOf(currentPrice?.let { "%.2f".format(it) } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alert for $symbol") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AlertKind.entries.forEach { k ->
                    Row(Modifier.fillMaxWidth().clickable { kind = k }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = kind == k, onClick = { kind = k })
                        Text(k.label)
                    }
                }
                if (kind.needsThreshold) {
                    OutlinedTextField(threshold, { threshold = it }, label = { Text("Price (₱)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                MutedText("Checked against closing prices after each daily sync.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val t = threshold.toDoubleOrNull()
                if (kind.needsThreshold && (t == null || t <= 0)) {
                    error = "Enter a price."
                } else {
                    scope.launch {
                        c.alerts.add(symbol, kind, if (kind.needsThreshold) t else null)
                        onDismiss()
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
