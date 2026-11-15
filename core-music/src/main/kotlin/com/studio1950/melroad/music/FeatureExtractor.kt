package com.studio1950.melroad.music

import kotlin.math.abs

/**
 * 정제된 샘플 창에서 음악 재료가 될 특징을 뽑는다.
 *
 * 수집 주기가 차량·SDK마다 다를 수 있어 샘플 개수가 아니라 "시간 커버리지"로 신뢰도를 판단한다.
 * 커버리지가 부족한 값은 null로 돌려 작곡기가 추측으로 채우지 않게 한다.
 */
class FeatureExtractor(
    private val stopSpeedMps: Float = 0.6f,
    private val movingSpeedMps: Float = 2.0f,
    private val maxGapMs: Long = 3_000,
    /** 1초에 이 각도 이상 바뀌는 방향 변화는 GPS 튐으로 보고 버린다. */
    private val maxBearingRateDegPerS: Float = 45f,
    private val minCoverage: Float = 0.5f,
    private val altitudeCoverage: Float = 0.6f,
) {

    fun extract(window: List<CleanSample>, fromMs: Long, toMs: Long): PhraseFeature {
        val span = (toMs - fromMs).coerceAtLeast(1L)
        val inWindow = window.filter { it.timestampMs in fromMs..toMs }
        if (inWindow.size < 2) return PhraseFeature.MISSING

        // --- 속도 ---
        val speedCov = coverage(inWindow, span) { it.speedMps != null }
        var speedKmh: Float? = null
        var stoppedRatio = 0f
        if (speedCov >= minCoverage) {
            val values = inWindow.mapNotNull { it.speedMps }.sorted()
            speedKmh = values[values.size / 2] * 3.6f
            val stoppedTime = weightedTime(inWindow) { s -> s.speedMps != null && s.speedMps < stopSpeedMps }
            val knownTime = weightedTime(inWindow) { s -> s.speedMps != null }
            stoppedRatio = if (knownTime > 0) (stoppedTime.toFloat() / knownTime).coerceIn(0f, 1f) else 0f
        }

        // --- 위치 기반: 방향 변화·고도 ---
        val locationCov = coverage(inWindow, span) { it.latitude != null }
        var turnDeg: Float? = null
        var climbM: Float? = null
        if (locationCov >= minCoverage) {
            var sum = 0f
            for (i in 1 until inWindow.size) {
                val a = inWindow[i - 1]
                val b = inWindow[i]
                val dt = b.timestampMs - a.timestampMs
                if (dt <= 0 || dt > maxGapMs) continue
                val ba = a.bearingDeg ?: continue
                val bb = b.bearingDeg ?: continue
                val moving = (a.speedMps ?: movingSpeedMps) >= movingSpeedMps &&
                    (b.speedMps ?: movingSpeedMps) >= movingSpeedMps
                if (!moving) continue
                val delta = GeoMath.normalizeDelta(bb - ba)
                if (abs(delta) / (dt / 1000f) > maxBearingRateDegPerS) continue
                sum += delta
            }
            // 정차 중에는 방향 변화가 0으로 누적된다(위치는 유효하므로 null이 아니다).
            turnDeg = sum

            val altCov = coverage(inWindow, span) { it.altitudeM != null }
            if (altCov >= altitudeCoverage) {
                val alts = inWindow.filter { it.altitudeM != null }
                val n = (alts.size / 4).coerceAtLeast(1)
                val head = alts.take(n).map { it.altitudeM!! }.average()
                val tail = alts.takeLast(n).map { it.altitudeM!! }.average()
                climbM = (tail - head).toFloat()
            }
        }

        val speedOk = speedKmh != null
        val locOk = turnDeg != null
        val quality = when {
            speedOk && locOk -> SignalQuality.FULL
            speedOk -> SignalQuality.SPEED_ONLY
            locOk -> SignalQuality.LOCATION_ONLY
            else -> SignalQuality.MISSING
        }
        return PhraseFeature(speedKmh, stoppedRatio, turnDeg, climbM, quality)
    }

    /** 조건을 만족하는 연속 샘플 쌍이 덮는 시간의 비율 */
    private fun coverage(samples: List<CleanSample>, span: Long, predicate: (CleanSample) -> Boolean): Float =
        weightedTime(samples, predicate).toFloat() / span

    private fun weightedTime(samples: List<CleanSample>, predicate: (CleanSample) -> Boolean): Long {
        var covered = 0L
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            val dt = b.timestampMs - a.timestampMs
            if (dt in 1..maxGapMs && predicate(a) && predicate(b)) covered += dt
        }
        return covered
    }
}
