package com.pisotiyempo.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pisotiyempo.AppContainer
import com.pisotiyempo.data.local.JournalEntity
import com.pisotiyempo.data.local.TradeEntity
import com.pisotiyempo.data.repo.MANILA
import com.pisotiyempo.domain.csv.CsvJournal
import com.pisotiyempo.domain.csv.CsvTrade
import com.pisotiyempo.domain.csv.JournalCsv
import com.pisotiyempo.domain.csv.TradeCsv
import com.pisotiyempo.ui.common.MutedText
import com.pisotiyempo.ui.common.SectionCard
import com.pisotiyempo.ui.common.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private enum class ExportKind(val label: String, val fileName: String) {
    REAL("My trades", "piso-tiyempo-trades"),
    PRACTICE("Practice trades", "piso-tiyempo-practice-trades"),
    JOURNAL("Journal", "piso-tiyempo-journal"),
}

/** What an import found, waiting for the user to confirm. */
private sealed interface PendingImport {
    val summary: String
    data class Trades(val rows: List<TradeEntity>, override val summary: String) : PendingImport
    data class Journal(val rows: List<JournalEntity>, override val summary: String) : PendingImport
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BackupCard() {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf<ExportKind?>(null) }
    var pending by remember { mutableStateOf<PendingImport?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val kind = exporting
        if (uri != null && kind != null) scope.launch { message = runCatching { export(context, c, kind, uri) }.getOrElse { "Export failed: ${it.message}" } }
        exporting = null
    }
    val importTrades = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { runCatching { pending = prepareTrades(context, c, uri) }.onFailure { message = "Couldn't read the file: ${it.message}" } }
    }
    val importJournal = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { runCatching { pending = prepareJournal(context, c, uri) }.onFailure { message = "Couldn't read the file: ${it.message}" } }
    }

    SectionCard("Backup, export and import") {
        MutedText("Your data lives only on this phone. Export CSV files to back it up or open it in a spreadsheet.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExportKind.entries.forEach { k ->
                OutlinedButton(onClick = {
                    exporting = k
                    exportLauncher.launch("${k.fileName}-${LocalDate.now(MANILA)}.csv")
                }) { Text("Export ${k.label.lowercase()}") }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { importTrades.launch(arrayOf("text/*", "application/vnd.ms-excel", "application/octet-stream")) }) { Text("Import trades") }
            OutlinedButton(onClick = { importJournal.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("Import journal") }
        }
        MutedText(
            "Trade files need columns for date, buy/sell, symbol, shares and price (fees optional). Most broker CSV exports work; " +
                "if yours doesn't, save it with the header: ${TradeCsv.HEADER.joinToString(",")}",
        )
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Import?") },
            text = { Text(p.summary) },
            confirmButton = {
                TextButton(enabled = when (p) { is PendingImport.Trades -> p.rows.isNotEmpty(); is PendingImport.Journal -> p.rows.isNotEmpty() }, onClick = {
                    scope.launch {
                        when (p) {
                            is PendingImport.Trades -> {
                                c.portfolio.addTrades(p.rows)
                                p.rows.map { it.symbol }.distinct().forEach { c.market.ensureHistory(it) }
                                message = "Imported ${p.rows.size} trades."
                            }
                            is PendingImport.Journal -> {
                                c.portfolio.addJournalEntries(p.rows)
                                message = "Imported ${p.rows.size} journal entries."
                            }
                        }
                    }
                    pending = null
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

private suspend fun readText(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("empty file")
}

private suspend fun export(context: Context, c: AppContainer, kind: ExportKind, uri: Uri): String {
    val (csv, count) = when (kind) {
        ExportKind.REAL, ExportKind.PRACTICE -> {
            val account = if (kind == ExportKind.REAL) TradeEntity.ACCOUNT_REAL else TradeEntity.ACCOUNT_PAPER
            val trades = c.portfolio.allTrades(account).sortedWith(compareBy({ it.date }, { it.id }))
                .map { CsvTrade(LocalDate.parse(it.date), it.side, it.symbol, it.shares, it.price, it.fees, it.note, false) }
            TradeCsv.export(trades) to trades.size
        }
        ExportKind.JOURNAL -> {
            val entries = c.portfolio.journalNow().sortedBy { it.date }.map {
                CsvJournal(LocalDate.parse(it.date), it.symbol, it.setup, it.emotion, it.mistakes.lines().filter { m -> m.isNotBlank() }, it.rating, it.reason, it.lesson)
            }
            JournalCsv.export(entries) to entries.size
        }
    }
    withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) } ?: error("can't write the file")
    }
    return "Exported $count ${kind.label.lowercase()}."
}

private suspend fun prepareTrades(context: Context, c: AppContainer, uri: Uri): PendingImport.Trades {
    val result = TradeCsv.import(readText(context, uri), c.settings.current().fees)
    // Skip rows already recorded, so importing the same statement twice doesn't double the portfolio.
    val existing = c.portfolio.allTrades(TradeEntity.ACCOUNT_REAL).map { listOf(it.date, it.side, it.symbol, it.shares.toString(), it.price.toString()) }.toSet()
    val fresh = result.rows.filter { listOf(it.date.toString(), it.side, it.symbol, it.shares.toString(), it.price.toString()) !in existing }
    val rows = fresh.map { TradeEntity(symbol = it.symbol, side = it.side, shares = it.shares, price = it.price, fees = it.fees, date = it.date.toString(), note = it.note) }
    val summary = buildString {
        append("${rows.size} new trades to add to My portfolio.")
        val dupes = result.rows.size - fresh.size
        if (dupes > 0) append(" $dupes already recorded will be skipped.")
        val estimated = fresh.count { it.feesEstimated }
        if (estimated > 0) append(" Fees for $estimated trades weren't in the file and were estimated from your fee settings.")
        if (result.errors.isNotEmpty()) append("\n\n${result.errors.size} rows couldn't be read:\n" + result.errors.take(5).joinToString("\n"))
    }
    return PendingImport.Trades(rows, summary)
}

private suspend fun prepareJournal(context: Context, c: AppContainer, uri: Uri): PendingImport.Journal {
    val result = JournalCsv.import(readText(context, uri))
    val now = System.currentTimeMillis()
    val rows = result.rows.map {
        JournalEntity(
            symbol = it.symbol, date = it.date.toString(), setup = it.setup, emotion = it.emotion, mistakes = it.mistakes.joinToString("\n"),
            lesson = it.lesson, rating = it.rating, reason = it.reason, createdAt = now,
        )
    }
    val summary = "${rows.size} journal entries to add." +
        if (result.errors.isNotEmpty()) "\n\n${result.errors.size} rows couldn't be read:\n" + result.errors.take(5).joinToString("\n") else ""
    return PendingImport.Journal(rows, summary)
}
