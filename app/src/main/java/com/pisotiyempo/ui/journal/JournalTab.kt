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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                SectionCard("AI coaching (Claude)") {
                    when {
                        reviewing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.padding(4.dp))
                            MutedText("Reading your journal…")
                        }
                        review != null -> ReviewContent(review!!)
                        else -> {
                            MutedText("Get personal coaching on your habits, based on your notes and closed trades. One review a day.")
                            Button(enabled = entries.isNotEmpty() || closed.isNotEmpty(), onClick = { confirmSend = true }) { Text("Get AI coaching") }
                        }
                    }
                    reviewError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { Text("Entries", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (entries.isEmpty()) {
                item { MutedText("Write a note each time you buy or sell: why, how you felt, and what you'd do differently. Patterns show up after a few weeks.") }
            }
            items(entries, key = { it.id }) { e ->
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${e.symbol} · ${e.setup}", fontWeight = FontWeight.SemiBold)
                            MutedText("${e.date} · felt ${e.emotion.lowercase()}" + (e.rating?.let { " · plan followed $it/5" } ?: ""))
                        }
                        IconButton(onClick = { scope.launch { c.portfolio.deleteJournal(e.id) } }) { Icon(Icons.Filled.Delete, "Delete entry") }
                    }
                    if (e.reason.isNotBlank()) Text(e.reason, style = MaterialTheme.typography.bodyMedium)
                    val mistakes = e.mistakes.lines().filter { it.isNotBlank() }
                    if (mistakes.isNotEmpty()) MutedText("Mistakes: ${mistakes.joinToString()}")
                    if (e.lesson.isNotBlank()) Text("Lesson: ${e.lesson}", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("New entry") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        )
    }

    if (adding) JournalEntryDialog(onDismiss = { adding = false })
    if (confirmSend) {
        AlertDialog(
            onDismissRequest = { confirmSend = false },
            title = { Text("Send your journal to Claude?") },
            text = {
                Text(
                    "Your last 40 journal notes (including what you wrote) and your closed-trade results (symbol, date, % gain or loss) " +
                        "will be sent to our server and to Anthropic's Claude to write your review. Share quantities and peso amounts are not sent.",
                )
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
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { confirmSend = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InsightsCard(i: Insights) {
    SectionCard("Your trading habits") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledValue("Closed trades", i.closedTrades.toString())
            LabeledValue("Win rate", i.winRate?.let { Fmt.pct(it * 100, signed = false) } ?: "—")
            LabeledValue("Avg win", i.avgWinPct?.let { Fmt.pct(it) } ?: "—", valueColor = changeColor(1.0))
            LabeledValue("Avg loss", i.avgLossPct?.let { Fmt.pct(it) } ?: "—", valueColor = changeColor(-1.0))
        }
        if (i.observations.isNotEmpty()) Bullets(i.observations)
        GroupTable("By setup", i.bySetup)
        GroupTable("By emotion", i.byEmotion)
        MutedText("Calculated on your phone from real (not practice) trades and your notes.")
    }
}

@Composable
private fun GroupTable(title: String, rows: List<GroupStat>) {
    if (rows.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelLarge)
    rows.take(6).forEach { g ->
        Row {
            Text(g.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text("${g.trades}×", Modifier.weight(0.3f), style = MaterialTheme.typography.bodySmall)
            Text("${(g.winRate * 100).toInt()}% won", Modifier.weight(0.5f), style = MaterialTheme.typography.bodySmall, color = changeColor(g.winRate - 0.5))
            Text(Fmt.pct(g.avgReturnPct), Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall, color = changeColor(g.avgReturnPct))
        }
    }
}

@Composable
private fun ReviewContent(r: JournalReviewDto) {
    Text(r.summary, style = MaterialTheme.typography.bodyMedium)
    if (r.strengths.isNotEmpty()) { Text("What's working", style = MaterialTheme.typography.labelLarge, color = changeColor(1.0)); Bullets(r.strengths) }
    if (r.recurringMistakes.isNotEmpty()) { Text("Recurring mistakes", style = MaterialTheme.typography.labelLarge, color = changeColor(-1.0)); Bullets(r.recurringMistakes) }
    if (r.suggestions.isNotEmpty()) { Text("Try this", style = MaterialTheme.typography.labelLarge); Bullets(r.suggestions) }
    Text("Focus next: ${r.focusNextWeek}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    MutedText("Written by Claude from your journal. Coaching, not investment advice.")
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Journal entry") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(symbol, { symbol = it.uppercase().trim() }, label = { Text("Symbol") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                    OutlinedTextField(date, { date = it }, label = { Text("Date") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Text("Setup", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    JournalTags.SETUPS.forEach { s -> FilterChip(selected = setup == s, onClick = { setup = s }, label = { Text(s) }) }
                }
                Text("How did you feel?", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    JournalTags.EMOTIONS.forEach { e -> FilterChip(selected = emotion == e, onClick = { emotion = e }, label = { Text(e) }) }
                }
                Text("Mistakes (if any)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    JournalTags.MISTAKES.forEach { m ->
                        FilterChip(selected = m in mistakes, onClick = { mistakes = if (m in mistakes) mistakes - m else mistakes + m }, label = { Text(m) })
                    }
                }
                Text("How well did you follow your plan?", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { r -> FilterChip(selected = rating == r, onClick = { rating = if (rating == r) null else r }, label = { Text("$r") }) }
                }
                OutlinedTextField(reason, { reason = it.take(400) }, label = { Text("Why did you take this trade?") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(lesson, { lesson = it.take(400) }, label = { Text("Lesson or what you'd do differently") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = try { LocalDate.parse(date) } catch (e: DateTimeParseException) { null }
                when {
                    !Regex("^[A-Z0-9]{1,8}$").matches(symbol) -> error = "Enter a valid PSE symbol."
                    parsed == null -> error = "Enter the date as YYYY-MM-DD."
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
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
