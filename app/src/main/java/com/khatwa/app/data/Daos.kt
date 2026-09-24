package com.khatwa.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DayDao {
    @Query("SELECT * FROM days WHERE closed = 0 ORDER BY date DESC LIMIT 1")
    suspend fun openDay(): DayEntity?

    @Query("SELECT * FROM days WHERE date = :date")
    suspend fun get(date: String): DayEntity?

    @Query("SELECT * FROM days WHERE date = :date")
    fun observe(date: String): Flow<DayEntity?>

    @Upsert
    suspend fun upsert(day: DayEntity)

    @Query("SELECT * FROM days ORDER BY date ASC")
    suspend fun all(): List<DayEntity>

    @Query("SELECT * FROM days ORDER BY date ASC")
    fun observeAll(): Flow<List<DayEntity>>

    @Query("SELECT * FROM days WHERE date BETWEEN :from AND :to ORDER BY date ASC")
    fun observeBetween(from: String, to: String): Flow<List<DayEntity>>

    @Query("DELETE FROM days")
    suspend fun deleteAll()
}

@Dao
interface SnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(s: SnapshotEntity)

    @Query("SELECT * FROM snapshots ORDER BY epochMs DESC LIMIT 1")
    suspend fun latest(): SnapshotEntity?

    @Query("SELECT * FROM snapshots WHERE epochMs BETWEEN :fromMs AND :toMs ORDER BY epochMs ASC")
    fun observeBetween(fromMs: Long, toMs: Long): Flow<List<SnapshotEntity>>

    @Query("SELECT * FROM snapshots ORDER BY epochMs ASC")
    suspend fun all(): List<SnapshotEntity>

    @Query("DELETE FROM snapshots")
    suspend fun deleteAll()
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(s: SessionEntity)

    @Query("SELECT * FROM sessions WHERE startMs BETWEEN :fromMs AND :toMs ORDER BY startMs ASC")
    fun observeBetween(fromMs: Long, toMs: Long): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startMs ASC")
    suspend fun all(): List<SessionEntity>

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}

@Dao
interface WeightDao {
    @Insert
    suspend fun insert(w: WeightEntity)

    @Query("DELETE FROM weights WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM weights ORDER BY date ASC, createdMs ASC")
    fun observeAll(): Flow<List<WeightEntity>>

    @Query("SELECT * FROM weights ORDER BY date ASC, createdMs ASC")
    suspend fun all(): List<WeightEntity>

    @Query("DELETE FROM weights")
    suspend fun deleteAll()
}

@Dao
interface SurrenderDao {
    @Insert
    suspend fun insert(s: SurrenderEntity)

    @Query("SELECT * FROM surrenders WHERE epochMs BETWEEN :fromMs AND :toMs ORDER BY epochMs DESC")
    fun observeBetween(fromMs: Long, toMs: Long): Flow<List<SurrenderEntity>>

    @Query("SELECT COUNT(*) FROM surrenders")
    suspend fun count(): Int

    @Query("SELECT * FROM surrenders ORDER BY epochMs ASC")
    suspend fun all(): List<SurrenderEntity>

    @Query("DELETE FROM surrenders")
    suspend fun deleteAll()
}

@Dao
interface QuoteDao {
    @Insert
    suspend fun insertAll(q: List<QuoteEntity>)

    @Upsert
    suspend fun upsert(q: QuoteEntity)

    @Query("DELETE FROM quotes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM quotes ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<QuoteEntity>>

    @Query("SELECT * FROM quotes WHERE lang = :lang ORDER BY sortOrder ASC, id ASC")
    fun observeByLang(lang: String): Flow<List<QuoteEntity>>

    @Query("SELECT * FROM quotes ORDER BY sortOrder ASC, id ASC")
    suspend fun all(): List<QuoteEntity>

    @Query("SELECT COUNT(*) FROM quotes")
    suspend fun count(): Int

    @Query("DELETE FROM quotes")
    suspend fun deleteAll()
}
