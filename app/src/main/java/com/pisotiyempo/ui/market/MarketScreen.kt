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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pisotiyempo.AppContainer
import com.pisotiyempo.R
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
fun MarketScreen(onOpenStock: (String) -> Unit, contentPadding: PaddingValues, onOpenScreener: () -> Unit) {
    val container = appContainer()
    val vm: MarketViewModel = viewModel { MarketViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val flow by vm.foreignFlow.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(stringResource(R.string.market_screener_title), trailing = { androidx.compose.material3.Button(onClick = onOpenScreener) { Text(stringResource(R.string.market_open)) } }) {
                MutedText(stringResource(R.string.market_screener_desc))
            }
        }
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
            MutedText(stringResource(R.string.market_psei_history_note))
        }
        state.source?.let { MutedText(stringResource(R.string.market_prices_as_of_source, s?.date ?: "—", it)) }
    }
}

@Composable
private fun BreadthCard(s: MarketStats?) {
    SectionCard(stringResource(R.string.market_breadth)) {
        if (s == null) {
            MutedText(stringResource(R.string.market_available_after_sync))
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
            LabeledValue(stringResource(R.string.market_advancers), s.advancers.toString(), valueColor = c.gain)
            LabeledValue(stringResource(R.string.market_decliners), s.decliners.toString(), valueColor = c.loss)
            LabeledValue(stringResource(R.string.market_unchanged), s.unchanged.toString())
            LabeledValue(stringResource(R.string.market_value_traded), Fmt.compactPeso(s.totalValue))
        }
        MutedText(stringResource(R.string.market_breadth_rose_pct, "%.0f".format(s.advancers * 100.0 / total)))
    }
}

@Composable
private fun SectorsCard(sectors: List<SectorMove>) {
    SectionCard(stringResource(R.string.market_sector_performance)) {
        if (sectors.isEmpty()) {
            MutedText(stringResource(R.string.market_available_after_sync))
            return@SectionCard
        }
        sectors.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.sector, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                MutedText(stringResource(R.string.market_sector_count_value, s.count, Fmt.compactPeso(s.value)) + "  ")
                ChangeText(s.changePct)
            }
        }
        MutedText(stringResource(R.string.market_sector_note))
    }
}

@Composable
private fun ForeignFlowCard(flow: ForeignFlowDto?, onOpenStock: (String) -> Unit) {
    SectionCard(stringResource(R.string.market_foreign_flow)) {
        if (flow == null) {
            MutedText(stringResource(R.string.market_foreign_flow_unavailable))
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledValue(stringResource(R.string.market_net), Fmt.compactPeso(flow.netValue), valueColor = changeColor(flow.netValue))
            LabeledValue(stringResource(R.string.market_buying), Fmt.compactPeso(flow.buyValue))
            LabeledValue(stringResource(R.string.market_selling), Fmt.compactPeso(flow.sellValue))
        }
        MutedText(stringResource(R.string.market_as_of, flow.date))
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
    val labels = listOf(
        stringResource(R.string.market_gainers),
        stringResource(R.string.market_losers),
        stringResource(R.string.market_most_active),
    )
    SectionCard(stringResource(R.string.market_top_movers)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, labels.size)) { Text(label) }
            }
        }
        val rows = when (tab) { 0 -> state.gainers; 1 -> state.losers; else -> state.active }
        if (rows.isEmpty()) MutedText(stringResource(R.string.market_available_after_sync))
        rows.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider()
            StockRow(s.symbol, s.name, s.close, s.changePct, subtitle = "${s.name} · ${Fmt.compactPeso(s.close * s.volume)}", onClick = { onOpenStock(s.symbol) })
        }
        if (tab != 2) MutedText(stringResource(R.string.market_liquid_note, Fmt.compactPeso(MarketStatsCalc.LIQUID_VALUE)))
    }
}
