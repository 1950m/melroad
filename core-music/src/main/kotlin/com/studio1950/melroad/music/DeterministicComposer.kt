package com.studio1950.melroad.music

import kotlin.math.abs

/** 악절(4마디) 시작 시점의 결정 기록. 테스트·디버깅과 화면의 보조 표시에 쓴다. */
data class PhraseDecision(
    val phraseIndex: Int,
    val startBar: Int,
    val section: SectionType,
    val progressionId: String,
    val density: Int,
    val registerStep: Int,
    val contour: Int,
    val keyOffset: Int,
    val quality: SignalQuality,
)

data class EndingResult(val events: List<MusicEvent>, val endMs: Long)

/**
 * 규칙 기반 결정론적 작곡기. 같은 seed + theme + 같은 특징 입력(마디 순서대로) → 항상 같은 MusicEvent.
 *
 * 긴 여정(30분~1시간 이상)을 위해 곡을 큰 구간(도입 → A → 후렴 → 간주 → 브리지 …)으로 순환시키고,
 * 두 순환마다 조를 바꾼다. 화성은 장음계 7화음(maj7·m7·V7)과 9음으로 부드럽게 채운다.
 *
 * 주행 신호 매핑(요구서 07):
 * - 속도 → 반주 밀도(0..3). 음량·템포는 속도에 비례해 올리지 않는다(과속 유도 금지).
 * - 정차 → 쉼(REST) 구간: 패드와 잔잔한 화음만 남겨 음악이 숨을 쉰다.
 * - 진행 방향 변화 → 다음 악절의 화성 진행(CR/CL)과 선율 방향.
 * - 검증된 고도 변화 → 음역(registerStep). 강한 오르막은 다음 구간을 후렴으로 끌어올린다.
 * - 큰 변화(구간·화성·음역)는 4마디 경계에서만, 밀도는 2마디마다 한 단계씩만 바뀐다.
 */
class DeterministicComposer(val seed: Long, val theme: Theme) {

    companion object {
        const val EIGHTHS_PER_BAR = 8
        const val BARS_PER_PHRASE = 4
        const val BARS_PER_SECTION = 8
        const val INTRO_BARS = 4
        const val ENDING_BARS = 4
        const val ENDING_TAIL_MS = 4_000L
        const val MAX_VELOCITY = 84

        /** 뒷박(홀수 8분음표)을 늦추는 비율(%). 12%면 약 56:44의 가벼운 스윙이 된다. */
        const val SWING_PERCENT = 12

        // 타악 종류(PERC 음높이)
        const val PERC_KICK = 36
        const val PERC_RIM = 37
        const val PERC_SNARE = 38
        const val PERC_HAT = 42
        const val PERC_OPEN_HAT = 46
        const val PERC_SWELL = 49

        private const val MELODY_LOW = 3
        private const val MELODY_SPAN = 10
        private const val MAX_LEAP = 4
        private const val TURN_THRESHOLD_DEG = 25f
        private const val CLIMB_THRESHOLD_M = 6f
        private const val SALT_THEME = 0x6D656C726F6164L // "melroad"

        /** 조 이동(반음). 음역이 크게 벗어나지 않도록 -4..+5 안에서만 움직인다. */
        private val KEY_OFFSETS = intArrayOf(0, 3, -2, 5, -4, 2)

        private val FORM_TEMPLATES = listOf(
            listOf(SectionType.VERSE, SectionType.VERSE, SectionType.CHORUS, SectionType.INTERLUDE,
                SectionType.VERSE, SectionType.CHORUS, SectionType.BRIDGE, SectionType.CHORUS),
            listOf(SectionType.VERSE, SectionType.CHORUS, SectionType.VERSE, SectionType.CHORUS,
                SectionType.INTERLUDE, SectionType.BRIDGE, SectionType.CHORUS, SectionType.INTERLUDE),
            listOf(SectionType.VERSE, SectionType.VERSE, SectionType.BRIDGE, SectionType.CHORUS,
                SectionType.INTERLUDE, SectionType.VERSE, SectionType.CHORUS, SectionType.CHORUS),
        )

        // 선율 리듬 셀: (8분음표 시작 위치, 길이)
        private val R_DOTTED = listOf(0 to 3, 3 to 1, 4 to 4)
        private val R_QQH = listOf(0 to 2, 2 to 2, 4 to 4)
        private val R_QEEH = listOf(0 to 2, 2 to 1, 3 to 1, 4 to 4)
        private val R_HQQ = listOf(0 to 4, 4 to 2, 6 to 2)
        private val R_PUSH = listOf(0 to 2, 2 to 1, 3 to 3, 6 to 2) // 당김음으로 앞으로 밀리는 느낌
        private val R_332 = listOf(0 to 3, 3 to 3, 6 to 2) // 3-3-2 리듬
    }

    /** 한 마디짜리 선율 모티브: 리듬 + 음 사이 간격(음계 단계). 같은 구간 종류에서 반복·변주되어 '후크'가 된다. */
    private class Motif(val rhythm: List<Pair<Int, Int>>, val intervals: IntArray)

    private class PhrasePlan(
        val motif: Motif,
        val anchor: Int,
        val sequenceStep: Int,
        val varyThirdBar: Boolean,
        val appoggiatura: BooleanArray,
        val question: Boolean,
    )


    private var density = 1
    private var registerStep = 0
    private var contour = 0
    private var keyOffset = 0
    private var quality = SignalQuality.MISSING
    private var section = SectionType.INTRO
    private var sectionStartBar = 0
    private var formCycle = 0
    private var formIndex = -1
    private var template = FORM_TEMPLATES[0]
    private var progression = Progressions.INTRO
    private var plan: PhrasePlan? = null
    private var lastMelodyStep = 7
    private var lastGuideStep = 2
    private var nextBar = 0
    private var ended = false

    private val decisionLog = mutableListOf<PhraseDecision>()
    private val densityLog = mutableListOf<Int>()

    val decisions: List<PhraseDecision> get() = decisionLog
    val barDensities: List<Int> get() = densityLog
    val currentDensity: Int get() = density
    val currentRegister: Int get() = registerStep
    val currentSection: SectionType get() = section
    val isEnded: Boolean get() = ended

    /** 여정마다 seed로 정하는 고정 템포(테마 기준 -2~+4 BPM, 70~86 범위). 속도와는 무관하다. */
    val bpm: Int

    init {
        val rng = SeededRandom(seed xor SALT_THEME)
        bpm = (theme.bpm + listOf(-2, 0, 2, 4)[rng.nextInt(4)]).coerceIn(70, 86)
        template = FORM_TEMPLATES[rng.nextInt(FORM_TEMPLATES.size)]
    }

    val barMs: Long get() = barStartMs(1)

    fun barStartMs(bar: Int): Long = eighthMs(bar, 0)

    /**
     * 8분음표 위치 → ms. 정수 연산이라 기기와 관계없이 같은 값이 나온다.
     * 뒷박은 [SWING_PERCENT]만큼 늦게 둬서 기계적으로 똑같은 간격 대신 살짝 흔들리는 그루브를 만든다(마디 시작은 그대로).
     */
    fun eighthMs(bar: Int, eighth: Int): Long {
        val straight = ((bar.toLong() * EIGHTHS_PER_BAR + eighth) * 30_000L) / bpm
        return if (eighth % 2 != 0) straight + swingMs else straight
    }

    private val swingMs: Long get() = (30_000L * SWING_PERCENT) / (bpm * 100L)

    fun composeBar(bar: Int, feature: PhraseFeature?): List<MusicEvent> {
        check(!ended) { "종지 이후에는 마디를 만들 수 없다" }
        require(bar == nextBar) { "마디는 순서대로 만들어야 한다: expected=$nextBar actual=$bar" }
        nextBar++

        val f = feature ?: PhraseFeature.MISSING
        if (bar % 2 == 0) updateDensity(f)
        if (bar % BARS_PER_PHRASE == 0) updatePhrase(bar, f)
        densityLog.add(density)

        val rng = SeededRandom.forBar(seed, bar)
        val events = ArrayList<MusicEvent>()
        val slots = harmonyOf(bar)
        addPadOrStrings(events, bar, slots)
        addBass(events, bar, slots)
        addComp(events, bar, slots, rng)
        addMelody(events, bar, rng)
        addGuideLine(events, bar, slots)
        addGroove(events, bar, rng)
        addSectionFill(events, bar, slots)
        applyBuildUp(events, bar, slots)
        return sortEvents(events)
    }

    /** IVmaj7 – Vsus4→V – Imaj7(2마디) 종지와 여운. 선율은 순차 진행으로 으뜸음에 내려앉는다. */
    fun composeEnding(fromBar: Int): EndingResult {
        check(!ended) { "이미 종지가 만들어졌다" }
        require(fromBar == nextBar) { "종지는 다음 마디부터 시작해야 한다: expected=$nextBar actual=$fromBar" }
        ended = true
        density = density.coerceAtMost(1)
        section = SectionType.INTERLUDE

        val events = ArrayList<MusicEvent>()
        val (low, high) = melodyRange()
        val start = lastMelodyStep.coerceIn(low, high)
        val tonic = listOf(0, 7, 14).filter { it in low..high }.minByOrNull { abs(it - start) } ?: 7
        val path = if (abs(tonic - start) <= 1) {
            listOf(tonic + 2, tonic + 1, tonic + 1, tonic - 1)
        } else {
            (1..4).map { k -> start + Math.round((tonic - start) * k / 5.0).toInt() }
        }.map { it.coerceIn(low, high) }

        val b0 = fromBar
        val b1 = fromBar + 1
        val b2 = fromBar + 2
        // 1마디: IVmaj7
        addPadVoicing(events, b0, 0, 8, 3, Instrument.STRINGS, 40, 300L)
        addBassNote(events, b0, 0, 8, 3, 48)
        addEpChord(events, b0, 0, 8, 3, 34)
        note(events, b0, 0, 4, Instrument.PIANO, path[0], 54)
        note(events, b0, 4, 4, Instrument.PIANO, path[1], 52)
        // 2마디: Vsus4 → V
        addSus4(events, b1, 0, 4, 4, 38)
        addPadVoicing(events, b1, 4, 4, 4, Instrument.STRINGS, 40, 300L)
        addBassNote(events, b1, 0, 8, 4, 48)
        note(events, b1, 0, 4, Instrument.PIANO, path[2], 50)
        note(events, b1, 4, 4, Instrument.PIANO, path[3], 48)
        // 3~4마디: Imaj7(add9) 길게 + 하프처럼 오르는 아르페지오
        addPadVoicing(events, b2, 0, 16, 0, Instrument.STRINGS, 42, 3_500L)
        addPadVoicing(events, b2, 0, 16, 0, Instrument.PAD, 40, 3_500L)
        addBassNote(events, b2, 0, 16, 0, 46)
        val arp = intArrayOf(-7, -3, 0, 2, 4, 6, 8)
        arp.forEachIndexed { i, s -> note(events, b2, i, 16 - i, Instrument.PIANO_SOFT, s, 30 - i) }
        note(events, b2, 0, 16, Instrument.PIANO, tonic, 46)

        repeat(ENDING_BARS) { densityLog.add(density) }
        nextBar += ENDING_BARS
        return EndingResult(sortEvents(events), barStartMs(fromBar + ENDING_BARS) + ENDING_TAIL_MS)
    }

    // ------------------------------------------------------------------ 결정

    private fun updateDensity(f: PhraseFeature) {
        val speed = f.speedKmh
        val target = when {
            speed == null -> 1
            f.stoppedRatio >= 0.6f -> 0
            speed < 30f -> 1
            speed < 65f -> 2
            else -> 3
        }
        // 한 번에 한 단계씩만 움직여 갑작스러운 밀도 변화를 막는다.
        if (target > density) density++ else if (target < density) density--
    }

    private fun updatePhrase(bar: Int, f: PhraseFeature) {
        val rng = SeededRandom.forBar(seed, bar, salt = 3L)
        val turn = f.turnDeg
        val turnSign = if (turn != null && abs(turn) >= TURN_THRESHOLD_DEG) (if (turn > 0f) 1 else -1) else 0
        val climb = f.climbM
        val climbUp = climb != null && climb > CLIMB_THRESHOLD_M

        registerStep = when {
            // 고도 품질이 낮으면 오르막을 추측하지 않고 기본 음역으로 한 단계씩 돌아간다.
            climb == null -> registerStep - Integer.signum(registerStep)
            climb > CLIMB_THRESHOLD_M -> registerStep + 1
            climb < -CLIMB_THRESHOLD_M -> registerStep - 1
            else -> registerStep
        }.coerceIn(-1, 2)

        val barsIn = bar - sectionStartBar
        when {
            density == 0 && section != SectionType.INTRO -> if (section != SectionType.REST) startSection(SectionType.REST, bar)
            section == SectionType.REST -> advanceForm(bar, climbUp)
            section == SectionType.INTRO && barsIn >= INTRO_BARS -> advanceForm(bar, climbUp)
            section != SectionType.INTRO && barsIn >= BARS_PER_SECTION -> advanceForm(bar, climbUp)
        }

        val phraseInSection = (bar - sectionStartBar) / BARS_PER_PHRASE
        progression = Progressions.choose(section, phraseInSection, turnSign, climbUp, rng)
        contour = turnSign
        quality = f.quality
        plan = planPhrase(phraseInSection, rng)

        decisionLog.add(
            PhraseDecision(
                phraseIndex = bar / BARS_PER_PHRASE,
                startBar = bar,
                section = section,
                progressionId = progression.id,
                density = density,
                registerStep = registerStep,
                contour = contour,
                keyOffset = keyOffset,
                quality = quality,
            ),
        )
    }

    private fun startSection(type: SectionType, bar: Int) {
        section = type
        sectionStartBar = bar
    }

    private fun advanceForm(bar: Int, climbUp: Boolean) {
        formIndex++
        if (formIndex >= template.size) {
            formIndex = 0
            formCycle++
            template = FORM_TEMPLATES[SeededRandom.forBar(seed, formCycle, salt = 11L).nextInt(FORM_TEMPLATES.size)]
            // 두 순환(약 7분)마다 조를 바꿔 긴 여정에서도 새로움을 준다.
            if (formCycle % 2 == 0) {
                val oldOffset = keyOffset
                keyOffset = KEY_OFFSETS[(formCycle / 2) % KEY_OFFSETS.size]
                // 새 조에서 직전 음과 가장 가까운 음을 기준으로 이어 가 조바꿈 순간의 큰 도약을 막는다.
                lastMelodyStep = equivalentStep(lastMelodyStep, oldOffset)
                lastGuideStep = equivalentStep(lastGuideStep, oldOffset)
            }
        }
        var next = template[formIndex]
        // 강한 오르막에서는 다음 구간을 후렴으로 끌어올린다.
        if (climbUp && (next == SectionType.VERSE || next == SectionType.INTERLUDE)) next = SectionType.CHORUS
        startSection(next, bar)
    }

    private fun planPhrase(phraseInSection: Int, rng: SeededRandom): PhrasePlan? {
        if (section == SectionType.INTRO || section == SectionType.REST) return null
        val question = phraseInSection % 2 == 0
        // 후렴 모티브는 오래 유지(정체성), 주제 모티브는 조가 바뀔 때마다 새로 만든다.
        val variant = if (section == SectionType.CHORUS) formCycle / 4 else formCycle / 2
        val motif = motifFor(section, variant)
        val shift = (rng.nextInt(3) - 1) + (if (section == SectionType.CHORUS) 2 else 0)
        val anchor = (MELODY_LOW + registerStep * 2) + (if (question) 4 else 5) + shift
        val sequenceStep = when (contour) {
            1 -> 2
            -1 -> -2
            else -> if (question) 1 else -1
        }
        val appoggiatura = BooleanArray(4) { i -> i < 3 && section != SectionType.INTERLUDE && rng.chance(0.25) }
        return PhrasePlan(motif, anchor, sequenceStep, varyThirdBar = !question || rng.chance(0.4), appoggiatura, question)
    }

    private val motifCache = HashMap<Long, Motif>()

    private fun motifFor(kind: SectionType, variant: Int): Motif {
        val key = kind.ordinal * 10_000L + variant
        return motifCache.getOrPut(key) {
            val rng = SeededRandom(seed xor SALT_THEME xor (key * 0x9E3779B1L))
            val choices = when (kind) {
                SectionType.CHORUS -> listOf(R_QEEH, R_PUSH, R_DOTTED, R_332)
                SectionType.BRIDGE -> listOf(R_HQQ, R_DOTTED, R_332)
                SectionType.INTERLUDE -> listOf(R_332, R_HQQ)
                else -> listOf(R_DOTTED, R_QQH, R_332, R_PUSH)
            }
            val rhythm = choices[rng.nextInt(choices.size)]
            val n = rhythm.size - 1
            val intervals = IntArray(n) { i ->
                val up = i < (n + 1) / 2 // 앞쪽은 오르고 뒤쪽은 내려오는 아치형
                val mag = if (rng.chance(0.65)) 1 else 2
                if (up) mag else -mag
            }
            if (n >= 2 && rng.chance(0.35)) intervals[rng.nextInt(n)] = 0 // 같은 음 반복으로 리듬을 강조
            Motif(rhythm, intervals)
        }
    }

    /** 다음에 올 예정인 구간(오르막 고조 같은 즉석 변경은 알 수 없다) */
    private fun plannedNextSection(): SectionType =
        if (formIndex + 1 < template.size) {
            template[formIndex + 1]
        } else {
            FORM_TEMPLATES[SeededRandom.forBar(seed, formCycle + 1, salt = 11L).nextInt(FORM_TEMPLATES.size)][0]
        }

    /**
     * 후렴 직전 2마디: 현악이 부풀고 킥이 4분음표로 걸으며, 마지막 반 마디는 반주를 비워 숨을 고른다.
     * (선율의 못갖춘 음만 남아 후렴으로 이어진다)
     */
    private fun applyBuildUp(events: MutableList<MusicEvent>, bar: Int, slots: List<Triple<Int, Int, Int>>) {
        val barsIn = bar - sectionStartBar
        if (section == SectionType.CHORUS || section == SectionType.REST || section == SectionType.INTRO || density == 0) return
        if (barsIn < BARS_PER_SECTION - 2 || plannedNextSection() != SectionType.CHORUS) return
        val last = barsIn == BARS_PER_SECTION - 1
        val degree = slots.first().third
        val v = if (last) 40 else 30
        note(events, bar, 0, 8, Instrument.STRINGS, Chords.fold(degree, -4, 3), v)
        note(events, bar, 0, 8, Instrument.STRINGS, Chords.fold(degree + 4, -4, 3), v - 4)
        if (last) {
            for (beat in 0 until 3) {
                val start = eighthMs(bar, beat * 2)
                val end = eighthMs(bar, beat * 2 + 1)
                events.add(MusicEvent(start, end - start, Instrument.PERC, PERC_KICK, 34 + beat * 4, 50))
            }
            val breath = eighthMs(bar, 6)
            events.removeAll {
                it.atMs >= breath && (it.instrument == Instrument.PERC || it.instrument == Instrument.PIANO_SOFT ||
                    it.instrument == Instrument.EPIANO || it.instrument == Instrument.GUITAR)
            }
            // 후렴 첫 박을 향해 서서히 차오르는 심벌 스웰(마지막 두 박 동안)
            val swellStart = eighthMs(bar, 4)
            events.add(MusicEvent(swellStart, eighthMs(bar + 1, 0) - swellStart, Instrument.PERC, PERC_SWELL, 30, 50))
        }
    }

    // ------------------------------------------------------------------ 화성

    /** (8분음표 위치, 길이, 화음 근음) */
    private fun harmonyOf(bar: Int): List<Triple<Int, Int, Int>> {
        val degrees = progression.bars[bar % BARS_PER_PHRASE]
        return if (degrees.size >= 2) {
            listOf(Triple(0, 4, degrees[0]), Triple(4, 4, degrees[1]))
        } else {
            listOf(Triple(0, 8, degrees[0]))
        }
    }

    private fun addPadOrStrings(out: MutableList<MusicEvent>, bar: Int, slots: List<Triple<Int, Int, Int>>) {
        for ((start, length, degree) in slots) {
            when (section) {
                SectionType.CHORUS -> {
                    addPadVoicing(out, bar, start, length, degree, Instrument.STRINGS, 38, 250L)
                    addPadVoicing(out, bar, start, length, degree, Instrument.PAD, 30, 250L)
                }
                SectionType.BRIDGE -> addPadVoicing(out, bar, start, length, degree, Instrument.PAD, 40, 250L)
                SectionType.REST -> addPadVoicing(out, bar, start, length, degree, Instrument.PAD, 46, 250L)
                else -> addPadVoicing(out, bar, start, length, degree, Instrument.PAD, 40, 250L)
            }
        }
    }

    /** 3·7·9(또는 5)음 중심의 부드러운 보이싱. 근음은 베이스가 맡는다. */
    private fun addPadVoicing(
        out: MutableList<MusicEvent>,
        bar: Int,
        startEighth: Int,
        lengthEighths: Int,
        degree: Int,
        instrument: Instrument,
        velocity: Int,
        tailMs: Long,
    ) {
        val lo = -5 + registerStep
        val hi = lo + 7
        val steps = mutableListOf(
            Chords.fold(degree + 2, lo, hi),
            Chords.fold(degree + 6, lo, hi),
            Chords.fold(if (Chords.allowsNinth(degree)) degree + 1 else degree + 4, lo, hi),
            Chords.fold(degree + 4, lo, hi) + 7,
        )
        if (registerStep >= 1) steps.add(Chords.fold(degree + 2, lo, hi) + 7)
        val start = eighthMs(bar, startEighth)
        val end = eighthMs(bar, startEighth + lengthEighths) + tailMs
        val expression = (54 + registerStep * 10).coerceIn(0, 127)
        for (s in steps.distinct()) {
            out.add(MusicEvent(start, end - start, instrument, pitch(s), velocity.coerceIn(1, MAX_VELOCITY), expression))
        }
    }

    private fun addSus4(out: MutableList<MusicEvent>, bar: Int, startEighth: Int, lengthEighths: Int, degree: Int, velocity: Int) {
        val lo = -5
        val hi = 2
        for (s in intArrayOf(degree + 3, degree + 4, degree + 7)) {
            note(out, bar, startEighth, lengthEighths, Instrument.STRINGS, Chords.fold(s, lo, hi), velocity)
        }
    }

    private fun addBass(out: MutableList<MusicEvent>, bar: Int, slots: List<Triple<Int, Int, Int>>) {
        val velocity = if (density == 0) 42 else 48
        if (section == SectionType.REST && bar % 2 == 1) return // 쉼 구간: 두 마디에 한 번
        for ((start, length, degree) in slots) {
            if (density <= 1 || length < 8 || section == SectionType.INTRO) {
                addBassNote(out, bar, start, length, degree, velocity)
            } else if (section == SectionType.CHORUS) {
                // 후렴: 근음–3음–5음–경과음으로 걷는 베이스
                val barInPhrase = bar % BARS_PER_PHRASE
                val nextRoot = if (barInPhrase < 3) progression.bars[barInPhrase + 1][0] else degree
                val approach = if (nextRoot > degree) nextRoot - 1 else nextRoot + 1
                addBassNote(out, bar, 0, 2, degree, velocity)
                addBassNote(out, bar, 2, 2, degree + 2, velocity - 6)
                addBassNote(out, bar, 4, 2, degree + 4, velocity - 4)
                addBassNote(out, bar, 6, 2, approach, velocity - 8)
            } else {
                addBassNote(out, bar, 0, 3, degree, velocity)
                addBassNote(out, bar, 3, 1, degree, velocity - 8)
                addBassNote(out, bar, 4, 3, degree + 4, velocity - 6)
                // 다음 마디 근음으로 이어 주는 경과음(같은 악절 안에서만 다음 화음을 안다)
                val barInPhrase = bar % BARS_PER_PHRASE
                if (barInPhrase < 3) {
                    val nextRoot = progression.bars[barInPhrase + 1][0]
                    val approach = if (nextRoot > degree) nextRoot - 1 else nextRoot + 1
                    addBassNote(out, bar, 7, 1, approach, velocity - 8)
                } else {
                    addBassNote(out, bar, 7, 1, degree + 4, velocity - 10)
                }
            }
        }
    }

    private fun addBassNote(out: MutableList<MusicEvent>, bar: Int, startEighth: Int, lengthEighths: Int, degree: Int, velocity: Int) {
        var midi = pitch(Chords.fold(degree, -14, -8))
        while (midi < 36) midi += 12
        while (midi > 52) midi -= 12
        val start = eighthMs(bar, startEighth)
        val end = eighthMs(bar, startEighth + lengthEighths)
        out.add(MusicEvent(start, end - start, Instrument.BASS, midi, velocity.coerceIn(1, MAX_VELOCITY), 40))
    }

    /** 일렉트릭 피아노 화음 + (밀도·구간에 따라) 피아노 아르페지오 */
    private fun addComp(out: MutableList<MusicEvent>, bar: Int, slots: List<Triple<Int, Int, Int>>, rng: SeededRandom) {
        for ((start, length, degree) in slots) {
            when (section) {
                SectionType.INTRO -> {
                    if (bar >= 1) addEpChord(out, bar, start, length, degree, 30)
                    if (bar >= 2) addGuitarPicking(out, bar, start, length, degree, 30)
                }
                SectionType.REST -> addEpChord(out, bar, start, length, degree, 30)
                SectionType.VERSE -> if (formCycle % 2 == 1 && density >= 1) {
                    // 짝수 번째 순환의 주제 구간은 기타 핑거피킹으로 반주 색을 바꾼다
                    addGuitarPicking(out, bar, start, length, degree, 34)
                } else when (density) {
                    // 주제: 피아노 왼손이 화음을 풀어 흐르듯 연주(느릴 땐 4분, 빨라지면 8분). 짧게 끊는 화음 대신 페달로 이어진다.
                    0, 1 -> addPianoFlow(out, bar, start, length, degree, 34, stepEighths = 2)
                    2 -> addPianoFlow(out, bar, start, length, degree, 33, stepEighths = 1)
                    else -> {
                        addPianoFlow(out, bar, start, length, degree, 31, stepEighths = 1)
                        addArpeggio(out, bar, start, length, degree, eighths = true, velocity = 26, rng = rng)
                    }
                }
                SectionType.CHORUS -> {
                    // 후렴: 첫 박에 화음을 굴려 넓게 펼친 뒤 피아노 분산화음이 4분으로 받치고, 밀도가 높으면 하프가 8분으로 흐른다
                    addEpChord(out, bar, start, minOf(length, 2), degree, 34)
                    addPianoFlow(out, bar, start + 2, length - 2, degree, 31, stepEighths = 2)
                    if (density >= 2) addArpeggio(out, bar, start, length, degree, eighths = true, velocity = 26, rng = rng)
                }
                SectionType.INTERLUDE -> {
                    addEpChord(out, bar, start, length, degree, 34)
                    if (density >= 2) addArpeggio(out, bar, start, length, degree, eighths = false, velocity = 28, rng = rng)
                }
                SectionType.BRIDGE -> if (formCycle % 2 == 0) {
                    addGuitarPicking(out, bar, start, length, degree, 32)
                } else {
                    addArpeggio(out, bar, start, length, degree, eighths = density >= 2, velocity = 30, rng = rng)
                }
            }
        }
    }

    /** 근음 없는 보이싱(3·5·7·9) */
    private fun addEpChord(out: MutableList<MusicEvent>, bar: Int, startEighth: Int, lengthEighths: Int, degree: Int, velocity: Int) {
        val lo = -4
        val hi = 3
        val steps = listOf(degree + 2, degree + 4, degree + 6, if (Chords.allowsNinth(degree)) degree + 8 else degree + 7)
            .map { Chords.fold(it, lo, hi) }
            .distinct()
            .sorted()
        // 손으로 굴리듯 아래 음부터 조금씩 늦게(음마다 22ms) 울려 화음이 부드럽게 펼쳐지게 한다.
        steps.forEachIndexed { i, s -> note(out, bar, startEighth, lengthEighths, Instrument.EPIANO, s, velocity, offsetMs = i * 22L) }
    }

    /**
     * 피아노 왼손 분산화음: 낮은 근음 → 5음 → 10음(3음+옥타브) → 5음 → 9음(또는 옥타브) … 순서로 풀어 친다.
     * 각 음은 화음이 끝날 때까지 이어 울려(페달) 화성이 부드럽게 겹친다. 박 머리는 조금 또렷하게.
     */
    private fun addPianoFlow(out: MutableList<MusicEvent>, bar: Int, startEighth: Int, lengthEighths: Int, degree: Int, velocity: Int, stepEighths: Int) {
        if (lengthEighths <= 0) return
        val r = Chords.fold(degree, -10, -4)
        val top = if (Chords.allowsNinth(degree)) r + 8 else r + 7
        val shape = intArrayOf(r, r + 4, r + 9, r + 4, top, r + 4, r + 9, r + 4)
        var i = 0
        var e = startEighth
        val end = startEighth + lengthEighths
        while (e < end) {
            val accent = if (e % 4 == 0) 4 else 0
            note(out, bar, e, end - e, Instrument.EPIANO, shape[i % shape.size], velocity + accent - (if (i == 0) 0 else 5))
            i++
            e += stepEighths
        }
    }

    /** 하프처럼 흐르는 분산화음. 위로 올랐다가 내려온다. */
    private fun addArpeggio(
        out: MutableList<MusicEvent>,
        bar: Int,
        startEighth: Int,
        lengthEighths: Int,
        degree: Int,
        eighths: Boolean,
        velocity: Int,
        rng: SeededRandom,
    ) {
        val r = Chords.fold(degree, -10, -4)
        val ninth = if (Chords.allowsNinth(degree)) r + 8 else r + 7
        val shape = intArrayOf(r, r + 4, r + 7, r + 9, ninth + 4, r + 9, r + 7, r + 4)
        val step = if (eighths) 1 else 2
        var i = 0
        var e = startEighth
        while (e < startEighth + lengthEighths) {
            val len = if (eighths) 2 else 3
            note(out, bar, e, len, Instrument.PIANO_SOFT, shape[i % shape.size], velocity + rng.nextInt(-3, 3))
            i++
            e += step
        }
    }

    /** 후렴의 현악 대선율: 화음의 3음·7음을 이어 가는 가이드 톤 라인 */
    private fun addGuideLine(out: MutableList<MusicEvent>, bar: Int, slots: List<Triple<Int, Int, Int>>) {
        if (section != SectionType.CHORUS) return
        for ((start, length, degree) in slots) {
            val lo = -2 + registerStep
            val hi = lo + 7
            val candidates = listOf(degree + 2, degree + 6).map { Chords.fold(it, lo, hi) }
            val pick = candidates.minByOrNull { abs(it - lastGuideStep) } ?: candidates[0]
            note(out, bar, start, length, Instrument.STRINGS, pick, 36)
            lastGuideStep = pick
        }
    }

    /** 구간별 셈여림. 후렴은 조금 크게, 간주·쉼은 여리게(전체 상한은 MAX_VELOCITY). */
    private fun sectionDynamics(): Int = when (section) {
        SectionType.INTRO -> -6
        SectionType.VERSE -> 0
        SectionType.CHORUS -> 6
        SectionType.BRIDGE -> -2
        SectionType.INTERLUDE -> -6
        SectionType.REST -> -10
    }

    /** 기타 핑거피킹: 저음 근음 뒤로 위 화음음을 엄지·검지·중지 순서로 튕긴다. */
    private fun addGuitarPicking(out: MutableList<MusicEvent>, bar: Int, startEighth: Int, lengthEighths: Int, degree: Int, velocity: Int) {
        val r = Chords.fold(degree, -10, -4)
        val upper = intArrayOf(r + 4, r + 9, r + 7, if (Chords.allowsNinth(degree)) r + 8 else r + 11)
        var e = startEighth
        var i = 0
        while (e < startEighth + lengthEighths) {
            val step = if (i == 0) r else upper[(i - 1) % upper.size]
            note(out, bar, e, 3, Instrument.GUITAR, step, velocity - (if (i == 0) 0 else 4))
            e++
            i++
        }
    }

    /**
     * 부드러운 리듬 섹션. 킥·스네어(또는 림)·하이햇으로 박을 분명히 하되 세기는 작게(상한 60) 둔다.
     * - 주제: 1박 킥과 2·4박 백비트, 8분 하이햇(앞박을 조금 세게). 밀도가 낮으면 스네어 대신 림으로 더 가볍게.
     * - 후렴: 킥이 당김음으로 더해지고 두 마디에 한 번 열린 하이햇으로 숨을 넣는다.
     * - 브리지: 하프타임(1박 킥, 3박 림)으로 느긋하게. 간주: 하이햇과 림만.
     * 정차(쉼)·도입에서는 쉬고, 밀도가 높아져도 세기는 오르지 않는다.
     */
    private fun addGroove(out: MutableList<MusicEvent>, bar: Int, rng: SeededRandom) {
        if (section == SectionType.INTRO || section == SectionType.REST || density == 0) return
        val dyn = sectionDynamics() / 2
        fun hit(eighth: Int, type: Int, velocity: Int) {
            val start = eighthMs(bar, eighth)
            val end = eighthMs(bar, eighth + 1)
            out.add(MusicEvent(start, end - start, Instrument.PERC, type, (velocity + dyn).coerceIn(8, 60), 50))
        }
        when (section) {
            SectionType.CHORUS -> {
                hit(0, PERC_KICK, 54); hit(3, PERC_KICK, 38)
                if (density >= 2) hit(4, PERC_KICK, 44)
                hit(2, PERC_SNARE, 42); hit(6, PERC_SNARE, 44)
                for (e in 0 until 8) hit(e, PERC_HAT, if (e % 2 == 0) 24 else 14)
                if (bar % 2 == 1) hit(7, PERC_OPEN_HAT, 18)
            }
            SectionType.VERSE -> {
                hit(0, PERC_KICK, 50)
                if (density >= 2) {
                    hit(5, PERC_KICK, 36)
                    hit(2, PERC_SNARE, 36); hit(6, PERC_SNARE, 38)
                } else {
                    hit(2, PERC_RIM, 30); hit(6, PERC_RIM, 32)
                }
                val hatStep = if (density >= 2) 1 else 2
                for (e in 0 until 8 step hatStep) hit(e, PERC_HAT, if (e % 2 == 0) 22 else 12)
                if (density >= 3 && rng.chance(0.4)) hit(7, PERC_SNARE, 16) // 여린 꾸밈 스네어
            }
            SectionType.BRIDGE -> {
                hit(0, PERC_KICK, 44); hit(4, PERC_RIM, 30)
                if (density >= 2) for (e in 0 until 8 step 2) hit(e, PERC_HAT, 14)
            }
            SectionType.INTERLUDE -> {
                for (e in 0 until 8 step 2) hit(e, PERC_HAT, if (e == 0) 16 else 11)
                hit(6, PERC_RIM, 22)
            }
            else -> Unit
        }
        // 악절 끝 4박 뒤에 가끔 작은 스네어 장식
        if ((section == SectionType.VERSE || section == SectionType.CHORUS) && bar % BARS_PER_PHRASE == 3 && rng.chance(0.5)) hit(7, PERC_SNARE, 20)
    }

    /** 구간의 마지막 마디: 다음 구간으로 넘어가는 짧은 상행 음계(피아노 분산음) */
    private fun addSectionFill(out: MutableList<MusicEvent>, bar: Int, slots: List<Triple<Int, Int, Int>>) {
        val barsIn = bar - sectionStartBar
        if (section == SectionType.REST || section == SectionType.INTRO || density == 0) return
        if (barsIn != BARS_PER_SECTION - 1) return
        val degree = slots.last().third
        val base = Chords.fold(degree, -3, 3)
        for (k in 0 until 4) {
            note(out, bar, 4 + k, 2, Instrument.PIANO_SOFT, base + k, 26 + k * 2)
        }
    }

    // ------------------------------------------------------------------ 선율

    private fun melodyRange(): Pair<Int, Int> {
        val low = MELODY_LOW + registerStep * 2
        return low to low + MELODY_SPAN
    }

    /**
     * 모티브 기반 선율: 1마디 모티브 → 화음에 맞춘 반복(시퀀스) → 반복/변주 → 종지.
     * 같은 리듬·윤곽이 되풀이되어 기억에 남고, 화음이 바뀌며 자연스럽게 변한다. 도약은 5도 이내.
     */
    private fun addMelody(out: MutableList<MusicEvent>, bar: Int, rng: SeededRandom) {
        val p = plan ?: return
        val barInPhrase = bar % BARS_PER_PHRASE
        val instrument = when (section) {
            SectionType.INTERLUDE -> Instrument.GUITAR
            SectionType.BRIDGE -> Instrument.STRINGS
            SectionType.VERSE -> if (formCycle % 3 == 1) Instrument.EPIANO else Instrument.PIANO
            else -> Instrument.PIANO
        }
        val (low, high) = melodyRange()
        val slots = harmonyOf(bar)
        val firstDegree = slots.first().third
        val baseVelocity = when (density) {
            0, 1 -> 60
            2 -> 57
            else -> 54 // 빠를수록 오히려 여리게: 속도 상승을 보상처럼 들리게 하지 않는다.
        } + (if (barInPhrase == 1 || barInPhrase == 2) 3 else 0) + sectionDynamics()
        val expression = (62 + registerStep * 10).coerceIn(0, 127)

        // (8분음표 위치, 길이, 음계 단계) 목록을 만든다
        val notes = ArrayList<Triple<Int, Int, Int>>()
        var prev = lastMelodyStep
        if (barInPhrase < 3) {
            val desired = when (barInPhrase) {
                0 -> p.anchor
                1 -> p.anchor + p.sequenceStep
                else -> p.anchor
            }
            val chord = Chords.triad(firstDegree)
            var step = limitLeap(prev, nearestTone(desired.coerceIn(low, high), chord, low, high), chord, low, high)
            val rhythm = densify(p.motif.rhythm)
            for ((i, slot) in rhythm.withIndex()) {
                if (i > 0) {
                    var interval = p.motif.intervals.getOrElse(i - 1) { if (rng.chance(0.5)) 1 else -1 }
                    if (barInPhrase == 2 && p.varyThirdBar && i == rhythm.lastIndex) interval = -interval
                    var next = step + interval
                    if (next > high || next < low) next = step - interval
                    if (slot.first == 4) {
                        // 3박은 화음음에 안착
                        val degree = slots.last { it.first <= slot.first }.third
                        next = limitLeap(step, nearestTone(next, Chords.triad(degree), low, high), Chords.triad(degree), low, high)
                    }
                    step = next.coerceIn(low, high)
                }
                notes.add(Triple(slot.first, slot.second, step))
            }
        } else {
            // 종지 마디: 질문 악절은 열린 음(3·5음)에서 멈추고 못갖춘 음으로 다음 악절을 부른다. 답 악절은 으뜸·3음에 내려앉는다.
            val chord = Chords.triad(firstDegree)
            val targetTones = if (p.question) intArrayOf(firstDegree + 2, firstDegree + 4) else intArrayOf(firstDegree, firstDegree + 2)
            val land = limitLeap(prev, nearestTone(prev, targetTones, low, high), chord, low, high)
            if (p.question) {
                notes.add(Triple(0, 6, land))
                val dir = Integer.signum(p.anchor - land).let { if (it == 0) 1 else it }
                notes.add(Triple(6, 1, (land + dir).coerceIn(low, high)))
                notes.add(Triple(7, 1, (land + 2 * dir).coerceIn(low, high)))
            } else {
                notes.add(Triple(0, 8, land))
            }
        }

        for ((i, n) in notes.withIndex()) {
            val (eighth, length, step) = n
            var velocity = baseVelocity + rng.nextInt(-3, 3) + (if (i == 0) 2 else 0)
            velocity = velocity.coerceIn(30, 80)
            if (i == 0 && length >= 3 && p.appoggiatura[barInPhrase] && abs(step - prev) <= 2 && step + 1 <= high) {
                // 앞꾸밈음: 위 이웃음에서 화음음으로 내려와 해결(달콤한 긴장)
                note(out, bar, eighth, 1, instrument, step + 1, velocity - 6, expression)
                note(out, bar, eighth + 1, length - 1, instrument, step, velocity, expression)
            } else {
                note(out, bar, eighth, length, instrument, step, velocity, expression)
            }
            // 후렴: 긴 음을 현악이 한 옥타브 아래에서 함께 노래하고, 피아노도 옥타브로 겹쳐 선율을 두텁게 한다
            if (section == SectionType.CHORUS && length >= 3) {
                note(out, bar, eighth, length, Instrument.STRINGS, step - 7, (velocity - 18).coerceAtLeast(24), expression)
            }
            if (section == SectionType.CHORUS && instrument == Instrument.PIANO) {
                note(out, bar, eighth, length, Instrument.EPIANO, step - 7, (velocity - 16).coerceAtLeast(26), expression)
            }
            prev = step
        }
        lastMelodyStep = prev
    }

    /** 밀도가 높으면 긴 음을 둘로 나눠 움직임을 더한다(세기는 올리지 않는다). */
    private fun densify(rhythm: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (density < 2) return rhythm
        val splits = if (density >= 3) 2 else 1
        var out = rhythm
        repeat(splits) {
            val i = out.indices.filter { out[it].second >= 4 && out[it].second % 2 == 0 }.maxByOrNull { out[it].second } ?: return out
            val (e, len) = out[i]
            out = out.subList(0, i) + listOf(e to len / 2, e + len / 2 to len / 2) + out.subList(i + 1, out.size)
        }
        return out
    }

    private fun nearestTone(target: Int, chord: IntArray, low: Int, high: Int): Int {
        var best = Int.MIN_VALUE
        var bestDist = Int.MAX_VALUE
        for (octave in -3..4) {
            for (c in chord) {
                val candidate = c + 7 * octave
                if (candidate < low || candidate > high) continue
                val d = abs(candidate - target)
                if (d < bestDist || (d == bestDist && candidate < best)) {
                    best = candidate
                    bestDist = d
                }
            }
        }
        return if (best == Int.MIN_VALUE) target.coerceIn(low, high) else best
    }

    /** 5도(4단계)를 넘는 도약을 막는다. 범위 안의 화음음 중 목표에 가장 가까운 것을 고른다. */
    private fun limitLeap(prev: Int, wanted: Int, chord: IntArray, low: Int, high: Int): Int {
        if (abs(wanted - prev) <= MAX_LEAP) return wanted
        val lo = maxOf(low, prev - MAX_LEAP)
        val hi = minOf(high, prev + MAX_LEAP)
        if (lo > hi) return prev.coerceIn(low, high)
        val inReach = nearestTone(wanted.coerceIn(lo, hi), chord, lo, hi)
        return if (abs(inReach - prev) <= MAX_LEAP) inReach else (prev + 2 * Integer.signum(wanted - prev)).coerceIn(low, high)
    }

    // ------------------------------------------------------------------ 공통

    private fun pitch(step: Int): Int = theme.midiOf(step) + keyOffset

    private fun equivalentStep(step: Int, oldOffset: Int): Int {
        val targetMidi = theme.midiOf(step) + oldOffset
        var best = step
        var bestDistance = Int.MAX_VALUE
        for (s in step - 4..step + 4) {
            val d = abs(theme.midiOf(s) + keyOffset - targetMidi)
            if (d < bestDistance) {
                best = s
                bestDistance = d
            }
        }
        return best
    }

    private fun note(
        out: MutableList<MusicEvent>,
        bar: Int,
        eighth: Int,
        lengthEighths: Int,
        instrument: Instrument,
        step: Int,
        velocity: Int,
        expression: Int = 56,
        offsetMs: Long = 0L,
    ) {
        val start = eighthMs(bar, eighth) + offsetMs
        val end = eighthMs(bar, eighth + lengthEighths)
        val midi = pitch(step)
        // 반주 음은 시각·음높이로 정해지는 작은 세기 차이(±3)를 줘서 기계처럼 똑같이 치지 않게 한다(선율은 따로 처리).
        val human = if (instrument == Instrument.PIANO) 0 else humanize(start, midi, instrument)
        out.add(
            MusicEvent(
                atMs = start,
                durationMs = end - start,
                instrument = instrument,
                pitch = midi,
                velocity = (velocity + human).coerceIn(1, MAX_VELOCITY),
                expression = expression.coerceIn(0, 127),
            ),
        )
    }

    /** 같은 입력이면 늘 같은 값(-3..3). 난수 순서를 쓰지 않아 다른 결정에 영향을 주지 않는다. */
    private fun humanize(atMs: Long, midi: Int, instrument: Instrument): Int {
        var h = atMs * 0x9E3779B97F4A7C15uL.toLong() + midi * 0x632BE59BD9B4E019L + instrument.ordinal * 0x85157AF5L
        h = h xor (h ushr 31)
        return ((h ushr 1) % 7).toInt() - 3
    }

    private fun sortEvents(events: List<MusicEvent>): List<MusicEvent> =
        events.sortedWith(
            compareBy<MusicEvent>({ it.atMs }, { it.instrument.ordinal }, { it.pitch }, { it.durationMs }, { it.velocity }),
        )
}
