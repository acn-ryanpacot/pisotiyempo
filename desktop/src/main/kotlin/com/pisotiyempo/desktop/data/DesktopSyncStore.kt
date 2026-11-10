package com.pisotiyempo.desktop.data

import com.pisotiyempo.sync.AlertSync
import com.pisotiyempo.sync.JournalSync
import com.pisotiyempo.sync.SyncEnvelope
import com.pisotiyempo.sync.SyncState
import com.pisotiyempo.sync.SyncStore
import com.pisotiyempo.sync.TradeSync
import com.pisotiyempo.sync.WatchSync
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

private val SyncJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

fun watchUid(symbol: String) = "watch-$symbol"

/** The desktop's side of sync. Same rules as the phone's RoomSyncStore: newest copy wins, tombstones block revivals. */
class DesktopSyncStore(private val db: Db) : SyncStore {

    override suspend fun localChangesSince(sinceMillis: Long): List<SyncEnvelope> = buildList {
        db.query("SELECT * FROM trades WHERE updatedAt >= ?", sinceMillis) {
            env(SyncEnvelope.TRADE, it.getString("uid"), it.getLong("updatedAt"), TradeSync.serializer(),
                TradeSync(it.getString("symbol"), it.getString("side"), it.getLong("shares"), it.getDouble("price"), it.getDouble("fees"),
                    it.getString("date"), it.getString("note"), it.getString("account")))
        }.forEach { add(it) }
        db.query("SELECT * FROM journal WHERE updatedAt >= ?", sinceMillis) {
            env(SyncEnvelope.JOURNAL, it.getString("uid"), it.getLong("updatedAt"), JournalSync.serializer(),
                JournalSync(
                    symbol = it.getString("symbol"), date = it.getString("date"), setup = it.getString("setup"), reason = it.getString("reason"),
                    emotion = it.getString("emotion"), mistakes = it.getString("mistakes").lines().filter { m -> m.isNotBlank() },
                    lesson = it.getString("lesson"), rating = it.intOrNull("rating"), createdAt = it.getLong("createdAt"),
                ))
        }.forEach { add(it) }
        db.query("SELECT * FROM watchlist WHERE updatedAt >= ?", sinceMillis) {
            env(SyncEnvelope.WATCH, watchUid(it.getString("symbol")), it.getLong("updatedAt"), WatchSync.serializer(),
                WatchSync(it.getString("symbol"), it.getLong("addedAt")))
        }.forEach { add(it) }
        db.query("SELECT * FROM alerts WHERE updatedAt >= ?", sinceMillis) {
            env(SyncEnvelope.ALERT, it.getString("uid"), it.getLong("updatedAt"), AlertSync.serializer(),
                AlertSync(it.getString("symbol"), it.getString("kind"), it.doubleOrNull("threshold"), it.getInt("active") == 1, it.getLong("createdAt")))
        }.forEach { add(it) }
        db.query("SELECT * FROM tombstones WHERE deletedAt >= ?", sinceMillis) {
            SyncEnvelope(it.getString("type"), it.getString("uid"), it.getLong("deletedAt"), deleted = true)
        }.forEach { add(it) }
    }

    override suspend fun apply(record: SyncEnvelope) {
        val tomb = db.one("SELECT deletedAt FROM tombstones WHERE uid = ?", record.uid) { it.getLong(1) }
        if (tomb != null && tomb >= record.updatedAt) return
        val (table, key) = when (record.type) {
            SyncEnvelope.TRADE -> "trades" to "uid"
            SyncEnvelope.JOURNAL -> "journal" to "uid"
            SyncEnvelope.ALERT -> "alerts" to "uid"
            SyncEnvelope.WATCH -> "watchlist" to "symbol"
            else -> return
        }
        val keyValue = if (record.type == SyncEnvelope.WATCH) record.uid.removePrefix("watch-") else record.uid
        val localAt = db.one("SELECT updatedAt FROM $table WHERE $key = ?", keyValue) { it.getLong(1) }
        if (localAt != null && localAt >= record.updatedAt) return

        if (record.deleted) {
            db.exec("DELETE FROM $table WHERE $key = ?", keyValue)
            db.exec("INSERT OR REPLACE INTO tombstones (uid, type, deletedAt) VALUES (?, ?, ?)", record.uid, record.type, record.updatedAt)
            return
        }
        val p = record.payload ?: return
        when (record.type) {
            SyncEnvelope.TRADE -> {
                val t = SyncJson.decodeFromJsonElement(TradeSync.serializer(), p)
                db.exec(
                    "INSERT OR REPLACE INTO trades (uid, symbol, side, shares, price, fees, date, note, account, updatedAt) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    record.uid, t.symbol, t.side, t.shares, t.price, t.fees, t.date, t.note, t.account, record.updatedAt,
                )
            }
            SyncEnvelope.JOURNAL -> {
                val j = SyncJson.decodeFromJsonElement(JournalSync.serializer(), p)
                db.exec(
                    "INSERT OR REPLACE INTO journal (uid, symbol, date, setup, reason, emotion, mistakes, lesson, rating, createdAt, updatedAt) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    record.uid, j.symbol, j.date, j.setup, j.reason, j.emotion, j.mistakes.joinToString("\n"), j.lesson, j.rating, j.createdAt, record.updatedAt,
                )
            }
            SyncEnvelope.WATCH -> {
                val w = SyncJson.decodeFromJsonElement(WatchSync.serializer(), p)
                db.exec("INSERT OR REPLACE INTO watchlist (symbol, addedAt, updatedAt) VALUES (?,?,?)", w.symbol, w.addedAt, record.updatedAt)
            }
            SyncEnvelope.ALERT -> {
                val a = SyncJson.decodeFromJsonElement(AlertSync.serializer(), p)
                db.exec(
                    "INSERT OR REPLACE INTO alerts (uid, symbol, kind, threshold, active, createdAt, updatedAt) VALUES (?,?,?,?,?,?,?)",
                    record.uid, a.symbol, a.kind, a.threshold, a.active, a.createdAt, record.updatedAt,
                )
            }
        }
        db.exec("DELETE FROM tombstones WHERE uid = ?", record.uid)
    }

    override suspend fun loadState(): SyncState =
        db.kvGet(STATE_KEY)?.let { runCatching { SyncJson.decodeFromString(SyncState.serializer(), it) }.getOrNull() } ?: SyncState()

    override suspend fun saveState(state: SyncState) = db.kvPut(STATE_KEY, SyncJson.encodeToString(SyncState.serializer(), state))

    private fun <T> env(type: String, uid: String, at: Long, serializer: KSerializer<T>, value: T) =
        SyncEnvelope(type, uid, at, payload = SyncJson.encodeToJsonElement(serializer, value))

    companion object {
        const val STATE_KEY = "sync-state"
    }
}
