package com.pisotiyempo.data.settings

import com.pisotiyempo.data.local.AppDatabase
import com.pisotiyempo.data.local.KvEntity
import com.pisotiyempo.data.security.KeystoreCipher

/**
 * Stores small secrets (the user's own EODHD key) encrypted with an Android Keystore key that never leaves
 * the device. The ciphertext sits in the app database, which is itself encrypted and excluded from backup.
 */
class SecretStore(private val db: AppDatabase) {
    private val cipher = KeystoreCipher(ALIAS)

    suspend fun get(name: String): String? =
        db.kv().get(PREFIX + name)?.value?.takeIf { it.isNotBlank() }?.let { cipher.decrypt(it) }

    suspend fun put(name: String, value: String?) {
        val stored = if (value.isNullOrBlank()) "" else cipher.encrypt(value)
        db.kv().put(KvEntity(PREFIX + name, stored, System.currentTimeMillis()))
    }

    companion object {
        const val EODHD_KEY = "eodhd-key"
        private const val PREFIX = "secret:"
        private const val ALIAS = "piso-secrets"
    }
}
