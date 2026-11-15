package com.studio1950.melroad.music

/**
 * 음악 테마. 조성·템포 등 "음악적 정체성"을 고정한다.
 * 템포는 속도에 1:1로 연결하지 않는다(요구서 07: 70~86 BPM 범위의 고정 템포).
 */
data class Theme(
    val id: String,
    val displayName: String,
    /** 음계 0번 음의 MIDI 번호 */
    val rootMidi: Int,
    val scale: List<Int>,
    val bpm: Int,
) {
    init {
        require(bpm in 70..86) { "템포는 70~86 BPM 범위여야 한다" }
        require(scale.size == 7)
    }

    /** 음계 단계(0 = rootMidi, 7 = 한 옥타브 위)를 MIDI 번호로 */
    fun midiOf(step: Int): Int {
        val octave = Math.floorDiv(step, 7)
        val degree = Math.floorMod(step, 7)
        return rootMidi + 12 * octave + scale[degree]
    }
}

object Themes {
    /** 기본 테마: 라장조, 76 BPM, 피아노·패드 중심의 잔잔한 음색 */
    val WARM = Theme(
        id = "warm",
        displayName = "따뜻한 길",
        rootMidi = 62,
        scale = listOf(0, 2, 4, 5, 7, 9, 11),
        bpm = 76,
    )

    val all = listOf(WARM)

    fun byId(id: String): Theme = all.firstOrNull { it.id == id } ?: WARM
}
