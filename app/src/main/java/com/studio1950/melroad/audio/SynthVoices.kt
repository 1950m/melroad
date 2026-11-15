package com.studio1950.melroad.audio

import com.studio1950.melroad.music.Instrument
import com.studio1950.melroad.music.MusicEvent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 자체 제작 합성 음색. 외부 샘플·음원을 쓰지 않아 라이선스 문제가 없다.
 *
 * - PIANO / PIANO_SOFT: 배음 5개 + 살짝 어긋난 두 번째 현(유니즌)으로 따뜻하게. 높은 배음일수록 빨리 사라진다.
 * - EPIANO: FM 합성 일렉트릭 피아노. 어택의 금속성은 빠르게 사라지고 부드러운 몸통만 남는다. 트레몰로·오토팬.
 * - STRINGS: 대역 제한 톱니파 3개(디튠) + 비브라토 + 저역 통과 필터. 느린 어택.
 * - PAD: 따뜻한 파형 2개(디튠) + 저역 통과 필터.
 * - BASS: 사인파 + 2배음, 둥근 저음.
 * - GUITAR: 카플러스-스트롱(현 튕김) 합성. 부드러운 나일론 기타처럼 들리도록 들뜸 잡음을 미리 걸러 둔다.
 * - PERC: 소프트 킥(음높이가 내려가는 사인파 + 아주 짧은 어택), 스네어(몸통 톤 + 걸러진 잡음), 림(짧은 톡),
 *   닫힌·열린 하이햇(고역 잡음), 심벌 스웰(서서히 차오르는 잡음). 날카로운 어택 없이 작게.
 * - 킥이 울리면 패드·현악·일렉트릭 피아노·기타가 아주 살짝 숨을 쉬듯 줄었다 돌아온다(사이드체인). 리듬과 화음이 한 덩어리로 들린다.
 *
 * 청각 피로를 줄이려고 날카로운 어택과 고배음을 약하게 잡고, 각 음색의 일부를 코러스 버스로 보낸다.
 */
internal class VoicePool(private val sampleRate: Int, size: Int = 64) {

    private val voices = Array(size) { Voice() }
    private var serial = 0L

    // 사이드체인: 킥이 울리는 순간 DUCK_DEPTH까지 내려갔다가 DUCK_RECOVERY_S에 걸쳐 돌아온다.
    private var duck = 1f
    private var kickPending = false
    private val duckRecover = (1.0 - exp(-1.0 / (DUCK_RECOVERY_S * sampleRate))).toFloat()
    private var duckBuf = FloatArray(1024)

    /** 오케스트라 샘플. 올라오기 전(또는 읽기 실패)에는 null이고 그동안은 합성 음색으로 연주한다. */
    @Volatile
    var library: SampleLibrary? = null

    fun trigger(event: MusicEvent) {
        // 오케스트라 편성에서는 팝 드럼(킥·스네어·하이햇)과 그에 맞춘 펌핑을 쓰지 않는다. 심벌 스웰 자리만 팀파니 롤로 연주한다.
        if (library != null && event.instrument == Instrument.PERC && event.pitch != 49) return
        val v = voices.firstOrNull { !it.active }
            ?: voices.filter { it.releasing }.minByOrNull { it.startSerial }
            ?: voices.minBy { it.startSerial }
        v.start(event, sampleRate, serial++, library)
        if (event.instrument == Instrument.PERC && event.pitch == 36) kickPending = true
    }

    fun allOff() {
        for (v in voices) v.active = false
        duck = 1f
        kickPending = false
    }

    /** 건조 신호는 left/right에, 코러스 보낼 양은 busL/busR에 더한다. [from, to) 구간. */
    fun render(left: FloatArray, right: FloatArray, busL: FloatArray, busR: FloatArray, from: Int, to: Int) {
        if (from >= to) return
        if (duckBuf.size < to) duckBuf = FloatArray(to)
        for (i in from until to) {
            if (kickPending) {
                duck = minOf(duck, DUCK_DEPTH)
                kickPending = false
            }
            duck += (1f - duck) * duckRecover
            duckBuf[i] = duck
        }
        for (v in voices) if (v.active) v.render(left, right, busL, busR, duckBuf, from, to)
    }

    private class Voice {
        var active = false
        var releasing = false
        var startSerial = 0L

        private var instrument = Instrument.PIANO
        private var sr = 44_100.0
        private val freq = DoubleArray(6)
        private val phase = DoubleArray(6)
        private val amp = FloatArray(6)
        private val decay = FloatArray(6)
        private var partials = 0
        private var gainL = 0f
        private var gainR = 0f
        private var send = 0f
        private var age = 0L
        private var noteOffAt = 0L
        private var attackSamples = 1
        private var releaseMul = 1f
        private var releaseLevel = 1f
        private var level = 1f

        // FM(일렉트릭 피아노)
        private var modPhase = 0.0
        private var modIndex = 0f
        private var modDecay = 1f

        // 기타(카플러스-스트롱)
        private var ks: FloatArray? = null
        private var ksWrite = 0
        private var ksDelay = 100.0
        private var ksPrev = 0f
        private var ksDecay = 0.99f
        private var ksLevel = 0f

        // 타악기: 몸통 톤(사인)과 잡음을 따로 감쇠시킨다
        private var percType = 42
        private var noiseState = 1L
        private var noiseLow = 0f
        private var bodyAmp = 0f
        private var bodyDecay = 1f
        private var bodyFreq = 0.0
        private var swellSamples = 0L
        private var ducks = false

        // 샘플 재생(오케스트라)
        private var sample: ShortArray? = null
        private var samplePos = 0.0
        private var sampleStep = 1.0 // 출력 한 프레임마다 샘플 안에서 나아가는 거리
        private var sampleGain = 0f
        private var loopStart = 0.0
        private var loopEnd = 0.0
        private var loopFade = 0.0
        private var looping = false

        // 필터·비브라토
        private var lpCoef = 1f
        private var lpL = 0f
        private var lpR = 0f
        private var lfoRate = 0.0

        fun start(e: MusicEvent, sampleRate: Int, serialNo: Long, library: SampleLibrary?) {
            sr = sampleRate.toDouble()
            instrument = e.instrument
            startSerial = serialNo
            active = true
            releasing = false
            age = 0
            noteOffAt = e.durationMs * sampleRate / 1000
            releaseLevel = 1f
            lpL = 0f
            lpR = 0f
            modPhase = 0.0
            for (i in phase.indices) phase[i] = 0.0
            val f0 = 440.0 * 2.0.pow((e.pitch - 69) / 12.0)
            val vel = (e.velocity / 127f).toDouble().pow(1.5).toFloat()
            val bright = 0.6f + (e.expression / 127f) * 0.6f
            var pan = 0f

            sample = null
            swellSamples = 0L
            val bank = library?.let { bankFor(e, it) }
            if (bank != null && library != null) {
                startSampled(e, library, bank, vel, bright)
                return
            }

            when (instrument) {
                Instrument.PIANO, Instrument.PIANO_SOFT -> {
                    val scale = if (instrument == Instrument.PIANO) 0.34f else 0.22f
                    partials = 6
                    val base = (3.0 * sqrt(261.6 / f0)).coerceIn(0.7, 4.5)
                    val shape = floatArrayOf(1f, 0.55f * bright, 0.34f * bright, 0.2f * bright, 0.11f * bright, 0.5f)
                    for (k in 0 until 5) {
                        // 약간의 비조화성으로 사인파 특유의 인공적인 느낌을 줄인다.
                        freq[k] = f0 * (k + 1) * (1.0 + 0.00035 * k * k)
                        amp[k] = vel * scale * shape[k]
                        decay[k] = exp(-1.0 / ((base / (1.0 + 0.9 * k)) * sr)).toFloat()
                    }
                    // 두 번째 현: 기음을 아주 살짝 어긋나게 겹쳐 맥놀이로 따뜻함을 만든다.
                    freq[5] = f0 * 1.0011
                    amp[5] = vel * scale * shape[5]
                    decay[5] = decay[0]
                    attackSamples = (0.005 * sr).toInt().coerceAtLeast(1)
                    releaseMul = exp(-1.0 / (0.32 * sr)).toFloat()
                    pan = ((e.pitch - 64) / 40f).coerceIn(-0.4f, 0.4f)
                    send = 0.18f
                    lpCoef = 1f
                }
                Instrument.EPIANO -> {
                    partials = 1
                    freq[0] = f0
                    amp[0] = vel * 0.30f
                    decay[0] = exp(-1.0 / ((2.2 * sqrt(261.6 / f0)).coerceIn(0.8, 3.5) * sr)).toFloat()
                    modIndex = 1.3f * (0.5f + vel)
                    modDecay = exp(-1.0 / (0.30 * sr)).toFloat()
                    attackSamples = (0.003 * sr).toInt().coerceAtLeast(1)
                    releaseMul = exp(-1.0 / (0.35 * sr)).toFloat()
                    lfoRate = 4.6
                    send = 0.5f
                    lpCoef = 1f
                }
                Instrument.STRINGS -> {
                    partials = 3
                    freq[0] = f0 * 0.9966
                    freq[1] = f0
                    freq[2] = f0 * 1.0034
                    for (k in 0 until 3) {
                        amp[k] = vel * 0.075f
                        decay[k] = 1f
                    }
                    attackSamples = (0.55 * sr).toInt()
                    releaseMul = exp(-1.0 / (0.9 * sr)).toFloat()
                    lfoRate = 5.2
                    send = 0.35f
                    lpCoef = onePole(2600.0 + 3200.0 * bright)
                }
                Instrument.PAD -> {
                    partials = 2
                    freq[0] = f0 * 0.998
                    freq[1] = f0 * 1.002
                    amp[0] = vel * 0.09f
                    amp[1] = vel * 0.09f
                    decay[0] = 1f
                    decay[1] = 1f
                    attackSamples = (1.2 * sr).toInt()
                    releaseMul = exp(-1.0 / (1.0 * sr)).toFloat()
                    lfoRate = 0.2
                    send = 0.4f
                    lpCoef = onePole(1800.0 + 1600.0 * bright)
                }
                Instrument.GUITAR -> {
                    partials = 0
                    ksDelay = (sr / f0 - 0.5).coerceIn(2.0, 2000.0)
                    val buf = ks ?: FloatArray(2048).also { ks = it }
                    noiseState = serialNo * 6364136223846793005L + 1442695040888963407L
                    var smooth = 0f
                    for (i in buf.indices) {
                        smooth += 0.42f * (nextNoise() - smooth)
                        buf[i] = smooth * vel * 0.85f
                    }
                    ksWrite = 0
                    ksPrev = 0f
                    ksLevel = 1f
                    // 음높이와 관계없이 약 2.2초에 -60dB가 되도록 주기당 감쇠를 정한다.
                    ksDecay = exp(kotlin.math.ln(0.001) / (f0 * 2.2)).toFloat()
                    attackSamples = 1
                    releaseMul = exp(-1.0 / (0.25 * sr)).toFloat()
                    pan = ((e.pitch - 60) / 50f).coerceIn(-0.3f, 0.3f)
                    send = 0.25f
                    lpCoef = onePole(3200.0)
                }
                Instrument.PERC -> {
                    partials = 0
                    percType = e.pitch
                    noiseState = serialNo * 2862933555777941757L + 3037000493L
                    noiseLow = 0f
                    phase[0] = 0.0
                    swellSamples = 0L
                    // (잡음 세기, 잡음 감쇠 초, 몸통 톤 세기, 몸통 감쇠 초, 몸통 Hz, 잡음 저역통과 Hz)
                    val spec = when (percType) {
                        36 -> doubleArrayOf(0.05, 0.006, 0.62, 0.28, 0.0, 2200.0) // 킥: 몸통 Hz는 렌더 중 내려간다
                        37 -> doubleArrayOf(0.10, 0.022, 0.16, 0.030, 420.0, 4200.0)
                        38 -> doubleArrayOf(0.13, 0.16, 0.20, 0.070, 185.0, 6500.0)
                        46 -> doubleArrayOf(0.06, 0.28, 0.0, 0.01, 0.0, 12_000.0)
                        49 -> doubleArrayOf(0.05, 0.45, 0.0, 0.01, 0.0, 8000.0)
                        else -> doubleArrayOf(0.085, 0.035, 0.0, 0.01, 0.0, 12_000.0) // 닫힌 하이햇
                    }
                    amp[0] = vel * spec[0].toFloat()
                    decay[0] = exp(-1.0 / (spec[1] * sr)).toFloat()
                    bodyAmp = vel * spec[2].toFloat()
                    bodyDecay = exp(-1.0 / (spec[3] * sr)).toFloat()
                    bodyFreq = spec[4]
                    lpCoef = onePole(spec[5])
                    attackSamples = (0.002 * sr).toInt().coerceAtLeast(1)
                    if (percType == 49) {
                        // 스웰: 이벤트 길이 동안 서서히 차오른 뒤 짧게 사라진다
                        swellSamples = (e.durationMs * sampleRate / 1000).coerceAtLeast(1)
                        attackSamples = swellSamples.toInt()
                    }
                    noteOffAt = Long.MAX_VALUE // 짧은 타악은 길이 대신 자연 감쇠로 끝난다
                    releaseMul = 1f
                    pan = when (percType) {
                        37, 38 -> -0.12f
                        42, 46 -> 0.28f
                        49 -> -0.2f
                        else -> 0f
                    }
                    send = if (percType == 38 || percType == 37 || percType == 49) 0.12f else 0f
                }
                Instrument.BASS -> {
                    partials = 2
                    freq[0] = f0
                    freq[1] = f0 * 2.0
                    amp[0] = vel * 0.34f
                    amp[1] = vel * 0.08f
                    decay[0] = 1f
                    decay[1] = exp(-1.0 / (0.8 * sr)).toFloat()
                    attackSamples = (0.015 * sr).toInt()
                    releaseMul = exp(-1.0 / (0.3 * sr)).toFloat()
                    send = 0f
                    lpCoef = onePole(1050.0)
                }
            }
            val angle = (pan + 1f) * (PI / 4).toFloat()
            gainL = cos(angle)
            gainR = sin(angle)
            ducks = instrument == Instrument.PAD || instrument == Instrument.STRINGS || instrument == Instrument.EPIANO ||
                instrument == Instrument.PIANO_SOFT || instrument == Instrument.GUITAR
        }

        private fun onePole(cutoffHz: Double): Float = (1.0 - exp(-2.0 * PI * cutoffHz / sr)).toFloat()

        /**
         * 작곡 엔진의 악기 역할 → 오케스트라 샘플 묶음.
         * 선율·반주 화음은 피아노, 분산화음·핑거피킹은 하프, 화음 지속음·대선율은 음역에 따라 바이올린·비올라·첼로 합주,
         * 짧은 저음은 첼로 피치카토·긴 저음은 첼로 활 긋기, 후렴 전 스웰은 팀파니 롤. 리듬(킥·스네어·하이햇)은 합성 그대로.
         */
        private fun bankFor(e: MusicEvent, lib: SampleLibrary): String? {
            val bank = when (e.instrument) {
                Instrument.PIANO, Instrument.EPIANO -> "piano"
                Instrument.PIANO_SOFT, Instrument.GUITAR -> "harp"
                Instrument.STRINGS -> when {
                    e.pitch >= 67 -> "violins"
                    e.pitch >= 55 -> "violas"
                    else -> "cellos"
                }
                Instrument.PAD -> when {
                    e.pitch >= 64 -> "violins"
                    e.pitch >= 52 -> "violas"
                    else -> "cellos"
                }
                Instrument.BASS -> if (e.durationMs < 700) "pizz" else "cellos"
                Instrument.PERC -> if (e.pitch == 49) "timproll" else null
            }
            return bank?.takeIf { lib.has(it) }
        }

        private fun startSampled(e: MusicEvent, lib: SampleLibrary, bank: String, vel: Float, bright: Float) {
            val s = lib.nearest(bank, e.pitch) ?: return
            sample = s.data
            samplePos = 0.0
            sampleStep = 2.0.pow((e.pitch - s.root) / 12.0) * SampleLibrary.SAMPLE_RATE / sr
            partials = 0
            val sustained = bank == "violins" || bank == "violas" || bank == "cellos" || bank == "horn" || bank == "flute"
            // 반복 구간: 샘플 뒤쪽 페이드(약 0.45초) 앞에서 끝나도록, 가운데 안정된 부분만 돌린다.
            looping = sustained
            val n = s.data.size.toDouble()
            loopEnd = (n - 0.45 * SampleLibrary.SAMPLE_RATE).coerceAtLeast(n * 0.6)
            loopStart = loopEnd * 0.55
            loopFade = 0.12 * SampleLibrary.SAMPLE_RATE

            // (세기 배율, 어택 초, 릴리스 초, 좌우 위치)
            val (gain, attack, release, pan) = when (bank) {
                // 피아노는 페달을 밟은 듯 손을 뗀 뒤에도 잠시 울린다(반주가 조금 더 길게)
                "piano" -> if (e.instrument == Instrument.EPIANO) Quad(0.58f, 0.002, 1.0, -0.08f) else Quad(0.95f, 0.002, 0.7, ((e.pitch - 64) / 60f).coerceIn(-0.25f, 0.25f))
                "harp" -> Quad(0.85f, 0.002, 1.1, -0.28f)
                "violins" -> Quad(if (e.instrument == Instrument.PAD) 0.42f else 0.62f, 0.06, 0.55, -0.35f)
                "violas" -> Quad(if (e.instrument == Instrument.PAD) 0.42f else 0.58f, 0.06, 0.55, 0.08f)
                "cellos" -> Quad(if (e.instrument == Instrument.BASS) 0.70f else 0.48f, 0.05, 0.50, 0.32f)
                "pizz" -> Quad(0.95f, 0.002, 0.45, 0.30f)
                "timproll" -> Quad(0.55f, 0.02, 0.6, -0.15f)
                else -> Quad(0.5f, 0.02, 0.4, 0f)
            }
            sampleGain = gain * vel * 1.6f
            // 지속음 패드는 더 천천히 부풀어 오른다
            val attackSeconds = if (e.instrument == Instrument.PAD) 0.45 else attack
            attackSamples = (attackSeconds * sr).toInt().coerceAtLeast(1)
            releaseMul = exp(-1.0 / (release * sr)).toFloat()
            // 여린 음일수록 조금 어둡게(실제 악기처럼)
            lpCoef = onePole(1800.0 + 9000.0 * (e.velocity / 127.0) * bright)
            send = 0f // 실제 악기 녹음에는 코러스를 걸지 않는다(전자음처럼 들리는 원인)
            val angle = (pan + 1f) * (PI / 4).toFloat()
            gainL = cos(angle)
            gainR = sin(angle)
            ducks = e.instrument == Instrument.PAD || e.instrument == Instrument.STRINGS || e.instrument == Instrument.EPIANO ||
                e.instrument == Instrument.PIANO_SOFT || e.instrument == Instrument.GUITAR
            if (bank == "timproll") {
                // 스웰 길이 동안 서서히 커진 뒤 다음 박에서 자연스럽게 사라진다
                attackSamples = (e.durationMs * this.sr / 1000).toInt().coerceAtLeast(1)
                swellSamples = attackSamples.toLong()
                noteOffAt = attackSamples.toLong()
            }
        }

        private data class Quad(val gain: Float, val attack: Double, val release: Double, val pan: Float)

        private fun nextNoise(): Float {
            noiseState = noiseState * 6364136223846793005L + 1442695040888963407L
            return ((noiseState ushr 33).toInt() / 2147483648f) * 2f - 1f
        }

        fun render(left: FloatArray, right: FloatArray, busL: FloatArray, busR: FloatArray, duckBuf: FloatArray, from: Int, to: Int) {
            for (i in from until to) {
                val t = age / sr
                val env: Float = when {
                    age < attackSamples -> {
                        val x = age.toFloat() / attackSamples
                        when {
                            instrument == Instrument.PAD || instrument == Instrument.STRINGS -> x * x * (3 - 2 * x)
                            swellSamples > 0 -> x * x * x // 스웰은 끝으로 갈수록 빠르게 차오른다
                            else -> x
                        }
                    }
                    instrument == Instrument.BASS -> (0.75 + 0.25 * exp(-t / 0.4)).toFloat()
                    else -> 1f
                }
                if (age >= noteOffAt) {
                    releasing = true
                    releaseLevel *= releaseMul
                }

                var outL = 0f
                var outR = 0f
                var alive = true
                val data = sample
                if (data != null) {
                    var p = samplePos
                    if (looping && p >= loopEnd) p -= (loopEnd - loopStart)
                    val i0 = p.toInt()
                    if (i0 + 1 >= data.size) {
                        alive = false
                    } else {
                        val frac = (p - i0).toFloat()
                        var v = (data[i0] + (data[i0 + 1] - data[i0]) * frac) / 32768f
                        if (looping && p > loopEnd - loopFade) {
                            // 반복 지점에 가까워지면 반복 시작 부분과 겹쳐 이어 붙여 '툭' 끊기는 소리를 없앤다.
                            val q = p - (loopEnd - loopStart)
                            val j0 = q.toInt()
                            val f2 = (q - j0).toFloat()
                            val v2 = (data[j0] + (data[j0 + 1] - data[j0]) * f2) / 32768f
                            val w = ((p - (loopEnd - loopFade)) / loopFade).toFloat()
                            v = v * (1f - w) + v2 * w
                        }
                        samplePos = p + sampleStep
                        lpL += lpCoef * (v - lpL)
                        val out = lpL * sampleGain
                        outL = out * gainL
                        outR = out * gainR
                    }
                } else when (instrument) {
                    Instrument.PIANO, Instrument.PIANO_SOFT, Instrument.BASS -> {
                        var s = 0f
                        alive = false
                        for (k in 0 until partials) {
                            val a = amp[k]
                            if (a < 1e-5f) continue
                            alive = true
                            phase[k] += freq[k] / sr
                            if (phase[k] >= 1.0) phase[k] -= 1.0
                            s += a * Wave.sine(phase[k])
                            amp[k] = a * decay[k]
                        }
                        if (instrument == Instrument.BASS) {
                            lpL += lpCoef * (s - lpL)
                            s = lpL
                        }
                        outL = s * gainL
                        outR = s * gainR
                    }
                    Instrument.EPIANO -> {
                        modPhase += freq[0] / sr
                        if (modPhase >= 1.0) modPhase -= 1.0
                        phase[0] += freq[0] / sr
                        if (phase[0] >= 1.0) phase[0] -= 1.0
                        val index = modIndex + 0.3f
                        val s = amp[0] * Wave.sine(phase[0] + index * Wave.sine(modPhase) / (2 * PI))
                        amp[0] *= decay[0]
                        modIndex *= modDecay
                        if (amp[0] < 1e-5f) alive = false
                        // 트레몰로 겸 오토팬: 좌우가 반대로 흔들리며 부드럽게 퍼진다
                        val trem = Wave.sine(t * lfoRate) * 0.18f
                        outL = s * (0.72f + trem)
                        outR = s * (0.72f - trem)
                    }
                    Instrument.STRINGS -> {
                        // 0.4초 뒤부터 비브라토가 서서히 들어온다
                        val vibDepth = (((t - 0.4) / 0.8).coerceIn(0.0, 1.0) * 0.0025)
                        val vib = 1.0 + vibDepth * Wave.sine(t * lfoRate)
                        var l = 0f
                        var r = 0f
                        for (k in 0 until 3) {
                            phase[k] += freq[k] * vib / sr
                            if (phase[k] >= 1.0) phase[k] -= 1.0
                            val s = amp[k] * Wave.saw(phase[k])
                            when (k) {
                                0 -> l += s
                                2 -> r += s
                                else -> {
                                    l += s * 0.7f
                                    r += s * 0.7f
                                }
                            }
                        }
                        lpL += lpCoef * (l - lpL)
                        lpR += lpCoef * (r - lpR)
                        outL = lpL
                        outR = lpR
                    }
                    Instrument.GUITAR -> {
                        val buf = ks!!
                        val size = buf.size
                        var readPos = ksWrite - ksDelay
                        if (readPos < 0) readPos += size
                        val i0 = readPos.toInt() % size
                        val i1 = (i0 + 1) % size
                        val frac = (readPos - floor(readPos)).toFloat()
                        val y = buf[i0] + (buf[i1] - buf[i0]) * frac
                        buf[ksWrite] = (y + ksPrev) * 0.5f * ksDecay
                        ksPrev = y
                        ksWrite = (ksWrite + 1) % size
                        lpL += lpCoef * (y - lpL)
                        ksLevel = maxOf(kotlin.math.abs(y), ksLevel * 0.9997f)
                        if (age > attackSamples + 2_000 && ksLevel < 1e-4f) alive = false
                        outL = lpL * gainL
                        outR = lpL * gainR
                    }
                    Instrument.PERC -> {
                        // 몸통 톤: 킥은 120Hz → 48Hz로 빠르게 내려가고, 스네어·림은 고정 음높이
                        var body = 0f
                        if (bodyAmp > 1e-5f) {
                            val f = if (percType == 36) 48.0 + 72.0 * exp(-t / 0.032) else bodyFreq
                            phase[0] += f / sr
                            if (phase[0] >= 1.0) phase[0] -= 1.0
                            body = bodyAmp * Wave.sine(phase[0])
                            bodyAmp *= bodyDecay
                        }
                        // 잡음: 저역을 덜어 낸 뒤 음색별 저역통과로 거칠지 않게
                        var noise = 0f
                        val a = amp[0]
                        if (a > 1e-5f) {
                            val n = nextNoise()
                            noiseLow += (if (percType == 42 || percType == 46) 0.35f else 0.12f) * (n - noiseLow)
                            noise = a * (n - noiseLow)
                            // 스웰은 차오르는 동안 감쇠하지 않는다
                            if (swellSamples == 0L || age >= swellSamples) amp[0] = a * decay[0]
                        }
                        lpL += lpCoef * (noise - lpL)
                        var s = body + lpL
                        if (percType == 36) s = s * 1.25f / (1f + kotlin.math.abs(s) * 0.6f) // 킥을 둥글게 살짝 눌러 따뜻하게
                        if (bodyAmp < 1e-4f && amp[0] < 1e-4f && (swellSamples == 0L || age > swellSamples)) alive = false
                        outL = s * gainL
                        outR = s * gainR
                    }
                    Instrument.PAD -> {
                        var l = 0f
                        var r = 0f
                        for (k in 0 until 2) {
                            phase[k] += freq[k] / sr
                            if (phase[k] >= 1.0) phase[k] -= 1.0
                            val s = amp[k] * Wave.warm(phase[k])
                            if (k == 0) l += s else r += s
                        }
                        val breath = 1f + 0.08f * Wave.sine(t * lfoRate)
                        lpL += lpCoef * (l * breath - lpL)
                        lpR += lpCoef * (r * breath - lpR)
                        outL = lpL
                        outR = lpR
                    }
                }

                val g = env * releaseLevel * (if (ducks) duckBuf[i] else 1f)
                val l = outL * g
                val r = outR * g
                left[i] += l
                right[i] += r
                if (send > 0f) {
                    busL[i] += l * send
                    busR[i] += r * send
                }
                age++
                if (!alive || releaseLevel < 2e-3f) {
                    active = false
                    return
                }
            }
        }
    }
}

/** 사이드체인으로 줄어드는 최저 비율(약 -3dB)과 회복 시간 */
private const val DUCK_DEPTH = 0.70f
private const val DUCK_RECOVERY_S = 0.22

/** 파형 테이블. 톱니·따뜻한 파형은 배음 수를 제한해 거친 고역(에일리어싱)을 줄였다. */
internal object Wave {
    private const val SIZE = 4096
    private val sineTable = FloatArray(SIZE + 1) { i -> sin(2 * PI * i / SIZE).toFloat() }
    private val sawTable = build { x -> (1..12).sumOf { n -> sin(2 * PI * n * x) / n } * 0.55 }
    private val warmTable = build { x ->
        sin(2 * PI * x) + 0.35 * sin(4 * PI * x) + 0.12 * sin(6 * PI * x) + 0.05 * sin(8 * PI * x)
    }

    private fun build(f: (Double) -> Double): FloatArray =
        FloatArray(SIZE + 1) { i -> f(i.toDouble() / SIZE).toFloat() }

    fun sine(phase: Double): Float = lookup(sineTable, phase)
    fun saw(phase: Double): Float = lookup(sawTable, phase)
    fun warm(phase: Double): Float = lookup(warmTable, phase)

    private fun lookup(table: FloatArray, phase: Double): Float {
        val p = phase - floor(phase)
        val x = p * SIZE
        val i = x.toInt().coerceIn(0, SIZE - 1)
        val frac = (x - i).toFloat()
        return table[i] + (table[i + 1] - table[i]) * frac
    }
}

/** 코러스: 살짝 흔들리는 짧은 지연을 좌우로 다르게 섞어 음을 넓고 부드럽게 만든다. */
internal class StereoChorus(private val sampleRate: Int) {
    private val size = 4096
    private val bufL = FloatArray(size)
    private val bufR = FloatArray(size)
    private var write = 0
    private var lfo1 = 0.0
    private var lfo2 = 0.25

    fun process(busL: FloatArray, busR: FloatArray, outL: FloatArray, outR: FloatArray, count: Int) {
        val base = 0.016 * sampleRate
        val depth = 0.004 * sampleRate
        val inc1 = 0.23 / sampleRate
        val inc2 = 0.31 / sampleRate
        for (i in 0 until count) {
            bufL[write] = busL[i]
            bufR[write] = busR[i]
            lfo1 += inc1
            lfo2 += inc2
            if (lfo1 >= 1.0) lfo1 -= 1.0
            if (lfo2 >= 1.0) lfo2 -= 1.0
            outL[i] += read(bufR, base + depth * Wave.sine(lfo1)) * 0.6f
            outR[i] += read(bufL, base + depth * Wave.sine(lfo2)) * 0.6f
            write = (write + 1) % size
        }
    }

    private fun read(buf: FloatArray, delaySamples: Double): Float {
        var pos = write - delaySamples
        while (pos < 0) pos += size
        val i = pos.toInt() % size
        val j = (i + 1) % size
        val frac = (pos - floor(pos)).toFloat()
        return buf[i] + (buf[j] - buf[i]) * frac
    }
}

/** Freeverb 구조의 스테레오 리버브(콤 8 + 올패스 4). 길고 부드러운 잔향으로 공간감을 준다. */
internal class StereoReverb(sampleRate: Int) {
    private val scale = sampleRate / 44_100.0
    private val combsL = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617).map { Comb((it * scale * HALL).toInt()) }
    private val combsR = intArrayOf(1139, 1211, 1300, 1379, 1445, 1514, 1580, 1640).map { Comb((it * scale * HALL).toInt()) }
    private val apL = intArrayOf(556, 441, 341, 225).map { AllPass((it * scale).toInt()) }
    private val apR = intArrayOf(579, 464, 364, 248).map { AllPass((it * scale).toInt()) }

    // 프리딜레이: 직접음과 잔향 사이에 짧은 틈을 둬 소리가 뭉개지지 않고 넓은 홀처럼 들리게 한다.
    private val pre = FloatArray((0.024 * sampleRate).toInt().coerceAtLeast(1))
    private var preIdx = 0

    fun process(left: FloatArray, right: FloatArray, count: Int, wet: Float) {
        for (i in 0 until count) {
            val delayed = pre[preIdx]
            pre[preIdx] = (left[i] + right[i]) * 0.015f
            preIdx = (preIdx + 1) % pre.size
            var l = 0f
            var r = 0f
            for (c in combsL) l += c.process(delayed)
            for (c in combsR) r += c.process(delayed)
            for (a in apL) l = a.process(l)
            for (a in apR) r = a.process(r)
            left[i] += l * wet
            right[i] += r * wet
        }
    }

    private class Comb(size: Int) {
        private val buf = FloatArray(size.coerceAtLeast(1))
        private var idx = 0
        private var store = 0f
        fun process(x: Float): Float {
            val y = buf[idx]
            store = y * 0.62f + store * 0.38f // 고역을 더 감쇠: 금속성 울림 대신 어둡고 부드러운 홀 잔향
            buf[idx] = x + store * 0.90f
            idx = (idx + 1) % buf.size
            return y
        }
    }

    private class AllPass(size: Int) {
        private val buf = FloatArray(size.coerceAtLeast(1))
        private var idx = 0
        fun process(x: Float): Float {
            val b = buf[idx]
            val y = -x + b
            buf[idx] = x + b * 0.5f
            idx = (idx + 1) % buf.size
            return y
        }
    }

    companion object {
        /** 콤 지연을 늘려 방 크기를 키운다(오케스트라 홀). */
        private const val HALL = 1.25

        /** 앱과 오프라인 렌더가 함께 쓰는 잔향 비율 */
        const val WET = 0.40f
    }
}
