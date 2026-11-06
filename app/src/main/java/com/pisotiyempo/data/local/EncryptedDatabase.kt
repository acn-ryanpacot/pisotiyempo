package com.pisotiyempo.data.local

import android.content.Context
import android.util.Log
import com.pisotiyempo.data.security.KeystoreCipher
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import java.security.SecureRandom

/**
 * The app database is encrypted with SQLCipher. Its passphrase is random, generated on first launch, and
 * stored only in encrypted form (wrapped by an Android Keystore key), so copying the database file off the
 * phone doesn't expose the user's trades or journal.
 */
object EncryptedDatabase {
    const val NAME = "piso-tiyempo-secure.db"
    const val LEGACY_NAME = "piso-tiyempo.db"
    private const val PREFS = "db-key"
    private const val PREF_KEY = "wrapped-passphrase"
    private const val TAG = "EncryptedDatabase"

    fun init() {
        System.loadLibrary("sqlcipher")
    }

    /** 64 hex characters. Used as a passphrase string so ATTACH ... KEY and the open-helper agree. */
    fun passphrase(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cipher = KeystoreCipher("piso-db-key")
        prefs.getString(PREF_KEY, null)?.let { wrapped -> cipher.decrypt(wrapped)?.let { return it } }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val passphrase = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(PREF_KEY, cipher.encrypt(passphrase)).apply()
        return passphrase
    }

    /**
     * Builds that ran before encryption kept an unencrypted database. Copy it into the encrypted file once,
     * keeping Room's schema version so its migrations still run, then delete the plaintext copy.
     * Returns false if the copy failed, in which case the caller keeps using the old file this launch.
     */
    fun migrateLegacy(context: Context, passphrase: String): Boolean {
        val legacy = context.getDatabasePath(LEGACY_NAME)
        val target = context.getDatabasePath(NAME)
        if (!legacy.exists() || target.exists()) return true
        return try {
            val db = SQLiteDatabase.openDatabase(legacy.path, "", null, SQLiteDatabase.OPEN_READWRITE, null as SQLiteDatabaseHook?)
            val version = db.version
            db.rawExecSQL("ATTACH DATABASE '${target.path.replace("'", "''")}' AS encrypted KEY '$passphrase'")
            db.rawExecSQL("SELECT sqlcipher_export('encrypted')")
            db.rawExecSQL("PRAGMA encrypted.user_version = $version")
            db.rawExecSQL("DETACH DATABASE encrypted")
            db.close()
            context.deleteDatabase(LEGACY_NAME)
            true
        } catch (e: Exception) {
            // Don't lose data: keep the old file, remove the partial copy, and try again next launch.
            Log.e(TAG, "could not encrypt the existing database", e)
            target.delete()
            false
        }
    }
}
