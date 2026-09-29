package com.khatwa.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DayEntity::class, SnapshotEntity::class, SessionEntity::class,
        WeightEntity::class, SurrenderEntity::class, QuoteEntity::class, WalkEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class KhatwaDatabase : RoomDatabase() {
    abstract fun days(): DayDao
    abstract fun snapshots(): SnapshotDao
    abstract fun sessions(): SessionDao
    abstract fun weights(): WeightDao
    abstract fun surrenders(): SurrenderDao
    abstract fun quotes(): QuoteDao
    abstract fun walks(): WalkDao

    companion object {
        const val NAME = "khatwa.db"

        /** v1 → v2: quotes carry a language. */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE quotes ADD COLUMN lang TEXT NOT NULL DEFAULT 'ar'")
            }
        }

        /** v2 → v3: walks recorded with GPS (0.7). Existing data is untouched. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(WALKS_TABLE_SQL)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_walks_startMs` ON `walks` (`startMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_walks_date` ON `walks` (`date`)")
            }
        }

        /** Same as Room's generated statement for [WalkEntity] (schemas/…/3.json); checked by the migration test. */
        const val WALKS_TABLE_SQL = "CREATE TABLE IF NOT EXISTS `walks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`date` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `distanceM` REAL NOT NULL, " +
            "`steps` INTEGER NOT NULL, `polyline` TEXT NOT NULL, `challengeKey` TEXT, `placeName` TEXT, `completed` INTEGER NOT NULL)"

        fun build(context: Context): KhatwaDatabase =
            Room.databaseBuilder(context.applicationContext, KhatwaDatabase::class.java, NAME)
                // Single-file journal so the database can be copied as evidence and in backups.
                .setJournalMode(JournalMode.TRUNCATE)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
