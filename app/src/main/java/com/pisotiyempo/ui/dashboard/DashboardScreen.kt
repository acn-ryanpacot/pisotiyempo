package com.pisotiyempo.ui.dashboard

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pisotiyempo.AppContainer
import com.pisotiyempo.R
import com.pisotiyempo.data.repo.MarketStatsCalc
import com.pisotiyempo.data.settings.Settings
import com.pisotiyempo.domain.analysis.MarketBrief
import com.pisotiyempo.domain.analysis.NarrativeSource
import com.pisotiyempo.domain.analysis.ScoredStock
import com.pisotiyempo.domain.analysis.Suggestions
import com.pisotiyempo.domain.portfolio.PortfolioSummary
import com.pisotiyempo.ui.common.Bullets
import com.pisotiyempo.ui.common.DisclaimerFooter
import com.pisotiyempo.ui.common.Fmt
import com.pisotiyempo.ui.common.LabeledValue
import com.pisotiyempo.ui.common.MutedText
import com.pisotiyempo.ui.common.Pill
import com.pisotiyempo.ui.common.SectionCard
import com.pisotiyempo.ui.common.SourceBadge
import com.pisotiyempo.ui.common.StanceBadge
import com.pisotiyempo.ui.common.StockRow
import com.pisotiyempo.ui.common.appContainer
import com.pisotiyempo.ui.common.changeColor
import com.pisotiyempo.ui.common.moodLabel
import com.pisotiyempo.work.SyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Reason(val text: String, val source: NarrativeSource)

data class DashboardState(
    val portfolio: PortfolioSummary? = null,
    val buys: List<ScoredStock> = emptyList(),
    val sells: List<ScoredStock> = emptyList(),
    val holdings: Set<String> = emptySet(),
    val watchlist: Set<String> = emptySet(),
    val hasData: Boolean = false,
    val settings: Settings = Settings(),
    val syncProgress: String? = null,
)

class DashboardViewModel(private val c: AppContainer, private val app: Application) : ViewModel() {

    val brief = MutableStateFlow<MarketBrief?>(null)
    val closedToday = MutableStateFlow<String?>(null)
    val reasons = MutableStateFlow<Map<String, Reason>>(emptyMap())

    private val lists = combine(c.portfolio.summary(), c.analysis.scored(), c.portfolio.watchlist()) { summary, scored, watch ->
        val holdings = summary.holdings.map { it.symbol }.toSet()
        Triple(summary, scored, holdings to watch.toSet())
    }

    val state: StateFlow<DashboardState> = combine(
        lists,
        c.market.stocks().map { it.isNotEmpty() }.distinctUntilChanged(),
        c.settings.settings,
        SyncService.progress(app),
    ) { (summary, scored, mine), hasData, settings, progress ->
        DashboardState(
            portfolio = summary,
            buys = Suggestions.topBuys(scored),
            sells = Suggestions.topSells(scored, mine.first, mine.second),
            holdings = mine.first,
            watchlist = mine.second,
            hasData = hasData,
            settings = settings,
            syncProgress = progress,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    init {
        viewModelScope.launch { closedToday.value = c.market.closedToday() }
        viewModelScope.launch {
            val s = c.settings.current()
            if (System.currentTimeMillis() - s.lastSyncAt > 6 * 3600_000L) SyncService.syncNow(app)
        }
        // Rebuild the market brief whenever a new trading day arrives.
        viewModelScope.launch {
            combine(c.market.stocks(), c.market.psei()) { stocks, psei -> MarketStatsCalc.compute(stocks, psei) }
                .filterNotNull()
                .distinctUntilChanged { a, b -> a.date == b.date && a.pseiClose == b.pseiClose }
                .collect { stats -> brief.value = c.ai.marketBrief(stats) }
        }
        // One-line reasons for the suggestion rows, from Claude when available (cached per stock per day).
        viewModelScope.launch {
            state.map { (it.buys + it.sells).map { s -> s.symbol } }.distinctUntilChanged().collect { symbols ->
                for (symbol in symbols) {
                    if (reasons.value.containsKey(symbol)) continue
                    val analysis = c.analysis.quickAnalysis(symbol) ?: continue
                    val n = c.ai.stockNarrative(analysis)
                    reasons.update { it + (symbol to Reason(n.oneLiner, n.source)) }
                }
            }
        }
    }

    fun syncNow() = SyncService.syncNow(app)
}

@Composable
fun DashboardScreen(onOpenStock: (String) -> Unit, contentPadding: PaddingValues) {
    val container = appContainer()
    val app = LocalContext.current.applicationContext as Application
    val vm: DashboardViewModel = viewModel { DashboardViewModel(container, app) }
    val state by vm.state.collectAsStateWithLifecycle()
    val brief by vm.brief.collectAsStateWithLifecycle()
    val reasons by vm.reasons.collectAsStateWithLifecycle()
    val closedToday by vm.closedToday.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SyncStatus(state, closedToday, onSync = vm::syncNow) }
        item { PortfolioCard(state.portfolio) }
        item { BriefCard(brief) }
        item {
            SuggestionCard(
                title = stringResource(R.string.dashboard_top_buys),
                empty = if (state.hasData) stringResource(R.string.dashboard_no_buys) else stringResource(R.string.dashboard_waiting_first_sync),
                rows = state.buys,
                reasons = reasons,
                tagFor = { null },
                onOpenStock = onOpenStock,
            )
        }
        item {
            val tagOwn = stringResource(R.string.dashboard_tag_you_own)
            val tagWatch = stringResource(R.string.dashboard_tag_watchlist)
            val tagAvoid = stringResource(R.string.dashboard_tag_avoid)
            SuggestionCard(
                title = stringResource(R.string.dashboard_top_sells),
                empty = if (state.hasData) stringResource(R.string.dashboard_no_sells) else stringResource(R.string.dashboard_waiting_first_sync),
                rows = state.sells,
                reasons = reasons,
                tagFor = { s ->
                    when (s.symbol) {
                        in state.holdings -> tagOwn
                        in state.watchlist -> tagWatch
                        else -> tagAvoid
                    }
                },
                onOpenStock = onOpenStock,
            )
        }
        item { DisclaimerFooter() }
    }
}

@Composable
private fun SyncStatus(state: DashboardState, closedToday: String?, onSync: () -> Unit) {
    Column {
        closedToday?.let { MutedText(if (it == "Weekend") stringResource(R.string.dashboard_closed_weekend) else stringResource(R.string.dashboard_closed_today, it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val s = state.settings
            MutedText(
                state.syncProgress ?: (s.lastSyncDate?.let { stringResource(R.string.dashboard_prices_as_of, it, s.lastSyncSource ?: "") } ?: stringResource(R.string.dashboard_not_synced)),
                Modifier.weight(1f),
            )
            TextButton(onClick = onSync, enabled = state.syncProgress == null) { Text(stringResource(R.string.dashboard_sync_now)) }
        }
        if (state.syncProgress != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.settings.lastSyncMessage?.takeIf { it.startsWith("Sync failed") }?.let { MutedText(it) }
    }
}

@Composable
private fun PortfolioCard(p: PortfolioSummary?) {
    SectionCard(stringResource(R.string.dashboard_portfolio_value)) {
        if (p == null || p.holdings.isEmpty()) {
            Text(Fmt.peso(0.0), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            MutedText(stringResource(R.string.dashboard_portfolio_empty))
            return@SectionCard
        }
        Text(Fmt.peso(p.marketValue), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledValue(stringResource(R.string.dashboard_today), "${Fmt.signedPeso(p.dayChange)} (${Fmt.pct(p.dayChangePct)})", valueColor = changeColor(p.dayChange))
            LabeledValue(stringResource(R.string.dashboard_unrealized), "${Fmt.signedPeso(p.unrealizedPnl)} (${Fmt.pct(p.unrealizedPct)})", valueColor = changeColor(p.unrealizedPnl))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledValue(stringResource(R.string.dashboard_realized), Fmt.signedPeso(p.realizedPnl), valueColor = changeColor(p.realizedPnl))
            LabeledValue(stringResource(R.string.dashboard_dividends_net), Fmt.peso(p.dividendsReceived))
            LabeledValue(stringResource(R.string.dashboard_holdings), p.holdings.size.toString())
        }
    }
}

@Composable
private fun BriefCard(brief: MarketBrief?) {
    SectionCard(stringResource(R.string.dashboard_market_brief), trailing = { brief?.let { SourceBadge(it.source) } }) {
        if (brief == null) {
            MutedText(stringResource(R.string.dashboard_brief_empty))
            return@SectionCard
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val color = when (brief.mood) { "BULLISH" -> changeColor(1.0); "BEARISH" -> changeColor(-1.0); else -> changeColor(0.0) }
            Pill(moodLabel(brief.mood), color.copy(alpha = 0.15f), color)
            MutedText(brief.date)
        }
        Text(brief.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(brief.overview, style = MaterialTheme.typography.bodyMedium)
        if (brief.highlights.isNotEmpty()) Bullets(brief.highlights.take(4))
        if (brief.watchTomorrow.isNotEmpty()) {
            Text(stringResource(R.string.dashboard_watch_next_session), style = MaterialTheme.typography.labelLarge)
            Bullets(brief.watchTomorrow.take(3))
        }
    }
}

@Composable
private fun SuggestionCard(
    title: String,
    empty: String,
    rows: List<ScoredStock>,
    reasons: Map<String, Reason>,
    tagFor: (ScoredStock) -> String?,
    onOpenStock: (String) -> Unit,
) {
    SectionCard(title) {
        if (rows.isEmpty()) {
            MutedText(empty)
            return@SectionCard
        }
        rows.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider()
            val reason = reasons[s.symbol]
            StockRow(
                symbol = s.symbol,
                name = s.name,
                price = s.close,
                changePct = s.changePct,
                subtitle = reason?.text ?: s.topSignal ?: s.name,
                trailing = {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StanceBadge(s.stance, s.score)
                        tagFor(s)?.let { MutedText(it) }
                    }
                },
                onClick = { onOpenStock(s.symbol) },
            )
        }
        if (reasons.values.any { it.source != NarrativeSource.RULES }) MutedText(stringResource(R.string.dashboard_reasons_by_claude))
    }
}
