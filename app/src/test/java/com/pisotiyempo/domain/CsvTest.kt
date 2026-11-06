package com.pisotiyempo.domain

import com.pisotiyempo.domain.csv.Csv
import com.pisotiyempo.domain.csv.CsvJournal
import com.pisotiyempo.domain.csv.CsvTrade
import com.pisotiyempo.domain.csv.JournalCsv
import com.pisotiyempo.domain.csv.TradeCsv
import com.pisotiyempo.domain.ph.FeeCalculator
import com.pisotiyempo.domain.ph.FeeSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CsvTest {
    @Test
    fun parsesQuotesCommasAndNewlines() {
        val rows = Csv.parse("﻿a,b,c\r\n1,\"x, y\",\"say \"\"hi\"\"\"\n2,\"multi\nline\",\n\n")
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "x, y", "say \"hi\""), listOf("2", "multi\nline", "")), rows)
    }

    @Test
    fun tradesRoundTrip() {
        val trades = listOf(CsvTrade(LocalDate.of(2026, 3, 5), "BUY", "JFC", 100, 143.5, 25.4, "first, \"test\"", false))
        val back = TradeCsv.import(TradeCsv.export(trades), FeeSchedule())
        assertTrue(back.errors.isEmpty())
        assertEquals(trades, back.rows)
    }

    @Test
    fun readsBrokerStyleColumnsAndEstimatesMissingFees() {
        val text = "Trade Date,Action,Stock Code,Quantity,Price\n03/05/2026,Bought,bdo,\"1,000\",₱150.50\n\"Mar 6, 2026\",S,BPI,100,PHP 120\nbad,X,??,0,0\n"
        val r = TradeCsv.import(text, FeeSchedule())
        assertEquals(2, r.rows.size)
        assertEquals(1, r.errors.size)
        val bdo = r.rows[0]
        assertEquals(LocalDate.of(2026, 3, 5), bdo.date)
        assertEquals("BUY", bdo.side)
        assertEquals("BDO", bdo.symbol)
        assertEquals(1000L, bdo.shares)
        assertEquals(FeeCalculator.buy(1000, 150.5).totalFees, bdo.fees, 1e-9)
        assertTrue(bdo.feesEstimated)
        assertEquals("SELL", r.rows[1].side)
    }

    @Test
    fun reportsMissingColumns() {
        val r = TradeCsv.import("when,what\n1,2\n", FeeSchedule())
        assertTrue(r.rows.isEmpty())
        assertTrue(r.errors.single().startsWith("Couldn't find these columns"))
    }

    @Test
    fun journalRoundTrip() {
        val entries = listOf(CsvJournal(LocalDate.of(2026, 3, 5), "JFC", "Breakout", "FOMO", listOf("Chased the price", "No stop loss"), 2, "Saw it on, a forum", ""))
        val back = JournalCsv.import(JournalCsv.export(entries))
        assertTrue(back.errors.isEmpty())
        assertEquals(entries, back.rows)
    }
}
