package com.pisotiyempo.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.pisotiyempo.data.local.AppDatabase
import com.pisotiyempo.data.local.KvEntity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores small secrets (the user's own EODHD key) encrypted with an AES key that lives in the Android
 * Keystore and never leaves the device. The ciphertext sits in the app database, which is excluded from backup.
 */
class SecretStore(private val db: AppDatabase) {

    suspend fun get(name: String): String? {
        val stored = db.kv().get(PREFIX + name)?.value?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val (iv, ct) = stored.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }

    suspend fun put(name: String, value: String?) {
        if (value.isNullOrBlank()) {
            db.kv().put(KvEntity(PREFIX + name, "", System.currentTimeMillis()))
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        db.kv().put(KvEntity(PREFIX + name, encoded, System.currentTimeMillis()))
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val EODHD_KEY = "eodhd-key"
        private const val PREFIX = "secret:"
        private const val ALIAS = "piso-secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
