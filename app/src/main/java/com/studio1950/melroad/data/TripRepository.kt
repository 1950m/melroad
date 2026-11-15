package com.studio1950.melroad.data

import com.studio1950.melroad.domain.TripDraft
import com.studio1950.melroad.domain.TripSource
import com.studio1950.melroad.domain.TripSummary
import com.studio1950.melroad.music.Instrument
import com.studio1950.melroad.music.MusicEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

class TripRepository(
    private val database: MelroadDatabase,
    private val cacheDir: File,
) {
    private val dao = database.tripDao()

    val trips: Flow<List<TripSummary>> = dao.observeTrips().map { list -> list.map { it.toSummary() } }

    suspend fun save(draft: TripDraft): Long = dao.insertTripWithEvents(
        TripEntity(
            title = draft.title,
            startedAt = draft.startedAt,
            endedAt = draft.endedAt,
            sourceType = draft.source.name,
            scenarioId = draft.scenarioId,
            themeId = draft.themeId,
            seed = draft.seed,
            durationMs = draft.durationMs,
            status = STATUS_COMPLETE,
            signalSummary = draft.signalSummary,
        ),
        draft.events,
    )

    suspend fun trip(id: Long): TripSummary? = dao.getTrip(id)?.toSummary()

    suspend fun allTrips(): List<TripSummary> = dao.getTrips().map { it.toSummary() }

    suspend fun events(tripId: Long): List<MusicEvent> = dao.getEvents(tripId).mapNotNull { e ->
        val instrument = runCatching { Instrument.valueOf(e.instrument) }.getOrNull() ?: return@mapNotNull null
        MusicEvent(e.atMs, e.durationMs, instrument, e.pitch, e.velocity, e.expression)
    }

    suspend fun delete(tripId: Long) = dao.deleteTrip(tripId)

    /** Room 데이터와 캐시를 실제로 지운다. */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        database.clearAllTables()
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    suspend fun savePosition(tripId: Long, positionMs: Long) =
        dao.upsertPlayback(PlaybackStateEntity(tripId, positionMs, System.currentTimeMillis()))

    suspend fun position(tripId: Long): Long = dao.getPlayback(tripId)?.positionMs ?: 0L

    private fun TripEntity.toSummary() = TripSummary(
        id = id,
        title = title,
        startedAt = startedAt,
        durationMs = durationMs,
        source = runCatching { TripSource.valueOf(sourceType) }.getOrDefault(TripSource.DEMO),
        scenarioId = scenarioId,
        signalSummary = signalSummary,
    )

    companion object {
        const val STATUS_COMPLETE = "COMPLETE"
    }
}
