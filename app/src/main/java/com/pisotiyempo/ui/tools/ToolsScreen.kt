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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    SectionCard("Fee and break-even calculator") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false, true).forEachIndexed { i, s ->
                SegmentedButton(selected = sell == s, onClick = { sell = s }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(if (s) "Sell" else "Buy") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(shares, { shares = it.filter(Char::isDigit) }, "Shares", Modifier.weight(1f))
            NumberField(price, { price = it }, "Price (₱)", Modifier.weight(1f))
        }
        val q = shares.toLongOrNull()
        val p = price.toDoubleOrNull()
        if (q == null || p == null || q <= 0 || p <= 0) {
            MutedText("Enter shares and price.")
            return@SectionCard
        }
        val f = if (sell) FeeCalculator.sell(q, p, fees) else FeeCalculator.buy(q, p, fees)
        Breakdown(f, sell)
        if (!PseRules.isValidQuantity(q, p)) MutedText("Board lot at ₱${Fmt.num(p)} is ${PseRules.boardLot(p)} shares; this is an odd lot.")
        if (!sell) {
            val be = FeeCalculator.breakEvenPrice(q, p, fees)
            HorizontalDivider()
            Text("Break-even sell price: ${Fmt.peso(be)} (${Fmt.pct((be / p - 1) * 100)})", fontWeight = FontWeight.SemiBold)
            MutedText("The lowest valid tick where selling covers the buy, all fees and the sales tax.")
        }
    }
}

@Composable
private fun Breakdown(f: FeeBreakdown, sell: Boolean) {
    val rows = listOfNotNull(
        "Gross amount" to f.gross,
        "Broker commission" to f.commission,
        "VAT on commission (12%)" to f.vat,
        "PSE transaction fee" to f.pseFee,
        "SCCP fee" to f.sccpFee,
        if (sell) "Stock transaction tax" to f.salesTax else null,
    )
    rows.forEach { (label, v) ->
        Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text(Fmt.peso(v), style = MaterialTheme.typography.bodyMedium) }
    }
    HorizontalDivider()
    Row {
        Text(if (sell) "You receive" else "Total cost", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Text(Fmt.peso(if (sell) f.gross - f.totalFees else f.gross + f.totalFees), fontWeight = FontWeight.SemiBold)
    }
    MutedText("Total fees ${Fmt.peso(f.totalFees)} (${Fmt.pct(f.totalFees / f.gross * 100, signed = false)} of gross)")
}

@Composable
private fun PositionSizerCard(fees: FeeSchedule, defaultCapital: Double, defaultRisk: Double) {
    var capital by remember(defaultCapital) { mutableStateOf(defaultCapital.toLong().toString()) }
    var risk by remember(defaultRisk) { mutableStateOf(defaultRisk.toString()) }
    var entry by remember { mutableStateOf("") }
    var stop by remember { mutableStateOf("") }
    SectionCard("Position size calculator") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(capital, { capital = it }, "Capital (₱)", Modifier.weight(1f))
            NumberField(risk, { risk = it }, "Risk per trade (%)", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(entry, { entry = it }, "Entry (₱)", Modifier.weight(1f))
            NumberField(stop, { stop = it }, "Stop loss (₱)", Modifier.weight(1f))
        }
        val c = capital.toDoubleOrNull()
        val r = risk.toDoubleOrNull()
        val e = entry.toDoubleOrNull()
        val s = stop.toDoubleOrNull()
        if (c == null || r == null || e == null || s == null) {
            MutedText("Enter capital, risk, entry and stop.")
            return@SectionCard
        }
        val size = RiskTools.positionSize(c, r, e, s, fees)
        if (size == null) {
            MutedText("The stop must be below the entry price.")
            return@SectionCard
        }
        if (size.shares == 0L) {
            MutedText("One board lot (${size.boardLot} shares) costs ${Fmt.peso(RiskTools.minimumInvestment(e, fees))}: more than this risk budget or capital allows.")
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledValue("Shares", Fmt.shares(size.shares))
            LabeledValue("Board lots", (size.shares / size.boardLot).toString())
            LabeledValue("Cost with fees", Fmt.peso(size.cost))
        }
        MutedText("Loss if stopped out ≈ ${Fmt.peso(size.riskAmount)} plus fees." + if (size.limitedByCapital) " Capped by your capital." else "")
    }
}

@Composable
private fun BoardLotCard() {
    var price by remember { mutableStateOf("") }
    SectionCard("Board lot and tick size") {
        NumberField(price, { price = it }, "Price (₱)", Modifier.fillMaxWidth())
        val p = price.toDoubleOrNull()
        if (p == null || p <= 0) {
            MutedText("Enter a price to see the minimum order size and price step.")
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledValue("Board lot", "${Fmt.shares(PseRules.boardLot(p))} shares")
            LabeledValue("Tick size", "₱${PseRules.tickSize(p)}")
            LabeledValue("Min. buy", Fmt.peso(RiskTools.minimumInvestment(p)))
        }
    }
}
