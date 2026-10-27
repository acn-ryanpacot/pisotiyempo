package com.pisotiyempo.ui.market

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pisotiyempo.AppContainer
import com.pisotiyempo.data.local.StockEntity
import com.pisotiyempo.data.remote.ForeignFlowDto
import com.pisotiyempo.data.repo.MarketStatsCalc
import com.pisotiyempo.data.repo.SectorMove
import com.pisotiyempo.domain.analysis.MarketStats
import com.pisotiyempo.domain.model.Bar
import com.pisotiyempo.ui.chart.PriceChart
import com.pisotiyempo.ui.common.ChangeText
import com.pisotiyempo.ui.common.DisclaimerFooter
import com.pisotiyempo.ui.common.Fmt
import com.pisotiyempo.ui.common.LabeledValue
import com.pisotiyempo.ui.common.MutedText
import com.pisotiyempo.ui.common.SectionCard
import com.pisotiyempo.ui.common.StockRow
import com.pisotiyempo.ui.common.appContainer
import com.pisotiyempo.ui.common.changeColor
import com.pisotiyempo.ui.theme.LocalMarketColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MarketState(
    val stats: MarketStats? = null,
    val psei: List<Bar> = emptyList(),
    val sectors: List<SectorMove> = emptyList(),
    val gainers: List<StockEntity> = emptyList(),
    val losers: List<StockEntity> = emptyList(),
    val active: List<StockEntity> = emptyList(),
    val source: String? = null,
)

class MarketViewModel(c: AppContainer) : ViewModel() {
    val foreignFlow = MutableStateFlow<ForeignFlowDto?>(null)

    val state: StateFlow<MarketState> = combine(c.market.stocks(), c.market.psei()) { stocks, psei ->
        val today = MarketStatsCalc.today(stocks)
        val liquid = today.filter { it.close * it.volume >= MarketStatsCalc.LIQUID_VALUE && it.changePct != null }
        MarketState(
            stats = MarketStatsCalc.compute(stocks, psei),
            psei = psei,
            sectors = MarketStatsCalc.sectors(stocks),
            gainers = liquid.sortedByDescending { it.changePct }.take(10),
            losers = liquid.sortedBy { it.changePct }.take(10),
            active = today.sortedByDescending { it.close * it.volume }.take(10),
            source = today.firstOrNull()?.source,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MarketState())

    init {
        viewModelScope.launch { foreignFlow.value = c.market.foreignFlow() }
    }
}

@Composable
fun MarketScreen(onOpenStock: (String) -> Unit, contentPadding: PaddingValues) {
    val container = appContainer()
    val vm: MarketViewModel = viewModel { MarketViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val flow by vm.foreignFlow.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PseiCard(state) }
        item { BreadthCard(state.stats) }
        item { SectorsCard(state.sectors) }
        item { ForeignFlowCard(flow, onOpenStock) }
        item { MoversCard(state, onOpenStock) }
        item { DisclaimerFooter() }
    }
}

@Composable
private fun PseiCard(state: MarketState) {
    SectionCard("PSEi") {
        val s = state.stats
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(Fmt.num(s?.pseiClose), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            ChangeText(s?.pseiChangePct, bold = true)
        }
        if (state.psei.size >= 2) {
            PriceChart(bars = state.psei.takeLast(260), lineMode = true, showVolume = false, modifier = Modifier.fillMaxWidth().height(200.dp))
        } else {
            MutedText("Index history builds up day by day when the primary data source is unavailable.")
        }
        state.source?.let { MutedText("Prices as of ${s?.date ?: "—"} · source: $it") }
    }
}

@Composable
private fun BreadthCard(s: MarketStats?) {
    SectionCard("Market breadth") {
        if (s == null) {
            MutedText("Available after the first sync.")
            return@SectionCard
        }
        val total = (s.advancers + s.decliners + s.unchanged).coerceAtLeast(1)
        val c = LocalMarketColors.current
        Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
            if (s.advancers > 0) Box(Modifier.weight(s.advancers.toFloat()).fillMaxHeight().background(c.gain))
            if (s.unchanged > 0) Box(Modifier.weight(s.unchanged.toFloat()).fillMaxHeight().background(c.neutral))
            if (s.decliners > 0) Box(Modifier.weight(s.decliners.toFloat()).fillMaxHeight().background(c.loss))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledValue("Advancers", s.advancers.toString(), valueColor = c.gain)
            LabeledValue("Decliners", s.decliners.toString(), valueColor = c.loss)
            LabeledValue("Unchanged", s.unchanged.toString())
            LabeledValue("Value traded", Fmt.compactPeso(s.totalValue))
        }
        MutedText("${"%.0f".format(s.advancers * 100.0 / total)}% of traded issues rose.")
    }
}

@Composable
private fun SectorsCard(sectors: List<SectorMove>) {
    SectionCard("Sector performance") {
        if (sectors.isEmpty()) {
            MutedText("Available after the first sync.")
            return@SectionCard
        }
        sectors.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.sector, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                MutedText("${s.count} stocks · ${Fmt.compactPeso(s.value)}  ")
                ChangeText(s.changePct)
            }
        }
        MutedText("Value-weighted average move of each sector's stocks, computed in the app.")
    }
}

@Composable
private fun ForeignFlowCard(flow: ForeignFlowDto?, onOpenStock: (String) -> Unit) {
    SectionCard("Foreign buying / selling") {
        if (flow == null) {
            MutedText("Not available yet. The PSE publishes foreign flow in reports that can't be redistributed without its consent, so this needs a licensed feed connected to the backend.")
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledValue("Net", Fmt.compactPeso(flow.netValue), valueColor = changeColor(flow.netValue))
            LabeledValue("Buying", Fmt.compactPeso(flow.buyValue))
            LabeledValue("Selling", Fmt.compactPeso(flow.sellValue))
        }
        MutedText("As of ${flow.date}")
        val top = flow.bySymbol.sortedByDescending { kotlin.math.abs(it.netValue) }.take(6)
        top.forEach { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.TextButton(onClick = { onOpenStock(s.symbol) }) { Text(s.symbol) }
                Text(Fmt.compactPeso(s.netValue), color = changeColor(s.netValue), modifier = Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoversCard(state: MarketState, onOpenStock: (String) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val labels = listOf("Gainers", "Losers", "Most active")
    SectionCard("Top movers") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, labels.size)) { Text(label) }
            }
        }
        val rows = when (tab) { 0 -> state.gainers; 1 -> state.losers; else -> state.active }
        if (rows.isEmpty()) MutedText("Available after the first sync.")
        rows.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider()
            StockRow(s.symbol, s.name, s.close, s.changePct, subtitle = "${s.name} · ${Fmt.compactPeso(s.close * s.volume)}", onClick = { onOpenStock(s.symbol) })
        }
        if (tab != 2) MutedText("Only stocks with at least ${Fmt.compactPeso(MarketStatsCalc.LIQUID_VALUE)} traded are ranked.")
    }
}
