package com.pisotiyempo.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pisotiyempo.desktop.DesktopContainer
import com.pisotiyempo.domain.analysis.Narrative
import com.pisotiyempo.domain.model.Direction
import com.pisotiyempo.domain.model.PatternCategory
import com.pisotiyempo.domain.model.StockAnalysis
import com.pisotiyempo.domain.ph.FeeCalculator
import com.pisotiyempo.domain.ph.FeeSchedule
import com.pisotiyempo.domain.ph.PseRules
import com.pisotiyempo.domain.ph.RiskTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Range(val label: String, val days: Long) { M1("1M", 31), M3("3M", 92), M6("6M", 183), Y1("1Y", 366), Y2("2Y", 731) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StockScreen(c: DesktopContainer, symbol: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val stock by rememberData(c, null, symbol) { c.market.stock(symbol) }
    val bars by rememberData(c, emptyList(), symbol) { c.market.bars(symbol) }
    val watched by rememberData(c, false, symbol) { symbol in c.portfolio.watchlist() }
    val analysis by produceState<StockAnalysis?>(null, symbol, bars.size) {
        value = withContext(Dispatchers.IO) {
            c.market.ensureHistory(symbol)
            c.market.analyze(symbol, withBacktest = true)
        }
    }
    val narrative by produceState<Narrative?>(null, analysis) { value = analysis?.let { c.ai.stock(it) } }
    var range by remember { mutableStateOf(Range.M6) }
    var dialog by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(symbol, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Muted(stock?.let { "${it.name} · ${it.sector}" } ?: "")
            Text(Fmt.peso(stock?.close), style = MaterialTheme.typography.titleLarge)
            Text(Fmt.pct(stock?.changePct), color = changeColor(stock?.changePct))
            analysis?.let { StanceBadge(it.stance, it.score) }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { c.portfolio.setWatched(symbol, !watched) }
                        c.touch()
                    }
                }) { Text(if (watched) "Remove from watchlist" else "Add to watchlist") }
            }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(Modifier.weight(1.4f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Section {
                        if (bars.isEmpty()) Muted("Loading price history…") else {
                            val cutoff = bars.last().date.minusDays(range.days)
                            val start = bars.indexOfFirst { !it.date.isBefore(cutoff) }.coerceAtLeast(0)
                            val shown = bars.subList(start, bars.size)
                            val a = analysis
                            CandleChart(
                                shown,
                                Modifier.fillMaxWidth().height(380.dp),
                                overlays = movingAverages(bars).map { ChartLine(it.values.copyOfRange(start, bars.size), it.color) },
                                levels = a?.let { an -> an.supports.take(2).map { ChartLevel(it.price, "S", Gain) } + an.resistances.take(2).map { ChartLevel(it.price, "R", Loss) } }.orEmpty(),
                                patterns = a?.patterns.orEmpty().filter { it.category != PatternCategory.INDICATOR && it.endIndex >= start }
                                    .map { it.copy(endIndex = it.endIndex - start, startIndex = (it.startIndex - start).coerceAtLeast(0)) },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Range.entries.forEach { r -> FilterChip(selected = r == range, onClick = { range = r }, label = { Text(r.label) }) }
                                Muted("  SMA 20 (orange) · SMA 50 (green) · SMA 200 (purple)")
                            }
                        }
                    }
                }
                analysis?.let { a ->
                    item { PatternsSection(a) }
                    item { BacktestSection(a) }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val a = analysis
                if (a == null) item { Row { CircularProgressIndicator(Modifier.padding(8.dp)); Muted("Analyzing…") } }
                else {
                    item { NarrativeSection(narrative) }
                    item { ScoreSection(a) }
                    item { PlanSection(a, onTrade = { dialog = "REAL" }, onPractice = { dialog = "PAPER" }) }
                    item {
                        Section("Indicators and levels") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                a.indicators.forEach { (k, v) -> Value(k, Fmt.num(v)) }
                            }
                            Muted("Support: " + a.supports.joinToString { Fmt.peso(it.price) }.ifBlank { "none nearby" })
                            Muted("Resistance: " + a.resistances.joinToString { Fmt.peso(it.price) }.ifBlank { "none nearby" })
                            if (a.fibLevels.isNotEmpty()) Muted("Fibonacci: " + a.fibLevels.joinToString { "${(it.ratio * 100).toInt()}% ${Fmt.peso(it.price)}" })
                        }
                    }
                }
            }
        }
    }
    dialog?.let { account ->
        TradeDialog(c, initialSymbol = symbol, account = account, lastPrice = stock?.close, onDismiss = { dialog = null })
    }
}

@Composable
private fun NarrativeSection(n: Narrative?) {
    Section("AI analysis", trailing = { n?.let { SourceBadge(it.source) } }) {
        if (n == null) {
            Muted("Writing analysis…")
            return@Section
        }
        Text(n.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(n.summary)
        Text("Bull case", color = Gain, style = MaterialTheme.typography.labelLarge)
        Bullets(n.bullCase)
        Text("Bear case", color = Loss, style = MaterialTheme.typography.labelLarge)
        Bullets(n.bearCase)
        Text("Key levels", style = MaterialTheme.typography.labelLarge)
        Text(n.keyLevels)
        Muted(n.riskNote)
    }
}

@Composable
private fun ScoreSection(a: StockAnalysis) {
    Section("Technical score") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(a.score.toString(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = changeColor(a.score.toDouble()))
            StanceBadge(a.stance)
        }
        a.components.forEach { (name, v) ->
            Row(Modifier.fillMaxWidth()) {
                Text(name, Modifier.weight(1f))
                Text(String.format(java.util.Locale.US, "%+.2f", v), color = changeColor(v))
            }
        }
        if (!a.isLiquid) Muted("Low liquidity: average daily value ${Fmt.compact(a.avgDailyValue)}.")
    }
}

@Composable
private fun PatternsSection(a: StockAnalysis) {
    Section("Patterns detected") {
        if (a.patterns.isEmpty()) Muted("No notable patterns right now.")
        a.patterns.groupBy { it.category }.forEach { (cat, hits) ->
            Text(cat.label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            hits.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Pill(p.direction.name.lowercase(), when (p.direction) { Direction.BULLISH -> Gain; Direction.BEARISH -> Loss; Direction.NEUTRAL -> Neutral })
                    if (!p.confirmed) Pill("forming", Neutral)
                }
                Muted(p.detail)
                Muted(listOfNotNull(p.target?.let { "Target ${Fmt.peso(it)}" }, p.invalidation?.let { "Invalid beyond ${Fmt.peso(it)}" }, "Confidence ${(p.confidence * 100).toInt()}%").joinToString(" · "))
            }
        }
    }
}

@Composable
private fun BacktestSection(a: StockAnalysis) {
    Section("How these signals worked on ${a.symbol}") {
        if (a.backtest.isEmpty()) {
            Muted("Not enough history to backtest yet.")
            return@Section
        }
        Row {
            Text("Signal", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text("Times", Modifier.weight(0.3f), style = MaterialTheme.typography.labelMedium)
            Text("Win %", Modifier.weight(0.3f), style = MaterialTheme.typography.labelMedium)
            Text("Avg ${a.backtest.first().horizonDays}-day move", Modifier.weight(0.5f), style = MaterialTheme.typography.labelMedium)
        }
        a.backtest.take(15).forEach { s ->
            Row {
                Text(s.signalName, Modifier.weight(1f))
                Text(s.samples.toString(), Modifier.weight(0.3f))
                Text("${(s.winRate * 100).toInt()}%", Modifier.weight(0.3f), color = changeColor(s.winRate - 0.5))
                Text(Fmt.pct(s.avgReturnPct), Modifier.weight(0.5f), color = changeColor(s.avgReturnPct))
            }
        }
        Muted("Small samples (under 5) are unreliable. Past results don't guarantee future moves.")
    }
}

@Composable
private fun PlanSection(a: StockAnalysis, onTrade: () -> Unit, onPractice: () -> Unit) {
    Section("Trade plan (long)") {
        val plan = a.plan
        if (plan == null) {
            Muted("Not enough data for a trade plan.")
            return@Section
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Value("Entry", Fmt.peso(plan.entry))
            Value("Stop", Fmt.peso(plan.stop), Loss)
            Value("Target", Fmt.peso(plan.target), Gain)
            Value("Reward:risk", String.format(java.util.Locale.US, "%.1f", plan.rewardToRisk))
        }
        val size = RiskTools.positionSize(100_000.0, 1.0, plan.entry, plan.stop, FeeSchedule())
        if (size != null && size.shares > 0) {
            Muted(
                "On ₱100,000 risking 1%: ${Fmt.shares(size.shares)} shares (board lot ${PseRules.boardLot(plan.entry)}) for ${Fmt.peso(size.cost)}; " +
                    "break-even ${Fmt.peso(FeeCalculator.breakEvenPrice(size.shares, plan.entry))}.",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTrade) { Text("Record a trade") }
            OutlinedButton(onClick = onPractice) { Text("Practice trade") }
        }
    }
}
