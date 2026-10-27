package com.pisotiyempo.ui.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisotiyempo.data.local.TradeEntity
import com.pisotiyempo.data.repo.MANILA
import com.pisotiyempo.domain.ph.FeeCalculator
import com.pisotiyempo.domain.ph.FeeSchedule
import com.pisotiyempo.domain.ph.PseRules
import com.pisotiyempo.domain.portfolio.Holding
import com.pisotiyempo.domain.portfolio.PortfolioSummary
import com.pisotiyempo.domain.portfolio.Side
import com.pisotiyempo.ui.common.DisclaimerFooter
import com.pisotiyempo.ui.common.Fmt
import com.pisotiyempo.ui.common.LabeledValue
import com.pisotiyempo.ui.common.MutedText
import com.pisotiyempo.ui.common.SectionCard
import com.pisotiyempo.ui.common.appContainer
import com.pisotiyempo.ui.common.changeColor
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeParseException

@Composable
fun PortfolioScreen(onOpenStock: (String) -> Unit, contentPadding: PaddingValues) {
    val c = appContainer()
    val summary by remember { c.portfolio.summary() }.collectAsStateWithLifecycle(null)
    val trades by remember { c.portfolio.trades() }.collectAsStateWithLifecycle(emptyList())
    val dividends by remember { c.market.allDividends() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SummaryCard(summary) }
            summary?.warnings?.takeIf { it.isNotEmpty() }?.let { warnings ->
                item {
                    SectionCard("Diversification check") { warnings.forEach { Text("⚠  $it", style = MaterialTheme.typography.bodyMedium) } }
                }
            }
            summary?.takeIf { it.holdings.isNotEmpty() }?.let { s ->
                item { SectorCard(s) }
                item {
                    SectionCard("Holdings") {
                        s.holdings.forEachIndexed { i, h ->
                            if (i > 0) HorizontalDivider()
                            HoldingRow(h, onClick = { onOpenStock(h.symbol) })
                        }
                    }
                }
            }
            val held = summary?.holdings?.map { it.symbol }?.toSet().orEmpty()
            val today = LocalDate.now(MANILA).toString()
            val upcoming = dividends.filter { it.symbol in held && it.exDate >= today }
            if (upcoming.isNotEmpty()) {
                item {
                    SectionCard("Upcoming dividends") {
                        upcoming.sortedBy { it.exDate }.forEach { d ->
                            Column {
                                Text("${d.symbol} · ${d.amount?.let { Fmt.peso(it) } ?: d.rateText} (${d.type.lowercase()})", style = MaterialTheme.typography.bodyMedium)
                                MutedText("Own it before ${d.exDate} to qualify · pays ${d.paymentDate ?: "—"}")
                            }
                        }
                    }
                }
            }
            item {
                SectionCard("Trade history") {
                    if (trades.isEmpty()) MutedText("No trades yet. Tap “Add trade” to record a buy or sell from your broker.")
                    trades.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${t.side} ${t.symbol}", fontWeight = FontWeight.SemiBold, color = changeColor(if (t.side == "BUY") 1.0 else -1.0))
                                MutedText("${t.date} · ${Fmt.shares(t.shares)} @ ${Fmt.peso(t.price)} · fees ${Fmt.peso(t.fees)}")
                            }
                            IconButton(onClick = { scope.launch { c.portfolio.deleteTrade(t.id) } }) { Icon(Icons.Filled.Delete, "Delete trade") }
                        }
                    }
                }
            }
            item { DisclaimerFooter() }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("Add trade") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        )
    }
    if (adding) AddTradeDialog(onDismiss = { adding = false })
}

@Composable
private fun SummaryCard(s: PortfolioSummary?) {
    SectionCard("Portfolio") {
        Text(Fmt.peso(s?.marketValue ?: 0.0), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (s == null) return@SectionCard
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledValue("Cost basis", Fmt.peso(s.costBasis))
            LabeledValue("Unrealized", "${Fmt.signedPeso(s.unrealizedPnl)} (${Fmt.pct(s.unrealizedPct)})", valueColor = changeColor(s.unrealizedPnl))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledValue("Today", Fmt.signedPeso(s.dayChange), valueColor = changeColor(s.dayChange))
            LabeledValue("Realized", Fmt.signedPeso(s.realizedPnl), valueColor = changeColor(s.realizedPnl))
            LabeledValue("Dividends (net of 10% tax)", Fmt.peso(s.dividendsReceived))
        }
        MutedText("Average-cost method; buy fees are included in cost, sell fees and taxes in realized profit.")
    }
}

@Composable
private fun SectorCard(s: PortfolioSummary) {
    SectionCard("Sector mix") {
        s.sectorWeights.entries.sortedByDescending { it.value }.forEach { (sector, w) ->
            Row {
                Text(sector, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(Fmt.pct(w * 100, signed = false), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun HoldingRow(h: Holding, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) { Text(h.symbol, fontWeight = FontWeight.Bold) }
            MutedText("${Fmt.shares(h.shares)} sh · avg ${Fmt.peso(h.avgCost)} · ${h.sector}")
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.peso(h.marketValue), style = MaterialTheme.typography.bodyMedium)
            Text("${Fmt.signedPeso(h.unrealizedPnl)} (${Fmt.pct(h.unrealizedPct)})", color = changeColor(h.unrealizedPnl), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Records a trade. Fees are pre-filled from the user's fee settings and can be overwritten with the broker's figure. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTradeDialog(initialSymbol: String = "", initialPrice: Double? = null, onDismiss: () -> Unit) {
    val c = appContainer()
    val settings by c.settings.settings.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var symbol by remember { mutableStateOf(initialSymbol) }
    var side by remember { mutableStateOf(Side.BUY) }
    var shares by remember { mutableStateOf("") }
    var price by remember { mutableStateOf(initialPrice?.let { "%.4f".format(it).trimEnd('0').trimEnd('.') } ?: "") }
    var date by remember { mutableStateOf(LocalDate.now(MANILA).toString()) }
    var feesText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val qty = shares.toLongOrNull()
    val px = price.toDoubleOrNull()
    val schedule = settings?.fees ?: FeeSchedule()
    val autoFees = if (qty != null && px != null && qty > 0 && px > 0) {
        (if (side == Side.BUY) FeeCalculator.buy(qty, px, schedule) else FeeCalculator.sell(qty, px, schedule)).totalFees
    } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record trade") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Side.entries.forEachIndexed { i, s ->
                        SegmentedButton(selected = side == s, onClick = { side = s }, shape = SegmentedButtonDefaults.itemShape(i, Side.entries.size)) { Text(s.name) }
                    }
                }
                OutlinedTextField(symbol, { symbol = it.uppercase().trim() }, label = { Text("Symbol") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                OutlinedTextField(shares, { shares = it.filter(Char::isDigit) }, label = { Text("Shares") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(price, { price = it }, label = { Text("Price (₱)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(date, { date = it }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
                OutlinedTextField(feesText, { feesText = it }, label = { Text("Fees and taxes (₱)") }, singleLine = true,
                    placeholder = { Text(autoFees?.let { Fmt.num(it) } ?: "auto") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (qty != null && px != null && px > 0 && !PseRules.isValidQuantity(qty, px)) {
                    MutedText("Board lot at this price is ${PseRules.boardLot(px)} shares; ${Fmt.shares(qty)} is an odd lot.")
                }
                autoFees?.let { MutedText("Estimated fees ${Fmt.peso(it)} with your fee settings. Leave blank to use this.") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsedDate = try { LocalDate.parse(date) } catch (e: DateTimeParseException) { null }
                val fees = feesText.toDoubleOrNull() ?: autoFees
                val problem = when {
                    !Regex("^[A-Z0-9]{1,8}$").matches(symbol) -> "Enter a valid PSE symbol."
                    qty == null || qty <= 0 -> "Enter the number of shares."
                    px == null || px <= 0 -> "Enter the price."
                    parsedDate == null || parsedDate.isAfter(LocalDate.now(MANILA)) -> "Enter a valid date, not in the future."
                    fees == null || fees < 0 -> "Enter the fees."
                    else -> null
                }
                if (problem != null) {
                    error = problem
                } else {
                    val trade = TradeEntity(symbol = symbol, side = side.name, shares = qty!!, price = px!!, fees = fees!!, date = parsedDate!!.toString())
                    scope.launch {
                        c.portfolio.addTrade(trade)
                        c.market.ensureHistory(symbol)
                        onDismiss()
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
