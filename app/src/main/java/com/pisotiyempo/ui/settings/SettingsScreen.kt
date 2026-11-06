package com.pisotiyempo.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisotiyempo.BuildConfig
import com.pisotiyempo.data.billing.BillingRepository
import com.pisotiyempo.data.remote.ApiException
import com.pisotiyempo.data.remote.StatusDto
import com.pisotiyempo.data.settings.Settings
import com.pisotiyempo.ui.common.MutedText
import com.pisotiyempo.ui.common.SectionCard
import com.pisotiyempo.ui.common.appContainer
import com.pisotiyempo.ui.common.changeColor
import com.pisotiyempo.work.CrashReporting
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
            item { BackendStatusCard() }
            item { PremiumCard() }
            item { DataCard(s, context) }
            item { EodhdKeyCard() }
            item { AiCard(s) }
            item { CrashReportsCard(s) }
            item { RiskCard(s) }
            item { FeesCard(s) }
            item { AboutCard() }
        }
    }
}

/** null = still checking; Result carries either the backend's status or why it couldn't be reached. */
@Composable
private fun BackendStatusCard() {
    val c = appContainer()
    var status by remember { mutableStateOf<Result<StatusDto>?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        status = null
        status = runCatching { c.market.backendStatus() }
    }
    SectionCard("Backend status", trailing = { TextButton(onClick = { attempt++ }, enabled = status != null) { Text("Check again") } }) {
        val r = status
        if (r == null) {
            MutedText("Checking…")
            return@SectionCard
        }
        when {
            r.isFailure -> {
                StatusLine("Backend", "Not reachable", ok = false)
                MutedText(
                    when (val e = r.exceptionOrNull()) {
                        is ApiException -> if (e.status == 401) "The app key doesn't match the backend's APP_KEY secret." else "HTTP ${e.status}: ${e.message}"
                        else -> "${e?.message ?: "No connection"}. The app is using prices saved on this phone and the phisix feed."
                    },
                )
                MutedText("Server: ${BuildConfig.API_BASE_URL}")
            }
            else -> {
                val st = r.getOrThrow()
                StatusLine("Backend", "Connected", ok = true)
                StatusLine("Market data", if (st.eodhdConfigured) "EODHD (licensed)" else "Free sources only", ok = st.eodhdConfigured)
                StatusLine("AI analysis", if (st.aiEnabled) "Claude connected" else "Off: rules engine", ok = st.aiEnabled)
                StatusLine("PSE today", st.marketClosedToday?.let { "Closed ($it)" } ?: "Trading day", ok = st.marketClosedToday == null)
            }
        }
    }
}

@Composable
private fun PremiumCard() {
    val c = appContainer()
    val context = LocalContext.current
    val premium by c.billing.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { c.billing.refresh() }
    SectionCard("Piso Tiyempo Premium") {
        when {
            premium.active -> {
                StatusLine("Status", "Active", ok = true)
                MutedText("Thank you! Claude analysis, disclosure summaries and alerts are unlocked.")
                TextButton(onClick = {
                    val url = "https://play.google.com/store/account/subscriptions?sku=${BillingRepository.PRODUCT_ID}&package=${context.packageName}"
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }) { Text("Manage subscription") }
            }
            premium.available -> {
                MutedText("Unlock Claude-written stock analysis and AI summaries of company disclosures. Cancel anytime in Google Play.")
                Button(onClick = { (context as? Activity)?.let { c.billing.purchase(it) } }) {
                    Text(premium.price?.let { "Subscribe · $it" } ?: "Subscribe")
                }
            }
            else -> MutedText(premium.message ?: "Checking Google Play…")
        }
        if (premium.active || premium.available) premium.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun EodhdKeyCard() {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var hasKey by remember { mutableStateOf<Boolean?>(null) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { hasKey = c.market.hasUserEodhdKey() }
    SectionCard("Your own EODHD key (optional)") {
        MutedText(
            "Have an EODHD subscription? Add your key and the app fetches prices with it directly from this phone, " +
                "under your own data licence. It's stored encrypted on this device and never sent to our servers.",
        )
        if (hasKey == true) {
            StatusLine("Your key", "Saved", ok = true)
            OutlinedButton(enabled = !busy, onClick = {
                scope.launch { c.market.setUserEodhdKey(null); hasKey = false; message = "Key removed." }
            }) { Text("Remove key") }
        } else if (hasKey == false) {
            OutlinedTextField(
                input, { input = it.trim() }, label = { Text("EODHD API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(enabled = !busy && input.isNotBlank(), onClick = {
                scope.launch {
                    busy = true
                    message = "Checking key…"
                    val ok = c.market.setUserEodhdKey(input)
                    busy = false
                    message = if (ok) "Key saved. It will be used from the next sync." else "That key didn't return PSE data. Check it and your EODHD plan."
                    if (ok) { hasKey = true; input = "" }
                }
            }) { Text("Check and save") }
        }
        message?.let { MutedText(it) }
    }
}

@Composable
private fun StatusLine(label: String, value: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = changeColor(if (ok) 1.0 else 0.0))
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
private fun CrashReportsCard(s: Settings) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SectionCard("Crash reports") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Send crash reports", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = s.crashReports, onCheckedChange = { on ->
                scope.launch {
                    c.settings.setCrashReports(on)
                    CrashReporting.setEnabled(context, on)
                }
            })
        }
        MutedText("Anonymous technical details when the app crashes (device model, Android version, the error). No portfolio or personal data.")
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
