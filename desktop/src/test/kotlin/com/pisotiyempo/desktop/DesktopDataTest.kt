package com.pisotiyempo.desktop

import com.pisotiyempo.desktop.data.Db
import com.pisotiyempo.desktop.data.DesktopPortfolio
import com.pisotiyempo.desktop.data.DesktopSyncStore
import com.pisotiyempo.domain.portfolio.Side
import com.pisotiyempo.sync.SyncEnvelope
import com.pisotiyempo.sync.TradeSync
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

class DesktopDataTest {
    private fun tempDb() = Db(File(Files.createTempDirectory("piso").toFile(), "test.db"))

    @Test
    fun localEditsBecomeSyncRecordsAndDeletesBecomeTombstones() = runBlocking {
        val db = tempDb()
        var changes = 0
        val portfolio = DesktopPortfolio(db) { changes++ }
        val store = DesktopSyncStore(db)

        portfolio.addTrade("JFC", Side.BUY, 100, 143.5, 25.0, LocalDate.of(2026, 11, 3))
        portfolio.setWatched("BDO", true)
        portfolio.addJournal("JFC", LocalDate.of(2026, 11, 3), "Breakout", "Calm", listOf("No stop loss"), 4, "Broke resistance", "")
        assertEquals(3, changes)

        val out = store.localChangesSince(0)
        assertEquals(setOf(SyncEnvelope.TRADE, SyncEnvelope.WATCH, SyncEnvelope.JOURNAL), out.map { it.type }.toSet())

        val uid = portfolio.trades().single().uid
        portfolio.deleteTrade(uid)
        val tomb = store.localChangesSince(0).single { it.uid == uid }
        assertTrue(tomb.deleted)
    }

    @Test
    fun applyKeepsTheNewestCopyAndRespectsTombstones() = runBlocking {
        val db = tempDb()
        val store = DesktopSyncStore(db)
        val portfolio = DesktopPortfolio(db) {}
        fun trade(at: Long, shares: Long) =
            SyncEnvelope(SyncEnvelope.TRADE, "t1", at, payload = Json.encodeToJsonElement(TradeSync("JFC", "BUY", shares, 140.0, 20.0, "2026-11-01")))

        store.apply(trade(100, 10))
        store.apply(trade(50, 99)) // older: ignored
        assertEquals(10L, portfolio.trades().single().shares)
        store.apply(trade(200, 20))
        assertEquals(20L, portfolio.trades().single().shares)

        store.apply(SyncEnvelope(SyncEnvelope.TRADE, "t1", 300, deleted = true))
        assertTrue(portfolio.trades().isEmpty())
        store.apply(trade(250, 30)) // older than the deletion: must not come back
        assertTrue(portfolio.trades().isEmpty())
    }
}
