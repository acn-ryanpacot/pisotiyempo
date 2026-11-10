package com.pisotiyempo.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.pisotiyempo.desktop.BuildInfo
import com.pisotiyempo.desktop.DesktopContainer
import com.pisotiyempo.desktop.data.DesktopPaths
import com.pisotiyempo.domain.analysis.PatternFilter
import com.pisotiyempo.domain.analysis.ScreenCriteria
import com.pisotiyempo.domain.analysis.ScreenPreset
import com.pisotiyempo.domain.analysis.Screener
import com.pisotiyempo.domain.ph.Sectors
import com.pisotiyempo.sync.LinkCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScreenerScreen(c: DesktopContainer, open: (String) -> Unit) {
    val rows by rememberData(c, emptyList()) { c.market.screenRows() }
    var preset by remember { mutableStateOf<ScreenPreset?>(ScreenPreset.STRONG_UPTREND) }
    var criteria by remember { mutableStateOf(ScreenPreset.STRONG_UPTREND.criteria) }
    val results = remember(rows, criteria) { Screener.run(rows, criteria) }
    fun update(f: (ScreenCriteria) -> ScreenCriteria) {
        criteria = f(criteria)
        preset = null
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Section("Scan", Modifier.weight(0.8f).fillMaxHeight()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ScreenPreset.entries.forEach { p -> FilterChip(preset == p, { preset = p; criteria = p.criteria }, { Text(p.label) }) }
            }
            Muted(preset?.description ?: "Custom scan")
            HorizontalDivider()
            Text("Sectors", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Sectors.ALL.forEach { s -> FilterChip(s in criteria.sectors, { update { it.copy(sectors = if (s in it.sectors) it.sectors - s else it.sectors + s) } }, { Text(s) }) }
            }
            Text("Patterns", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PatternFilter.entries.forEach { f -> FilterChip(criteria.patternFilter == f, { update { it.copy(patternFilter = f) } }, { Text(f.label) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Liquid stocks only", Modifier.weight(1f))
                Switch(criteria.liquidOnly, { on -> update { it.copy(liquidOnly = on) } })
            }
            TextButton(onClick = { update { ScreenCriteria() } }) { Text("Clear filters") }
        }
        Section("${results.size} of ${rows.size} analyzed stocks", Modifier.weight(1.2f).fillMaxHeight()) {
            LazyColumn {
                items(results, key = { it.symbol }) { r ->
                    StockLine(
                        r.symbol, listOfNotNull(r.sector, r.rsi14?.let { "RSI ${it.toInt()}" }, r.return3mPct?.let { "3M ${Fmt.pct(it)}" }, r.patterns.firstOrNull()).joinToString(" · "),
                        r.close, r.changePct, trailing = { StanceBadge(r.stance, r.score) },
                    ) { open(r.symbol) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(c: DesktopContainer) {
    val sync by c.sync.status.collectAsState()
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<LinkCode?>(null) }
    var entering by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Section("Link phone") {
                Muted("Trades, journal, watchlist and alerts sync with the Piso Tiyempo phone app. Everything is encrypted on your devices with a key that never leaves them; the server only stores ciphertext.")
                if (!sync.linked || code != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            Text("1. On your phone: Settings › Sync with your computer › Scan desktop QR code.", style = MaterialTheme.typography.bodyMedium)
                            Text("2. Scan the code shown here. Your phone's data comes over in a few seconds.", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { scope.launch { code = withContext(Dispatchers.IO) { c.sync.startLinking() } } }) { Text(if (code == null) "Show QR code" else "Showing QR code") }
                            OutlinedButton(onClick = { entering = !entering }) { Text("Enter a code from my phone instead") }
                            if (entering) {
                                OutlinedTextField(typed, { typed = it }, label = { Text("Link code (starts with PT1.)") }, modifier = Modifier.fillMaxWidth())
                                Button(onClick = {
                                    val parsed = LinkCode.parse(typed)
                                    if (parsed == null) message = "That isn't a valid link code." else scope.launch {
                                        withContext(Dispatchers.IO) { c.sync.join(parsed) }
                                        code = null
                                        entering = false
                                        typed = ""
                                        c.touch()
                                    }
                                }) { Text("Link") }
                            }
                        }
                        code?.let { QrCode(it.encode(), Modifier.size(240.dp)) }
                    }
                }
                if (sync.linked) {
                    val s = sync.state
                    Muted(if (s.lastSyncAt == 0L) "Linked. Waiting for the first sync." else "Last synced ${DateFormat.getDateTimeInstance().format(Date(s.lastSyncAt))}")
                    sync.message?.let { Muted(it) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !sync.running, onClick = { scope.launch { c.syncDevices(); code = null } }) { Text(if (sync.running) "Syncing…" else "Sync now") }
                        OutlinedButton(onClick = { scope.launch { code = withContext(Dispatchers.IO) { c.sync.startLinking() } } }) { Text("Show QR code again") }
                        TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { c.sync.unlink() }; code = null } }) { Text("Unlink this computer") }
                    }
                }
                message?.let { Muted(it) }
            }
        }
        item { FeedSection(c) }
        item {
            Section("AI and data") {
                var ai by remember { mutableStateOf(c.config.aiEnabled) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use Claude for analysis", Modifier.weight(1f))
                    Switch(ai, { on -> ai = on; c.config.aiEnabled = on })
                }
                Muted("Off, or when AI is unavailable, the built-in rules engine writes the analysis. Premium AI follows your phone's subscription.")
                var url by remember { mutableStateOf(c.config.apiBaseUrl) }
                OutlinedTextField(url, { url = it.trim() }, label = { Text("Backend address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { c.config.apiBaseUrl = url; message = "Saved. Restart the app to use the new address." }) { Text("Save address") }
            }
        }
        item {
            Section("About") {
                Text("Piso Tiyempo for desktop ${BuildInfo.VERSION}")
                Muted("Data folder: ${DesktopPaths.home.absolutePath}")
                Muted(DISCLAIMER)
            }
        }
    }
}

@Composable
private fun FeedSection(c: DesktopContainer) {
    var exported by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val endpoints = listOf("/api/summary", "/api/holdings", "/api/trades?account=REAL", "/api/closed-trades", "/api/journal", "/api/watchlist", "/api/analyses", "/api/prices")
    Section("Personal dashboard feed") {
        Muted("Use this data in your own dashboard. Three ways, pick whichever your dashboard tool supports:")
        Text("1. Local JSON API (only this computer can reach it)", style = MaterialTheme.typography.labelLarge)
        if (c.config.feedEnabled) {
            SelectionContainer {
                Column {
                    endpoints.forEach { Text(c.feed.url + it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Muted("Browser dashboards on another port need their origin listed in feedAllowOrigins in ${DesktopPaths.config.absolutePath}.")
        } else {
            Muted("Turned off (feedEnabled=false in the config file).")
        }
        Text("2. CSV and JSON files, refreshed after every sync", style = MaterialTheme.typography.labelLarge)
        SelectionContainer { Text(c.config.exportDir.absolutePath, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { exported = withContext(Dispatchers.IO) { runCatching { c.feed.export().absolutePath }.getOrElse { "Export failed: ${it.message}" } } } }) { Text("Export now") }
            OutlinedButton(onClick = { runCatching { Desktop.getDesktop().open(c.config.exportDir.also { it.mkdirs() }) } }) { Text("Open folder") }
        }
        exported?.let { Muted("Exported to $it") }
        Text("3. The SQLite database (read it read-only)", style = MaterialTheme.typography.labelLarge)
        SelectionContainer { Text(DesktopPaths.database.absolutePath, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(c.feed.url + "/api/summary"), null) }) { Text("Copy summary URL") }
    }
}

/** Draws [text] as a QR code (ZXing) with a white quiet zone so phones scan it in dark mode too. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
    }
    Box(modifier.background(Color.White).padding(8.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = size.minDimension / matrix.width
            for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
                if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell, cell))
            }
        }
    }
}
