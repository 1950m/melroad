package com.studio1950.melroad.audio

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * 오케스트라 샘플 묶음(assets/orchestra). VSCO 2 Community Edition(CC0)과 Upright Piano(Simon Dalzell / Ivy Audio,
 * Versilian Studios가 재배포 허용)를 22.05kHz 모노 16비트 PCM으로 줄여 넣었다. 출처: assets/orchestra/LICENSE.txt
 *
 * index.txt 한 줄 = "묶음 MIDI음높이 파일이름 프레임수". 연주할 때는 가장 가까운 음높이의 샘플을 골라 재생 속도로 맞춘다.
 */
internal class SampleLibrary private constructor(private val banks: Map<String, List<Sample>>) {

    class Sample(val root: Int, val data: ShortArray)

    fun has(bank: String): Boolean = banks[bank]?.isNotEmpty() == true

    fun nearest(bank: String, midi: Int): Sample? = banks[bank]?.minByOrNull { abs(it.root - midi) }

    val totalFrames: Long get() = banks.values.sumOf { list -> list.sumOf { it.data.size.toLong() } }

    companion object {
        const val SAMPLE_RATE = 22_050
        private const val INDEX = "index.txt"

        /**
         * @param open 파일 이름 → 스트림(앱: AssetManager, 테스트: 소스 폴더). 하나라도 읽지 못하면 null을 돌려
         * 합성 음색으로 대신 연주하게 한다(부분적으로만 올라간 상태로 섞어 쓰지 않는다).
         */
        fun load(open: (String) -> InputStream?): SampleLibrary? = runCatching {
            val lines = open(INDEX)?.bufferedReader()?.use { it.readLines() } ?: return null
            val banks = HashMap<String, MutableList<Sample>>()
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val (bank, midi, file, frames) = line.trim().split(' ')
                val bytes = open(file)?.use { it.readBytes() } ?: return null
                val data = ShortArray(bytes.size / 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(data)
                check(data.size == frames.toInt()) { "샘플 길이 불일치: $file" }
                banks.getOrPut(bank) { ArrayList() }.add(Sample(midi.toInt(), data))
            }
            SampleLibrary(banks)
        }.getOrNull()
    }
}
