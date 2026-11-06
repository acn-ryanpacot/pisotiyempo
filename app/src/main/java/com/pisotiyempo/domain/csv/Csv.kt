package com.pisotiyempo.domain.csv

import com.pisotiyempo.domain.ph.FeeCalculator
import com.pisotiyempo.domain.ph.FeeSchedule
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** RFC 4180 CSV: quoted fields, embedded commas, quotes and newlines, CRLF or LF. */
object Csv {
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = if (text.startsWith("﻿")) 1 else 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                !quoted && ch == ',' -> { row += field.toString(); field.clear() }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += field.toString(); field.clear()
                    if (row.any { it.isNotBlank() }) rows += row
                    row = mutableListOf()
                }
                else -> field.append(ch)
            }
            i++
        }
        row += field.toString()
        if (row.any { it.isNotBlank() }) rows += row
        return rows
    }

    fun write(rows: List<List<String>>): String = rows.joinToString("\r\n", postfix = "\r\n") { r -> r.joinToString(",") { escape(it) } }

    private fun escape(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}

data class CsvTrade(
    val date: LocalDate,
    val side: String,
    val symbol: String,
    val shares: Long,
    val price: Double,
    val fees: Double,
    val note: String,
    /** True when the file had no fee column and fees were estimated from the fee settings. */
    val feesEstimated: Boolean,
)

data class CsvJournal(
    val date: LocalDate,
    val symbol: String,
    val setup: String,
    val emotion: String,
    val mistakes: List<String>,
    val rating: Int?,
    val reason: String,
    val lesson: String,
)

data class ImportResult<T>(val rows: List<T>, val errors: List<String>)

/** Reads trade files exported by this app or by brokers, matching common column names. */
object TradeCsv {
    val HEADER = listOf("date", "side", "symbol", "shares", "price", "fees", "note")

    private val ALIASES = mapOf(
        "date" to listOf("date", "trade date", "transaction date", "txn date", "posting date"),
        "side" to listOf("side", "action", "type", "transaction", "transaction type", "buy/sell", "b/s"),
        "symbol" to listOf("symbol", "stock", "stock code", "ticker", "security", "code", "stock symbol"),
        "shares" to listOf("shares", "quantity", "qty", "volume", "no. of shares", "number of shares"),
        "price" to listOf("price", "avg price", "average price", "unit price", "executed price", "price per share"),
        "fees" to listOf("fees", "charges", "total charges", "fees and taxes", "total fees"),
        "note" to listOf("note", "notes", "remarks"),
    )

    fun export(trades: List<CsvTrade>): String =
        Csv.write(listOf(HEADER) + trades.map { listOf(it.date.toString(), it.side, it.symbol, it.shares.toString(), plain(it.price), plain(it.fees), it.note) })

    fun import(text: String, fees: FeeSchedule): ImportResult<CsvTrade> {
        val rows = Csv.parse(text)
        if (rows.isEmpty()) return ImportResult(emptyList(), listOf("The file is empty."))
        val cols = columns(rows.first(), ALIASES)
        val missing = listOf("date", "side", "symbol", "shares", "price").filter { it !in cols }
        if (missing.isNotEmpty()) {
            return ImportResult(emptyList(), listOf("Couldn't find these columns: ${missing.joinToString()}. Expected a header like: ${HEADER.joinToString(",")}"))
        }
        val out = mutableListOf<CsvTrade>()
        val errors = mutableListOf<String>()
        rows.drop(1).forEachIndexed { idx, r ->
            val line = idx + 2
            fun cell(name: String) = cols[name]?.let { r.getOrNull(it) }?.trim().orEmpty()
            val date = parseDate(cell("date"))
            val side = parseSide(cell("side"))
            val symbol = cell("symbol").uppercase().takeIf { Regex("^[A-Z0-9]{1,8}$").matches(it) }
            val shares = number(cell("shares"))?.toLong()?.takeIf { it > 0 }
            val price = number(cell("price"))?.takeIf { it > 0 }
            val problem = when {
                date == null -> "date \"${cell("date")}\""
                side == null -> "buy/sell \"${cell("side")}\""
                symbol == null -> "symbol \"${cell("symbol")}\""
                shares == null -> "shares \"${cell("shares")}\""
                price == null -> "price \"${cell("price")}\""
                else -> null
            }
            if (problem != null) {
                errors += "Line $line: couldn't read $problem."
                return@forEachIndexed
            }
            val fileFees = if ("fees" in cols) number(cell("fees")) else null
            val estimated = fileFees == null
            val fee = fileFees ?: (if (side == "BUY") FeeCalculator.buy(shares!!, price!!, fees) else FeeCalculator.sell(shares!!, price!!, fees)).totalFees
            out += CsvTrade(date!!, side!!, symbol!!, shares!!, price!!, kotlin.math.abs(fee), cell("note"), estimated)
        }
        return ImportResult(out, errors)
    }

    /** Accepts 2026-03-05, 03/05/2026 (month first, as most PH broker statements use), Mar 5, 2026 and 05-Mar-2026. */
    fun parseDate(s: String): LocalDate? {
        // Also try without a trailing time, e.g. "2026-03-05 10:30".
        for (t in listOf(s.trim(), s.trim().substringBefore(' '))) {
            for (f in DATE_FORMATS) {
                runCatching { return LocalDate.parse(t, f) }
            }
        }
        return null
    }

    private val DATE_FORMATS: List<DateTimeFormatter> = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMM d, yyyy").toFormatter(Locale.US),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-MMM-yyyy").toFormatter(Locale.US),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM yyyy").toFormatter(Locale.US),
    )

    private fun parseSide(s: String): String? = when (s.trim().uppercase()) {
        "BUY", "B", "BOUGHT", "PURCHASE" -> "BUY"
        "SELL", "S", "SOLD", "SALE" -> "SELL"
        else -> null
    }
}

object JournalCsv {
    val HEADER = listOf("date", "symbol", "setup", "emotion", "mistakes", "rating", "reason", "lesson")

    fun export(entries: List<CsvJournal>): String = Csv.write(
        listOf(HEADER) + entries.map {
            listOf(it.date.toString(), it.symbol, it.setup, it.emotion, it.mistakes.joinToString("; "), it.rating?.toString().orEmpty(), it.reason, it.lesson)
        },
    )

    fun import(text: String): ImportResult<CsvJournal> {
        val rows = Csv.parse(text)
        if (rows.isEmpty()) return ImportResult(emptyList(), listOf("The file is empty."))
        val cols = columns(rows.first(), HEADER.associateWith { listOf(it) })
        if ("date" !in cols || "symbol" !in cols) return ImportResult(emptyList(), listOf("Expected a header like: ${HEADER.joinToString(",")}"))
        val out = mutableListOf<CsvJournal>()
        val errors = mutableListOf<String>()
        rows.drop(1).forEachIndexed { idx, r ->
            fun cell(name: String) = cols[name]?.let { r.getOrNull(it) }?.trim().orEmpty()
            val date = TradeCsv.parseDate(cell("date"))
            val symbol = cell("symbol").uppercase()
            if (date == null || !Regex("^[A-Z0-9]{1,8}$").matches(symbol)) {
                errors += "Line ${idx + 2}: couldn't read the date or symbol."
                return@forEachIndexed
            }
            out += CsvJournal(
                date, symbol, cell("setup").ifBlank { "Other" }, cell("emotion").ifBlank { "Calm" },
                cell("mistakes").split(';').map { it.trim() }.filter { it.isNotEmpty() },
                cell("rating").toIntOrNull()?.takeIf { it in 1..5 }, cell("reason").take(400), cell("lesson").take(400),
            )
        }
        return ImportResult(out, errors)
    }
}

/** Maps each logical column to its index in [header], matching any alias case-insensitively. */
private fun columns(header: List<String>, aliases: Map<String, List<String>>): Map<String, Int> {
    val normalized = header.map { it.trim().lowercase() }
    return aliases.mapNotNull { (name, options) ->
        normalized.indexOfFirst { it in options }.takeIf { it >= 0 }?.let { name to it }
    }.toMap()
}

/** Parses "1,234.50", "₱1,234.50", "PHP 1234.5" and "(12.50)" (accounting negative). */
private fun number(s: String): Double? {
    val t = s.trim()
    val negative = t.startsWith("(") && t.endsWith(")")
    val digits = t.replace(Regex("[^0-9.\\-]"), "")
    return digits.toDoubleOrNull()?.let { if (negative) -it else it }
}

private fun plain(v: Double): String = java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
