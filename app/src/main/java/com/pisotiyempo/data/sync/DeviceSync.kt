package com.pisotiyempo.data.sync

import android.util.Log
import com.pisotiyempo.data.local.AppDatabase
import com.pisotiyempo.data.local.KvEntity
import com.pisotiyempo.data.remote.PisoApi
import com.pisotiyempo.data.remote.PisoSyncTransport
import com.pisotiyempo.data.security.KeystoreCipher
import com.pisotiyempo.sync.LinkCode
import com.pisotiyempo.sync.SyncEngine
import com.pisotiyempo.sync.SyncResult
import com.pisotiyempo.sync.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SyncStatus(val linked: Boolean, val state: SyncState, val running: Boolean = false)

/**
 * Sync with the desktop app (and any other linked device). The link code holds the group's encryption key,
 * so it's stored wrapped by an Android Keystore key. Local edits trigger a sync a few seconds later.
 */
@OptIn(FlowPreview::class)
class DeviceSync(private val db: AppDatabase, private val api: PisoApi) {
    private val store = RoomSyncStore(db)
    private val engine = SyncEngine(store, PisoSyncTransport(api))
    private val cipher = KeystoreCipher("piso-sync-link")
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _status = MutableStateFlow(SyncStatus(linked = false, state = SyncState()))
    val status: StateFlow<SyncStatus> = _status

    init {
        scope.launch { refreshStatus() }
        scope.launch { changes.debounce(5_000).collect { runCatching { syncNow() } } }
    }

    /** Call after any local change to synced data. Cheap: syncs once, a few seconds after the last change. */
    fun changed() {
        changes.tryEmit(Unit)
    }

    suspend fun link(): LinkCode? = db.kv().get(LINK_KEY)?.value?.takeIf { it.isNotBlank() }?.let { cipher.decrypt(it) }?.let { LinkCode.parse(it) }

    /** Creates a new sync group from this phone; show its code on the other device. */
    suspend fun createGroup(): LinkCode {
        val code = LinkCode.create()
        saveLink(code)
        syncNow()
        return code
    }

    /** Joins the group in [code] (from the desktop's QR code). All local data is uploaded and merged. */
    suspend fun join(code: LinkCode) {
        saveLink(code)
        syncNow()
    }

    suspend fun unlink() {
        db.kv().put(KvEntity(LINK_KEY, "", System.currentTimeMillis()))
        store.resetState()
        refreshStatus()
    }

    /** Removes the group's encrypted data from the server, then unlinks this device. */
    suspend fun deleteFromServer() {
        link()?.let { api.syncDelete(it.groupId, it.authToken) }
        unlink()
    }

    suspend fun syncNow(): SyncResult? {
        mutex.withLock {
            val code = link() ?: return null
            _status.value = _status.value.copy(running = true)
            return try {
                engine.run(code)
            } catch (e: Exception) {
                Log.w(TAG, "sync failed", e)
                null
            } finally {
                refreshStatus()
            }
        }
    }

    private suspend fun saveLink(code: LinkCode) {
        db.kv().put(KvEntity(LINK_KEY, cipher.encrypt(code.encode()), System.currentTimeMillis()))
        // A new group means everything local must be pushed and everything remote pulled.
        store.resetState()
        refreshStatus()
    }

    private suspend fun refreshStatus() {
        _status.value = SyncStatus(linked = link() != null, state = store.loadState(), running = false)
    }

    companion object {
        private const val TAG = "DeviceSync"
        private const val LINK_KEY = "sync-link"
    }
}
