package com.khatwa.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One calendar day. `closed=false` marks the single open day (today). Dates are ISO yyyy-MM-dd. */
@Entity(tableName = "days")
data class DayEntity(
    @PrimaryKey val date: String,
    val zone: String,
    val carry: Long,
    val baseline: Long,
    val lastReading: Long,
    val steps: Long,
    val goal: Int,
    val lastUpdatedMs: Long,
    val closed: Boolean,
)

/** Intra-day snapshot (every ~15 minutes) used for the hour-of-day distribution. */
@Entity(tableName = "snapshots", indices = [Index("epochMs"), Index("date")])
data class SnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochMs: Long,
    val date: String,
    val stepsToday: Long,
)

/** A detected walking session. */
@Entity(tableName = "sessions", indices = [Index("startMs"), Index("date")])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val startMs: Long,
    val endMs: Long,
    val steps: Long,
)

/** Weekly weight log entry, one decimal. */
@Entity(tableName = "weights", indices = [Index("date")])
data class WeightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val kg: Double,
    val createdMs: Long,
)

/** An emergency unlock ("surrender"). */
@Entity(tableName = "surrenders", indices = [Index("epochMs")])
data class SurrenderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochMs: Long,
    val date: String,
    val remainingSteps: Long,
    val lockType: String,
)

/** User-editable quotes. Seeded from assets/quotes.json on first launch. */
@Entity(tableName = "quotes")
data class QuoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val source: String?,
    val sortOrder: Int,
    /** "ar" or "en": the card shows quotes in the app language. */
    @androidx.room.ColumnInfo(defaultValue = "ar") val lang: String = "ar",
)
