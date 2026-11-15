package com.studio1950.melroad.music

/** [fromMs, toMs] 음악 시간 창의 주행 특징을 돌려준다. */
fun interface FeatureProvider {
    fun featureFor(fromMs: Long, toMs: Long): PhraseFeature
}

/**
 * 원본 샘플 버퍼 → 정제 → 특징 추출.
 * 중간값 필터 예열을 위해 창보다 약간 앞의 샘플까지 함께 정제한다.
 */
class SampleFeatureProvider(
    private val samplesInRange: (fromMs: Long, toMs: Long) -> List<DriveSample>,
    private val normalizer: SignalNormalizer = SignalNormalizer(),
    private val extractor: FeatureExtractor = FeatureExtractor(),
    private val warmUpMs: Long = 10_000L,
) : FeatureProvider {
    override fun featureFor(fromMs: Long, toMs: Long): PhraseFeature {
        if (toMs <= fromMs) return PhraseFeature.MISSING
        val raw = samplesInRange(fromMs - warmUpMs, toMs)
        if (raw.isEmpty()) return PhraseFeature.MISSING
        val clean = normalizer.normalize(raw)
        return extractor.extract(clean, fromMs, toMs)
    }
}

/**
 * 재생 위치(playhead)에 맞춰 한 마디씩 앞서 작곡을 진행한다.
 *
 * 마디 n의 판단 시점은 항상 "마디 n 시작 1마디 전"으로 고정한다. 그래서 pump가 언제(몇 ms 간격으로)
 * 호출되든 같은 입력이면 같은 결과가 나온다. DEMO·오프라인 테스트·LIVE가 이 클래스를 공유한다.
 */
class CompositionDriver(
    val composer: DeterministicComposer,
    private val provider: FeatureProvider,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    var nextBar: Int = 0
        private set

    /** 종지가 만들어졌다면 마지막 소리가 끝나는 시각 */
    var endMs: Long? = null
        private set

    val isEnding: Boolean get() = endMs != null

    /** 지금까지 각 마디에 사용한 특징(테스트·화면 보조 표시용) */
    val featureLog = mutableListOf<PhraseFeature>()

    fun pump(playheadMs: Long): List<MusicEvent> {
        if (endMs != null) return emptyList()
        val out = ArrayList<MusicEvent>()
        val barMs = composer.barMs
        while (composer.barStartMs(nextBar) <= playheadMs + barMs) {
            val decisionMs = (composer.barStartMs(nextBar) - barMs).coerceAtLeast(0L)
            val feature = provider.featureFor((decisionMs - windowMs).coerceAtLeast(0L), decisionMs)
            featureLog.add(feature)
            out += composer.composeBar(nextBar, feature)
            nextBar++
        }
        return out
    }

    fun finish(): List<MusicEvent> {
        if (endMs != null) return emptyList()
        val result = composer.composeEnding(nextBar)
        nextBar += DeterministicComposer.ENDING_BARS
        endMs = result.endMs
        return result.events
    }

    companion object {
        /** 약 4마디(12초). 요구서: 10~20초 누적 변화만 반영 */
        const val DEFAULT_WINDOW_MS = 12_000L
    }
}

/** 정해진 샘플 목록(시뮬레이션 주행) 기반 특징 공급자 */
class FixtureFeatureProvider(samples: List<DriveSample>) : FeatureProvider {
    private val sorted = samples.sortedBy { it.timestampMs }
    private val delegate = SampleFeatureProvider({ from, to ->
        sorted.filter { it.timestampMs in from..to }
    })

    override fun featureFor(fromMs: Long, toMs: Long): PhraseFeature = delegate.featureFor(fromMs, toMs)
}

data class OfflineResult(
    val events: List<MusicEvent>,
    val endMs: Long,
    val decisions: List<PhraseDecision>,
    val barDensities: List<Int>,
    val features: List<PhraseFeature>,
    val barMs: Long,
    val bpm: Int,
) {
    /** 이벤트 시각 → 마디 번호(정수 연산으로 만든 마디 시작점과 정확히 맞춘다) */
    fun barOf(ms: Long): Int = ((ms + 1) * bpm / 240_000L).toInt()
}

/** 시뮬레이션 주행을 실시간 재생 없이 끝까지 작곡한다. DEMO 재생 결과와 같은 절차를 따른다. */
object OfflineComposition {
    fun compose(fixture: DriveFixture, seed: Long = fixture.seed, theme: Theme = Themes.WARM, stepMs: Long = 250L): OfflineResult {
        val composer = DeterministicComposer(seed, theme)
        val driver = CompositionDriver(composer, FixtureFeatureProvider(fixture.samples))
        val events = ArrayList<MusicEvent>()
        var t = 0L
        while (t < fixture.durationMs) {
            events += driver.pump(t)
            t += stepMs
        }
        events += driver.pump(fixture.durationMs)
        events += driver.finish()
        return OfflineResult(
            events,
            driver.endMs!!,
            composer.decisions.toList(),
            composer.barDensities.toList(),
            driver.featureLog.toList(),
            composer.barMs,
            composer.bpm,
        )
    }
}
