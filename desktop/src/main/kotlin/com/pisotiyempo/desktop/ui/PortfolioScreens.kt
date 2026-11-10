package com.pisotiyempo.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pisotiyempo.desktop.DesktopContainer
import com.pisotiyempo.desktop.data.MANILA
import com.pisotiyempo.domain.journal.JournalInsights
import com.pisotiyempo.domain.journal.JournalNote
import com.pisotiyempo.domain.journal.JournalTags
import com.pisotiyempo.domain.ph.FeeCalculator
import com.pisotiyempo.domain.ph.FeeSchedule
import com.pisotiyempo.domain.ph.PseRules
import com.pisotiyempo.domain.portfolio.PaperTrading
import com.pisotiyempo.domain.portfolio.Side
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun PortfolioScreen(c: DesktopContainer, open: (String) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    val account = if (tab == 0) "REAL" else "PAPER"
    val summary by rememberData(c, null, account) { c.portfolio.summary(c.market.stocks().associate { it.symbol to (it.close to it.changePct) }, account) }
    val trades by rememberData(c, emptyList(), account) { c.portfolio.trades(account) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TabRow(selectedTabIndex = tab, modifier = Modifier.width(360.dp)) {
                listOf("My portfolio", "Practice").forEachIndexed { i, l -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(l) }) }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                Button(onClick = { adding = true }) { Text(if (tab == 0) "Add trade" else "Practice trade") }
            }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(Modifier.weight(1.3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    val s = summary
                    Section(if (tab == 0) "Portfolio" else "Practice account") {
                        if (tab == 1) {
                            val cash = s?.let { PaperTrading.cash(PaperTrading.DEFAULT_STARTING_CASH, it) } ?: PaperTrading.DEFAULT_STARTING_CASH
                            Text(Fmt.peso(cash + (s?.marketValue ?: 0.0)), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Muted("Cash ${Fmt.peso(cash)} · virtual money, real PSE fees and board lots")
                        } else {
                            Text(Fmt.peso(s?.marketValue ?: 0.0), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        }
                        if (s != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                Value("Cost basis", Fmt.peso(s.costBasis))
                                Value("Unrealized", "${Fmt.signedPeso(s.unrealizedPnl)} (${Fmt.pct(s.unrealizedPct)})", changeColor(s.unrealizedPnl))
                                Value("Realized", Fmt.signedPeso(s.realizedPnl), changeColor(s.realizedPnl))
                                Value("Today", Fmt.signedPeso(s.dayChange), changeColor(s.dayChange))
                            }
                            s.warnings.forEach { Muted("⚠  $it") }
                        }
                    }
                }
                item {
                    Section("Holdings") {
                        val hs = summary?.holdings.orEmpty()
                        if (hs.isEmpty()) Muted("No open positions.")
                        Row {
                            listOf("Symbol" to 0.7f, "Shares" to 0.6f, "Avg cost" to 0.7f, "Last" to 0.6f, "Value" to 0.8f, "P/L" to 1f, "Sector" to 0.8f).forEach { (h, w) ->
                                Text(h, Modifier.weight(w), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        hs.forEach { h ->
                            HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { open(h.symbol) }, modifier = Modifier.weight(0.7f)) { Text(h.symbol, fontWeight = FontWeight.Bold) }
                                Text(Fmt.shares(h.shares), Modifier.weight(0.6f))
                                Text(Fmt.peso(h.avgCost), Modifier.weight(0.7f))
                                Text(Fmt.peso(h.lastPrice), Modifier.weight(0.6f))
                                Text(Fmt.peso(h.marketValue), Modifier.weight(0.8f))
                                Text("${Fmt.signedPeso(h.unrealizedPnl)} (${Fmt.pct(h.unrealizedPct)})", Modifier.weight(1f), color = changeColor(h.unrealizedPnl))
                                Text(h.sector, Modifier.weight(0.8f))
                            }
                        }
                    }
                }
            }
            Section("Trades", Modifier.weight(1f).fillMaxHeight()) {
                if (trades.isEmpty()) Muted("No trades yet.")
                LazyColumn {
                    items(trades, key = { it.uid }) { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${t.side} ${t.symbol}", fontWeight = FontWeight.SemiBold, color = changeColor(if (t.side == "BUY") 1.0 else -1.0))
                                Muted("${t.date} · ${Fmt.shares(t.shares)} @ ${Fmt.peso(t.price)} · fees ${Fmt.peso(t.fees)}")
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { c.portfolio.deleteTrade(t.uid) }
                                    c.touch()
                                }
                            }) { Icon(Icons.Filled.Delete, "Delete trade") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    if (adding) TradeDialog(c, initialSymbol = "", account = account, lastPrice = null, onDismiss = { adding = false })
}

/**
 * Records a real trade (any price, fees estimated but editable) or places a practice order (last close,
 * whole board lots, checked against practice cash). Same rules as the phone.
 */
@Composable
fun TradeDialog(c: DesktopContainer, initialSymbol: String, account: String, lastPrice: Double?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var symbol by remember { mutableStateOf(initialSymbol) }
    var side by remember { mutableStateOf(Side.BUY) }
    var shares by remember { mutableStateOf("") }
    var price by remember { mutableStateOf(lastPrice?.let { java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() } ?: "") }
    var date by remember { mutableStateOf(LocalDate.now(MANILA).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    val practice = account == "PAPER"
    val stock by rememberData(c, null, symbol) { if (symbol.isBlank()) null else c.market.stock(symbol) }
    val practiceSummary by rememberData(c, null) { c.portfolio.summary(c.market.stocks().associate { it.symbol to (it.close to it.changePct) }, "PAPER") }

    val qty = shares.toLongOrNull()
    val px = if (practice) stock?.close else price.toDoubleOrNull()
    val fees = if (qty != null && px != null && qty > 0 && px > 0) {
        (if (side == Side.BUY) FeeCalculator.buy(qty, px, FeeSchedule()) else FeeCalculator.sell(qty, px, FeeSchedule())).totalFees
    } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (practice) "Practice trade" else "Record trade") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Side.entries.forEach { s -> FilterChip(selected = side == s, onClick = { side = s }, label = { Text(s.name) }) }
                }
                OutlinedTextField(symbol, { symbol = it.uppercase().trim() }, label = { Text("Symbol") }, singleLine = true)
                OutlinedTextField(shares, { shares = it.filter(Char::isDigit) }, label = { Text("Shares") }, singleLine = true)
                if (practice) {
                    Muted(stock?.let { "Fills at the last close ${Fmt.peso(it.close)} (${it.date}). Board lot ${PseRules.boardLot(it.close)}." } ?: "No price for this symbol yet.")
                } else {
                    OutlinedTextField(price, { price = it }, label = { Text("Price (₱)") }, singleLine = true)
                    OutlinedTextField(date, { date = it }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
                }
                fees?.let { Muted("Fees and taxes: ${Fmt.peso(it)} (standard 0.25% commission schedule)") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = runCatching { LocalDate.parse(date) }.getOrNull()
                val problem = when {
                    !Regex("^[A-Z0-9]{1,8}$").matches(symbol) -> "Enter a valid PSE symbol."
                    qty == null || qty <= 0 -> "Enter the number of shares."
                    px == null || px <= 0 -> if (practice) "No price for this symbol yet." else "Enter the price."
                    !practice && (d == null || d.isAfter(LocalDate.now(MANILA))) -> "Enter a valid date, not in the future."
                    else -> null
                }
                if (problem != null) {
                    error = problem
                    return@TextButton
                }
                if (practice) {
                    val s = practiceSummary
                    val cash = s?.let { PaperTrading.cash(PaperTrading.DEFAULT_STARTING_CASH, it) } ?: PaperTrading.DEFAULT_STARTING_CASH
                    val held = s?.holdings?.firstOrNull { it.symbol == symbol }?.shares ?: 0L
                    val order = PaperTrading.order(side, qty!!, px, cash, held, FeeSchedule())
                    order.onFailure { error = it.message }.onSuccess { o ->
                        scope.launch {
                            withContext(Dispatchers.IO) { c.portfolio.addTrade(symbol, o.side, o.shares, o.price, o.fees, LocalDate.now(MANILA), "PAPER") }
                            c.touch()
                            onDismiss()
                        }
                    }
                } else {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            c.portfolio.addTrade(symbol, side, qty!!, px!!, fees ?: 0.0, d!!, "REAL")
                            c.market.ensureHistory(symbol)
                        }
                        c.touch()
                        onDismiss()
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JournalScreen(c: DesktopContainer) {
    val entries by rememberData(c, emptyList()) { c.portfolio.journal() }
    val closed by rememberData(c, emptyList()) { c.portfolio.summary(c.market.stocks().associate { it.symbol to (it.close to it.changePct) }).closedTrades }
    val insights = remember(entries, closed) {
        JournalInsights.compute(entries.map { JournalNote(it.symbol, LocalDate.parse(it.date), it.setup, it.emotion, it.mistakes, it.rating) }, closed)
    }
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Section("Your trading habits") {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Value("Closed trades", insights.closedTrades.toString())
                        Value("Win rate", insights.winRate?.let { Fmt.pct(it * 100, signed = false) } ?: "—")
                        Value("Avg win", insights.avgWinPct?.let { Fmt.pct(it) } ?: "—", Gain)
                        Value("Avg loss", insights.avgLossPct?.let { Fmt.pct(it) } ?: "—", Loss)
                    }
                    Bullets(insights.observations)
                    listOf("By setup" to insights.bySetup, "By emotion" to insights.byEmotion).forEach { (title, rows) ->
                        if (rows.isNotEmpty()) {
                            Text(title, style = MaterialTheme.typography.labelLarge)
                            rows.forEach { g ->
                                Row {
                                    Text(g.label, Modifier.weight(1f))
                                    Text("${g.trades}×", Modifier.weight(0.3f))
                                    Text("${(g.winRate * 100).toInt()}% won", Modifier.weight(0.5f), color = changeColor(g.winRate - 0.5))
                                    Text(Fmt.pct(g.avgReturnPct), Modifier.weight(0.4f), color = changeColor(g.avgReturnPct))
                                }
                            }
                        }
                    }
                    Muted("AI coaching is in the phone app (it needs your confirmation before your notes are sent).")
                }
            }
        }
        Section("Entries", Modifier.weight(1f).fillMaxHeight(), trailing = { Button(onClick = { adding = true }) { Text("New entry") } }) {
            if (entries.isEmpty()) Muted("No journal entries yet.")
            LazyColumn {
                items(entries, key = { it.uid }) { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${e.symbol} · ${e.setup}", fontWeight = FontWeight.SemiBold)
                            Muted("${e.date} · felt ${e.emotion.lowercase()}" + (e.rating?.let { " · plan followed $it/5" } ?: ""))
                            if (e.reason.isNotBlank()) Text(e.reason)
                            if (e.mistakes.isNotEmpty()) Muted("Mistakes: ${e.mistakes.joinToString()}")
                            if (e.lesson.isNotBlank()) Muted("Lesson: ${e.lesson}")
                        }
                        IconButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { c.portfolio.deleteJournal(e.uid) }
                                c.touch()
                            }
                        }) { Icon(Icons.Filled.Delete, "Delete entry") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (adding) {
        var symbol by remember { mutableStateOf("") }
        var setup by remember { mutableStateOf(JournalTags.SETUPS.first()) }
        var emotion by remember { mutableStateOf(JournalTags.EMOTIONS.first()) }
        var mistakes by remember { mutableStateOf(emptySet<String>()) }
        var rating by remember { mutableStateOf<Int?>(null) }
        var reason by remember { mutableStateOf("") }
        var lesson by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Journal entry") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(symbol, { symbol = it.uppercase().trim() }, label = { Text("Symbol") }, singleLine = true)
                    Text("Setup", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { JournalTags.SETUPS.forEach { s -> FilterChip(setup == s, { setup = s }, { Text(s) }) } }
                    Text("How did you feel?", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { JournalTags.EMOTIONS.forEach { e -> FilterChip(emotion == e, { emotion = e }, { Text(e) }) } }
                    Text("Mistakes", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        JournalTags.MISTAKES.forEach { m -> FilterChip(m in mistakes, { mistakes = if (m in mistakes) mistakes - m else mistakes + m }, { Text(m) }) }
                    }
                    Text("Plan followed (1-5)", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { (1..5).forEach { r -> FilterChip(rating == r, { rating = if (rating == r) null else r }, { Text("$r") }) } }
                    OutlinedTextField(reason, { reason = it.take(400) }, label = { Text("Why did you take this trade?") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(lesson, { lesson = it.take(400) }, label = { Text("Lesson") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = Regex("^[A-Z0-9]{1,8}$").matches(symbol), onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            c.portfolio.addJournal(symbol, LocalDate.now(MANILA), setup, emotion, mistakes.toList(), rating, reason.trim(), lesson.trim())
                        }
                        c.touch()
                        adding = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun WatchlistScreen(c: DesktopContainer, open: (String) -> Unit) {
    val watch by rememberData(c, emptyList()) { c.portfolio.watchlist() }
    val stocks by rememberData(c, emptyMap()) { c.market.stocks().associateBy { it.symbol } }
    val scores by rememberData(c, emptyMap()) { c.market.screenRows().associateBy { it.symbol } }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Add to watchlist") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it.uppercase().trim() }, label = { Text("Symbol or name") }, singleLine = true)
                val matches = if (query.length < 1) emptyList() else stocks.values.filter { it.symbol.startsWith(query) || it.name.uppercase().contains(query) }.take(6)
                matches.forEach { s ->
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { c.portfolio.setWatched(s.symbol, true) }
                            query = ""
                            c.touch()
                        }
                    }) { Text("+ ${s.symbol}") }
                }
            }
        }
        Section("Watchlist", Modifier.weight(1f)) {
            if (watch.isEmpty()) Muted("Nothing here yet. Add symbols above, or star them on your phone.")
            LazyColumn {
                items(watch, key = { it }) { symbol ->
                    val s = stocks[symbol]
                    StockLine(symbol, scores[symbol]?.patterns?.firstOrNull() ?: s?.name ?: "", s?.close, s?.changePct, trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            scores[symbol]?.let { StanceBadge(it.stance, it.score) }
                            IconButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { c.portfolio.setWatched(symbol, false) }
                                    c.touch()
                                }
                            }) { Icon(Icons.Filled.Delete, "Remove") }
                        }
                    }) { open(symbol) }
                    HorizontalDivider()
                }
            }
        }
    }
}
