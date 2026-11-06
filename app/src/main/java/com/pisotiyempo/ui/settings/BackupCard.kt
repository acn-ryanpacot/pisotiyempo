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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pisotiyempo.AppContainer
import com.pisotiyempo.R
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

/** [buttonRes] is the export button's text; [exportedRes] the "Exported N …" message, which takes the count. */
private enum class ExportKind(val buttonRes: Int, val exportedRes: Int, val fileName: String) {
    REAL(R.string.backup_export_real, R.string.backup_exported_real, "piso-tiyempo-trades"),
    PRACTICE(R.string.backup_export_practice, R.string.backup_exported_practice, "piso-tiyempo-practice-trades"),
    JOURNAL(R.string.backup_export_journal, R.string.backup_exported_journal, "piso-tiyempo-journal"),
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
        if (uri != null && kind != null) scope.launch {
            message = runCatching { export(context, c, kind, uri) }.getOrElse { context.getString(R.string.backup_export_failed, it.message.toString()) }
        }
        exporting = null
    }
    val importTrades = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { pending = prepareTrades(context, c, uri) }.onFailure { message = context.getString(R.string.backup_read_failed, it.message.toString()) }
        }
    }
    val importJournal = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { pending = prepareJournal(context, c, uri) }.onFailure { message = context.getString(R.string.backup_read_failed, it.message.toString()) }
        }
    }

    SectionCard(stringResource(R.string.backup_title)) {
        MutedText(stringResource(R.string.backup_intro))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExportKind.entries.forEach { k ->
                OutlinedButton(onClick = {
                    exporting = k
                    exportLauncher.launch("${k.fileName}-${LocalDate.now(MANILA)}.csv")
                }) { Text(stringResource(k.buttonRes)) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { importTrades.launch(arrayOf("text/*", "application/vnd.ms-excel", "application/octet-stream")) }) { Text(stringResource(R.string.backup_import_trades)) }
            OutlinedButton(onClick = { importJournal.launch(arrayOf("text/*", "application/octet-stream")) }) { Text(stringResource(R.string.backup_import_journal)) }
        }
        MutedText(stringResource(R.string.backup_trade_format_help, TradeCsv.HEADER.joinToString(",")))
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.backup_import_confirm_title)) },
            text = { Text(p.summary) },
            confirmButton = {
                TextButton(enabled = when (p) { is PendingImport.Trades -> p.rows.isNotEmpty(); is PendingImport.Journal -> p.rows.isNotEmpty() }, onClick = {
                    scope.launch {
                        when (p) {
                            is PendingImport.Trades -> {
                                c.portfolio.addTrades(p.rows)
                                p.rows.map { it.symbol }.distinct().forEach { c.market.ensureHistory(it) }
                                message = context.getString(R.string.backup_imported_trades, p.rows.size)
                            }
                            is PendingImport.Journal -> {
                                c.portfolio.addJournalEntries(p.rows)
                                message = context.getString(R.string.backup_imported_journal, p.rows.size)
                            }
                        }
                    }
                    pending = null
                }) { Text(stringResource(R.string.backup_import)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.backup_cancel)) } },
        )
    }
}

private suspend fun readText(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error(context.getString(R.string.backup_error_empty_file))
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
        context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) } ?: error(context.getString(R.string.backup_error_cant_write))
    }
    return context.getString(kind.exportedRes, count)
}

private suspend fun prepareTrades(context: Context, c: AppContainer, uri: Uri): PendingImport.Trades {
    val result = TradeCsv.import(readText(context, uri), c.settings.current().fees)
    // Skip rows already recorded, so importing the same statement twice doesn't double the portfolio.
    val existing = c.portfolio.allTrades(TradeEntity.ACCOUNT_REAL).map { listOf(it.date, it.side, it.symbol, it.shares.toString(), it.price.toString()) }.toSet()
    val fresh = result.rows.filter { listOf(it.date.toString(), it.side, it.symbol, it.shares.toString(), it.price.toString()) !in existing }
    val rows = fresh.map { TradeEntity(symbol = it.symbol, side = it.side, shares = it.shares, price = it.price, fees = it.fees, date = it.date.toString(), note = it.note) }
    val summary = buildString {
        append(context.getString(R.string.backup_trades_to_add, rows.size))
        val dupes = result.rows.size - fresh.size
        if (dupes > 0) append(" " + context.getString(R.string.backup_trades_skipped, dupes))
        val estimated = fresh.count { it.feesEstimated }
        if (estimated > 0) append(" " + context.getString(R.string.backup_fees_estimated, estimated))
        if (result.errors.isNotEmpty()) {
            append("\n\n" + context.getString(R.string.backup_rows_unreadable, result.errors.size) + "\n" + result.errors.take(5).joinToString("\n"))
        }
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
    val summary = context.getString(R.string.backup_journal_to_add, rows.size) +
        if (result.errors.isNotEmpty()) "\n\n" + context.getString(R.string.backup_rows_unreadable, result.errors.size) + "\n" + result.errors.take(5).joinToString("\n") else ""
    return PendingImport.Journal(rows, summary)
}
