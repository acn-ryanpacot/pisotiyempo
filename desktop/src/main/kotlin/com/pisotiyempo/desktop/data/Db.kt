package com.pisotiyempo.desktop.data

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * The desktop's local SQLite database (via sqlite-jdbc). It's a plain SQLite file so a personal dashboard can
 * read it directly (read-only); it lives in the user's profile folder, protected by the Windows account.
 * One connection, serialized with a lock: plenty for a single-user desktop app.
 */
class Db(file: File) {
    private val conn: Connection
    private val lock = Any()

    init {
        file.parentFile?.mkdirs()
        conn = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA foreign_keys=ON")
            SCHEMA.forEach { st.execute(it) }
        }
    }

    fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = synchronized(lock) {
        conn.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
    }

    fun <T> one(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? = query(sql, *args, map = map).firstOrNull()

    fun exec(sql: String, vararg args: Any?): Int = synchronized(lock) {
        conn.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeUpdate()
        }
    }

    /** Runs [rows] through one prepared statement in a transaction. */
    fun batch(sql: String, rows: List<Array<out Any?>>) = synchronized(lock) {
        if (rows.isEmpty()) return@synchronized
        conn.autoCommit = false
        try {
            conn.prepareStatement(sql).use { ps ->
                for (r in rows) {
                    bind(ps, r)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    fun kvGet(key: String): String? = one("SELECT value FROM kv WHERE key = ?", key) { it.getString(1) }

    fun kvPut(key: String, value: String) {
        exec("INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value)
    }

    private fun bind(ps: PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> ps.setObject(i + 1, null)
                is Boolean -> ps.setInt(i + 1, if (a) 1 else 0)
                else -> ps.setObject(i + 1, a)
            }
        }
    }

    companion object {
        /** Mirrors the phone's tables that matter on the desktop. Column names match for the dashboard's sake. */
        private val SCHEMA = listOf(
            """CREATE TABLE IF NOT EXISTS stocks (symbol TEXT PRIMARY KEY, name TEXT NOT NULL, sector TEXT NOT NULL, close REAL NOT NULL,
                changePct REAL, volume INTEGER NOT NULL, date TEXT NOT NULL, source TEXT NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS bars (symbol TEXT NOT NULL, date TEXT NOT NULL, open REAL NOT NULL, high REAL NOT NULL,
                low REAL NOT NULL, close REAL NOT NULL, volume INTEGER NOT NULL, PRIMARY KEY (symbol, date))""",
            """CREATE TABLE IF NOT EXISTS analyses (symbol TEXT PRIMARY KEY, name TEXT NOT NULL, sector TEXT NOT NULL, asOf TEXT NOT NULL,
                close REAL NOT NULL, changePct REAL, score INTEGER NOT NULL, stance TEXT NOT NULL, topSignal TEXT, rsi14 REAL,
                aboveSma50 INTEGER, aboveSma200 INTEGER, patternNames TEXT NOT NULL, bullishCount INTEGER NOT NULL,
                bearishCount INTEGER NOT NULL, volumeRatio REAL, return3mPct REAL, avgDailyValue REAL NOT NULL, isLiquid INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS trades (uid TEXT PRIMARY KEY, symbol TEXT NOT NULL, side TEXT NOT NULL, shares INTEGER NOT NULL,
                price REAL NOT NULL, fees REAL NOT NULL, date TEXT NOT NULL, note TEXT NOT NULL DEFAULT '', account TEXT NOT NULL DEFAULT 'REAL',
                updatedAt INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS journal (uid TEXT PRIMARY KEY, symbol TEXT NOT NULL, date TEXT NOT NULL, setup TEXT NOT NULL,
                reason TEXT NOT NULL, emotion TEXT NOT NULL, mistakes TEXT NOT NULL, lesson TEXT NOT NULL, rating INTEGER,
                createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS watchlist (symbol TEXT PRIMARY KEY, addedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS alerts (uid TEXT PRIMARY KEY, symbol TEXT NOT NULL, kind TEXT NOT NULL, threshold REAL,
                active INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS tombstones (uid TEXT PRIMARY KEY, type TEXT NOT NULL, deletedAt INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)""",
        )
    }
}

fun ResultSet.doubleOrNull(col: String): Double? = getDouble(col).let { if (wasNull()) null else it }

fun ResultSet.intOrNull(col: String): Int? = getInt(col).let { if (wasNull()) null else it }

fun ResultSet.boolOrNull(col: String): Boolean? = getInt(col).let { if (wasNull()) null else it == 1 }
