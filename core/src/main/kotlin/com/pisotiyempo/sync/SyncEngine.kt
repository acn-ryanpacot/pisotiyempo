package com.pisotiyempo.sync

/** Network side of sync, implemented by PisoApi. Separate so the engine can be tested without HTTP. */
interface SyncTransport {
    suspend fun push(link: LinkCode, records: List<SyncBlob>): SyncPushResponse

    suspend fun pull(link: LinkCode, since: Long): SyncPullResponse
}

data class SyncResult(val pulled: Int, val pushed: Int, val rejected: Int)

/**
 * Pull-then-push sync with last-write-wins per record. Pulling first means a device that was offline gets
 * newer edits from others before sending its own; [SyncStore.apply] keeps whichever copy is newer, so the
 * order of devices doesn't matter and running sync twice is harmless.
 */
class SyncEngine(
    private val store: SyncStore,
    private val transport: SyncTransport,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(link: LinkCode): SyncResult {
        var state = store.loadState()
        var pulled = 0
        var rejected = 0
        try {
            var cursor = state.pullCursor
            do {
                val page = transport.pull(link, cursor)
                for (blob in page.records) {
                    val record = SyncCrypto.open(link, blob)
                    if (record == null) rejected++ else {
                        store.apply(record)
                        pulled++
                    }
                }
                cursor = maxOf(cursor, page.cursor)
            } while (page.more && page.records.isNotEmpty())

            // Capture the time before reading changes so edits made during the push are sent next time.
            val pushStart = now()
            val changes = store.localChangesSince(state.pushedUntil)
            var pushed = 0
            for (batch in changes.chunked(PUSH_BATCH)) {
                val res = transport.push(link, batch.map { SyncCrypto.seal(link, it) })
                pushed += res.stored
                cursor = maxOf(cursor, res.cursor)
            }
            state = SyncState(pullCursor = cursor, pushedUntil = pushStart, lastSyncAt = now(), lastError = null)
            store.saveState(state)
            return SyncResult(pulled, pushed, rejected)
        } catch (e: Exception) {
            store.saveState(state.copy(lastError = e.message ?: e.javaClass.simpleName))
            throw e
        }
    }

    companion object {
        const val PUSH_BATCH = 200
    }
}
