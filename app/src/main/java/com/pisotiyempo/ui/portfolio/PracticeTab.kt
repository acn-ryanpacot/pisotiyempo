package com.pisotiyempo.ui.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisotiyempo.R
import com.pisotiyempo.data.local.StockEntity
import com.pisotiyempo.data.local.TradeEntity
import com.pisotiyempo.data.repo.MANILA
import com.pisotiyempo.domain.ph.FeeSchedule
import com.pisotiyempo.domain.ph.PseRules
import com.pisotiyempo.domain.portfolio.PaperTrading
import com.pisotiyempo.domain.portfolio.Side
import com.pisotiyempo.ui.common.Fmt
import com.pisotiyempo.ui.common.LabeledValue
import com.pisotiyempo.ui.common.MutedText
import com.pisotiyempo.ui.common.SectionCard
import com.pisotiyempo.ui.common.appContainer
import com.pisotiyempo.ui.common.changeColor
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
internal fun PracticeTab(onOpenStock: (String) -> Unit, contentPadding: PaddingValues) {
    val c = appContainer()
    val summary by remember { c.portfolio.summary(TradeEntity.ACCOUNT_PAPER) }.collectAsStateWithLifecycle(null)
    val trades by remember { c.portfolio.trades(TradeEntity.ACCOUNT_PAPER) }.collectAsStateWithLifecycle(emptyList())
    val settings by c.settings.settings.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var ordering by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val start = settings?.paperStartingCash ?: PaperTrading.DEFAULT_STARTING_CASH
    val s = summary
    val cash = s?.let { PaperTrading.cash(start, it) } ?: start
    val total = cash + (s?.marketValue ?: 0.0)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = contentPadding.calculateBottomPadding() + 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(stringResource(R.string.practice_account)) {
                    Text(Fmt.peso(total), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LabeledValue(stringResource(R.string.practice_cash), Fmt.peso(cash))
                        LabeledValue(stringResource(R.string.practice_invested), Fmt.peso(s?.marketValue ?: 0.0))
                        LabeledValue(stringResource(R.string.practice_return), Fmt.pct((total / start - 1) * 100), valueColor = changeColor(total - start))
                    }
                    MutedText(stringResource(R.string.practice_virtual_money_note))
                    TextButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.practice_reset_account)) }
                }
            }
            if (s != null && s.holdings.isNotEmpty()) {
                item {
                    SectionCard(stringResource(R.string.practice_holdings)) {
                        s.holdings.forEachIndexed { i, h ->
                            if (i > 0) HorizontalDivider()
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    TextButton(onClick = { onOpenStock(h.symbol) }, contentPadding = PaddingValues(0.dp)) { Text(h.symbol, fontWeight = FontWeight.Bold) }
                                    MutedText(stringResource(R.string.practice_holding_line, Fmt.shares(h.shares), Fmt.peso(h.avgCost)))
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(Fmt.peso(h.marketValue))
                                    Text("${Fmt.signedPeso(h.unrealizedPnl)} (${Fmt.pct(h.unrealizedPct)})", color = changeColor(h.unrealizedPnl), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { ordering = h.symbol }) { Text(stringResource(R.string.practice_trade_button)) }
                                }
                            }
                        }
                    }
                }
            }
            if (s != null && s.closedTrades.isNotEmpty()) {
                item {
                    val wins = s.closedTrades.count { it.pnl > 0 }
                    SectionCard(stringResource(R.string.practice_results)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            LabeledValue(stringResource(R.string.practice_closed_trades), s.closedTrades.size.toString())
                            LabeledValue(stringResource(R.string.practice_win_rate), Fmt.pct(wins * 100.0 / s.closedTrades.size, signed = false))
                            LabeledValue(stringResource(R.string.practice_realized), Fmt.signedPeso(s.realizedPnl), valueColor = changeColor(s.realizedPnl))
                        }
                    }
                }
            }
            item {
                SectionCard(stringResource(R.string.practice_orders)) {
                    if (trades.isEmpty()) MutedText(stringResource(R.string.practice_no_trades))
                    trades.take(50).forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider()
                        Text("${tradeSideLabel(t.side)} ${t.symbol}", fontWeight = FontWeight.SemiBold, color = changeColor(if (t.side == "BUY") 1.0 else -1.0))
                        MutedText(stringResource(R.string.portfolio_trade_line, t.date, Fmt.shares(t.shares), Fmt.peso(t.price), Fmt.peso(t.fees)))
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { ordering = "" },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text(stringResource(R.string.practice_trade)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        )
    }

    ordering?.let { PracticeOrderDialog(initialSymbol = it, cash = cash, onDismiss = { ordering = null }) }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.practice_reset_title)) },
            text = { Text(stringResource(R.string.practice_reset_body, Fmt.peso(start))) },
            confirmButton = { TextButton(onClick = { scope.launch { c.portfolio.resetPractice() }; confirmReset = false }) { Text(stringResource(R.string.practice_reset)) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.portfolio_cancel)) } },
        )
    }
}

/** Places a practice order at the latest close after checking board lots, cash and holdings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeOrderDialog(initialSymbol: String, cash: Double? = null, onDismiss: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle(null)
    val summary by remember { c.portfolio.summary(TradeEntity.ACCOUNT_PAPER) }.collectAsStateWithLifecycle(null)
    var symbol by remember { mutableStateOf(initialSymbol) }
    var side by remember { mutableStateOf(Side.BUY) }
    var shares by remember { mutableStateOf("") }
    var stock by remember { mutableStateOf<StockEntity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(symbol) { stock = if (symbol.isNotBlank()) c.market.stockNow(symbol) else null }

    val fees = settings?.fees ?: FeeSchedule()
    val start = settings?.paperStartingCash ?: PaperTrading.DEFAULT_STARTING_CASH
    val available = cash ?: summary?.let { PaperTrading.cash(start, it) } ?: start
    val held = summary?.holdings?.firstOrNull { it.symbol == symbol }?.shares ?: 0L
    val price = stock?.close
    val preview = shares.toLongOrNull()?.let { PaperTrading.order(side, it, price, available, held, fees) }
    val errCheckOrder = stringResource(R.string.practice_error_check_order)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.practice_trade)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Side.entries.forEachIndexed { i, s0 ->
                        SegmentedButton(selected = side == s0, onClick = { side = s0 }, shape = SegmentedButtonDefaults.itemShape(i, Side.entries.size)) { Text(tradeSideLabel(s0.name)) }
                    }
                }
                OutlinedTextField(symbol, { symbol = it.uppercase().trim() }, label = { Text(stringResource(R.string.portfolio_symbol)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                OutlinedTextField(shares, { shares = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.portfolio_shares)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                when {
                    symbol.isBlank() -> Unit
                    price == null -> MutedText(stringResource(R.string.practice_no_price, symbol))
                    else -> MutedText(stringResource(R.string.practice_fills_at, Fmt.peso(price), stock?.date.toString(), PseRules.boardLot(price).toString()))
                }
                MutedText(stringResource(R.string.practice_cash_available, Fmt.peso(available)) + if (held > 0) " · " + stringResource(R.string.practice_you_hold, Fmt.shares(held)) else "")
                preview?.onSuccess { MutedText(stringResource(if (side == Side.BUY) R.string.practice_preview_cost else R.string.practice_preview_receive, Fmt.peso(kotlin.math.abs(it.cashChange)), Fmt.peso(it.fees))) }
                    ?.onFailure { MutedText(it.message.orEmpty()) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = preview?.isSuccess == true, onClick = {
                val order = preview?.getOrNull()
                if (order == null) {
                    error = errCheckOrder
                } else {
                    scope.launch {
                        c.portfolio.addTrade(
                            TradeEntity(
                                symbol = symbol, side = order.side.name, shares = order.shares, price = order.price, fees = order.fees,
                                date = LocalDate.now(MANILA).toString(), account = TradeEntity.ACCOUNT_PAPER,
                            ),
                        )
                        c.market.ensureHistory(symbol)
                        onDismiss()
                    }
                }
            }) { Text(stringResource(R.string.practice_place_order)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.portfolio_cancel)) } },
    )
}
