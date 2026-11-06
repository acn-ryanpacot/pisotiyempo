package com.pisotiyempo.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisotiyempo.R
import com.pisotiyempo.domain.ph.FeeBreakdown
import com.pisotiyempo.domain.ph.FeeCalculator
import com.pisotiyempo.domain.ph.FeeSchedule
import com.pisotiyempo.domain.ph.PseRules
import com.pisotiyempo.domain.ph.RiskTools
import com.pisotiyempo.ui.common.DisclaimerFooter
import com.pisotiyempo.ui.common.Fmt
import com.pisotiyempo.ui.common.LabeledValue
import com.pisotiyempo.ui.common.MutedText
import com.pisotiyempo.ui.common.SectionCard
import com.pisotiyempo.ui.common.appContainer

@Composable
fun ToolsScreen(contentPadding: PaddingValues) {
    val settings by appContainer().settings.settings.collectAsStateWithLifecycle(null)
    val fees = settings?.fees ?: FeeSchedule()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { FeeCalculatorCard(fees) }
        item { PositionSizerCard(fees, settings?.capital ?: 100_000.0, settings?.riskPct ?: 1.0) }
        item { BoardLotCard() }
        item { DisclaimerFooter() }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeeCalculatorCard(fees: FeeSchedule) {
    var sell by remember { mutableStateOf(false) }
    var shares by remember { mutableStateOf("1000") }
    var price by remember { mutableStateOf("25") }
    SectionCard(stringResource(R.string.tools_fee_calc_title)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false, true).forEachIndexed { i, s ->
                SegmentedButton(selected = sell == s, onClick = { sell = s }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(if (s) stringResource(R.string.tools_sell) else stringResource(R.string.tools_buy)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(shares, { shares = it.filter(Char::isDigit) }, stringResource(R.string.tools_shares), Modifier.weight(1f))
            NumberField(price, { price = it }, stringResource(R.string.common_price_peso), Modifier.weight(1f))
        }
        val q = shares.toLongOrNull()
        val p = price.toDoubleOrNull()
        if (q == null || p == null || q <= 0 || p <= 0) {
            MutedText(stringResource(R.string.tools_enter_shares_price))
            return@SectionCard
        }
        val f = if (sell) FeeCalculator.sell(q, p, fees) else FeeCalculator.buy(q, p, fees)
        Breakdown(f, sell)
        if (!PseRules.isValidQuantity(q, p)) MutedText(stringResource(R.string.tools_odd_lot, Fmt.num(p), PseRules.boardLot(p)))
        if (!sell) {
            val be = FeeCalculator.breakEvenPrice(q, p, fees)
            HorizontalDivider()
            Text(stringResource(R.string.tools_break_even_price, Fmt.peso(be), Fmt.pct((be / p - 1) * 100)), fontWeight = FontWeight.SemiBold)
            MutedText(stringResource(R.string.tools_break_even_note))
        }
    }
}

@Composable
private fun Breakdown(f: FeeBreakdown, sell: Boolean) {
    val rows = listOfNotNull(
        stringResource(R.string.tools_gross_amount) to f.gross,
        stringResource(R.string.tools_broker_commission) to f.commission,
        stringResource(R.string.tools_vat_on_commission) to f.vat,
        stringResource(R.string.tools_pse_fee) to f.pseFee,
        stringResource(R.string.tools_sccp_fee) to f.sccpFee,
        if (sell) stringResource(R.string.tools_sales_tax) to f.salesTax else null,
    )
    rows.forEach { (label, v) ->
        Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text(Fmt.peso(v), style = MaterialTheme.typography.bodyMedium) }
    }
    HorizontalDivider()
    Row {
        Text(if (sell) stringResource(R.string.tools_you_receive) else stringResource(R.string.tools_total_cost), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Text(Fmt.peso(if (sell) f.gross - f.totalFees else f.gross + f.totalFees), fontWeight = FontWeight.SemiBold)
    }
    MutedText(stringResource(R.string.tools_total_fees, Fmt.peso(f.totalFees), Fmt.pct(f.totalFees / f.gross * 100, signed = false)))
}

@Composable
private fun PositionSizerCard(fees: FeeSchedule, defaultCapital: Double, defaultRisk: Double) {
    var capital by remember(defaultCapital) { mutableStateOf(defaultCapital.toLong().toString()) }
    var risk by remember(defaultRisk) { mutableStateOf(defaultRisk.toString()) }
    var entry by remember { mutableStateOf("") }
    var stop by remember { mutableStateOf("") }
    SectionCard(stringResource(R.string.tools_position_size_title)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(capital, { capital = it }, stringResource(R.string.tools_capital), Modifier.weight(1f))
            NumberField(risk, { risk = it }, stringResource(R.string.tools_risk_per_trade), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(entry, { entry = it }, stringResource(R.string.tools_entry), Modifier.weight(1f))
            NumberField(stop, { stop = it }, stringResource(R.string.tools_stop_loss), Modifier.weight(1f))
        }
        val c = capital.toDoubleOrNull()
        val r = risk.toDoubleOrNull()
        val e = entry.toDoubleOrNull()
        val s = stop.toDoubleOrNull()
        if (c == null || r == null || e == null || s == null) {
            MutedText(stringResource(R.string.tools_enter_position_inputs))
            return@SectionCard
        }
        val size = RiskTools.positionSize(c, r, e, s, fees)
        if (size == null) {
            MutedText(stringResource(R.string.tools_stop_below_entry))
            return@SectionCard
        }
        if (size.shares == 0L) {
            MutedText(stringResource(R.string.tools_one_lot_too_big, size.boardLot, Fmt.peso(RiskTools.minimumInvestment(e, fees))))
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledValue(stringResource(R.string.tools_shares), Fmt.shares(size.shares))
            LabeledValue(stringResource(R.string.tools_board_lots), (size.shares / size.boardLot).toString())
            LabeledValue(stringResource(R.string.tools_cost_with_fees), Fmt.peso(size.cost))
        }
        MutedText(stringResource(R.string.tools_loss_if_stopped, Fmt.peso(size.riskAmount)) + if (size.limitedByCapital) " " + stringResource(R.string.tools_capped_by_capital) else "")
    }
}

@Composable
private fun BoardLotCard() {
    var price by remember { mutableStateOf("") }
    SectionCard(stringResource(R.string.tools_board_lot_title)) {
        NumberField(price, { price = it }, stringResource(R.string.common_price_peso), Modifier.fillMaxWidth())
        val p = price.toDoubleOrNull()
        if (p == null || p <= 0) {
            MutedText(stringResource(R.string.tools_board_lot_hint))
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledValue(stringResource(R.string.tools_board_lot), stringResource(R.string.tools_n_shares, Fmt.shares(PseRules.boardLot(p))))
            LabeledValue(stringResource(R.string.tools_tick_size), "₱${PseRules.tickSize(p)}")
            LabeledValue(stringResource(R.string.tools_min_buy), Fmt.peso(RiskTools.minimumInvestment(p)))
        }
    }
}
