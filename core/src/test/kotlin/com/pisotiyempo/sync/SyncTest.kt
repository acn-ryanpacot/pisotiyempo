package com.pisotiyempo.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SyncTest {

    @Test
    fun linkCodeRoundTripsAndRejectsJunk() {
        val link = LinkCode.create()
        assertEquals(link, LinkCode.parse(link.encode()))
        // Pasted with line breaks and spaces.
        assertEquals(link, LinkCode.parse(" " + link.encode().chunked(10).joinToString("\n ") + " "))
        assertNull(LinkCode.parse("PT1.nothex.abc"))
        assertNull(LinkCode.parse("hello"))
    }

    @Test
    fun sealedRecordsOpenOnlyWithTheRightKeyAndUid() {
        val link = LinkCode.create()
        val record = SyncEnvelope(SyncEnvelope.WATCH, "u1", 100, payload = Json.encodeToJsonElement(WatchSync("JFC", 1)))
        val blob = SyncCrypto.seal(link, record)
        assertEquals(record, SyncCrypto.open(link, blob))
        assertNull(SyncCrypto.open(LinkCode.create(), blob))
        // Moving a blob to another uid breaks authentication.
        assertNull(SyncCrypto.open(link, blob.copy(uid = "u2")))
        assertNull(SyncCrypto.open(link, blob.copy(blob = blob.blob.dropLast(2) + "AA")))
    }

    /** A device's records in memory, with last-write-wins apply. */
    private class FakeStore : SyncStore {
        val records = mutableMapOf<String, SyncEnvelope>()
        var state = SyncState()
        fun edit(r: SyncEnvelope) { records[r.uid] = r }
        override suspend fun localChangesSince(sinceMillis: Long) = records.values.filter { it.updatedAt >= sinceMillis }
        override suspend fun apply(record: SyncEnvelope) {
            val local = records[record.uid]
            if (local == null || record.updatedAt > local.updatedAt) records[record.uid] = record
        }
        override suspend fun loadState() = state
        override suspend fun saveState(state: SyncState) { this.state = state }
    }

    /** The backend: stores blobs by uid with an increasing sequence; it can't read them. */
    private class FakeServer : SyncTransport {
        private var seq = 0L
        val blobs = mutableMapOf<String, SyncBlob>()
        override suspend fun push(link: LinkCode, records: List<SyncBlob>): SyncPushResponse {
            records.forEach { blobs[it.uid] = it.copy(seq = ++seq) }
            return SyncPushResponse(records.size, seq)
        }
        override suspend fun pull(link: LinkCode, since: Long): SyncPullResponse {
            val page = blobs.values.filter { it.seq > since }.sortedBy { it.seq }
            return SyncPullResponse(page, page.maxOfOrNull { it.seq } ?: since)
        }
    }

    private fun trade(uid: String, at: Long, shares: Long, deleted: Boolean = false) = SyncEnvelope(
        SyncEnvelope.TRADE, uid, at, deleted,
        if (deleted) null else Json.encodeToJsonElement(TradeSync("JFC", "BUY", shares, 140.0, 20.0, "2026-11-01")),
    )

    @Test
    fun twoDevicesConvergeWithLastWriteWins() = runBlocking {
        val link = LinkCode.create()
        val server = FakeServer()
        val phone = FakeStore()
        val laptop = FakeStore()
        var clock = 1_000L
        val phoneSync = SyncEngine(phone, server) { clock }
        val laptopSync = SyncEngine(laptop, server) { clock }

        phone.edit(trade("t1", 10, 100))
        phoneSync.run(link)
        laptopSync.run(link)
        assertEquals(100L, laptop.records.getValue("t1").let { Json.decodeFromJsonElement(TradeSync.serializer(), it.payload!!).shares })

        // Both edit t1 offline; the laptop's edit is newer and must win on both.
        clock = 2_000
        phone.edit(trade("t1", 1_500, 200))
        laptop.edit(trade("t1", 1_800, 300))
        laptop.edit(trade("t2", 1_900, 10))
        phoneSync.run(link)
        laptopSync.run(link)
        phoneSync.run(link)
        for (device in listOf(phone, laptop)) {
            assertEquals(300L, Json.decodeFromJsonElement(TradeSync.serializer(), device.records.getValue("t1").payload!!).shares)
            assertNotNull(device.records["t2"])
        }

        // A deletion syncs as a tombstone.
        clock = 3_000
        phone.edit(trade("t2", 2_500, 0, deleted = true))
        phoneSync.run(link)
        laptopSync.run(link)
        assertEquals(true, laptop.records.getValue("t2").deleted)
    }
}
