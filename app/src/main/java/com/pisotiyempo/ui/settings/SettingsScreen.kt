package com.pisotiyempo.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisotiyempo.BuildConfig
import com.pisotiyempo.data.settings.Settings
import com.pisotiyempo.ui.common.MutedText
import com.pisotiyempo.ui.common.SectionCard
import com.pisotiyempo.ui.common.appContainer
import com.pisotiyempo.work.SyncService
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = appContainer()
    val settings by c.settings.settings.collectAsStateWithLifecycle(null)
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val s = settings ?: return@Scaffold
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DataCard(s, context) }
            item { AiCard(s) }
            item { RiskCard(s) }
            item { FeesCard(s) }
            item { AboutCard() }
        }
    }
}

@Composable
private fun DataCard(s: Settings, context: Context) {
    SectionCard("Market data") {
        MutedText(
            if (s.lastSyncAt == 0L) "Never synced."
            else "Last sync ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.lastSyncAt))}\n" +
                "Prices as of ${s.lastSyncDate ?: "—"} from ${s.lastSyncSource ?: "—"}\n${s.lastSyncMessage.orEmpty()}",
        )
        MutedText("Sources, in order: EODHD (licensed), then PSE Edge and the phisix community feed, then data saved on this phone. The app syncs automatically every weekday evening.")
        Button(onClick = { SyncService.syncNow(context) }) { Text("Sync now") }
    }
}

@Composable
private fun AiCard(s: Settings) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    SectionCard("AI analysis") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use Claude for analysis", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = s.aiEnabled, onCheckedChange = { on -> scope.launch { c.settings.setAiEnabled(on) } })
        }
        MutedText(
            "On: stock analyses and disclosure summaries are written by Claude Haiku, and the daily market brief by Claude Opus. " +
                "Only the computed technical indicators are sent; your portfolio and trades never leave this phone. " +
                "Off, or when AI is unavailable, the built-in rules engine writes the analysis instead.",
        )
    }
}

@Composable
private fun RiskCard(s: Settings) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var capital by remember(s.capital) { mutableStateOf(s.capital.toLong().toString()) }
    var risk by remember(s.riskPct) { mutableStateOf(s.riskPct.toString()) }
    SectionCard("Position sizing") {
        OutlinedTextField(capital, { capital = it }, label = { Text("Trading capital (₱)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(risk, { risk = it }, label = { Text("Risk per trade (% of capital)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        MutedText("Most traders risk 0.5% to 2% per trade. Used for the trade plan on each stock.")
        val cap = capital.toDoubleOrNull()
        val r = risk.toDoubleOrNull()
        OutlinedButton(
            enabled = cap != null && cap > 0 && r != null && r > 0 && r <= 10,
            onClick = { scope.launch { c.settings.setRisk(cap!!, r!!) } },
        ) { Text("Save") }
    }
}

@Composable
private fun FeesCard(s: Settings) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var commission by remember(s.fees) { mutableStateOf((s.fees.commissionRate * 100).toString()) }
    var minCommission by remember(s.fees) { mutableStateOf(s.fees.minCommission.toString()) }
    var salesTax by remember(s.fees) { mutableStateOf((s.fees.salesTaxRate * 100).toString()) }
    SectionCard("Broker fees") {
        OutlinedTextField(commission, { commission = it }, label = { Text("Commission (%)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(minCommission, { minCommission = it }, label = { Text("Minimum commission (₱)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(salesTax, { salesTax = it }, label = { Text("Stock transaction tax on sells (%)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        MutedText("Defaults: 0.25% commission (₱20 minimum) + 12% VAT, 0.005% PSE fee, 0.01% SCCP fee, 0.1% transaction tax on sells. Check your broker's schedule.")
        val cm = commission.toDoubleOrNull()
        val mn = minCommission.toDoubleOrNull()
        val st = salesTax.toDoubleOrNull()
        OutlinedButton(
            enabled = cm != null && cm in 0.0..2.0 && mn != null && mn >= 0 && st != null && st in 0.0..2.0,
            onClick = { scope.launch { c.settings.setFees(cm!! / 100, mn!!, st!! / 100) } },
        ) { Text("Save") }
    }
}

@Composable
private fun AboutCard() {
    SectionCard("About") {
        Text("Piso Tiyempo ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
        MutedText("Buy right. Sell right. Tamang tiyempo.")
        MutedText(
            "Piso Tiyempo is an educational tool, not a broker or a licensed investment adviser, and nothing in it is a " +
                "recommendation to buy or sell. Technical signals are probabilities and can be wrong. Market data may be " +
                "delayed or contain errors. Always do your own research and consider consulting a licensed professional.",
        )
        MutedText("Charts by TradingView Lightweight Charts (Apache 2.0). Disclosures from PSE Edge. AI by Anthropic Claude.")
    }
}
