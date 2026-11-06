package com.pisotiyempo.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisotiyempo.R
import com.pisotiyempo.data.local.JournalEntity
import com.pisotiyempo.data.remote.ClosedTradeDto
import com.pisotiyempo.data.remote.JournalEntryDto
import com.pisotiyempo.data.remote.JournalReviewDto
import com.pisotiyempo.data.repo.MANILA
import com.pisotiyempo.domain.journal.GroupStat
import com.pisotiyempo.domain.journal.Insights
import com.pisotiyempo.domain.journal.JournalInsights
import com.pisotiyempo.domain.journal.JournalNote
import com.pisotiyempo.domain.journal.JournalTags
import com.pisotiyempo.ui.common.Bullets
import com.pisotiyempo.ui.common.Fmt
import com.pisotiyempo.ui.common.LabeledValue
import com.pisotiyempo.ui.common.MutedText
import com.pisotiyempo.ui.common.SectionCard
import com.pisotiyempo.ui.common.appContainer
import com.pisotiyempo.ui.common.changeColor
import com.pisotiyempo.ui.common.journalTagLabel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeParseException

private fun JournalEntity.toNote() = JournalNote(
    symbol, runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.MIN), setup, emotion,
    mistakes.lines().filter { it.isNotBlank() }, rating,
)

@Composable
fun JournalTab(contentPadding: PaddingValues) {
    val c = appContainer()
    val entries by remember { c.portfolio.journal() }.collectAsStateWithLifecycle(emptyList())
    val summary by remember { c.portfolio.summary() }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<JournalReviewDto?>(null) }
    var reviewError by remember { mutableStateOf<String?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    var confirmSend by remember { mutableStateOf(false) }
    val closed = summary?.closedTrades.orEmpty()
    val insights = remember(entries, closed) { JournalInsights.compute(entries.map { it.toNote() }, closed) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = contentPadding.calculateBottomPadding() + 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { InsightsCard(insights) }
            item {
                SectionCard(stringResource(R.string.journal_ai_coaching)) {
                    when {
                        reviewing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.padding(4.dp))
                            MutedText(stringResource(R.string.journal_reading))
                        }
                        review != null -> ReviewContent(review!!)
                        else -> {
                            MutedText(stringResource(R.string.journal_coaching_intro))
                            Button(enabled = entries.isNotEmpty() || closed.isNotEmpty(), onClick = { confirmSend = true }) { Text(stringResource(R.string.journal_get_coaching)) }
                        }
                    }
                    reviewError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { Text(stringResource(R.string.journal_entries), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (entries.isEmpty()) {
                item { MutedText(stringResource(R.string.journal_empty)) }
            }
            items(entries, key = { it.id }) { e ->
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${e.symbol} · ${journalTagLabel(e.setup)}", fontWeight = FontWeight.SemiBold)
                            MutedText(stringResource(R.string.journal_entry_felt, e.date, journalTagLabel(e.emotion).lowercase()) + (e.rating?.let { " · " + stringResource(R.string.journal_entry_plan_followed, it) } ?: ""))
                        }
                        IconButton(onClick = { scope.launch { c.portfolio.deleteJournal(e.id) } }) { Icon(Icons.Filled.Delete, stringResource(R.string.journal_delete_entry)) }
                    }
                    if (e.reason.isNotBlank()) Text(e.reason, style = MaterialTheme.typography.bodyMedium)
                    val mistakes = e.mistakes.lines().filter { it.isNotBlank() }
                    if (mistakes.isNotEmpty()) MutedText(stringResource(R.string.journal_mistakes_line, mistakes.map { journalTagLabel(it) }.joinToString()))
                    if (e.lesson.isNotBlank()) Text(stringResource(R.string.journal_lesson_line, e.lesson), style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text(stringResource(R.string.journal_new_entry)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        )
    }

    if (adding) JournalEntryDialog(onDismiss = { adding = false })
    if (confirmSend) {
        AlertDialog(
            onDismissRequest = { confirmSend = false },
            title = { Text(stringResource(R.string.journal_consent_title)) },
            text = {
                Text(stringResource(R.string.journal_consent_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSend = false
                    reviewing = true
                    reviewError = null
                    scope.launch {
                        val payload = entries.take(40).map {
                            JournalEntryDto(it.date, it.symbol, it.setup, it.emotion, it.mistakes.lines().filter { m -> m.isNotBlank() }, it.rating, it.reason, it.lesson)
                        }
                        val trades = closed.map { ClosedTradeDto(it.symbol, it.date.toString(), it.pnlPct, it.holdingDays) }
                        c.ai.journalReview(payload, trades)
                            .onSuccess { review = it }
                            .onFailure { reviewError = it.message }
                        reviewing = false
                    }
                }) { Text(stringResource(R.string.journal_send)) }
            },
            dismissButton = { TextButton(onClick = { confirmSend = false }) { Text(stringResource(R.string.journal_cancel)) } },
        )
    }
}

@Composable
private fun InsightsCard(i: Insights) {
    SectionCard(stringResource(R.string.journal_habits)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledValue(stringResource(R.string.journal_closed_trades), i.closedTrades.toString())
            LabeledValue(stringResource(R.string.journal_win_rate), i.winRate?.let { Fmt.pct(it * 100, signed = false) } ?: "—")
            LabeledValue(stringResource(R.string.journal_avg_win), i.avgWinPct?.let { Fmt.pct(it) } ?: "—", valueColor = changeColor(1.0))
            LabeledValue(stringResource(R.string.journal_avg_loss), i.avgLossPct?.let { Fmt.pct(it) } ?: "—", valueColor = changeColor(-1.0))
        }
        if (i.observations.isNotEmpty()) Bullets(i.observations)
        GroupTable(stringResource(R.string.journal_by_setup), i.bySetup)
        GroupTable(stringResource(R.string.journal_by_emotion), i.byEmotion)
        MutedText(stringResource(R.string.journal_habits_note))
    }
}

@Composable
private fun GroupTable(title: String, rows: List<GroupStat>) {
    if (rows.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelLarge)
    rows.take(6).forEach { g ->
        Row {
            Text(journalTagLabel(g.label), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text("${g.trades}×", Modifier.weight(0.3f), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.journal_group_won, (g.winRate * 100).toInt()), Modifier.weight(0.5f), style = MaterialTheme.typography.bodySmall, color = changeColor(g.winRate - 0.5))
            Text(Fmt.pct(g.avgReturnPct), Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall, color = changeColor(g.avgReturnPct))
        }
    }
}

@Composable
private fun ReviewContent(r: JournalReviewDto) {
    Text(r.summary, style = MaterialTheme.typography.bodyMedium)
    if (r.strengths.isNotEmpty()) { Text(stringResource(R.string.journal_review_strengths), style = MaterialTheme.typography.labelLarge, color = changeColor(1.0)); Bullets(r.strengths) }
    if (r.recurringMistakes.isNotEmpty()) { Text(stringResource(R.string.journal_review_mistakes), style = MaterialTheme.typography.labelLarge, color = changeColor(-1.0)); Bullets(r.recurringMistakes) }
    if (r.suggestions.isNotEmpty()) { Text(stringResource(R.string.journal_review_suggestions), style = MaterialTheme.typography.labelLarge); Bullets(r.suggestions) }
    Text(stringResource(R.string.journal_review_focus, r.focusNextWeek), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    MutedText(stringResource(R.string.journal_review_footer))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JournalEntryDialog(initialSymbol: String = "", onDismiss: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var symbol by remember { mutableStateOf(initialSymbol) }
    var date by remember { mutableStateOf(LocalDate.now(MANILA).toString()) }
    var setup by remember { mutableStateOf(JournalTags.SETUPS.first()) }
    var emotion by remember { mutableStateOf(JournalTags.EMOTIONS.first()) }
    var mistakes by remember { mutableStateOf(emptySet<String>()) }
    var rating by remember { mutableStateOf<Int?>(null) }
    var reason by remember { mutableStateOf("") }
    var lesson by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val errSymbol = stringResource(R.string.journal_error_symbol)
    val errDate = stringResource(R.string.journal_error_date)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.journal_entry_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(symbol, { symbol = it.uppercase().trim() }, label = { Text(stringResource(R.string.journal_symbol)) }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                    OutlinedTextField(date, { date = it }, label = { Text(stringResource(R.string.journal_date)) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Text(stringResource(R.string.journal_setup), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    JournalTags.SETUPS.forEach { s -> FilterChip(selected = setup == s, onClick = { setup = s }, label = { Text(journalTagLabel(s)) }) }
                }
                Text(stringResource(R.string.journal_feel), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    JournalTags.EMOTIONS.forEach { e -> FilterChip(selected = emotion == e, onClick = { emotion = e }, label = { Text(journalTagLabel(e)) }) }
                }
                Text(stringResource(R.string.journal_mistakes_if_any), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    JournalTags.MISTAKES.forEach { m ->
                        FilterChip(selected = m in mistakes, onClick = { mistakes = if (m in mistakes) mistakes - m else mistakes + m }, label = { Text(journalTagLabel(m)) })
                    }
                }
                Text(stringResource(R.string.journal_plan_rating), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { r -> FilterChip(selected = rating == r, onClick = { rating = if (rating == r) null else r }, label = { Text("$r") }) }
                }
                OutlinedTextField(reason, { reason = it.take(400) }, label = { Text(stringResource(R.string.journal_reason)) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(lesson, { lesson = it.take(400) }, label = { Text(stringResource(R.string.journal_lesson)) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = try { LocalDate.parse(date) } catch (e: DateTimeParseException) { null }
                when {
                    !Regex("^[A-Z0-9]{1,8}$").matches(symbol) -> error = errSymbol
                    parsed == null -> error = errDate
                    else -> {
                        val entry = JournalEntity(
                            symbol = symbol, date = parsed.toString(), setup = setup, emotion = emotion,
                            mistakes = mistakes.joinToString("\n"), lesson = lesson.trim(), rating = rating, reason = reason.trim(),
                            createdAt = System.currentTimeMillis(),
                        )
                        scope.launch {
                            c.portfolio.addJournal(entry)
                            onDismiss()
                        }
                    }
                }
            }) { Text(stringResource(R.string.journal_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.journal_cancel)) } },
    )
}
