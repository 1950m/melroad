package com.studio1950.melroad.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.studio1950.melroad.music.MusicEvent
import kotlin.math.abs

/**
 * MusicEvent 타임라인을 실시간으로 합성해 AudioTrack으로 내보내는 렌더러.
 *
 * - 재생 위치(playhead)는 실제로 출력한 샘플 수로 센다. 작곡 엔진은 이 값을 "음악 시간"으로 쓴다.
 * - 일시정지하면 재생 위치는 즉시 멈추고, 이미 울리던 소리만 짧게 페이드아웃된다.
 * - 시작·종료는 1.5~3초 크로스페이드(요구서 07), 출력단에 소프트 클리퍼를 둬 클리핑을 막는다.
 * - 오디오 포커스 판단은 하지 않는다(TripSessionController 담당). 여기서는 볼륨 감쇄(duck)만 수행.
 */
class AudioEngine(private val sampleRate: Int = 44_100) {

    private val timeline = EventTimeline()
    private val voices = VoicePool(sampleRate)
    private val reverb = StereoReverb(sampleRate)
    private val chorus = StereoChorus(sampleRate)
    private val lock = Object()

    @Volatile private var alive = false
    @Volatile private var advancing = false
    @Volatile private var targetGain = 0f
    @Volatile private var rampMs = 1500
    @Volatile private var ducked = false
    @Volatile private var playheadFrames = 0L
    @Volatile private var resetRequested = false

    private var thread: Thread? = null

    val playheadMs: Long get() = playheadFrames * 1000L / sampleRate

    /** 재생 위치가 진행 중인지(일시정지 페이드아웃 중에는 false) */
    val isAdvancing: Boolean get() = advancing

    /** 새 타임라인으로 교체한다. 울리던 소리는 끊는다. */
    fun load(events: List<MusicEvent>, positionMs: Long = 0L) {
        synchronized(lock) {
            advancing = false
            targetGain = 0f
            timeline.reset(events, positionMs)
            playheadFrames = positionMs * sampleRate / 1000L
            resetRequested = true
        }
    }

    fun append(events: List<MusicEvent>) {
        if (events.isNotEmpty()) timeline.append(events)
    }

    fun play(fadeInMs: Int) {
        ensureThread()
        synchronized(lock) {
            rampMs = fadeInMs.coerceAtLeast(50)
            targetGain = 1f
            advancing = true
            lock.notifyAll()
        }
    }

    fun pause(fadeOutMs: Int) {
        synchronized(lock) {
            rampMs = fadeOutMs.coerceAtLeast(50)
            targetGain = 0f
            advancing = false
        }
    }

    /** 위치는 유지하고 소리만 서서히 줄인다(종지 여운 처리용). 이후 pause로 정지한다. */
    fun fadeOut(fadeOutMs: Int) {
        rampMs = fadeOutMs.coerceAtLeast(50)
        targetGain = 0f
    }

    fun setDucked(value: Boolean) {
        ducked = value
    }

    fun release() {
        synchronized(lock) {
            alive = false
            advancing = false
            lock.notifyAll()
        }
        thread?.join(500)
        thread = null
    }

    private fun ensureThread() {
        if (thread?.isAlive == true) return
        alive = true
        thread = Thread({ renderLoop() }, "melroad-audio").also { it.start() }
    }

    private fun renderLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, BLOCK * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack 생성 실패: ${e.javaClass.simpleName}")
            alive = false
            return
        }

        val left = FloatArray(BLOCK)
        val right = FloatArray(BLOCK)
        val busL = FloatArray(BLOCK)
        val busR = FloatArray(BLOCK)
        val pcm = ShortArray(BLOCK * 2)
        val due = ArrayList<MusicEvent>()
        var gain = 0f
        var trackPlaying = false

        try {
            while (alive) {
                if (resetRequested) {
                    voices.allOff()
                    gain = 0f
                    resetRequested = false
                }

                // 완전히 조용해지면 트랙을 멈추고 재생 요청을 기다린다(CPU·배터리 절약).
                if (!advancing && gain < 1e-4f && targetGain == 0f) {
                    if (trackPlaying) {
                        track.pause()
                        track.flush()
                        trackPlaying = false
                    }
                    voices.allOff()
                    synchronized(lock) {
                        while (alive && !advancing) lock.wait(250)
                    }
                    continue
                }
                if (!trackPlaying) {
                    track.play()
                    trackPlaying = true
                }

                left.fill(0f)
                right.fill(0f)
                busL.fill(0f)
                busR.fill(0f)

                val blockStart = playheadFrames
                if (advancing) {
                    due.clear()
                    timeline.drain(framesToMsCeil(blockStart + BLOCK), due)
                    var cursor = 0
                    for (e in due) {
                        val frame = e.atMs * sampleRate / 1000L
                        val offset = (frame - blockStart).coerceIn(0L, (BLOCK - 1).toLong()).toInt()
                        if (offset > cursor) {
                            voices.render(left, right, busL, busR, cursor, offset)
                            cursor = offset
                        }
                        voices.trigger(e)
                    }
                    voices.render(left, right, busL, busR, cursor, BLOCK)
                } else {
                    voices.render(left, right, busL, busR, 0, BLOCK)
                }

                chorus.process(busL, busR, left, right, BLOCK)
                reverb.process(left, right, BLOCK, WET)

                val duckFactor = if (ducked) DUCK_LEVEL else 1f
                val goal = targetGain * duckFactor
                val step = 1f / (rampMs / 1000f * sampleRate)
                for (i in 0 until BLOCK) {
                    gain = when {
                        abs(goal - gain) <= step -> goal
                        goal > gain -> gain + step
                        else -> gain - step
                    }
                    pcm[i * 2] = toPcm(softClip(left[i] * gain * MASTER))
                    pcm[i * 2 + 1] = toPcm(softClip(right[i] * gain * MASTER))
                }
                track.write(pcm, 0, pcm.size)
                if (advancing) playheadFrames = blockStart + BLOCK
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "렌더링 중단: ${e.javaClass.simpleName}")
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun framesToMsCeil(frames: Long): Long = (frames * 1000L + sampleRate - 1) / sampleRate

    private fun softClip(x: Float): Float {
        // tanh 근사. ±1 근처에서 부드럽게 눌러 클리핑 잡음을 막는다.
        val c = x.coerceIn(-3f, 3f)
        return c * (27f + c * c) / (27f + 9f * c * c)
    }

    private fun toPcm(x: Float): Short = (x.coerceIn(-1f, 1f) * 32_000f).toInt().toShort()

    companion object {
        private const val TAG = "MelroadAudio"
        private const val BLOCK = 512
        private const val WET = 0.32f
        private const val MASTER = 1.4f
        private const val DUCK_LEVEL = 0.3f
    }
}
