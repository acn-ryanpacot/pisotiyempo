package com.pisotiyempo.data.sync

import com.pisotiyempo.data.local.AlertEntity
import com.pisotiyempo.data.local.AppDatabase
import com.pisotiyempo.data.local.JournalEntity
import com.pisotiyempo.data.local.KvEntity
import com.pisotiyempo.data.local.TombstoneEntity
import com.pisotiyempo.data.local.TradeEntity
import com.pisotiyempo.data.local.WatchEntity
import com.pisotiyempo.sync.AlertSync
import com.pisotiyempo.sync.JournalSync
import com.pisotiyempo.sync.SyncEnvelope
import com.pisotiyempo.sync.SyncState
import com.pisotiyempo.sync.SyncStore
import com.pisotiyempo.sync.TradeSync
import com.pisotiyempo.sync.WatchSync
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val SyncJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/** Watchlist rows are keyed by symbol, so their sync uid is derived from it. */
fun watchUid(symbol: String) = "watch-$symbol"

/** The phone's side of sync: maps Room rows to [SyncEnvelope]s and applies incoming ones (newest wins). */
class RoomSyncStore(private val db: AppDatabase) : SyncStore {

    override suspend fun localChangesSince(sinceMillis: Long): List<SyncEnvelope> = buildList {
        db.trades().changedSince(sinceMillis).forEach { add(env(SyncEnvelope.TRADE, it.uid, it.updatedAt, it.toSync(), TradeSync.serializer())) }
        db.journal().changedSince(sinceMillis).forEach { add(env(SyncEnvelope.JOURNAL, it.uid, it.updatedAt, it.toSync(), JournalSync.serializer())) }
        db.watchlist().changedSince(sinceMillis).forEach { add(env(SyncEnvelope.WATCH, watchUid(it.symbol), it.updatedAt, WatchSync(it.symbol, it.addedAt), WatchSync.serializer())) }
        db.alerts().changedSince(sinceMillis).forEach { add(env(SyncEnvelope.ALERT, it.uid, it.updatedAt, it.toSync(), AlertSync.serializer())) }
        db.tombstones().since(sinceMillis).forEach { add(SyncEnvelope(it.type, it.uid, it.deletedAt, deleted = true)) }
    }

    override suspend fun apply(record: SyncEnvelope) {
        // A local deletion at least as new as the incoming copy wins.
        db.tombstones().get(record.uid)?.let { if (it.deletedAt >= record.updatedAt) return }
        if (record.deleted) {
            applyDelete(record)
            return
        }
        val payload = record.payload ?: return
        when (record.type) {
            SyncEnvelope.TRADE -> {
                val local = db.trades().byUid(record.uid)
                if (local != null && local.updatedAt >= record.updatedAt) return
                val t = SyncJson.decodeFromJsonElement(TradeSync.serializer(), payload)
                db.trades().upsert(
                    TradeEntity(
                        id = local?.id ?: 0, symbol = t.symbol, side = t.side, shares = t.shares, price = t.price, fees = t.fees,
                        date = t.date, note = t.note, account = t.account, uid = record.uid, updatedAt = record.updatedAt,
                    ),
                )
            }
            SyncEnvelope.JOURNAL -> {
                val local = db.journal().byUid(record.uid)
                if (local != null && local.updatedAt >= record.updatedAt) return
                val j = SyncJson.decodeFromJsonElement(JournalSync.serializer(), payload)
                db.journal().upsert(
                    JournalEntity(
                        id = local?.id ?: 0, symbol = j.symbol, date = j.date, setup = j.setup, reason = j.reason, emotion = j.emotion,
                        mistakes = j.mistakes.joinToString("\n"), lesson = j.lesson, rating = j.rating, createdAt = j.createdAt,
                        uid = record.uid, updatedAt = record.updatedAt,
                    ),
                )
            }
            SyncEnvelope.WATCH -> {
                val w = SyncJson.decodeFromJsonElement(WatchSync.serializer(), payload)
                val local = db.watchlist().get(w.symbol)
                if (local != null && local.updatedAt >= record.updatedAt) return
                db.watchlist().add(WatchEntity(w.symbol, w.addedAt, record.updatedAt))
            }
            SyncEnvelope.ALERT -> {
                val local = db.alerts().byUid(record.uid)
                if (local != null && local.updatedAt >= record.updatedAt) return
                val a = SyncJson.decodeFromJsonElement(AlertSync.serializer(), payload)
                db.alerts().update(
                    listOf(
                        AlertEntity(
                            id = local?.id ?: 0, symbol = a.symbol, kind = a.kind, threshold = a.threshold, active = a.active,
                            createdAt = a.createdAt, lastTriggered = local?.lastTriggered, uid = record.uid, updatedAt = record.updatedAt,
                        ),
                    ),
                )
            }
        }
        // A newer copy from another device overrides an older local deletion.
        db.tombstones().remove(record.uid)
    }

    private suspend fun applyDelete(record: SyncEnvelope) {
        when (record.type) {
            SyncEnvelope.TRADE -> db.trades().byUid(record.uid)?.takeIf { it.updatedAt <= record.updatedAt }?.let { db.trades().deleteUid(it.uid) }
            SyncEnvelope.JOURNAL -> db.journal().byUid(record.uid)?.takeIf { it.updatedAt <= record.updatedAt }?.let { db.journal().deleteUid(it.uid) }
            SyncEnvelope.ALERT -> db.alerts().byUid(record.uid)?.takeIf { it.updatedAt <= record.updatedAt }?.let { db.alerts().deleteUid(it.uid) }
            SyncEnvelope.WATCH -> {
                val symbol = record.uid.removePrefix("watch-")
                db.watchlist().get(symbol)?.takeIf { it.updatedAt <= record.updatedAt }?.let { db.watchlist().remove(symbol) }
            }
        }
        db.tombstones().put(listOf(TombstoneEntity(record.uid, record.type, record.updatedAt)))
    }

    override suspend fun loadState(): SyncState =
        db.kv().get(STATE_KEY)?.value?.let { runCatching { SyncJson.decodeFromString(SyncState.serializer(), it) }.getOrNull() } ?: SyncState()

    override suspend fun saveState(state: SyncState) {
        db.kv().put(KvEntity(STATE_KEY, SyncJson.encodeToString(SyncState.serializer(), state), System.currentTimeMillis()))
    }

    suspend fun resetState() = saveState(SyncState())

    private fun <T> env(type: String, uid: String, at: Long, value: T, serializer: kotlinx.serialization.KSerializer<T>): SyncEnvelope =
        SyncEnvelope(type, uid, at, payload = SyncJson.encodeToJsonElement(serializer, value) as JsonElement)

    companion object {
        private const val STATE_KEY = "sync-state"
    }
}

private fun TradeEntity.toSync() = TradeSync(symbol, side, shares, price, fees, date, note, account)

private fun JournalEntity.toSync() = JournalSync(
    symbol = symbol, date = date, setup = setup, reason = reason, emotion = emotion,
    mistakes = mistakes.lines().filter { it.isNotBlank() }, lesson = lesson, rating = rating, createdAt = createdAt,
)

private fun AlertEntity.toSync() = AlertSync(symbol, kind, threshold, active, createdAt)
