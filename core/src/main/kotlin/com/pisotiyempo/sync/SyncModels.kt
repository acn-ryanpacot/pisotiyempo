package com.pisotiyempo.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One synced record, before encryption. The server only ever sees [uid] and the encrypted bytes of this
 * whole object; [type], [updatedAt] and the payload are inside the ciphertext.
 */
@Serializable
data class SyncEnvelope(
    val type: String,
    val uid: String,
    /** Milliseconds since epoch on the device that made the change. Newer wins. */
    val updatedAt: Long,
    val deleted: Boolean = false,
    val payload: JsonElement? = null,
) {
    companion object {
        const val TRADE = "trade"
        const val JOURNAL = "journal"
        const val WATCH = "watch"
        const val ALERT = "alert"
    }
}

@Serializable
data class TradeSync(
    val symbol: String,
    val side: String,
    val shares: Long,
    val price: Double,
    val fees: Double,
    val date: String,
    val note: String = "",
    val account: String = "REAL",
)

@Serializable
data class JournalSync(
    val symbol: String,
    val date: String,
    val tradeUid: String? = null,
    val setup: String,
    val reason: String,
    val emotion: String,
    val mistakes: List<String>,
    val lesson: String,
    val rating: Int? = null,
    val createdAt: Long,
)

@Serializable
data class WatchSync(val symbol: String, val addedAt: Long)

@Serializable
data class AlertSync(
    val symbol: String,
    val kind: String,
    val threshold: Double? = null,
    val active: Boolean = true,
    val createdAt: Long,
)

/** What a device's database must provide for [SyncEngine]. */
interface SyncStore {
    /** Local records (including deletions) changed at or after [sinceMillis]. Re-sending a few is harmless. */
    suspend fun localChangesSince(sinceMillis: Long): List<SyncEnvelope>

    /** Applies a record from another device if it's newer than the local copy. */
    suspend fun apply(record: SyncEnvelope)

    suspend fun loadState(): SyncState

    suspend fun saveState(state: SyncState)
}

@Serializable
data class SyncState(
    /** Server cursor: records with a server sequence above this haven't been pulled yet. */
    val pullCursor: Long = 0,
    /** Local changes up to this time have been pushed. */
    val pushedUntil: Long = 0,
    val lastSyncAt: Long = 0,
    val lastError: String? = null,
)

// ---- Wire format (what the backend stores and returns) ---------------------------------------------

@Serializable
data class SyncBlob(val uid: String, val blob: String, val seq: Long = 0)

@Serializable
data class SyncPushRequest(val records: List<SyncBlob>)

@Serializable
data class SyncPushResponse(val stored: Int, val cursor: Long)

@Serializable
data class SyncPullResponse(val records: List<SyncBlob>, val cursor: Long, val more: Boolean = false)
