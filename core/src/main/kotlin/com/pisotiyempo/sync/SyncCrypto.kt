package com.pisotiyempo.sync

import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A sync group: the devices that share one encrypted copy of the user's data. Linking a device means
 * giving it the [LinkCode] (by QR code or typing it), which contains both the group id and the 256-bit
 * key. The backend never receives the key, only an auth token derived from it.
 */
data class LinkCode(val groupId: String, val key: ByteArray) {
    init {
        require(GROUP_ID.matches(groupId)) { "bad group id" }
        require(key.size == 32) { "key must be 32 bytes" }
    }

    /** Proves membership to the backend without revealing the key. */
    val authToken: String get() = b64(hmac(key, "piso-sync-auth-v1"))

    /** Text form, e.g. for a QR code: PT1.<groupId>.<base64url key> */
    fun encode(): String = "$PREFIX$groupId.${b64(key)}"

    override fun equals(other: Any?) = other is LinkCode && other.groupId == groupId && other.key.contentEquals(key)

    override fun hashCode() = groupId.hashCode()

    companion object {
        private const val PREFIX = "PT1."
        private val GROUP_ID = Regex("^[0-9a-f]{32}$")
        private val random = SecureRandom()

        fun create(): LinkCode {
            val id = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            return LinkCode(id, ByteArray(32).also { random.nextBytes(it) })
        }

        /** Accepts the code with surrounding whitespace or line breaks, as users paste it. */
        fun parse(text: String): LinkCode? {
            val t = text.filterNot { it.isWhitespace() }
            if (!t.startsWith(PREFIX)) return null
            val parts = t.removePrefix(PREFIX).split('.')
            if (parts.size != 2) return null
            return runCatching { LinkCode(parts[0], unb64(parts[1])) }.getOrNull()
        }
    }
}

object SyncCrypto {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val random = SecureRandom()

    /** AES-256-GCM. The record uid is bound as associated data so a blob can't be swapped onto another uid. */
    fun seal(link: LinkCode, record: SyncEnvelope): SyncBlob {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(link.key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(record.uid.toByteArray())
        val ct = cipher.doFinal(json.encodeToString(SyncEnvelope.serializer(), record).toByteArray())
        return SyncBlob(record.uid, "v1.${b64(iv)}.${b64(ct)}")
    }

    /** Null when the blob wasn't made with this group's key or was tampered with. */
    fun open(link: LinkCode, blob: SyncBlob): SyncEnvelope? = runCatching {
        val parts = blob.blob.split('.')
        require(parts.size == 3 && parts[0] == "v1")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(link.key, "AES"), GCMParameterSpec(128, unb64(parts[1])))
        cipher.updateAAD(blob.uid.toByteArray())
        val record = json.decodeFromString(SyncEnvelope.serializer(), String(cipher.doFinal(unb64(parts[2]))))
        require(record.uid == blob.uid)
        record
    }.getOrNull()
}

private fun hmac(key: ByteArray, message: String): ByteArray =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message.toByteArray())

internal fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

internal fun unb64(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
