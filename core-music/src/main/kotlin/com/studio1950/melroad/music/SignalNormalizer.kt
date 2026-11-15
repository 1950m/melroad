package com.studio1950.melroad.music

import kotlin.math.abs

/** 정제된 신호. 위치·고도·방향은 유효성 검사를 통과한 경우에만 값이 있다. */
data class CleanSample(
    val timestampMs: Long,
    /** 중간값 필터 + 지수 평활을 거친 절대 속도(m/s). */
    val speedMps: Float?,
    /** 원본 속도가 음수(후진)였는지. 작곡에는 절댓값만 쓰고 플래그는 분리 보관한다. */
    val reversing: Boolean,
    val latitude: Double?,
    val longitude: Double?,
    val altitudeM: Double?,
    val bearingDeg: Float?,
)

/**
 * 원본 신호 정제기.
 *
 * - 시각 정렬, 같은 시각 중복 제거(나중 값 유지)
 * - 속도: 비정상 값 제거 → 과거 쪽 중간값 필터 → 지수 평활. 모든 필터를 과거 샘플만 보도록(causal) 만들어
 *   LIVE의 실시간 처리와 오프라인(테스트·DEMO) 결과가 같게 했다.
 * - 위치: 범위 밖 좌표, (0,0), 물리적으로 불가능한 순간 이동(GPS 튐)을 무효 처리한다.
 * - 방향: 일정 속도 이상에서 기기가 준 bearing을 우선 쓰고, 없으면 실제 이동 거리가 충분할 때만 계산한다.
 *   오래 끊긴 뒤 다시 잡힌 위치와의 사이로는 방향을 계산하지 않는다(가짜 커브 방지).
 * - 마지막 유효값을 계속 들고 있지 않는다. 값이 없으면 null로 남긴다.
 */
class SignalNormalizer(
    private val maxSpeedMps: Float = 70f,
    private val maxJumpSpeedMps: Double = 90.0,
    private val staleLocationMs: Long = 5_000,
    private val medianWindow: Int = 5,
    private val emaAlpha: Float = 0.45f,
    private val emaResetGapMs: Long = 4_000,
    private val minMoveForBearingM: Double = 3.0,
    private val minSpeedForDeviceBearingMps: Float = 1.5f,
) {

    fun normalize(raw: List<DriveSample>): List<CleanSample> {
        if (raw.isEmpty()) return emptyList()

        val sorted = raw.sortedBy { it.timestampMs } // 안정 정렬
        val samples = ArrayList<DriveSample>(sorted.size)
        for (s in sorted) {
            if (samples.isNotEmpty() && samples.last().timestampMs == s.timestampMs) {
                samples[samples.lastIndex] = s
            } else {
                samples.add(s)
            }
        }

        val speeds = smoothSpeeds(samples)

        val out = ArrayList<CleanSample>(samples.size)
        var lastValidIndex = -1
        for (i in samples.indices) {
            val s = samples[i]
            var lat = s.latitude
            var lon = s.longitude
            var valid = lat != null && lon != null &&
                lat.isFinite() && lon.isFinite() &&
                lat in -90.0..90.0 && lon in -180.0..180.0 &&
                !(lat == 0.0 && lon == 0.0)

            var bearing: Float? = null
            if (valid && lastValidIndex >= 0) {
                val prev = samples[lastValidIndex]
                val pLat = prev.latitude!!
                val pLon = prev.longitude!!
                val cLat = lat!!
                val cLon = lon!!
                val dtMs = s.timestampMs - prev.timestampMs
                val dist = GeoMath.distanceM(pLat, pLon, cLat, cLon)
                if (dtMs > 0 && dist / (dtMs / 1000.0) > maxJumpSpeedMps) {
                    valid = false
                } else if (dtMs <= staleLocationMs && dist >= minMoveForBearingM) {
                    bearing = GeoMath.bearingDeg(pLat, pLon, cLat, cLon)
                }
            }

            val speed = speeds[i]
            val deviceBearing = s.bearingDeg?.takeIf { it.isFinite() && it in 0f..360f }
            if (valid && deviceBearing != null && speed != null && speed >= minSpeedForDeviceBearingMps) {
                bearing = deviceBearing % 360f
            }
            if (!valid) {
                lat = null
                lon = null
                bearing = null
            } else {
                lastValidIndex = i
            }

            val alt = if (valid) s.altitudeM?.takeIf { it.isFinite() && it in -500.0..9_000.0 } else null

            out.add(
                CleanSample(
                    timestampMs = s.timestampMs,
                    speedMps = speed,
                    reversing = (s.speedMps ?: 0f) < -0.1f,
                    latitude = lat,
                    longitude = lon,
                    altitudeM = alt,
                    bearingDeg = bearing,
                ),
            )
        }
        return out
    }

    private fun smoothSpeeds(samples: List<DriveSample>): List<Float?> {
        val rawAbs: List<Float?> = samples.map { s ->
            s.speedMps?.takeIf { it.isFinite() && abs(it) <= maxSpeedMps }?.let { abs(it) }
        }
        val result = ArrayList<Float?>(samples.size)
        var ema: Float? = null
        var lastValueTime = Long.MIN_VALUE
        for (i in samples.indices) {
            val v = rawAbs[i]
            if (v == null) {
                result.add(null)
                continue
            }
            // 과거 쪽 창(현재 포함)의 중간값
            val window = ArrayList<Float>(medianWindow)
            var j = i
            while (j >= 0 && window.size < medianWindow) {
                rawAbs[j]?.let { window.add(it) }
                j--
            }
            window.sort()
            val median = window[window.size / 2]
            val t = samples[i].timestampMs
            ema = if (ema == null || t - lastValueTime > emaResetGapMs) median else ema + emaAlpha * (median - ema)
            lastValueTime = t
            result.add(ema)
        }
        return result
    }
}
