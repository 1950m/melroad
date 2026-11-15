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
        private val R_SYNC = listOf(1 to 1, 2 to 2, 4 to 3, 7 to 1)
        private val R_FLOW = listOf(0 to 1, 1 to 1, 2 to 2, 4 to 1, 5 to 1, 6 to 2)
        private val R_QQQQ = listOf(0 to 2, 2 to 2, 4 to 2, 6 to 2)
        private val R_QUESTION = listOf(0 to 6, 6 to 1, 7 to 1) // 긴 음 뒤 다음 악절로 이어 주는 못갖춘 음
        private val R_ANSWER = listOf(0 to 8)
        private val R_SPARSE = listOf(0 to 4)
    }

    private class PhrasePlan(
        val rhythms: List<List<Pair<Int, Int>>>,
        val targets: IntArray,
        val appoggiatura: BooleanArray,
        val endStable: Boolean,
    )

    private val verseCells: Pair<List<Pair<Int, Int>>, List<Pair<Int, Int>>>
    private val chorusCells: Pair<List<Pair<Int, Int>>, List<Pair<Int, Int>>>

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
        val verseChoices = listOf(R_DOTTED, R_QQH, R_QEEH, R_HQQ)
        val chorusChoices = listOf(R_DOTTED, R_QEEH, R_SYNC, R_QQH)
        verseCells = verseChoices[rng.nextInt(verseChoices.size)] to verseChoices[rng.nextInt(verseChoices.size)]
        chorusCells = chorusChoices[rng.nextInt(chorusChoices.size)] to chorusChoices[rng.nextInt(chorusChoices.size)]
        template = FORM_TEMPLATES[rng.nextInt(FORM_TEMPLATES.size)]
    }

    val barMs: Long get() = barStartMs(1)

    fun barStartMs(bar: Int): Long = eighthMs(bar, 0)

    /** 8분음표 위치 → ms. 정수 연산이라 기기와 관계없이 같은 값이 나온다. */
    fun eighthMs(bar: Int, eighth: Int): Long =
        ((bar.toLong() * EIGHTHS_PER_BAR + eighth) * 30_000L) / bpm

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
        val question = phraseInSection % 2 == 0
        val endCell = if (question) R_QUESTION else R_ANSWER
        val (c1, c2) = when (section) {
            SectionType.VERSE -> verseCells
            SectionType.CHORUS, SectionType.BRIDGE -> chorusCells
            SectionType.INTERLUDE -> R_HQQ to R_SPARSE
            SectionType.INTRO, SectionType.REST -> return null
        }
        val rhythms = listOf(c1, c2, vary(c1, rng), endCell).mapIndexed { i, cell ->
            if (i == 3) cell else adaptRhythm(cell, rng)
        }
        // 악절마다 선율의 높낮이 중심을 조금씩 옮기고, 후렴은 한층 높여 고조시킨다.
        val shift = (rng.nextInt(3) - 1) + (if (section == SectionType.CHORUS) 2 else 0)
        val base = (if (question) intArrayOf(3, 6, 8, 5) else intArrayOf(6, 8, 5, 2)).map { (it + shift).coerceIn(0, MELODY_SPAN) }.toIntArray()
        val targets = when (contour) {
            1 -> intArrayOf(base[0], base[1] + 1, base[2] + 2, base[3] + 1) // 오른쪽 굽이: 위로 열리는 선율
            -1 -> intArrayOf(base[2] + 1, base[1], base[0] - 1, base[3] - 1) // 왼쪽 굽이: 내려앉는 선율
            else -> base
        }
        val appoggiatura = BooleanArray(4) { i -> i < 3 && section != SectionType.INTERLUDE && rng.chance(0.3) }
        return PhrasePlan(rhythms, targets, appoggiatura, endStable = !question)
    }

    /** 리듬 변주: 긴 음 하나를 둘로 나눠 같은 주제를 조금 다르게 들려준다. */
    private fun vary(cell: List<Pair<Int, Int>>, rng: SeededRandom): List<Pair<Int, Int>> {
        val splittable = cell.indices.filter { cell[it].second >= 2 && cell[it].second % 2 == 0 }
        if (splittable.isEmpty() || rng.chance(0.35)) return cell
        val i = splittable[rng.nextInt(splittable.size)]
        val (e, len) = cell[i]
        val half = len / 2
        return cell.subList(0, i) + listOf(e to half, e + half to half) + cell.subList(i + 1, cell.size)
    }

    /** 속도 밀도에 따라 리듬 셀을 조정한다. 빠를수록 음표가 잘게 나뉘지만 세기는 오르지 않는다. */
    private fun adaptRhythm(cell: List<Pair<Int, Int>>, rng: SeededRandom): List<Pair<Int, Int>> = when (density) {
        0 -> R_SPARSE
        1 -> cell
        2 -> if (cell.size <= 3 && rng.chance(0.5)) R_QEEH else cell
        else -> if (rng.chance(0.5)) R_FLOW else if (rng.chance(0.5)) R_SYNC else R_QQQQ
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
                addBassNote(out, bar, 0, 4, degree, velocity)
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
                    0, 1 -> {
                        if (length >= 8) {
                            addEpChord(out, bar, 0, 4, degree, 36)
                            addEpChord(out, bar, 4, 4, degree, 32)
                        } else {
                            addEpChord(out, bar, start, length, degree, 36)
                        }
                    }
                    2 -> if (length >= 8) {
                        addEpChord(out, bar, 0, 3, degree, 36)
                        addEpChord(out, bar, 3, 3, degree, 32)
                        addEpChord(out, bar, 6, 2, degree, 30)
                    } else {
                        addEpChord(out, bar, start, length, degree, 34)
                    }
                    else -> {
                        addEpChord(out, bar, start, length, degree, 32)
                        addArpeggio(out, bar, start, length, degree, eighths = true, velocity = 28, rng = rng)
                    }
                }
                SectionType.CHORUS -> {
                    addEpChord(out, bar, start, length, degree, 30)
                    if (density >= 1) addArpeggio(out, bar, start, length, degree, eighths = density >= 2, velocity = 30, rng = rng)
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
        for (s in steps) note(out, bar, startEighth, lengthEighths, Instrument.EPIANO, s, velocity)
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
     * 부드러운 리듬: 소프트 킥(36)·브러시(38)·셰이커(42). 날카로운 타격음 없이 가볍게 흐름만 준다.
     * 정차(쉼)·도입에서는 쉬고, 밀도에 따라 촘촘해지되 세기는 오르지 않는다.
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
                hit(0, 36, 46); hit(4, 36, 40)
                hit(2, 38, 30); hit(6, 38, 30)
                for (e in 0 until 8) hit(e, 42, if (e % 2 == 0) 22 else 16)
            }
            SectionType.VERSE -> {
                hit(0, 36, 42)
                if (density >= 2) {
                    hit(4, 36, 36)
                    hit(2, 38, 26); hit(6, 38, 26)
                }
                val shakerStep = if (density >= 3) 1 else 2
                for (e in 0 until 8 step shakerStep) hit(e, 42, if (e % 2 == 0) 20 else 14)
            }
            SectionType.BRIDGE -> {
                hit(0, 36, 38)
                for (e in 0 until 8 step 2) hit(e, 42, 16)
            }
            SectionType.INTERLUDE -> if (density >= 2) {
                for (e in 2 until 8 step 4) hit(e, 42, 14)
            }
            else -> Unit
        }
        // 가끔 4박에 작은 브러시 장식
        if (section != SectionType.INTERLUDE && bar % BARS_PER_PHRASE == 3 && rng.chance(0.5)) hit(7, 38, 20)
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

    private fun addMelody(out: MutableList<MusicEvent>, bar: Int, rng: SeededRandom) {
        val p = plan ?: return
        val barInPhrase = bar % BARS_PER_PHRASE
        val rhythm = p.rhythms[barInPhrase]
        if (rhythm.isEmpty()) return
        // 순환마다 주제를 맡는 악기를 바꿔 같은 선율도 새롭게 들리게 한다.
        val instrument = when (section) {
            SectionType.INTERLUDE -> Instrument.GUITAR
            SectionType.BRIDGE -> Instrument.STRINGS
            SectionType.VERSE -> if (formCycle % 3 == 1) Instrument.EPIANO else Instrument.PIANO
            else -> Instrument.PIANO
        }
        val (low, high) = melodyRange()
        val slots = harmonyOf(bar)
        val target = (low + p.targets[barInPhrase]).coerceIn(low, high)
        val baseVelocity = when (density) {
            0, 1 -> 60
            2 -> 57
            else -> 54 // 빠를수록 오히려 여리게: 속도 상승을 보상처럼 들리게 하지 않는다.
        } + (if (barInPhrase == 1 || barInPhrase == 2) 3 else 0) + sectionDynamics()
        val expression = (62 + registerStep * 10).coerceIn(0, 127)

        var prev = lastMelodyStep
        for ((i, slot) in rhythm.withIndex()) {
            val (eighth, length) = slot
            val degree = slots.last { it.first <= eighth }.third
            val chord = Chords.triad(degree)
            val strong = i == 0 || eighth == 4 || length >= 4
            val lastOfPhrase = barInPhrase == 3 && i == rhythm.lastIndex
            val step = if (strong) {
                val wanted = if (lastOfPhrase && p.endStable) {
                    nearestTone(prev, intArrayOf(degree, degree + 2), low, high)
                } else {
                    nearestTone(target, chord, low, high)
                }
                limitLeap(prev, wanted, chord, low, high)
            } else {
                // 경과음: 다음 목표 쪽으로 한 단계. 이미 목표에 있으면 이웃음.
                val dir = Integer.signum(target - prev).let { if (it == 0) (if (rng.chance(0.5)) 1 else -1) else it }
                (prev + dir).coerceIn(low, high)
            }
            var velocity = baseVelocity + rng.nextInt(-4, 4) + (if (i == 0) 2 else 0)
            velocity = velocity.coerceIn(30, 80)

            if (i == 0 && length >= 3 && p.appoggiatura[barInPhrase] && abs(step - prev) <= 2 && step + 1 <= high) {
                // 앞꾸밈음: 위 이웃음에서 화음음으로 내려와 해결(달콤한 긴장)
                note(out, bar, eighth, 1, instrument, step + 1, velocity - 6, expression)
                note(out, bar, eighth + 1, length - 1, instrument, step, velocity, expression)
            } else {
                note(out, bar, eighth, length, instrument, step, velocity, expression)
            }
            // 후렴: 긴 음을 현악이 한 옥타브 아래에서 함께 노래한다
            if (section == SectionType.CHORUS && length >= 3) {
                note(out, bar, eighth, length, Instrument.STRINGS, step - 7, (velocity - 18).coerceAtLeast(24), expression)
            }
            prev = step
        }
        lastMelodyStep = prev
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
    ) {
        val start = eighthMs(bar, eighth)
        val end = eighthMs(bar, eighth + lengthEighths)
        out.add(
            MusicEvent(
                atMs = start,
                durationMs = end - start,
                instrument = instrument,
                pitch = pitch(step),
                velocity = velocity.coerceIn(1, MAX_VELOCITY),
                expression = expression.coerceIn(0, 127),
            ),
        )
    }

    private fun sortEvents(events: List<MusicEvent>): List<MusicEvent> =
        events.sortedWith(
            compareBy<MusicEvent>({ it.atMs }, { it.instrument.ordinal }, { it.pitch }, { it.durationMs }, { it.velocity }),
        )
}
