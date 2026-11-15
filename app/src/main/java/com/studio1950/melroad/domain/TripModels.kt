package com.studio1950.melroad.domain

import com.studio1950.melroad.integration.LiveCapability
import com.studio1950.melroad.integration.SignalSourceStatus
import com.studio1950.melroad.music.MusicEvent
import com.studio1950.melroad.music.SectionType

enum class TripSource { LIVE, DEMO }

data class TripSummary(
    val id: Long,
    val title: String,
    val startedAt: Long,
    val durationMs: Long,
    val source: TripSource,
    val scenarioId: String?,
    val signalSummary: String,
)

/** 종료됐지만 아직 저장 여부를 정하지 않은 여정. 사용자 동의 없이 자동 보관하지 않는다. */
data class TripDraft(
    val title: String,
    val startedAt: Long,
    val endedAt: Long,
    val source: TripSource,
    val scenarioId: String?,
    val themeId: String,
    val seed: Long,
    val durationMs: Long,
    val events: List<MusicEvent>,
    val signalSummary: String,
)

/**
 * 요구서 09 상태 머신.
 * READY → CHECKING → READY_TO_START | UNSUPPORTED → RUNNING ↔ PAUSED → FINALIZING → FINISHED → SAVED
 * MEMORY_PLAYING ↔ MEMORY_PAUSED
 */
enum class SessionPhase {
    READY,
    CHECKING,
    READY_TO_START,
    UNSUPPORTED,
    RUNNING,
    PAUSED,
    FINALIZING,
    FINISHED,
    SAVED,
    MEMORY_PLAYING,
    MEMORY_PAUSED,
    ERROR_SAFE,
}

enum class PlaybackItemKind { LIVE_TRIP, DEMO_TRIP, DRAFT, MEMORY }

data class SessionState(
    val phase: SessionPhase = SessionPhase.READY,
    val source: TripSource? = null,
    val scenarioId: String? = null,
    val title: String = "",
    val liveCapability: LiveCapability? = null,
    val signalStatus: SignalSourceStatus = SignalSourceStatus.IDLE,
    val elapsedMs: Long = 0L,
    val durationMs: Long = 0L,
    val density: Int = 1,
    val section: SectionType? = null,
    /** 화면 장면 표시용(저장하지 않음): 속도, 굽이 -1..1, 오르내림 -1..1 */
    val sceneSpeedKmh: Float = 0f,
    val sceneCurve: Float = 0f,
    val sceneClimb: Float = 0f,
    val pausedByFocus: Boolean = false,
    val draft: TripDraft? = null,
    val savedTripId: Long? = null,
    val memoryTripId: Long? = null,
    val itemKind: PlaybackItemKind? = null,
    /** 화면 낭독기·상태 표시에 쓰는 메시지(문자열 리소스 ID) */
    val messageRes: Int? = null,
) {
    val isTripActive: Boolean get() = phase == SessionPhase.RUNNING || phase == SessionPhase.PAUSED || phase == SessionPhase.FINALIZING
    val isMemoryActive: Boolean get() = phase == SessionPhase.MEMORY_PLAYING || phase == SessionPhase.MEMORY_PAUSED
    val isAudible: Boolean get() = phase == SessionPhase.RUNNING || phase == SessionPhase.FINALIZING || phase == SessionPhase.MEMORY_PLAYING
    val isSimulation: Boolean get() = source == TripSource.DEMO
}
