package com.pisotiyempo.desktop.data

import com.pisotiyempo.data.remote.ApiException
import com.pisotiyempo.data.remote.PisoApi
import com.pisotiyempo.data.remote.PisoSyncTransport
import com.pisotiyempo.data.remote.SessionDto
import com.pisotiyempo.data.remote.SessionProvider
import com.pisotiyempo.data.remote.SessionRequest
import com.pisotiyempo.data.remote.ApiJson
import com.pisotiyempo.sync.LinkCode
import com.pisotiyempo.sync.SyncEngine
import com.pisotiyempo.sync.SyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Where the desktop's link code lives: sealed with [SecretBox] in the kv table. */
class LinkStore(private val db: Db) {
    fun get(): LinkCode? = db.kvGet(KEY)?.takeIf { it.isNotBlank() }?.let { SecretBox.open(it) }?.let { LinkCode.parse(it) }

    fun set(code: LinkCode?) = db.kvPut(KEY, code?.let { SecretBox.seal(it.encode()) } ?: "")

    private companion object {
        const val KEY = "sync-link"
    }
}

/**
 * Backend session for the desktop. There's no Play Integrity on a PC, so it proves it belongs to a sync group
 * that the user's (verified) phone registered, and inherits the phone's premium status.
 */
class DesktopSession(private val db: Db, private val links: LinkStore) : SessionProvider {
    private val mutex = Mutex()
    private var cached: SessionDto? = null

    private fun installId(): String = db.kvGet("install-id") ?: UUID.randomUUID().toString().also { db.kvPut("install-id", it) }

    override suspend fun token(api: PisoApi): String? = mutex.withLock {
        val link = links.get() ?: return@withLock null
        val current = cached ?: db.kvGet(SESSION_KEY)?.let { runCatching { ApiJson.decodeFromString(SessionDto.serializer(), it) }.getOrNull() }
        if (current != null && current.expiresAt - System.currentTimeMillis() > 10 * 60_000) {
            cached = current
            return@withLock current.token
        }
        runCatching { api.createSession(SessionRequest(installId(), null, null, link.groupId, link.authToken)) }
            .onSuccess {
                cached = it
                db.kvPut(SESSION_KEY, ApiJson.encodeToString(SessionDto.serializer(), it))
            }
            .getOrNull()?.token
    }

    override suspend fun invalidate() = mutex.withLock {
        cached = null
        db.kvPut(SESSION_KEY, "")
    }

    /** Premium inherited from the phone, as of the last session. */
    fun premiumUntil(): Long? = cached?.premiumUntil

    private companion object {
        const val SESSION_KEY = "session"
    }
}

data class DesktopSyncStatus(val linked: Boolean, val state: SyncState, val running: Boolean = false, val message: String? = null)

/** Links with the phone and runs sync. The desktop shows a QR code; the phone scans it to join the group. */
class DesktopSync(private val db: Db, private val api: PisoApi, private val links: LinkStore, private val session: DesktopSession) {
    private val store = DesktopSyncStore(db)
    private val engine = SyncEngine(store, PisoSyncTransport(api))
    private val mutex = Mutex()
    private val _status = MutableStateFlow(DesktopSyncStatus(links.get() != null, SyncState()))
    val status: StateFlow<DesktopSyncStatus> = _status

    /** Creates a fresh group (if not linked yet) and returns the code to show as a QR code. */
    suspend fun startLinking(): LinkCode = links.get() ?: LinkCode.create().also {
        links.set(it)
        store.saveState(SyncState())
        refresh(null)
    }

    suspend fun join(code: LinkCode) {
        links.set(code)
        store.saveState(SyncState())
        session.invalidate()
        syncNow()
    }

    suspend fun unlink() {
        links.set(null)
        store.saveState(SyncState())
        session.invalidate()
        refresh(null)
    }

    /** Returns a short status message for the UI. */
    suspend fun syncNow(): String? = mutex.withLock {
        val link = links.get() ?: return@withLock null
        _status.value = _status.value.copy(running = true)
        val message = try {
            val r = engine.run(link)
            if (r.pulled + r.pushed == 0) null else "Synced: ${r.pulled} received, ${r.pushed} sent."
        } catch (e: ApiException) {
            when (e.code) {
                "group_not_registered" -> "Waiting for your phone: open Piso Tiyempo on your phone, go to Settings › Sync with your computer, and scan this QR code."
                "forbidden" -> "This link code is no longer valid. Unlink and link again."
                else -> "Sync failed: ${e.message}"
            }
        } catch (e: Exception) {
            "Sync failed: ${e.message ?: e.javaClass.simpleName}"
        }
        refresh(message)
        message
    }

    suspend fun refresh(message: String? = _status.value.message) {
        _status.value = DesktopSyncStatus(links.get() != null, store.loadState(), running = false, message = message)
    }
}
