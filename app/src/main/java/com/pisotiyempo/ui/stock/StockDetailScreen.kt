package com.pisotiyempo.ui.stock

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pisotiyempo.AppContainer
import com.pisotiyempo.data.local.DisclosureEntity
import com.pisotiyempo.data.local.DividendEntity
import com.pisotiyempo.data.local.StockEntity
import com.pisotiyempo.domain.analysis.Narrative
import com.pisotiyempo.domain.model.Bar
import com.pisotiyempo.domain.model.Direction
import com.pisotiyempo.domain.model.PatternCategory
import com.pisotiyempo.domain.model.StockAnalysis
import com.pisotiyempo.domain.ph.FeeCalculator
import com.pisotiyempo.domain.ph.PseRules
import com.pisotiyempo.domain.ph.RiskTools
import com.pisotiyempo.ui.chart.ChartLevel
import com.pisotiyempo.ui.chart.Overlay
import com.pisotiyempo.ui.chart.PriceChart
import com.pisotiyempo.ui.common.Bullets
import com.pisotiyempo.ui.common.ChangeText
import com.pisotiyempo.ui.common.DisclaimerFooter
import com.pisotiyempo.ui.common.Fmt
import com.pisotiyempo.ui.common.LabeledValue
import com.pisotiyempo.ui.common.MutedText
import com.pisotiyempo.ui.common.Pill
import com.pisotiyempo.ui.common.SectionCard
import com.pisotiyempo.ui.common.SourceBadge
import com.pisotiyempo.ui.common.StanceBadge
import com.pisotiyempo.ui.common.appContainer
import com.pisotiyempo.ui.common.changeColor
import com.pisotiyempo.ui.portfolio.AddTradeDialog
import com.pisotiyempo.ui.theme.LocalMarketColors
import com.pisotiyempo.ui.watchlist.AddAlertDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

enum class Range(val label: String, val days: Long) { M1("1M", 31), M3("3M", 92), M6("6M", 183), Y1("1Y", 366), Y2("2Y", 731) }

class StockDetailViewModel(private val c: AppContainer, val symbol: String) : ViewModel() {
    val stock = c.market.stock(symbol)
    val bars = c.market.bars(symbol)
    val watched = c.portfolio.isWatched(symbol)
    val dividends = c.market.dividends(symbol)
    val disclosures = c.market.disclosures(symbol)

    val analysis = MutableStateFlow<StockAnalysis?>(null)
    val narrative = MutableStateFlow<Narrative?>(null)
    val loading = MutableStateFlow(true)
    val summarizing = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            c.market.ensureHistory(symbol)
            load()
            c.market.refreshDisclosures(symbol)
            c.market.refreshDividends(symbol)
        }
    }

    private suspend fun load() {
        loading.value = true
        val a = c.analysis.fullAnalysis(symbol)
        analysis.value = a
        loading.value = false
        narrative.value = a?.let { c.ai.stockNarrative(it) }
    }

    fun toggleWatch(current: Boolean) = viewModelScope.launch { c.portfolio.setWatched(symbol, !current) }

    fun summarize(d: DisclosureEntity) = viewModelScope.launch {
        summarizing.value = d.id
        c.ai.summarizeDisclosure(d)
        summarizing.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(symbol: String, onBack: () -> Unit) {
    val container = appContainer()
    val vm: StockDetailViewModel = viewModel(key = "stock-$symbol") { StockDetailViewModel(container, symbol) }
    val stock by vm.stock.collectAsStateWithLifecycle(null)
    val bars by vm.bars.collectAsStateWithLifecycle(emptyList())
    val watched by vm.watched.collectAsStateWithLifecycle(false)
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val narrative by vm.narrative.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val dividends by vm.dividends.collectAsStateWithLifecycle(emptyList())
    val disclosures by vm.disclosures.collectAsStateWithLifecycle(emptyList())
    val summarizing by vm.summarizing.collectAsStateWithLifecycle()
    var showTrade by remember { mutableStateOf(false) }
    var showAlert by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(symbol, fontWeight = FontWeight.Bold)
                        stock?.let { MutedText("${it.name} · ${it.sector}") }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { showAlert = true }) { Icon(Icons.Filled.AddAlert, "Add alert") }
                    IconButton(onClick = { vm.toggleWatch(watched) }) {
                        Icon(if (watched) Icons.Filled.Star else Icons.Filled.StarBorder, if (watched) "Remove from watchlist" else "Add to watchlist")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PriceHeader(stock, analysis) }
            item { ChartCard(bars, analysis) }
            if (loading && analysis == null) {
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
            }
            analysis?.let { a ->
                item { ScoreCard(a) }
                item { NarrativeCard(narrative) }
                item { PatternsCard(a) }
                item { IndicatorsCard(a) }
                item { LevelsCard(a) }
                item { BacktestCard(a) }
                item { TradePlanCard(a, onAddTrade = { showTrade = true }) }
            }
            if (!loading && analysis == null) {
                item { SectionCard { MutedText("Not enough price history to analyze $symbol yet (needs at least 30 trading days).") } }
            }
            item { DividendsCard(dividends) }
            item { DisclosuresCard(disclosures, summarizing, onSummarize = vm::summarize) }
            item { DisclaimerFooter() }
        }
    }

    if (showTrade) AddTradeDialog(initialSymbol = symbol, initialPrice = stock?.close, onDismiss = { showTrade = false })
    if (showAlert) AddAlertDialog(symbol = symbol, currentPrice = stock?.close, onDismiss = { showAlert = false })
}

@Composable
private fun PriceHeader(stock: StockEntity?, analysis: StockAnalysis?) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(Fmt.peso(stock?.close), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        ChangeText(stock?.changePct, bold = true)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            analysis?.let { StanceBadge(it.stance, it.score) }
            stock?.let { MutedText("As of ${it.date}") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartCard(allBars: List<Bar>, analysis: StockAnalysis?) {
    var range by remember { mutableStateOf(Range.M6) }
    var overlays by remember { mutableStateOf(setOf(Overlay.SMA50, Overlay.SMA200)) }
    val market = LocalMarketColors.current
    SectionCard {
        if (allBars.isEmpty()) {
            MutedText("Loading price history…")
            return@SectionCard
        }
        val cutoff = allBars.last().date.minusDays(range.days)
        // Indicators need lead-in history, so the chart gets everything and the window only trims what's shown.
        val start = allBars.indexOfFirst { !it.date.isBefore(cutoff) }.coerceAtLeast(0)
        val shown = allBars.subList(start, allBars.size)
        val patterns = analysis?.patterns
            ?.filter { it.category != PatternCategory.INDICATOR && it.endIndex >= start }
            ?.map { it.copy(endIndex = it.endIndex - start, startIndex = (it.startIndex - start).coerceAtLeast(0)) }
            .orEmpty()
        val levels = buildList {
            analysis?.supports?.take(2)?.forEach { add(ChartLevel(it.price, "S", market.gain)) }
            analysis?.resistances?.take(2)?.forEach { add(ChartLevel(it.price, "R", market.loss)) }
        }
        PriceChart(
            bars = shown,
            overlays = overlays,
            patterns = patterns,
            levels = levels,
            modifier = Modifier.fillMaxWidth().height(300.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Range.entries.forEach { r -> FilterChip(selected = r == range, onClick = { range = r }, label = { Text(r.label) }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Overlay.entries.forEach { o ->
                FilterChip(selected = o in overlays, onClick = { overlays = if (o in overlays) overlays - o else overlays + o }, label = { Text(o.label) })
            }
        }
        if (analysis != null && !analysis.hasOhlc) MutedText("Recent data has closing prices only (fallback source), so candles may look flat.")
    }
}

@Composable
private fun ScoreCard(a: StockAnalysis) {
    SectionCard("Technical score") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(a.score.toString(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = changeColor(a.score.toDouble()))
            Column {
                StanceBadge(a.stance)
                MutedText("From -100 (strong sell) to +100 (strong buy)")
            }
        }
        a.components.forEach { (name, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(if (v >= 0) "+%.2f".format(v) else "%.2f".format(v), color = changeColor(v), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (!a.isLiquid) MutedText("Low liquidity: average daily value ${Fmt.compactPeso(a.avgDailyValue)}. Excluded from buy suggestions.")
    }
}

@Composable
private fun NarrativeCard(n: Narrative?) {
    SectionCard("AI analysis", trailing = { n?.let { SourceBadge(it.source) } }) {
        if (n == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.height(20.dp))
                MutedText("Writing analysis…")
            }
            return@SectionCard
        }
        Text(n.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(n.summary, style = MaterialTheme.typography.bodyMedium)
        Text("Bull case", style = MaterialTheme.typography.labelLarge, color = changeColor(1.0))
        Bullets(n.bullCase)
        Text("Bear case", style = MaterialTheme.typography.labelLarge, color = changeColor(-1.0))
        Bullets(n.bearCase)
        Text("Key levels", style = MaterialTheme.typography.labelLarge)
        Text(n.keyLevels, style = MaterialTheme.typography.bodyMedium)
        Text("Risk", style = MaterialTheme.typography.labelLarge)
        Text(n.riskNote, style = MaterialTheme.typography.bodyMedium)
        MutedText("Stance: ${n.stance.label} · confidence ${n.confidence}%")
    }
}

@Composable
private fun PatternsCard(a: StockAnalysis) {
    SectionCard("Patterns detected") {
        if (a.patterns.isEmpty()) {
            MutedText("No notable patterns right now.")
            return@SectionCard
        }
        a.patterns.groupBy { it.category }.forEach { (category, hits) ->
            Text(category.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            hits.forEach { p ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val color = when (p.direction) { Direction.BULLISH -> changeColor(1.0); Direction.BEARISH -> changeColor(-1.0); Direction.NEUTRAL -> changeColor(0.0) }
                        Text(p.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Pill(p.direction.name.lowercase(), color.copy(alpha = 0.15f), color)
                        if (!p.confirmed) Pill("forming", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MutedText(p.detail)
                    val extras = listOfNotNull(p.target?.let { "Target ${Fmt.peso(it)}" }, p.invalidation?.let { "Invalid beyond ${Fmt.peso(it)}" }, "Confidence ${(p.confidence * 100).toInt()}%")
                    MutedText(extras.joinToString(" · "))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IndicatorsCard(a: StockAnalysis) {
    val labels = mapOf(
        "rsi14" to "RSI (14)", "macd" to "MACD", "macdSignal" to "MACD signal", "macdHist" to "MACD histogram",
        "sma20" to "SMA 20", "sma50" to "SMA 50", "sma200" to "SMA 200", "bbUpper" to "Bollinger upper",
        "bbLower" to "Bollinger lower", "atr14" to "ATR (14)", "adx14" to "ADX (14)", "stochK" to "Stochastic %K", "stochD" to "Stochastic %D",
    )
    SectionCard("Indicators") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            a.indicators.forEach { (k, v) -> LabeledValue(labels[k] ?: k, Fmt.num(v)) }
        }
        Bullets(a.signals)
    }
}

@Composable
private fun LevelsCard(a: StockAnalysis) {
    SectionCard("Support, resistance and Fibonacci") {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Support", style = MaterialTheme.typography.labelLarge, color = changeColor(1.0))
                if (a.supports.isEmpty()) MutedText("None nearby")
                a.supports.forEach { Text("${Fmt.peso(it.price)}  (${it.touches}×)", style = MaterialTheme.typography.bodyMedium) }
            }
            Column(Modifier.weight(1f)) {
                Text("Resistance", style = MaterialTheme.typography.labelLarge, color = changeColor(-1.0))
                if (a.resistances.isEmpty()) MutedText("None nearby")
                a.resistances.forEach { Text("${Fmt.peso(it.price)}  (${it.touches}×)", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (a.fibLevels.isNotEmpty()) {
            HorizontalDivider()
            Text("Fibonacci retracements (last swing)", style = MaterialTheme.typography.labelLarge)
            a.fibLevels.forEach {
                Row { Text("${"%.1f".format(it.ratio * 100)}%", modifier = Modifier.weight(1f)); Text(Fmt.peso(it.price)) }
            }
        }
    }
}

@Composable
private fun BacktestCard(a: StockAnalysis) {
    SectionCard("How these signals worked on ${a.symbol}") {
        if (a.backtest.isEmpty()) {
            MutedText("Not enough history to backtest yet.")
            return@SectionCard
        }
        MutedText("Each past signal, ${a.backtest.first().horizonDays} trading days later. Win = price moved the expected way.")
        Row {
            Text("Signal", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text("Times", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(0.3f))
            Text("Win %", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(0.35f))
            Text("Avg move", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(0.4f))
        }
        a.backtest.take(12).forEach { s ->
            Row {
                Text(s.signalName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(s.samples.toString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.3f))
                Text("${(s.winRate * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = changeColor(s.winRate - 0.5), modifier = Modifier.weight(0.35f))
                Text(Fmt.pct(s.avgReturnPct), style = MaterialTheme.typography.bodySmall, color = changeColor(s.avgReturnPct), modifier = Modifier.weight(0.4f))
            }
        }
        MutedText("Small samples (under 5) are unreliable. Past results don't guarantee future moves.")
    }
}

@Composable
private fun TradePlanCard(a: StockAnalysis, onAddTrade: () -> Unit) {
    val settings by appContainer().settings.settings.collectAsStateWithLifecycle(null)
    SectionCard("Trade plan (long)") {
        val plan = a.plan
        val s = settings
        if (plan == null || s == null) {
            MutedText("Not enough data for a trade plan.")
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledValue("Entry", Fmt.peso(plan.entry))
            LabeledValue("Stop", Fmt.peso(plan.stop), valueColor = changeColor(-1.0))
            LabeledValue("Target", Fmt.peso(plan.target), valueColor = changeColor(1.0))
            LabeledValue("Reward:risk", "%.1f".format(plan.rewardToRisk))
        }
        val size = RiskTools.positionSize(s.capital, s.riskPct, plan.entry, plan.stop, s.fees)
        val lot = PseRules.boardLot(plan.entry)
        if (size == null || size.shares == 0L) {
            MutedText("Your capital of ${Fmt.peso(s.capital)} can't cover one board lot ($lot shares, ${Fmt.peso(RiskTools.minimumInvestment(plan.entry, s.fees))}) within your risk settings.")
        } else {
            val be = FeeCalculator.breakEvenPrice(size.shares, plan.entry, s.fees)
            Text(
                "Risking ${Fmt.num(s.riskPct, 1)}% of ${Fmt.peso(s.capital)}: buy ${Fmt.shares(size.shares)} shares " +
                    "(${size.shares / lot} board lot${if (size.shares / lot == 1L) "" else "s"} of $lot) for ${Fmt.peso(size.cost)} including fees.",
                style = MaterialTheme.typography.bodyMedium,
            )
            MutedText("Max loss at stop ≈ ${Fmt.peso(size.riskAmount)} plus fees. Break-even after all fees and taxes: ${Fmt.peso(be)}." +
                if (size.limitedByCapital) " Position capped by available capital." else "")
        }
        OutlinedButton(onClick = onAddTrade) { Text("Record a trade") }
    }
}

@Composable
private fun DividendsCard(rows: List<DividendEntity>) {
    SectionCard("Dividends") {
        if (rows.isEmpty()) {
            MutedText("No dividend history found.")
            return@SectionCard
        }
        rows.take(6).forEach { d ->
            Column(Modifier.padding(vertical = 2.dp)) {
                Text("${d.type.lowercase().replaceFirstChar { it.uppercase() }} · ${d.amount?.let { Fmt.peso(it) } ?: d.rateText}", style = MaterialTheme.typography.bodyMedium)
                MutedText("Ex-date ${d.exDate} · Pay ${d.paymentDate ?: "—"}")
            }
        }
    }
}

@Composable
private fun DisclosuresCard(rows: List<DisclosureEntity>, summarizing: String?, onSummarize: (DisclosureEntity) -> Unit) {
    val context = LocalContext.current
    SectionCard("Company disclosures (PSE Edge)") {
        if (rows.isEmpty()) {
            MutedText("No recent disclosures.")
            return@SectionCard
        }
        rows.take(8).forEachIndexed { i, d ->
            if (i > 0) HorizontalDivider()
            Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(d.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                MutedText(d.announcedAt.take(16).replace('T', ' '))
                if (d.summary != null) {
                    val color = when (d.impact) { "POSITIVE" -> changeColor(1.0); "NEGATIVE" -> changeColor(-1.0); else -> changeColor(0.0) }
                    Pill("AI: ${d.impact?.lowercase() ?: "neutral"}", color.copy(alpha = 0.15f), color)
                    Text(d.summary, style = MaterialTheme.typography.bodySmall)
                    d.keyPoints?.lines()?.filter { it.isNotBlank() }?.let { Bullets(it) }
                }
                Row {
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.url))) }) { Text("Open") }
                    if (d.summary == null) {
                        TextButton(onClick = { onSummarize(d) }, enabled = summarizing == null) {
                            Text(if (summarizing == d.id) "Summarizing…" else "AI summary")
                        }
                    }
                }
            }
        }
    }
}
