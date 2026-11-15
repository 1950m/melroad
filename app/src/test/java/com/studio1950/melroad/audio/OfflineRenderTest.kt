package com.studio1950.melroad.audio

import com.studio1950.melroad.music.DriveFixtures
import com.studio1950.melroad.music.MusicEvent
import com.studio1950.melroad.music.OfflineComposition
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 실제 앱과 같은 합성 음색(VoicePool·코러스·리버브)으로 시뮬레이션 곡 일부를 WAV로 렌더링한다.
 * 청취 확인용 미리듣기(app/build/preview/)와 클리핑·음량 점검을 겸한다.
 */
class OfflineRenderTest {

    private val sampleRate = 44_100

    @Test
    fun renderCoastPreview_withoutClipping() {
        val result = OfflineComposition.compose(DriveFixtures.COAST)
        val clips = listOf(0L to 90_000L, 600_000L to 75_000L, result.endMs - 40_000L to 40_000L)
        val names = listOf("coast-start", "coast-10min", "coast-ending")
        val dir = File("build/preview").apply { mkdirs() }
        for ((i, clip) in clips.withIndex()) {
            val (startMs, lengthMs) = clip
            val stats = render(result.events, startMs, lengthMs, File(dir, "${names[i]}.wav"))
            println("${names[i]}: peak=${"%.3f".format(stats.first)} rms=${"%.3f".format(stats.second)}")
            assertTrue("클리핑 ${names[i]} peak=${stats.first}", stats.first < 0.99)
            assertTrue("너무 작은 소리 ${names[i]} rms=${stats.second}", stats.second > 0.02)
        }
    }

    /** @return (peak, rms) — 최종 출력단(컴프레서·소프트 클립) 이후 값 기준 */
    private fun render(events: List<MusicEvent>, startMs: Long, lengthMs: Long, out: File): Pair<Double, Double> {
        val voices = VoicePool(sampleRate)
        // 앱과 같은 오케스트라 샘플(소스 폴더의 assets)을 쓴다. 없으면 합성 음색으로 렌더링된다.
        voices.library = SampleLibrary.load { name -> File("src/main/assets/orchestra/$name").takeIf { it.exists() }?.inputStream() }
        check(voices.library != null) { "오케스트라 샘플을 읽지 못함" }
        val chorus = StereoChorus(sampleRate)
        val reverb = StereoReverb(sampleRate)
        val master = MasterBus(sampleRate)
        val block = 512
        val totalFrames = (lengthMs * sampleRate / 1000).toInt()
        val left = FloatArray(block)
        val right = FloatArray(block)
        val busL = FloatArray(block)
        val busR = FloatArray(block)
        val pcm = ShortArray(totalFrames * 2)
        val sorted = events.filter { it.atMs >= startMs && it.atMs < startMs + lengthMs }.sortedBy { it.atMs }
        var cursor = 0
        var peak = 0.0
        var sumSq = 0.0
        var frame = 0
        val startFrame = startMs * sampleRate / 1000
        while (frame < totalFrames) {
            val n = minOf(block, totalFrames - frame)
            left.fill(0f); right.fill(0f); busL.fill(0f); busR.fill(0f)
            val blockStart = startFrame + frame
            var pos = 0
            while (cursor < sorted.size) {
                val e = sorted[cursor]
                val f = e.atMs * sampleRate / 1000
                if (f >= blockStart + n) break
                val offset = (f - blockStart).coerceIn(0L, (n - 1).toLong()).toInt()
                if (offset > pos) {
                    voices.render(left, right, busL, busR, pos, offset)
                    pos = offset
                }
                voices.trigger(e)
                cursor++
            }
            voices.render(left, right, busL, busR, pos, n)
            chorus.process(busL, busR, left, right, n)
            reverb.process(left, right, n, StereoReverb.WET)
            for (i in 0 until n) {
                left[i] *= 1.4f
                right[i] *= 1.4f
            }
            master.process(left, right, n)
            for (i in 0 until n) {
                val l = left[i]
                val r = right[i]
                peak = maxOf(peak, abs(l).toDouble(), abs(r).toDouble())
                sumSq += (l * l + r * r) / 2.0
                pcm[(frame + i) * 2] = (l * 32_000f).toInt().toShort()
                pcm[(frame + i) * 2 + 1] = (r * 32_000f).toInt().toShort()
            }
            frame += n
        }
        writeWav(out, pcm)
        return peak to sqrt(sumSq / totalFrames)
    }

    private fun writeWav(file: File, pcm: ShortArray) {
        DataOutputStream(FileOutputStream(file).buffered()).use { o ->
            val dataBytes = pcm.size * 2
            fun le32(v: Int) { o.write(v and 0xff); o.write(v shr 8 and 0xff); o.write(v shr 16 and 0xff); o.write(v shr 24 and 0xff) }
            fun le16(v: Int) { o.write(v and 0xff); o.write(v shr 8 and 0xff) }
            o.writeBytes("RIFF"); le32(36 + dataBytes); o.writeBytes("WAVE")
            o.writeBytes("fmt "); le32(16); le16(1); le16(2); le32(sampleRate); le32(sampleRate * 4); le16(4); le16(16)
            o.writeBytes("data"); le32(dataBytes)
            for (s in pcm) le16(s.toInt())
        }
    }
}
