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
    ],
    version = 2,
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

    companion object {
        fun create(context: Context): AppDatabase {
            EncryptedDatabase.init()
            val passphrase = EncryptedDatabase.passphrase(context)
            if (!EncryptedDatabase.migrateLegacy(context, passphrase)) {
                // Rare: encrypting the old file failed. Use it as-is for now rather than show an empty app.
                return Room.databaseBuilder(context, AppDatabase::class.java, EncryptedDatabase.LEGACY_NAME)
                    .addMigrations(MIGRATION_1_2)
                    .build()
            }
            return Room.databaseBuilder(context, AppDatabase::class.java, EncryptedDatabase.NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8)))
                .addMigrations(MIGRATION_1_2)
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
    }
}
