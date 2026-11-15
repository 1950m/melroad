package com.studio1950.melroad.music

/** 곡의 큰 구간. 긴 여정에서도 지루하지 않도록 구간이 순환하며 바뀐다. */
enum class SectionType { INTRO, VERSE, CHORUS, INTERLUDE, BRIDGE, REST }

/**
 * 4마디 화성 진행. bars[i]는 i번째 마디의 화음 근음(장음계 단계 0~6) 목록이다.
 * 2개면 반 마디씩 연주한다. 화음 성격은 장음계 안의 7화음(Imaj7, ii7, iii7, IVmaj7, V7, vi7)을 따른다.
 */
data class ProgressionDef(val id: String, val bars: List<List<Int>>) {
    init {
        require(bars.size == 4)
    }
}

object Progressions {
    val INTRO = ProgressionDef("INTRO", listOf(listOf(0), listOf(3), listOf(0), listOf(4)))

    // 기본(Verse)
    val A1 = ProgressionDef("A1", listOf(listOf(0), listOf(5), listOf(3), listOf(4))) // I–vi–IV–V
    val A2 = ProgressionDef("A2", listOf(listOf(0), listOf(2), listOf(3), listOf(4))) // I–iii–IV–V
    val A3 = ProgressionDef("A3", listOf(listOf(0), listOf(4), listOf(5), listOf(3))) // I–V–vi–IV

    // 후렴(Chorus): 4–5–3–6 진행과 반 마디 턴어라운드
    val B1 = ProgressionDef("B1", listOf(listOf(3), listOf(4), listOf(2), listOf(5))) // IV–V–iii–vi
    val B2 = ProgressionDef("B2", listOf(listOf(3, 4), listOf(2, 5), listOf(1, 4), listOf(0))) // IV V | iii vi | ii V | I

    // 굽은 길: 진행 방향에 따라 색채를 바꾼다
    val CR = ProgressionDef("CR", listOf(listOf(5), listOf(3), listOf(0), listOf(4))) // vi–IV–I–V (오른쪽)
    val CL = ProgressionDef("CL", listOf(listOf(3), listOf(0), listOf(4), listOf(5))) // IV–I–V–vi (왼쪽)

    // 간주·브리지·쉼
    val I1 = ProgressionDef("I1", listOf(listOf(0), listOf(3), listOf(5), listOf(4))) // I–IV–vi–V
    val BR1 = ProgressionDef("BR1", listOf(listOf(5), listOf(2), listOf(3), listOf(0))) // vi–iii–IV–I
    val BR2 = ProgressionDef("BR2", listOf(listOf(1), listOf(2), listOf(3), listOf(4))) // ii–iii–IV–V (오르막의 고조)
    val R1 = ProgressionDef("R1", listOf(listOf(0), listOf(3), listOf(0), listOf(3))) // I–IV 머무름
    val R2 = ProgressionDef("R2", listOf(listOf(3), listOf(0), listOf(5), listOf(0))) // IV–I–vi–I

    fun choose(
        section: SectionType,
        phraseInSection: Int,
        turnSign: Int,
        climbUp: Boolean,
        rng: SeededRandom,
    ): ProgressionDef {
        val curve = when {
            turnSign > 0 -> CR
            turnSign < 0 -> CL
            else -> null
        }
        return when (section) {
            SectionType.INTRO -> INTRO
            SectionType.REST -> if (rng.chance(0.5)) R1 else R2
            SectionType.VERSE -> curve ?: listOf(A1, A2, A3)[rng.nextInt(3)]
            SectionType.CHORUS -> curve ?: if (phraseInSection % 2 == 0) B1 else B2
            SectionType.INTERLUDE -> curve ?: I1
            SectionType.BRIDGE -> if (climbUp) BR2 else BR1
        }
    }
}

internal object Chords {
    /** 9음을 더해도 거칠지 않은 화음(iii, vii 제외) */
    fun allowsNinth(degree: Int): Boolean = Math.floorMod(degree, 7) in setOf(0, 1, 3, 4, 5)

    fun triad(degree: Int): IntArray = intArrayOf(degree, degree + 2, degree + 4)

    /** step을 [lo, hi] 안으로 옥타브(7단계) 이동 */
    fun fold(step: Int, lo: Int, hi: Int): Int {
        var s = step
        while (s < lo) s += 7
        while (s > hi) s -= 7
        return s
    }
}
