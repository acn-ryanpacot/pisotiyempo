package com.pisotiyempo.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisotiyempo.BuildConfig
import com.pisotiyempo.R
import com.pisotiyempo.data.billing.BillingRepository
import com.pisotiyempo.data.remote.ApiException
import com.pisotiyempo.data.remote.StatusDto
import com.pisotiyempo.data.settings.Settings
import com.pisotiyempo.ui.common.MutedText
import com.pisotiyempo.ui.common.SectionCard
import com.pisotiyempo.ui.common.appContainer
import com.pisotiyempo.ui.common.changeColor
import com.pisotiyempo.ui.UiLanguage
import com.pisotiyempo.ui.lock.AppLock
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
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back)) } },
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
            item { BackupCard() }
            item { PracticeCashCard(s) }
            item { LanguageCard(s) }
            item { AiCard(s) }
            item { AppLockCard(s) }
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
    SectionCard(stringResource(R.string.settings_backend_status), trailing = { TextButton(onClick = { attempt++ }, enabled = status != null) { Text(stringResource(R.string.settings_check_again)) } }) {
        val r = status
        if (r == null) {
            MutedText(stringResource(R.string.settings_checking))
            return@SectionCard
        }
        when {
            r.isFailure -> {
                StatusLine(stringResource(R.string.settings_backend), stringResource(R.string.settings_not_reachable), ok = false)
                MutedText(
                    when (val e = r.exceptionOrNull()) {
                        is ApiException -> if (e.status == 401) stringResource(R.string.settings_app_key_mismatch) else stringResource(R.string.settings_http_error, e.status, e.message.toString())
                        else -> stringResource(R.string.settings_offline_reason, e?.message ?: stringResource(R.string.settings_no_connection))
                    },
                )
                MutedText(stringResource(R.string.settings_server, BuildConfig.API_BASE_URL))
            }
            else -> {
                val st = r.getOrThrow()
                StatusLine(stringResource(R.string.settings_backend), stringResource(R.string.settings_connected), ok = true)
                StatusLine(
                    stringResource(R.string.settings_market_data),
                    if (st.eodhdConfigured) stringResource(R.string.settings_eodhd_licensed) else stringResource(R.string.settings_free_sources_only),
                    ok = st.eodhdConfigured,
                )
                StatusLine(
                    stringResource(R.string.settings_ai_analysis),
                    if (st.aiEnabled) stringResource(R.string.settings_claude_connected) else stringResource(R.string.settings_ai_off),
                    ok = st.aiEnabled,
                )
                val closed = st.marketClosedToday
                StatusLine(
                    stringResource(R.string.settings_pse_today),
                    if (closed != null) stringResource(R.string.settings_market_closed, closed) else stringResource(R.string.settings_trading_day),
                    ok = closed == null,
                )
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
    SectionCard(stringResource(R.string.settings_premium_title)) {
        when {
            premium.active -> {
                StatusLine(stringResource(R.string.settings_status), stringResource(R.string.settings_active), ok = true)
                MutedText(stringResource(R.string.settings_premium_thanks))
                TextButton(onClick = {
                    val url = "https://play.google.com/store/account/subscriptions?sku=${BillingRepository.PRODUCT_ID}&package=${context.packageName}"
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }) { Text(stringResource(R.string.settings_manage_subscription)) }
            }
            premium.available -> {
                MutedText(stringResource(R.string.settings_premium_pitch))
                Button(onClick = { (context as? Activity)?.let { c.billing.purchase(it) } }) {
                    val price = premium.price
                    Text(if (price != null) stringResource(R.string.settings_subscribe_price, price) else stringResource(R.string.settings_subscribe))
                }
            }
            else -> MutedText(premium.message ?: stringResource(R.string.settings_checking_play))
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
    val keyRemovedMsg = stringResource(R.string.settings_key_removed)
    val checkingKeyMsg = stringResource(R.string.settings_checking_key)
    val keySavedMsg = stringResource(R.string.settings_key_saved)
    val keyInvalidMsg = stringResource(R.string.settings_key_invalid)
    LaunchedEffect(Unit) { hasKey = c.market.hasUserEodhdKey() }
    SectionCard(stringResource(R.string.settings_eodhd_title)) {
        MutedText(stringResource(R.string.settings_eodhd_body))
        if (hasKey == true) {
            StatusLine(stringResource(R.string.settings_your_key), stringResource(R.string.settings_saved), ok = true)
            OutlinedButton(enabled = !busy, onClick = {
                scope.launch { c.market.setUserEodhdKey(null); hasKey = false; message = keyRemovedMsg }
            }) { Text(stringResource(R.string.settings_remove_key)) }
        } else if (hasKey == false) {
            OutlinedTextField(
                input, { input = it.trim() }, label = { Text(stringResource(R.string.settings_eodhd_key_label)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(enabled = !busy && input.isNotBlank(), onClick = {
                scope.launch {
                    busy = true
                    message = checkingKeyMsg
                    val ok = c.market.setUserEodhdKey(input)
                    busy = false
                    message = if (ok) keySavedMsg else keyInvalidMsg
                    if (ok) { hasKey = true; input = "" }
                }
            }) { Text(stringResource(R.string.settings_check_and_save)) }
        }
        message?.let { MutedText(it) }
    }
}

@Composable
private fun PracticeCashCard(s: Settings) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var amount by remember(s.paperStartingCash) { mutableStateOf(s.paperStartingCash.toLong().toString()) }
    SectionCard(stringResource(R.string.settings_practice_account)) {
        OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.settings_starting_cash)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        MutedText(stringResource(R.string.settings_practice_cash_note))
        val v = amount.toDoubleOrNull()
        OutlinedButton(enabled = v != null && v >= 1_000, onClick = { scope.launch { c.settings.setPaperStartingCash(v!!) } }) { Text(stringResource(R.string.settings_save)) }
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
    SectionCard(stringResource(R.string.settings_market_data)) {
        MutedText(
            if (s.lastSyncAt == 0L) stringResource(R.string.settings_never_synced)
            else stringResource(
                R.string.settings_last_sync,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.lastSyncAt)),
                s.lastSyncDate ?: "—",
                s.lastSyncSource ?: "—",
                s.lastSyncMessage.orEmpty(),
            ),
        )
        MutedText(stringResource(R.string.settings_data_sources))
        Button(onClick = { SyncService.syncNow(context) }) { Text(stringResource(R.string.settings_sync_now)) }
    }
}

@Composable
private fun AiCard(s: Settings) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    SectionCard(stringResource(R.string.settings_ai_analysis)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_use_claude), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = s.aiEnabled, onCheckedChange = { on -> scope.launch { c.settings.setAiEnabled(on) } })
        }
        MutedText(stringResource(R.string.settings_ai_body))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageCard(s: Settings) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiLanguage = remember { UiLanguage.get(context) }
    SectionCard(stringResource(R.string.settings_language_title)) {
        Text(stringResource(R.string.settings_app_screens), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(UiLanguage.ENGLISH to stringResource(R.string.settings_lang_english), UiLanguage.FILIPINO to stringResource(R.string.settings_lang_filipino)).forEach { (code, label) ->
                FilterChip(
                    selected = uiLanguage == code,
                    onClick = {
                        if (code != uiLanguage) {
                            UiLanguage.set(context, code)
                            // Redraw every screen in the new language.
                            (context as? Activity)?.recreate()
                        }
                    },
                    label = { Text(label) },
                )
            }
        }
        Text(stringResource(R.string.settings_analysis_ai_text), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "en" to stringResource(R.string.settings_lang_english),
                "fil" to stringResource(R.string.settings_lang_filipino),
                "taglish" to stringResource(R.string.settings_lang_taglish),
            ).forEach { (code, label) ->
                FilterChip(selected = s.aiLanguage == code, onClick = { scope.launch { c.settings.setAiLanguage(code) } }, label = { Text(label) })
            }
        }
        MutedText(stringResource(R.string.settings_language_note))
    }
}

@Composable
private fun AppLockCard(s: Settings) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unavailable = remember { AppLock.unavailableReason(context) }
    SectionCard(stringResource(R.string.settings_app_lock)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_require_lock), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = s.appLock, enabled = unavailable == null || s.appLock, onCheckedChange = { on -> scope.launch { c.settings.setAppLock(on) } })
        }
        MutedText(unavailable ?: stringResource(R.string.settings_app_lock_body))
    }
}

@Composable
private fun CrashReportsCard(s: Settings) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SectionCard(stringResource(R.string.settings_crash_reports)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_send_crash_reports), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = s.crashReports, onCheckedChange = { on ->
                scope.launch {
                    c.settings.setCrashReports(on)
                    CrashReporting.setEnabled(context, on)
                }
            })
        }
        MutedText(stringResource(R.string.settings_crash_body))
    }
}

@Composable
private fun RiskCard(s: Settings) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var capital by remember(s.capital) { mutableStateOf(s.capital.toLong().toString()) }
    var risk by remember(s.riskPct) { mutableStateOf(s.riskPct.toString()) }
    SectionCard(stringResource(R.string.settings_position_sizing)) {
        OutlinedTextField(capital, { capital = it }, label = { Text(stringResource(R.string.settings_trading_capital)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(risk, { risk = it }, label = { Text(stringResource(R.string.settings_risk_per_trade)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        MutedText(stringResource(R.string.settings_risk_note))
        val cap = capital.toDoubleOrNull()
        val r = risk.toDoubleOrNull()
        OutlinedButton(
            enabled = cap != null && cap > 0 && r != null && r > 0 && r <= 10,
            onClick = { scope.launch { c.settings.setRisk(cap!!, r!!) } },
        ) { Text(stringResource(R.string.settings_save)) }
    }
}

@Composable
private fun FeesCard(s: Settings) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var commission by remember(s.fees) { mutableStateOf((s.fees.commissionRate * 100).toString()) }
    var minCommission by remember(s.fees) { mutableStateOf(s.fees.minCommission.toString()) }
    var salesTax by remember(s.fees) { mutableStateOf((s.fees.salesTaxRate * 100).toString()) }
    SectionCard(stringResource(R.string.settings_broker_fees)) {
        OutlinedTextField(commission, { commission = it }, label = { Text(stringResource(R.string.settings_commission)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(minCommission, { minCommission = it }, label = { Text(stringResource(R.string.settings_min_commission)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(salesTax, { salesTax = it }, label = { Text(stringResource(R.string.settings_sales_tax)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        MutedText(stringResource(R.string.settings_fees_defaults))
        val cm = commission.toDoubleOrNull()
        val mn = minCommission.toDoubleOrNull()
        val st = salesTax.toDoubleOrNull()
        OutlinedButton(
            enabled = cm != null && cm in 0.0..2.0 && mn != null && mn >= 0 && st != null && st in 0.0..2.0,
            onClick = { scope.launch { c.settings.setFees(cm!! / 100, mn!!, st!! / 100) } },
        ) { Text(stringResource(R.string.settings_save)) }
    }
}

@Composable
private fun AboutCard() {
    SectionCard(stringResource(R.string.settings_about)) {
        Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge)
        MutedText(stringResource(R.string.settings_tagline))
        MutedText(stringResource(R.string.settings_about_disclaimer))
        MutedText(stringResource(R.string.settings_credits))
    }
}
