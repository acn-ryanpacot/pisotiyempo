package com.pisotiyempo.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pisotiyempo.desktop.DesktopContainer
import com.pisotiyempo.domain.analysis.MarketBrief
import com.pisotiyempo.domain.analysis.ScoredStock
import com.pisotiyempo.domain.analysis.Suggestions
import com.pisotiyempo.domain.ph.Sectors
import com.pisotiyempo.domain.portfolio.PaperTrading
import com.pisotiyempo.domain.portfolio.PortfolioSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class DashboardData(
    val real: PortfolioSummary? = null,
    val practiceValue: Double = 0.0,
    val buys: List<ScoredStock> = emptyList(),
    val sells: List<ScoredStock> = emptyList(),
    val owned: Set<String> = emptySet(),
    val watched: Set<String> = emptySet(),
)

@Composable
fun DashboardScreen(c: DesktopContainer, open: (String) -> Unit) {
    val data by rememberData(c, DashboardData()) {
        val prices = c.market.stocks().associate { it.symbol to (it.close to it.changePct) }
        val real = c.portfolio.summary(prices)
        val practice = c.portfolio.summary(prices, "PAPER")
        val scored = c.market.screenRows().map { ScoredStock(it.symbol, it.name, it.close, it.changePct, it.score, it.stance, it.patterns.firstOrNull(), it.avgDailyValue, it.isLiquid) }
        val owned = real.holdings.map { it.symbol }.toSet()
        val watched = c.portfolio.watchlist().toSet()
        DashboardData(real, PaperTrading.cash(PaperTrading.DEFAULT_STARTING_CASH, practice) + practice.marketValue, Suggestions.topBuys(scored), Suggestions.topSells(scored, owned, watched), owned, watched)
    }
    val version by c.dataVersion.collectAsState()
    val brief by produceState<MarketBrief?>(null, version) {
        value = withContext(Dispatchers.IO) { c.market.marketStats() }?.let { c.ai.brief(it) }
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Section("Portfolio value") {
                    val p = data.real
                    Text(Fmt.peso(p?.marketValue ?: 0.0), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    if (p != null && p.holdings.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Value("Today", "${Fmt.signedPeso(p.dayChange)} (${Fmt.pct(p.dayChangePct)})", changeColor(p.dayChange))
                            Value("Unrealized", "${Fmt.signedPeso(p.unrealizedPnl)} (${Fmt.pct(p.unrealizedPct)})", changeColor(p.unrealizedPnl))
                            Value("Realized", Fmt.signedPeso(p.realizedPnl), changeColor(p.realizedPnl))
                            Value("Dividends (net)", Fmt.peso(p.dividendsReceived))
                        }
                        p.warnings.forEach { Muted("⚠  $it") }
                    } else {
                        Muted("Record trades in Portfolio, or link your phone in Settings to bring them over.")
                    }
                    Muted("Practice account: ${Fmt.peso(data.practiceValue)}")
                }
            }
            item {
                Section("Market brief", trailing = { brief?.let { SourceBadge(it.source) } }) {
                    val b = brief
                    if (b == null) Muted("The brief appears after prices are updated.") else {
                        Text(b.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Muted("${b.mood.lowercase()} · ${b.date}")
                        Text(b.overview)
                        Bullets(b.highlights.take(5))
                        if (b.watchTomorrow.isNotEmpty()) {
                            Text("Watch next session", style = MaterialTheme.typography.labelLarge)
                            Bullets(b.watchTomorrow)
                        }
                    }
                }
            }
            item { Muted(DISCLAIMER) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SuggestionList("Top 5 buy ideas", data.buys, "No liquid stock scores 20 or higher right now.", open) { null } }
            item {
                SuggestionList("Top 5 sell signals", data.sells, "Nothing you own or watch is flashing a sell.", open) { s ->
                    when (s.symbol) {
                        in data.owned -> "You own"
                        in data.watched -> "Watchlist"
                        else -> "Avoid"
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionList(title: String, rows: List<ScoredStock>, empty: String, open: (String) -> Unit, tag: (ScoredStock) -> String?) {
    Section(title) {
        if (rows.isEmpty()) Muted(empty)
        rows.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider()
            StockLine(s.symbol, listOfNotNull(s.topSignal ?: s.name, tag(s)).joinToString(" · "), s.close, s.changePct, trailing = { StanceBadge(s.stance, s.score) }) { open(s.symbol) }
        }
    }
}

@Composable
fun MarketScreen(c: DesktopContainer, open: (String) -> Unit) {
    val stocks by rememberData(c, emptyList()) { c.market.stocks() }
    val psei by rememberData(c, emptyList()) { c.market.psei() }
    val stats by rememberData(c, null) { c.market.marketStats() }
    val date = stocks.groupingBy { it.date }.eachCount().maxByOrNull { it.value }?.key
    val today = stocks.filter { it.date == date }
    val liquid = today.filter { it.close * it.volume >= 5_000_000 && it.changePct != null }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LazyColumn(Modifier.weight(1.3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Section("PSEi") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(Fmt.num(stats?.pseiClose), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(Fmt.pct(stats?.pseiChangePct), color = changeColor(stats?.pseiChangePct), style = MaterialTheme.typography.titleMedium)
                    }
                    if (psei.size >= 2) CandleChart(psei.takeLast(260), Modifier.fillMaxWidth().height(260.dp), lineMode = true)
                    else Muted("Index history builds up day by day when the primary data source is unavailable.")
                }
            }
            item {
                Section("Breadth and sectors") {
                    val s = stats
                    if (s == null) Muted("Available after prices are updated.") else {
                        val total = (s.advancers + s.decliners + s.unchanged).coerceAtLeast(1)
                        Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
                            if (s.advancers > 0) Box(Modifier.weight(s.advancers.toFloat()).fillMaxHeight().background(Gain))
                            if (s.unchanged > 0) Box(Modifier.weight(s.unchanged.toFloat()).fillMaxHeight().background(Neutral))
                            if (s.decliners > 0) Box(Modifier.weight(s.decliners.toFloat()).fillMaxHeight().background(Loss))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Value("Advancers", s.advancers.toString(), Gain)
                            Value("Decliners", s.decliners.toString(), Loss)
                            Value("Unchanged", s.unchanged.toString())
                            Value("Value traded", Fmt.compact(s.totalValue))
                        }
                        Muted("${s.advancers * 100 / total}% of traded issues rose.")
                    }
                    HorizontalDivider()
                    today.filter { it.changePct != null && it.sector in Sectors.ALL }.groupBy { it.sector }.map { (sector, rows) ->
                        val w = rows.map { maxOf(it.close * it.volume, 1.0) }
                        sector to rows.indices.sumOf { rows[it].changePct!! * w[it] } / w.sum()
                    }.sortedByDescending { it.second }.forEach { (sector, move) ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(sector, Modifier.weight(1f))
                            Text(Fmt.pct(move), color = changeColor(move))
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("Top gainers", Modifier.weight(1f)) {
                LazyColumn {
                    items(liquid.sortedByDescending { it.changePct }.take(10), key = { it.symbol }) { s ->
                        StockLine(s.symbol, s.name, s.close, s.changePct) { open(s.symbol) }
                    }
                }
            }
            Section("Top losers", Modifier.weight(1f)) {
                LazyColumn {
                    items(liquid.sortedBy { it.changePct }.take(10), key = { it.symbol }) { s ->
                        StockLine(s.symbol, s.name, s.close, s.changePct) { open(s.symbol) }
                    }
                }
            }
            Section("Most active", Modifier.weight(1f)) {
                LazyColumn {
                    items(today.sortedByDescending { it.close * it.volume }.take(10), key = { it.symbol }) { s ->
                        StockLine(s.symbol, "${s.name} · ${Fmt.compact(s.close * s.volume)}", s.close, s.changePct) { open(s.symbol) }
                    }
                }
            }
        }
    }
}
