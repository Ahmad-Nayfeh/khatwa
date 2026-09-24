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
    version = 2,
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

        /** v1 → v2: quotes carry a language. */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE quotes ADD COLUMN lang TEXT NOT NULL DEFAULT 'ar'")
            }
        }

        fun build(context: Context): KhatwaDatabase =
            Room.databaseBuilder(context.applicationContext, KhatwaDatabase::class.java, NAME)
                // Single-file journal so the database can be copied as evidence and in backups.
                .setJournalMode(JournalMode.TRUNCATE)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
