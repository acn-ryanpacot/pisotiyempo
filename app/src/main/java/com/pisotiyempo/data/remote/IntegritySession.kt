package com.pisotiyempo.data.remote

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import com.pisotiyempo.BuildConfig
import com.pisotiyempo.data.local.AppDatabase
import com.pisotiyempo.data.local.KvEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.util.UUID

/**
 * Backend session for this install. About once a day it exchanges (a) a Play Integrity token proving this is
 * a genuine Play install and (b) the premium purchase token, if any, for a 24-hour session token that the
 * backend checks on each request. With CLOUD_PROJECT_NUMBER = 0 integrity is skipped; with neither integrity
 * nor a purchase there is nothing to prove, so no session is requested.
 */
class IntegritySession(
    private val context: Context,
    private val db: AppDatabase,
    private val cloudProjectNumber: Long = BuildConfig.CLOUD_PROJECT_NUMBER,
) : SessionProvider {
    private val mutex = Mutex()
    private var cached: SessionDto? = null
    private var provider: StandardIntegrityTokenProvider? = null

    override suspend fun token(api: PisoApi): String? {
        mutex.withLock {
            val purchaseToken = db.kv().get(PURCHASE_TOKEN_KEY)?.value?.takeIf { it.isNotBlank() }
            if (cloudProjectNumber == 0L && purchaseToken == null) return null
            val current = cached ?: stored()
            // Renew 10 minutes early so a request never goes out with a token about to expire.
            if (current != null && current.expiresAt - System.currentTimeMillis() > 10 * 60_000) {
                cached = current
                return current.token
            }
            return try {
                val installId = installId()
                val integrityToken = if (cloudProjectNumber != 0L) integrityToken(sha256Hex("piso-session:$installId")) else null
                val session = api.createSession(SessionRequest(installId, integrityToken, purchaseToken))
                cached = session
                db.kv().put(KvEntity(SESSION_KEY, ApiJson.encodeToString(SessionDto.serializer(), session), System.currentTimeMillis()))
                session.token
            } catch (e: Exception) {
                // The backend decides what to do with an unauthenticated request (allowed unless enforcing).
                Log.w(TAG, "could not get an integrity session", e)
                null
            }
        }
    }

    override suspend fun invalidate() = mutex.withLock {
        cached = null
        db.kv().put(KvEntity(SESSION_KEY, "", System.currentTimeMillis()))
    }

    private suspend fun stored(): SessionDto? =
        db.kv().get(SESSION_KEY)?.value?.takeIf { it.isNotBlank() }
            ?.let { runCatching { ApiJson.decodeFromString(SessionDto.serializer(), it) }.getOrNull() }

    /** Random per-install id. Not linked to the user or the device's hardware ids. */
    private suspend fun installId(): String {
        db.kv().get(INSTALL_ID_KEY)?.value?.let { return it }
        val id = UUID.randomUUID().toString()
        db.kv().put(KvEntity(INSTALL_ID_KEY, id, System.currentTimeMillis()))
        return id
    }

    private suspend fun integrityToken(requestHash: String): String {
        val p = provider ?: IntegrityManagerFactory.createStandard(context)
            .prepareIntegrityToken(PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(cloudProjectNumber).build())
            .await()
            .also { provider = it }
        return try {
            p.request(StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()).await().token()
        } catch (e: Exception) {
            // Providers can go stale; prepare a fresh one next time.
            provider = null
            throw e
        }
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "IntegritySession"
        private const val SESSION_KEY = "integrity-session"
        private const val INSTALL_ID_KEY = "install-id"

        /** Written by BillingRepository; duplicated here to keep the network layer free of billing imports. */
        private const val PURCHASE_TOKEN_KEY = "premium-purchase-token"
    }
}
