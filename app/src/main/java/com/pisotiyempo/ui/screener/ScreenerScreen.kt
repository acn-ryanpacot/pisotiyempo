package com.pisotiyempo.ui.screener

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisotiyempo.R
import com.pisotiyempo.data.local.AnalysisEntity
import com.pisotiyempo.domain.analysis.PatternFilter
import com.pisotiyempo.domain.analysis.ScreenCriteria
import com.pisotiyempo.domain.analysis.ScreenPreset
import com.pisotiyempo.domain.analysis.ScreenRow
import com.pisotiyempo.domain.analysis.ScreenSort
import com.pisotiyempo.domain.analysis.Screener
import com.pisotiyempo.domain.model.Stance
import com.pisotiyempo.domain.ph.Sectors
import com.pisotiyempo.ui.common.Fmt
import com.pisotiyempo.ui.common.MutedText
import com.pisotiyempo.ui.common.SectionCard
import com.pisotiyempo.ui.common.StanceBadge
import com.pisotiyempo.ui.common.StockRow
import com.pisotiyempo.ui.common.appContainer
import com.pisotiyempo.ui.common.patternFilterLabel
import com.pisotiyempo.ui.common.presetDescription
import com.pisotiyempo.ui.common.presetLabel
import com.pisotiyempo.ui.common.sortLabel
import com.pisotiyempo.ui.common.stanceLabel
import kotlinx.coroutines.flow.map

private fun AnalysisEntity.toRow() = ScreenRow(
    symbol = symbol,
    name = name,
    sector = sector,
    close = close,
    changePct = changePct,
    score = score,
    stance = runCatching { Stance.valueOf(stance) }.getOrDefault(Stance.fromScore(score)),
    rsi14 = rsi14,
    aboveSma50 = aboveSma50,
    aboveSma200 = aboveSma200,
    patterns = patternNames.lines().filter { it.isNotBlank() },
    bullishCount = bullishCount,
    bearishCount = bearishCount,
    volumeRatio = volumeRatio,
    return3mPct = return3mPct,
    avgDailyValue = avgDailyValue,
    isLiquid = isLiquid,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScreenerScreen(onOpenStock: (String) -> Unit, onBack: () -> Unit) {
    val c = appContainer()
    val rows by remember { c.analysis.observeAll().map { list -> list.map { it.toRow() } } }.collectAsStateWithLifecycle(emptyList())
    var criteria by remember { mutableStateOf(ScreenPreset.STRONG_UPTREND.criteria) }
    var preset by remember { mutableStateOf<ScreenPreset?>(ScreenPreset.STRONG_UPTREND) }
    var showFilters by remember { mutableStateOf(false) }
    val results = remember(rows, criteria) { Screener.run(rows, criteria) }
    fun update(transform: (ScreenCriteria) -> ScreenCriteria) {
        criteria = transform(criteria)
        preset = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screener_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.screener_back)) } },
                actions = { TextButton(onClick = { showFilters = !showFilters }) { Text(stringResource(if (showFilters) R.string.screener_hide_filters else R.string.screener_filters)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
        ) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScreenPreset.entries.forEach { p ->
                        FilterChip(selected = preset == p, onClick = { preset = p; criteria = p.criteria }, label = { Text(presetLabel(p)) })
                    }
                }
                MutedText(preset?.let { presetDescription(it) } ?: stringResource(R.string.screener_custom_scan))
            }
            if (showFilters) item { Filters(criteria, ::update) }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.screener_sort), style = MaterialTheme.typography.labelLarge)
                    ScreenSort.entries.forEach { s ->
                        FilterChip(
                            selected = criteria.sort == s,
                            onClick = { update { c0 -> if (c0.sort == s) c0.copy(descending = !c0.descending) else c0.copy(sort = s, descending = true) } },
                            label = { Text(if (criteria.sort == s) "${sortLabel(s)} ${if (criteria.descending) "↓" else "↑"}" else sortLabel(s)) },
                        )
                    }
                }
                MutedText(stringResource(R.string.screener_match_count, results.size, rows.size))
            }
            if (rows.isEmpty()) item { MutedText(stringResource(R.string.screener_empty)) }
            items(results, key = { it.symbol }) { r ->
                StockRow(
                    symbol = r.symbol,
                    name = r.name,
                    price = r.close,
                    changePct = r.changePct,
                    subtitle = listOfNotNull(
                        r.sector,
                        r.rsi14?.let { "RSI ${it.toInt()}" },
                        r.return3mPct?.let { "3M ${Fmt.pct(it)}" },
                        r.patterns.firstOrNull(),
                    ).joinToString(" · "),
                    trailing = { StanceBadge(r.stance, r.score) },
                    onClick = { onOpenStock(r.symbol) },
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Filters(c: ScreenCriteria, update: ((ScreenCriteria) -> ScreenCriteria) -> Unit) {
    SectionCard(stringResource(R.string.screener_filters)) {
        Text(stringResource(R.string.screener_sectors), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sectors.ALL.forEach { s ->
                FilterChip(selected = s in c.sectors, onClick = { update { it.copy(sectors = if (s in it.sectors) it.sectors - s else it.sectors + s) } }, label = { Text(s) })
            }
        }
        Text(stringResource(R.string.screener_rating), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Stance.entries.forEach { s ->
                FilterChip(selected = s in c.stances, onClick = { update { it.copy(stances = if (s in it.stances) it.stances - s else it.stances + s) } }, label = { Text(stanceLabel(s)) })
            }
        }
        Text(stringResource(R.string.screener_trend), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TriChip(stringResource(R.string.screener_above_50_day), c.aboveSma50) { v -> update { it.copy(aboveSma50 = v) } }
            TriChip(stringResource(R.string.screener_above_200_day), c.aboveSma200) { v -> update { it.copy(aboveSma200 = v) } }
        }
        Text(stringResource(R.string.screener_patterns), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PatternFilter.entries.forEach { p ->
                FilterChip(selected = c.patternFilter == p, onClick = { update { it.copy(patternFilter = p) } }, label = { Text(patternFilterLabel(p)) })
            }
        }
        OutlinedTextField(c.patternName, { v -> update { it.copy(patternName = v) } }, label = { Text(stringResource(R.string.screener_pattern_name_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberBox(stringResource(R.string.screener_min_score), c.minScore?.toString(), Modifier.weight(1f)) { v -> update { it.copy(minScore = v?.toInt()) } }
            NumberBox(stringResource(R.string.screener_max_score), c.maxScore?.toString(), Modifier.weight(1f)) { v -> update { it.copy(maxScore = v?.toInt()) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberBox(stringResource(R.string.screener_min_rsi), c.minRsi?.let { Fmt.num(it, 0) }, Modifier.weight(1f)) { v -> update { it.copy(minRsi = v) } }
            NumberBox(stringResource(R.string.screener_max_rsi), c.maxRsi?.let { Fmt.num(it, 0) }, Modifier.weight(1f)) { v -> update { it.copy(maxRsi = v) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberBox(stringResource(R.string.screener_min_volume_ratio), c.minVolumeRatio?.let { Fmt.num(it, 1) }, Modifier.weight(1f)) { v -> update { it.copy(minVolumeRatio = v) } }
            NumberBox(stringResource(R.string.screener_min_return_3m), c.minReturn3m?.let { Fmt.num(it, 0) }, Modifier.weight(1f)) { v -> update { it.copy(minReturn3m = v) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.screener_liquid_only), style = MaterialTheme.typography.bodyLarge)
                MutedText(stringResource(R.string.screener_liquid_only_desc))
            }
            Switch(checked = c.liquidOnly, onCheckedChange = { on -> update { it.copy(liquidOnly = on) } })
        }
        TextButton(onClick = { update { ScreenCriteria() } }) { Text(stringResource(R.string.screener_clear_filters)) }
        MutedText(stringResource(R.string.screener_fundamentals_note))
    }
}

/** A chip that cycles: any → yes → no → any. */
@Composable
private fun TriChip(label: String, value: Boolean?, onChange: (Boolean?) -> Unit) {
    FilterChip(
        selected = value != null,
        onClick = { onChange(when (value) { null -> true; true -> false; false -> null }) },
        label = {
            Text(
                when (value) {
                    null -> label
                    true -> stringResource(R.string.screener_tri_yes, label)
                    false -> stringResource(R.string.screener_tri_no, label)
                },
            )
        },
    )
}

/** A number field that keeps its own text so partial input like "-" or "1." isn't lost while typing. */
@Composable
private fun NumberBox(label: String, value: String?, modifier: Modifier, onChange: (Double?) -> Unit) {
    // Reset only when the filter is cleared, not on every keystroke's reformatted value.
    var text by remember(value == null) { mutableStateOf(value.orEmpty()) }
    OutlinedTextField(
        text,
        { t ->
            text = t
            if (t.isBlank()) onChange(null) else t.toDoubleOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}
