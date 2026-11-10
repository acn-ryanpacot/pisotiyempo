package com.pisotiyempo.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        StockEntity::class, BarEntity::class, AnalysisEntity::class, TradeEntity::class, WatchEntity::class,
        AlertEntity::class, DividendEntity::class, DisclosureEntity::class, KvEntity::class, JournalEntity::class,
        TombstoneEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stocks(): StockDao
    abstract fun bars(): BarDao
    abstract fun analyses(): AnalysisDao
    abstract fun trades(): TradeDao
    abstract fun watchlist(): WatchDao
    abstract fun alerts(): AlertDao
    abstract fun dividends(): DividendDao
    abstract fun disclosures(): DisclosureDao
    abstract fun kv(): KvDao
    abstract fun journal(): JournalDao
    abstract fun tombstones(): TombstoneDao

    companion object {
        fun create(context: Context): AppDatabase {
            EncryptedDatabase.init()
            val passphrase = EncryptedDatabase.passphrase(context)
            if (!EncryptedDatabase.migrateLegacy(context, passphrase)) {
                // Rare: encrypting the old file failed. Use it as-is for now rather than show an empty app.
                return Room.databaseBuilder(context, AppDatabase::class.java, EncryptedDatabase.LEGACY_NAME)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
            }
            return Room.databaseBuilder(context, AppDatabase::class.java, EncryptedDatabase.NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8)))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }

        /**
         * v2: screener columns on analyses (a cache rebuilt every sync, so it's recreated), an account
         * column on trades for practice trading, and the journal table.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `analyses`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `analyses` (`symbol` TEXT NOT NULL, `name` TEXT NOT NULL, `asOf` TEXT NOT NULL, " +
                        "`close` REAL NOT NULL, `changePct` REAL, `score` INTEGER NOT NULL, `stance` TEXT NOT NULL, `topSignal` TEXT, " +
                        "`alertPatterns` TEXT NOT NULL, `avgDailyValue` REAL NOT NULL, `isLiquid` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`sector` TEXT NOT NULL, `rsi14` REAL, `aboveSma50` INTEGER, `aboveSma200` INTEGER, `patternNames` TEXT NOT NULL, " +
                        "`bullishCount` INTEGER NOT NULL, `bearishCount` INTEGER NOT NULL, `volumeRatio` REAL, `return3mPct` REAL, " +
                        "PRIMARY KEY(`symbol`))",
                )
                db.execSQL("ALTER TABLE `trades` ADD COLUMN `account` TEXT NOT NULL DEFAULT 'REAL'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `journal` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `symbol` TEXT NOT NULL, " +
                        "`date` TEXT NOT NULL, `tradeId` INTEGER, `setup` TEXT NOT NULL, `reason` TEXT NOT NULL, `emotion` TEXT NOT NULL, " +
                        "`mistakes` TEXT NOT NULL, `lesson` TEXT NOT NULL, `rating` INTEGER, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_symbol` ON `journal` (`symbol`)")
            }
        }

        /** v3: sync ids and change times on user data, plus tombstones for deletions. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("trades", "journal", "alerts")) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                    // Give every existing row its own random id and mark it changed now, so the first sync uploads it.
                    db.execSQL("UPDATE `$table` SET `uid` = lower(hex(randomblob(16))), `updatedAt` = CAST(strftime('%s','now') AS INTEGER) * 1000")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_uid` ON `$table` (`uid`)")
                }
                db.execSQL("ALTER TABLE `watchlist` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `watchlist` SET `updatedAt` = CAST(strftime('%s','now') AS INTEGER) * 1000")
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_tombstones` (`uid` TEXT NOT NULL, `type` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, PRIMARY KEY(`uid`))")
            }
        }
    }
}
