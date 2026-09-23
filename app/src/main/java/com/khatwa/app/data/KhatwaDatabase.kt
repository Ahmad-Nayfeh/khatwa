package com.khatwa.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DayEntity::class, SnapshotEntity::class, SessionEntity::class,
        WeightEntity::class, SurrenderEntity::class, QuoteEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class KhatwaDatabase : RoomDatabase() {
    abstract fun days(): DayDao
    abstract fun snapshots(): SnapshotDao
    abstract fun sessions(): SessionDao
    abstract fun weights(): WeightDao
    abstract fun surrenders(): SurrenderDao
    abstract fun quotes(): QuoteDao

    companion object {
        const val NAME = "khatwa.db"

        fun build(context: Context): KhatwaDatabase =
            Room.databaseBuilder(context.applicationContext, KhatwaDatabase::class.java, NAME)
                // Single-file journal so the database can be copied as evidence and in backups.
                .setJournalMode(JournalMode.TRUNCATE)
                .build()
    }
}
