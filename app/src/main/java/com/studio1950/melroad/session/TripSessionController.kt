package com.studio1950.melroad.session

import android.content.Context
import com.studio1950.melroad.R
import com.studio1950.melroad.audio.AudioEngine
import com.studio1950.melroad.audio.AudioFocusController
import com.studio1950.melroad.data.TripRepository
import com.studio1950.melroad.domain.PlaybackItemKind
import com.studio1950.melroad.domain.SessionPhase
import com.studio1950.melroad.domain.SessionState
import com.studio1950.melroad.domain.TripDraft
import com.studio1950.melroad.domain.TripSource
import com.studio1950.melroad.integration.CapabilityState
import com.studio1950.melroad.integration.DemoVehicleSignalSource
import com.studio1950.melroad.integration.LiveCapability
import com.studio1950.melroad.integration.MusicClock
import com.studio1950.melroad.integration.RealVehicleSignalSource
import com.studio1950.melroad.integration.VehicleSignalSource
import com.studio1950.melroad.music.CompositionDriver
import com.studio1950.melroad.music.DeterministicComposer
import com.studio1950.melroad.music.DriveFixture
import com.studio1950.melroad.music.DriveFixtures
import com.studio1950.melroad.music.MusicEvent
import com.studio1950.melroad.music.SampleFeatureProvider
import com.studio1950.melroad.music.Themes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LIVE / DEMO / MEMORY 세션 상태 머신(요구서 09). 메인 스레드에서만 호출한다.
 *
 * - 시작은 사용자의 명시적 선택으로만. 앱 실행·서비스 연결만으로 재생하지 않는다.
 * - LIVE 신호가 없으면 UNSUPPORTED로 멈추고, DEMO는 사용자가 따로 고르게 한다(자동 전환 금지).
 * - 진행 중인 여행이 있으면 새 시작 요청을 무시해 여행이 두 개 생기지 않게 한다.
 * - 종료 후 저장 여부는 사용자가 정한다(자동 보관 금지).
 */
class TripSessionController(
    private val appContext: Context,
    private val engine: AudioEngine,
    private val repository: TripRepository,
    private val focus: AudioFocusController,
    private val realSource: RealVehicleSignalSource,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val theme = Themes.WARM
    private var driver: CompositionDriver? = null
    private var source: VehicleSignalSource? = null
    private var demoFixture: DriveFixture? = null
    private val recorded = ArrayList<MusicEvent>()
    private var tripStartedAt = 0L
    private var tripSeed = 0L

    private var memoryEvents: List<MusicEvent> = emptyList()
    private var memoryDurationMs = 0L
    private var memoryIsDraft = false
    private var lastPositionSaveMs = 0L

    private var tickJob: Job? = null
    private var sourceStatusJob: Job? = null
    private var resumeOnFocusGain = false
    private var saving = false

    init {
        focus.listener = ::onFocusChange
    }

    val positionMs: Long get() = engine.playheadMs

    /**
     * 화면의 선율 그래픽용: [fromMs, toMs] 근처에서 시작한 이벤트(진행 중인 여행이면 기록 중인 목록,
     * 다시 듣기면 저장된 목록). 두 목록 모두 시각순이라 이진 탐색으로 범위를 찾는다. 메인 스레드 전용.
     */
    fun visibleEvents(fromMs: Long, toMs: Long): List<MusicEvent> {
        val s = _state.value
        val list: List<MusicEvent> = when {
            s.isTripActive -> recorded
            s.isMemoryActive -> memoryEvents
            else -> return emptyList()
        }
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].atMs < fromMs) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<MusicEvent>()
        var i = lo
        while (i < list.size && list[i].atMs <= toMs) {
            out.add(list[i])
            i++
        }
        return out
    }

    // ---------------------------------------------------------------- 준비

    fun checkLiveCapability() {
        val s = _state.value
        if (s.isTripActive || s.phase == SessionPhase.FINISHED) return
        if (s.isMemoryActive) stopPlayback()
        _state.update { it.copy(phase = SessionPhase.CHECKING, messageRes = null) }
        val cap = runCatching { realSource.capability() }
            .getOrElse { LiveCapability(CapabilityState.ERROR, CapabilityState.NOT_INTEGRATED) }
        _state.update {
            it.copy(
                phase = if (cap.canStartLive) SessionPhase.READY_TO_START else SessionPhase.UNSUPPORTED,
                liveCapability = cap,
            )
        }
    }

    // ---------------------------------------------------------------- 여행

    fun startDemo(fixtureId: String) {
        if (_state.value.isTripActive) return
        val fixture = DriveFixtures.byId(fixtureId) ?: return
        if (_state.value.isMemoryActive) stopPlayback()
        if (!focus.request()) {
            _state.update { it.copy(messageRes = R.string.msg_focus_denied) }
            return
        }
        beginTrip(
            source = TripSource.DEMO,
            signalSource = DemoVehicleSignalSource(fixture),
            seed = fixture.seed,
            fixture = fixture,
            title = appContext.getString(R.string.title_demo_trip, fixture.title),
        )
        _state.update { it.copy(messageRes = R.string.msg_demo_start) }
    }

    fun startLive() {
        if (_state.value.isTripActive) return
        val cap = runCatching { realSource.capability() }
            .getOrElse { LiveCapability(CapabilityState.ERROR, CapabilityState.NOT_INTEGRATED) }
        if (!cap.canStartLive) {
            _state.update { it.copy(phase = SessionPhase.UNSUPPORTED, liveCapability = cap) }
            return
        }
        if (_state.value.isMemoryActive) stopPlayback()
        if (!focus.request()) {
            _state.update { it.copy(messageRes = R.string.msg_focus_denied) }
            return
        }
        val date = SimpleDateFormat("M월 d일 a h:mm", Locale.KOREAN).format(Date())
        beginTrip(
            source = TripSource.LIVE,
            signalSource = realSource,
            seed = System.currentTimeMillis() xor System.nanoTime(),
            fixture = null,
            title = appContext.getString(R.string.title_live_trip, date),
        )
        _state.update { it.copy(liveCapability = cap, messageRes = R.string.msg_live_start) }
    }

    private fun beginTrip(
        source: TripSource,
        signalSource: VehicleSignalSource,
        seed: Long,
        fixture: DriveFixture?,
        title: String,
    ) {
        recorded.clear()
        tripSeed = seed
        tripStartedAt = System.currentTimeMillis()
        demoFixture = fixture
        this.source = signalSource

        val composer = DeterministicComposer(seed, theme)
        val d = CompositionDriver(composer, SampleFeatureProvider(signalSource::samplesBetween))
        driver = d

        engine.load(emptyList(), 0L)
        signalSource.start(MusicClock { if (engine.isAdvancing) engine.playheadMs else null })
        val first = d.pump(0L)
        recorded += first
        engine.append(first)
        engine.play(FADE_IN_MS)

        sourceStatusJob?.cancel()
        sourceStatusJob = scope.launch {
            signalSource.status.collect { st -> _state.update { it.copy(signalStatus = st) } }
        }
        val previous = _state.value
        _state.value = SessionState(
            phase = SessionPhase.RUNNING,
            source = source,
            scenarioId = fixture?.id,
            title = title,
            liveCapability = previous.liveCapability,
            signalStatus = signalSource.status.value,
            durationMs = fixture?.durationMs ?: 0L,
            itemKind = if (source == TripSource.DEMO) PlaybackItemKind.DEMO_TRIP else PlaybackItemKind.LIVE_TRIP,
        )
        startTicker()
    }

    /** 사용자가 '여행 마치기'를 눌렀을 때: 다음 마디부터 종지를 연주하고 마무리한다. */
    fun finishTrip() {
        val s = _state.value
        val d = driver ?: return
        if (!s.isTripActive || d.isEnding) return
        if (s.phase == SessionPhase.PAUSED) {
            if (!focus.request()) {
                _state.update { it.copy(messageRes = R.string.msg_focus_denied) }
                return
            }
            engine.play(RESUME_FADE_MS)
        }
        val ending = d.finish()
        recorded += ending
        engine.append(ending)
        _state.update { it.copy(phase = SessionPhase.FINALIZING, pausedByFocus = false) }
    }

    private fun completeTrip(endMs: Long) {
        val s = _state.value
        source?.stop()
        source = null
        sourceStatusJob?.cancel()
        engine.pause(FADE_OUT_MS)
        focus.abandon()
        resumeOnFocusGain = false
        val src = s.source ?: TripSource.DEMO
        val draft = TripDraft(
            title = s.title,
            startedAt = tripStartedAt,
            endedAt = System.currentTimeMillis(),
            source = src,
            scenarioId = s.scenarioId,
            themeId = theme.id,
            seed = tripSeed,
            durationMs = endMs,
            events = recorded.toList(),
            signalSummary = if (src == TripSource.DEMO) "SIMULATION" else liveSummary(s.liveCapability),
        )
        driver = null
        demoFixture = null
        _state.update {
            it.copy(
                phase = SessionPhase.FINISHED,
                draft = draft,
                savedTripId = null,
                elapsedMs = endMs,
                durationMs = endMs,
                itemKind = PlaybackItemKind.DRAFT,
                pausedByFocus = false,
                messageRes = R.string.msg_finished,
            )
        }
    }

    private fun liveSummary(cap: LiveCapability?): String = when {
        cap == null -> "UNKNOWN"
        cap.speedOnly -> "SPEED_ONLY"
        cap.locationOnly -> "LOCATION_ONLY"
        else -> "SPEED_LOCATION"
    }

    fun saveDraft() {
        val s = _state.value
        val draft = s.draft ?: return
        if (s.savedTripId != null || saving) return
        if (s.isMemoryActive) stopPlayback()
        saving = true
        scope.launch {
            val id = runCatching { repository.save(draft) }.getOrNull()
            saving = false
            _state.update {
                if (id != null) {
                    it.copy(phase = SessionPhase.SAVED, savedTripId = id, messageRes = R.string.msg_saved)
                } else {
                    it.copy(messageRes = R.string.msg_save_failed)
                }
            }
        }
    }

    /** 저장하지 않고 마치기. 메모리의 이벤트도 함께 버린다. */
    fun discardDraft() {
        if (_state.value.isMemoryActive) stopPlayback()
        recorded.clear()
        _state.value = SessionState(messageRes = R.string.msg_discarded)
    }

    /** 저장 후 첫 화면으로 */
    fun closeFinished() {
        if (_state.value.isMemoryActive) stopPlayback()
        _state.value = SessionState()
    }

    // ---------------------------------------------------------------- 재생 제어

    fun pause() {
        when (_state.value.phase) {
            SessionPhase.RUNNING, SessionPhase.FINALIZING -> {
                engine.pause(PAUSE_FADE_MS)
                _state.update { it.copy(phase = SessionPhase.PAUSED) }
            }
            SessionPhase.MEMORY_PLAYING -> {
                engine.pause(PAUSE_FADE_MS)
                persistPosition()
                _state.update { it.copy(phase = SessionPhase.MEMORY_PAUSED) }
            }
            else -> Unit
        }
    }

    fun resume() {
        val s = _state.value
        when (s.phase) {
            SessionPhase.PAUSED -> {
                if (!focus.request()) {
                    _state.update { it.copy(messageRes = R.string.msg_focus_denied) }
                    return
                }
                engine.play(RESUME_FADE_MS)
                _state.update {
                    it.copy(
                        phase = if (driver?.isEnding == true) SessionPhase.FINALIZING else SessionPhase.RUNNING,
                        pausedByFocus = false,
                    )
                }
            }
            SessionPhase.MEMORY_PAUSED -> {
                if (!focus.request()) {
                    _state.update { it.copy(messageRes = R.string.msg_focus_denied) }
                    return
                }
                if (engine.playheadMs >= memoryDurationMs) engine.load(memoryEvents, 0L)
                engine.play(RESUME_FADE_MS)
                _state.update { it.copy(phase = SessionPhase.MEMORY_PLAYING, pausedByFocus = false) }
                startTicker()
            }
            else -> Unit
        }
    }

    /** 시스템 미디어 '정지' 요청. 진행 중인 여행은 끝내지 않고 일시정지만 한다. */
    fun stopFromSystem() {
        val s = _state.value
        when {
            s.isTripActive -> pause()
            s.isMemoryActive -> stopPlayback()
        }
    }

    // ---------------------------------------------------------------- MEMORY

    /** 저장된 MusicEvent 타임라인을 그대로 불러온다. 새로 작곡하지 않는다. */
    suspend fun prepareMemory(tripId: Long, autoPlay: Boolean, fromStart: Boolean = false) {
        val s = _state.value
        if (s.isTripActive || s.phase == SessionPhase.FINISHED) return
        val trip = repository.trip(tripId) ?: return
        val events = repository.events(tripId)
        if (events.isEmpty()) {
            _state.update { it.copy(messageRes = R.string.msg_memory_broken) }
            return
        }
        val saved = if (fromStart) 0L else repository.position(tripId)
        val position = if (saved in 1 until trip.durationMs - 3_000L) saved else 0L
        if (s.isMemoryActive) engine.pause(150)
        memoryEvents = events
        memoryDurationMs = trip.durationMs
        memoryIsDraft = false
        engine.load(events, position)
        lastPositionSaveMs = position
        _state.value = SessionState(
            phase = SessionPhase.MEMORY_PAUSED,
            source = trip.source,
            scenarioId = trip.scenarioId,
            title = trip.title,
            elapsedMs = position,
            durationMs = trip.durationMs,
            memoryTripId = tripId,
            itemKind = PlaybackItemKind.MEMORY,
        )
        if (autoPlay) resume()
    }

    fun playMemory(tripId: Long, fromStart: Boolean = false) {
        scope.launch { prepareMemory(tripId, autoPlay = true, fromStart = fromStart) }
    }

    /** 저장 전후의 '다시 듣기'. 방금 만든 이벤트 목록을 처음부터 그대로 재생한다. */
    fun replayDraft() {
        val s = _state.value
        val draft = s.draft ?: return
        if (s.phase != SessionPhase.FINISHED && s.phase != SessionPhase.SAVED) return
        memoryEvents = draft.events
        memoryDurationMs = draft.durationMs
        memoryIsDraft = true
        engine.load(draft.events, 0L)
        _state.update { it.copy(phase = SessionPhase.MEMORY_PAUSED, elapsedMs = 0L, durationMs = draft.durationMs) }
        resume()
    }

    fun restartPlayback() {
        if (!_state.value.isMemoryActive) return
        engine.load(memoryEvents, 0L)
        _state.update { it.copy(phase = SessionPhase.MEMORY_PAUSED, elapsedMs = 0L) }
        resume()
    }

    fun stopPlayback() {
        val s = _state.value
        if (!s.isMemoryActive) return
        persistPosition()
        engine.pause(PAUSE_FADE_MS)
        focus.abandon()
        _state.value = if (memoryIsDraft) {
            s.copy(phase = if (s.savedTripId != null) SessionPhase.SAVED else SessionPhase.FINISHED, elapsedMs = 0L)
        } else {
            SessionState()
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(messageRes = null) }
    }

    /** 태스크 제거(Pleos 음악 앱 가이드): 내부 정지 로직을 먼저 수행한다. 저장되지 않은 여정은 남지 않는다. */
    fun stopAll() {
        tickJob?.cancel()
        sourceStatusJob?.cancel()
        source?.stop()
        source = null
        driver = null
        demoFixture = null
        persistPosition()
        engine.pause(150)
        focus.abandon()
        resumeOnFocusGain = false
        _state.value = SessionState()
    }

    // ---------------------------------------------------------------- 내부

    /**
     * 화면의 3D 장면용 값(속도·굽이·오르내림). 최근 4초 원본 샘플로 계산하고 단계를 나눠 불필요한 갱신을 줄인다.
     * 음악·저장에는 쓰지 않는다. 위치 신호가 없으면(LIVE 속도 기반) 굽이·오르내림은 0으로 둔다.
     */
    /** 화면 장면 표시용 값. 저장하지 않으며, 신호가 없으면 0 또는 null로 둬서 없는 굽이·방위를 지어내지 않는다. */
    private data class Scene(val speedKmh: Float, val curve: Float, val climb: Float, val headingDeg: Float?)

    private fun sceneAt(ph: Long): Scene {
        val samples = source?.samplesBetween(ph - 4_000L, ph).orEmpty()
        if (samples.isEmpty()) return Scene(0f, 0f, 0f, null)
        val speed = (samples.lastOrNull { it.speedMps != null }?.speedMps ?: 0f) * 3.6f
        val bearings = samples.mapNotNull { it.bearingDeg }
        val curve = if (bearings.size >= 2) {
            var delta = (bearings.last() - bearings.first()) % 360f
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            (delta / 40f).coerceIn(-1f, 1f)
        } else {
            0f
        }
        val altitudes = samples.mapNotNull { it.altitudeM }
        val climb = if (altitudes.size >= 2) ((altitudes.last() - altitudes.first()) / 5.0).toFloat().coerceIn(-1f, 1f) else 0f
        // 방위는 1도 단위로만 갱신해 불필요한 화면 갱신을 줄인다.
        val heading = bearings.lastOrNull()?.let { ((Math.round(it) % 360) + 360) % 360 }?.toFloat()
        return Scene(
            Math.round(speed).toFloat(),
            Math.round(curve * 20f) / 20f,
            Math.round(climb * 10f) / 10f,
            heading,
        )
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    private fun tick() {
        val s = _state.value
        val ph = engine.playheadMs
        when {
            s.isTripActive -> tickTrip(ph)
            s.isMemoryActive -> tickMemory(ph)
            else -> tickJob?.cancel()
        }
    }

    private fun tickTrip(ph: Long) {
        val d = driver ?: return
        val fixture = demoFixture
        if (!d.isEnding) {
            val newEvents = if (fixture != null && ph >= fixture.durationMs) {
                // 시뮬레이션 경로의 끝 = 도착. 항상 같은 지점에서 마무리해 같은 결말이 나오게 한다.
                d.pump(fixture.durationMs) + d.finish()
            } else {
                d.pump(ph)
            }
            if (newEvents.isNotEmpty()) {
                recorded += newEvents
                engine.append(newEvents)
            }
            if (d.isEnding && _state.value.phase == SessionPhase.RUNNING) {
                _state.update { it.copy(phase = SessionPhase.FINALIZING) }
            }
        }
        val density = d.composer.currentDensity
        val section = d.composer.currentSection
        val scene = sceneAt(ph)
        val cur = _state.value
        if (ph / 1000 != cur.elapsedMs / 1000 || density != cur.density || section != cur.section ||
            scene.speedKmh != cur.sceneSpeedKmh || scene.curve != cur.sceneCurve || scene.climb != cur.sceneClimb ||
            scene.headingDeg != cur.sceneHeadingDeg
        ) {
            _state.update {
                it.copy(
                    elapsedMs = ph,
                    density = density,
                    section = section,
                    sceneSpeedKmh = scene.speedKmh,
                    sceneCurve = scene.curve,
                    sceneClimb = scene.climb,
                    sceneHeadingDeg = scene.headingDeg,
                )
            }
        }
        val end = d.endMs
        if (end != null && ph >= end) completeTrip(end)
    }

    private fun tickMemory(ph: Long) {
        val s = _state.value
        if (ph / 1000 != s.elapsedMs / 1000) _state.update { it.copy(elapsedMs = ph) }
        if (s.phase == SessionPhase.MEMORY_PLAYING) {
            if (ph >= memoryDurationMs) {
                onMemoryEnded()
            } else if (ph - lastPositionSaveMs > 5_000L) {
                persistPosition()
            }
        }
    }

    private fun onMemoryEnded() {
        engine.pause(FADE_OUT_MS)
        focus.abandon()
        val s = _state.value
        if (memoryIsDraft) {
            _state.value = s.copy(
                phase = if (s.savedTripId != null) SessionPhase.SAVED else SessionPhase.FINISHED,
                elapsedMs = 0L,
                messageRes = R.string.msg_memory_end,
            )
        } else {
            s.memoryTripId?.let { id -> scope.launch { runCatching { repository.savePosition(id, 0L) } } }
            engine.load(memoryEvents, 0L)
            _state.value = s.copy(phase = SessionPhase.MEMORY_PAUSED, elapsedMs = 0L, messageRes = R.string.msg_memory_end)
        }
    }

    private fun persistPosition() {
        val s = _state.value
        val id = s.memoryTripId ?: return
        if (memoryIsDraft) return
        val pos = engine.playheadMs
        lastPositionSaveMs = pos
        scope.launch { runCatching { repository.savePosition(id, pos) } }
    }

    private fun onFocusChange(change: AudioFocusController.Change) {
        val audible = _state.value.isAudible
        when (change) {
            AudioFocusController.Change.LOSS -> {
                // 다른 앱이 계속 소리를 낸다: 정지하고 포커스를 놓는다. 자동 재개하지 않는다.
                resumeOnFocusGain = false
                if (audible) {
                    pause()
                    _state.update { it.copy(pausedByFocus = true, messageRes = R.string.msg_focus_lost) }
                }
                focus.abandon()
            }
            AudioFocusController.Change.LOSS_TRANSIENT -> {
                // 안내 음성·통화 등 잠깐의 우선 소리: 일시정지 후 포커스가 돌아오면 재개
                if (audible) {
                    resumeOnFocusGain = true
                    pause()
                    _state.update { it.copy(pausedByFocus = true, messageRes = R.string.msg_focus_lost) }
                }
            }
            AudioFocusController.Change.DUCK -> engine.setDucked(true)
            AudioFocusController.Change.GAIN -> {
                engine.setDucked(false)
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    resume()
                }
            }
        }
    }

    companion object {
        private const val TICK_MS = 100L
        private const val FADE_IN_MS = 2_500
        private const val FADE_OUT_MS = 2_000
        private const val RESUME_FADE_MS = 800
        private const val PAUSE_FADE_MS = 400
    }
}
