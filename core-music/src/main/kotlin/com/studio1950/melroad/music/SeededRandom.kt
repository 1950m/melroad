package com.studio1950.melroad.music

/**
 * SplitMix64 기반 난수. 플랫폼·JVM 구현에 관계없이 같은 seed에서 같은 수열을 보장하려고
 * kotlin.random 대신 직접 구현했다.
 */
class SeededRandom(seed: Long) {
    private var state: Long = seed

    fun nextLong(): Long {
        state += GOLDEN
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** 0 until bound */
    fun nextInt(bound: Int): Int {
        require(bound > 0)
        return ((nextLong() ushr 1) % bound).toInt()
    }

    /** from..to (양끝 포함) */
    fun nextInt(from: Int, to: Int): Int = from + nextInt(to - from + 1)

    /** 0.0 until 1.0 */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() / (1L shl 53).toDouble()

    fun chance(probability: Double): Boolean = nextDouble() < probability

    companion object {
        private const val GOLDEN = -0x61c8864680b583ebL

        /** 마디별 독립 수열. 앞 마디의 난수 소비량이 뒤 마디 결과에 영향을 주지 않는다. */
        fun forBar(seed: Long, bar: Int, salt: Long = 0L): SeededRandom =
            SeededRandom(seed xor (bar.toLong() * GOLDEN) xor (salt * 0x2545F4914F6CDD1DL))
    }
}
