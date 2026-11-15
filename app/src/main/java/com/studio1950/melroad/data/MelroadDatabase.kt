package com.studio1950.melroad.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import com.studio1950.melroad.music.MusicEvent
import kotlinx.coroutines.flow.Flow

/**
 * 여정 메타데이터. 원본 GPS 좌표·목적지·실명은 저장하지 않는다(요구서 10).
 * sourceType으로 실제 주행(LIVE)과 시뮬레이션(DEMO)을 분리 기록한다.
 */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startedAt: Long,
    val endedAt: Long,
    val sourceType: String,
    val scenarioId: String?,
    val themeId: String,
    val seed: Long,
    val durationMs: Long,
    val status: String,
    /** 예: "SPEED_ONLY", "SIMULATION" — 어떤 신호로 만든 음악인지 */
    val signalSummary: String,
)

@Entity(
    tableName = "music_events",
    foreignKeys = [
        ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tripId")],
)
data class MusicEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val seq: Int,
    val atMs: Long,
    val durationMs: Long,
    val instrument: String,
    val pitch: Int,
    val velocity: Int,
    val expression: Int,
)

@Entity(
    tableName = "playback_state",
    foreignKeys = [
        ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class PlaybackStateEntity(
    @PrimaryKey val tripId: Long,
    val positionMs: Long,
    val updatedAt: Long,
)

@Dao
abstract class TripDao {
    @Query("SELECT * FROM trips ORDER BY startedAt DESC")
    abstract fun observeTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips ORDER BY startedAt DESC")
    abstract suspend fun getTrips(): List<TripEntity>

    @Query("SELECT * FROM trips WHERE id = :id")
    abstract suspend fun getTrip(id: Long): TripEntity?

    @Query("SELECT * FROM music_events WHERE tripId = :tripId ORDER BY seq")
    abstract suspend fun getEvents(tripId: Long): List<MusicEventEntity>

    @Insert
    abstract suspend fun insertTrip(trip: TripEntity): Long

    @Insert
    abstract suspend fun insertEvents(events: List<MusicEventEntity>)

    /** 메타데이터와 이벤트를 한 트랜잭션으로 저장한다. 일부만 저장된 곡이 완료로 보이지 않게 한다. */
    @Transaction
    open suspend fun insertTripWithEvents(trip: TripEntity, events: List<MusicEvent>): Long {
        val id = insertTrip(trip)
        insertEvents(
            events.mapIndexed { i, e ->
                MusicEventEntity(
                    tripId = id,
                    seq = i,
                    atMs = e.atMs,
                    durationMs = e.durationMs,
                    instrument = e.instrument.name,
                    pitch = e.pitch,
                    velocity = e.velocity,
                    expression = e.expression,
                )
            },
        )
        return id
    }

    @Query("DELETE FROM trips WHERE id = :id")
    abstract suspend fun deleteTrip(id: Long)

    @Upsert
    abstract suspend fun upsertPlayback(state: PlaybackStateEntity)

    @Query("SELECT * FROM playback_state WHERE tripId = :tripId")
    abstract suspend fun getPlayback(tripId: Long): PlaybackStateEntity?
}

@Database(
    entities = [TripEntity::class, MusicEventEntity::class, PlaybackStateEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MelroadDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
}
