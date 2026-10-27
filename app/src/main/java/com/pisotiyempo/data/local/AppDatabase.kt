package com.pisotiyempo.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        StockEntity::class, BarEntity::class, AnalysisEntity::class, TradeEntity::class, WatchEntity::class,
        AlertEntity::class, DividendEntity::class, DisclosureEntity::class, KvEntity::class,
    ],
    version = 1,
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

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "piso-tiyempo.db").build()
    }
}
