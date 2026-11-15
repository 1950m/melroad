package com.studio1950.melroad.ui

import com.studio1950.melroad.music.DriveSample
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 주행 장면이 앞길 모양을 그릴 때 쓰는 원본 신호와 재생 시각. 화면 표시 전용이며 저장하지 않는다.
 * [samples]는 음악과 같은 시간축(ms)의 신호 조회, [position]은 지금 재생 시각이다.
 */
class RoadFeed(
    val samples: (fromMs: Long, toMs: Long) -> List<DriveSample>,
    val position: () -> Long,
)

/**
 * 차 기준 좌표의 앞길 중심선. 앞쪽 거리 [forward], 오른쪽 [lateral], 차 위치 노면 기준 높이 [height](m),
 * 진행 방향 [angle](라디안, 오른쪽 +). 점 간격은 [RoadBuilder.STEP_M]이고 [firstIndex]는 첫 점의 땅 기준 번호다
 * (차선 점선·반사봉을 노면에 고정해 차가 나아가는 만큼만 흘러가게 하는 데 쓴다).
 */
class RoadGeometry(
    val count: Int,
    val forward: FloatArray,
    val lateral: FloatArray,
    val height: FloatArray,
    val angle: FloatArray,
    val firstIndex: Int,
    /** 차 바로 앞(약 30m) 굽이 -1..1. 차체를 살짝 기울이는 데 쓴다. */
    val nearCurve: Float,
    /** 차 위치의 경사(오르막 +). 시선을 길 따라 반쯤 들어 올리는 데 쓴다. */
    val slope: Float,
    val speedMps: Float,
    /** 지금 진행 방위(도). 방향 신호가 없으면 null — 먼 산 배경을 움직이지 않는다. */
    val headingDeg: Float?,
)

/**
 * 원본 신호(방위·고도·속도)로 앞길 모양을 만든다.
 *
 * - 시뮬레이션처럼 앞으로의 신호가 이미 있으면 실제로 다가올 굽이와 오르내림을 그대로 그린다.
 * - 실제 주행처럼 지난 신호만 있으면 최근 굽이·경사를 이어 그리되 멀어질수록 직선·평지로 풀어 준다.
 *   다가올 길을 지어내지 않기 위해서다.
 * - 방위가 없으면(속도만 쓰는 제한 모드) 굽이 없이, 고도가 없으면 오르내림 없이 그린다.
 *
 * 방위는 1초 간격 신호에 섞인 흔들림 때문에 길이 꿈틀대지 않도록 앞뒤 2개씩 평균을 낸다.
 * 고도는 평지 위주인 도로에서도 변화가 보이도록 [HEIGHT_GAIN]배로 과장한다(표시 전용).
 */
internal object RoadBuilder {
    const val STEP_M = 4f
    const val AHEAD_M = 360f
    private const val DECAY_M = 90f
    private const val HEIGHT_GAIN = 1.6f
    private const val SMOOTH = 2
    private const val MAX_TURN_DEG_PER_M = 2.0f
    private const val MAX_SLOPE = 0.12f

    fun build(
        samples: List<DriveSample>,
        nowMs: Long,
        odometerM: Float,
        fallbackCurve: Float,
        fallbackClimb: Float,
    ): RoadGeometry {
        val track = Track.of(samples.sortedBy { it.timestampMs }, nowMs)
        val turnEnd: Float
        val slopeEnd: Float
        if (track == null) {
            // 신호 자체가 없는 장면(저장 곡 재생 등): 호출자가 준 굽이·오르내림을 그대로 쓴다.
            turnEnd = fallbackCurve * 0.35f
            slopeEnd = fallbackClimb * 0.06f
        } else {
            turnEnd = track.endTurn().coerceIn(-MAX_TURN_DEG_PER_M, MAX_TURN_DEG_PER_M)
            slopeEnd = track.endSlope().coerceIn(-MAX_SLOPE, MAX_SLOPE)
        }
        val lastDist = track?.lastDist ?: 0f
        val h0 = track?.head?.get(0) ?: 0f
        val z0 = track?.alt?.get(0) ?: 0f

        fun headingAt(u: Float): Float {
            if (track != null && u <= lastDist) return track.headAt(u)
            val e = u - lastDist
            val base = if (track != null) track.head[track.n - 1] else 0f
            return base + turnEnd * decayIntegral(e)
        }
        fun altAt(u: Float): Float {
            if (track != null && u <= lastDist) return track.altAt(u)
            val e = u - lastDist
            val base = if (track != null) track.alt[track.n - 1] else 0f
            return base + slopeEnd * decayIntegral(e)
        }

        // 고운 격자(0..AHEAD)에서 중심선을 적분한다.
        val fineN = ceil(AHEAD_M / STEP_M).toInt() + 2
        val fF = FloatArray(fineN)
        val fL = FloatArray(fineN)
        val fZ = FloatArray(fineN)
        val fA = FloatArray(fineN)
        for (j in 0 until fineN) {
            val u = j * STEP_M
            fA[j] = ((headingAt(u) - h0) * PI / 180.0).toFloat()
            fZ[j] = (altAt(u) - z0) * HEIGHT_GAIN
            if (j > 0) {
                val mid = (fA[j - 1] + fA[j]) * 0.5f
                fF[j] = fF[j - 1] + cos(mid) * STEP_M
                fL[j] = fL[j - 1] + sin(mid) * STEP_M
            }
        }

        // 출력 격자는 주행 거리만큼 밀어서 노면 표시가 차의 움직임에 맞춰 부드럽게 흘러가게 한다.
        val frac = ((odometerM % STEP_M) + STEP_M) % STEP_M
        val kMin = -1
        val kMax = floor((AHEAD_M + frac) / STEP_M).toInt()
        val count = kMax - kMin + 1
        val f = FloatArray(count)
        val l = FloatArray(count)
        val z = FloatArray(count)
        val a = FloatArray(count)
        for (k in kMin..kMax) {
            val i = k - kMin
            val s = k * STEP_M - frac
            if (s <= 0f) {
                f[i] = s
            } else {
                val x = s / STEP_M
                val j = x.toInt().coerceAtMost(fineN - 2)
                val t = x - j
                f[i] = lerp(fF[j], fF[j + 1], t)
                l[i] = lerp(fL[j], fL[j + 1], t)
                z[i] = lerp(fZ[j], fZ[j + 1], t)
                a[i] = lerp(fA[j], fA[j + 1], t)
            }
        }
        val near = (headingAt(30f) - h0) / 18f
        val slope = (altAt(12f) - z0) / 12f
        return RoadGeometry(
            count = count,
            forward = f,
            lateral = l,
            height = z,
            angle = a,
            firstIndex = floor(odometerM / STEP_M).toInt() + kMin,
            nearCurve = near.coerceIn(-1f, 1f),
            slope = slope.coerceIn(-MAX_SLOPE, MAX_SLOPE),
            speedMps = track?.speed0 ?: 0f,
            headingDeg = if (track?.hasHeading == true) ((h0 % 360f) + 360f) % 360f else null,
        )
    }

    /** 끝에서 굽이·경사가 [DECAY_M]에 걸쳐 0으로 줄어들 때의 누적량(거리 e 지점까지). */
    private fun decayIntegral(e: Float): Float =
        if (e >= DECAY_M) DECAY_M * 0.5f else e - e * e / (2f * DECAY_M)

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /**
     * 지금 시각부터 앞으로의 신호를 주행 거리 순으로 늘어놓은 것. [dist] 0이 지금 위치.
     * [past*]는 실제 주행처럼 앞 신호가 없을 때 최근 굽이·경사를 추정하는 데 쓴다.
     */
    private class Track(
        val n: Int,
        val dist: FloatArray,
        val head: FloatArray,
        val alt: FloatArray,
        val speed0: Float,
        val hasHeading: Boolean,
        val pastTurn: Float,
        val pastSlope: Float,
    ) {
        val lastDist: Float get() = dist[n - 1]

        fun headAt(u: Float) = interp(head, u)
        fun altAt(u: Float) = interp(alt, u)

        private fun interp(v: FloatArray, u: Float): Float {
            if (n == 1 || u <= 0f) return v[0]
            var i = 1
            while (i < n - 1 && dist[i] < u) i++
            val d0 = dist[i - 1]
            val d1 = dist[i]
            val t = if (d1 > d0) ((u - d0) / (d1 - d0)).coerceIn(0f, 1f) else 1f
            return v[i - 1] + (v[i] - v[i - 1]) * t
        }

        /** 끝부분 약 20m의 굽이(도/m). 앞 신호가 거의 없으면 지난 4초로 추정한 값. */
        fun endTurn(): Float {
            val m = backIndex(20f) ?: return pastTurn
            return (head[n - 1] - head[m]) / (dist[n - 1] - dist[m])
        }

        fun endSlope(): Float {
            val m = backIndex(20f) ?: return pastSlope
            return (alt[n - 1] - alt[m]) / (dist[n - 1] - dist[m])
        }

        private fun backIndex(span: Float): Int? {
            if (lastDist < span) return null
            var m = n - 1
            while (m > 0 && dist[n - 1] - dist[m] < span) m--
            return m
        }

        companion object {
            fun of(s: List<DriveSample>, nowMs: Long): Track? {
                if (s.isEmpty()) return null
                val n = s.size
                val hasHeading = s.any { it.bearingDeg != null }
                val head = smooth(unwrapHeadings(s))
                val alt = smooth(fillAltitude(s))
                val speed = FloatArray(n) { s[it].speedMps ?: 0f }

                var k0 = -1
                for (i in 0 until n) if (s[i].timestampMs <= nowMs) k0 = i
                val h0: Float
                val z0: Float
                val v0: Float
                if (k0 < 0) {
                    h0 = head[0]; z0 = alt[0]; v0 = speed[0]
                } else if (k0 == n - 1) {
                    h0 = head[k0]; z0 = alt[k0]; v0 = speed[k0]
                } else {
                    val t0 = s[k0].timestampMs
                    val t1 = s[k0 + 1].timestampMs
                    val t = if (t1 > t0) ((nowMs - t0).toFloat() / (t1 - t0)).coerceIn(0f, 1f) else 0f
                    h0 = head[k0] + (head[k0 + 1] - head[k0]) * t
                    z0 = alt[k0] + (alt[k0 + 1] - alt[k0]) * t
                    v0 = speed[k0] + (speed[k0 + 1] - speed[k0]) * t
                }

                val dist = ArrayList<Float>(); val hd = ArrayList<Float>(); val al = ArrayList<Float>()
                dist += 0f; hd += h0; al += z0
                var d = 0f
                var tPrev = nowMs
                var vPrev = v0
                for (j in (k0 + 1) until n) {
                    val tj = s[j].timestampMs
                    if (tj <= nowMs) continue
                    d += (vPrev + speed[j]) * 0.5f * (tj - tPrev) / 1000f
                    tPrev = tj
                    vPrev = speed[j]
                    if (d - dist.last() < 0.5f) continue
                    dist += d; hd += head[j]; al += alt[j]
                    if (d > AHEAD_M + 40f) break
                }

                // 지난 4초의 굽이·경사(도/m, m/m). 천천히 움직일 때는 신호 흔들림이 커서 쓰지 않는다.
                var pastTurn = 0f
                var pastSlope = 0f
                if (k0 > 0) {
                    var p = k0
                    while (p > 0 && s[p - 1].timestampMs >= s[k0].timestampMs - 4_000L) p--
                    if (p < k0) {
                        var travelled = 0f
                        for (i in p + 1..k0) travelled += (speed[i - 1] + speed[i]) * 0.5f * (s[i].timestampMs - s[i - 1].timestampMs) / 1000f
                        if (travelled > 8f) {
                            pastTurn = (head[k0] - head[p]) / travelled
                            pastSlope = (alt[k0] - alt[p]) / travelled
                        }
                    }
                }
                return Track(
                    n = dist.size,
                    dist = dist.toFloatArray(),
                    head = hd.toFloatArray(),
                    alt = al.toFloatArray(),
                    speed0 = v0,
                    hasHeading = hasHeading,
                    pastTurn = pastTurn,
                    pastSlope = pastSlope,
                )
            }

            /** 359도→1도처럼 넘어갈 때 끊기지 않도록 방위를 이어 붙이고, 빈 값은 앞뒤 값으로 채운다. */
            private fun unwrapHeadings(s: List<DriveSample>): FloatArray {
                val out = FloatArray(s.size)
                var last: Float? = null
                var acc = 0f
                var first = -1
                for (i in s.indices) {
                    val b = s[i].bearingDeg
                    if (b != null) {
                        if (last == null) {
                            acc = b
                            first = i
                        } else {
                            var dd = (b - last) % 360f
                            if (dd > 180f) dd -= 360f
                            if (dd < -180f) dd += 360f
                            acc += dd
                        }
                        last = b
                    }
                    out[i] = acc
                }
                if (first > 0) for (i in 0 until first) out[i] = out[first]
                return out
            }

            private fun fillAltitude(s: List<DriveSample>): FloatArray {
                val out = FloatArray(s.size)
                var last: Float? = null
                var first = -1
                for (i in s.indices) {
                    val a = s[i].altitudeM?.toFloat()
                    if (a != null) {
                        if (last == null) first = i
                        last = a
                    }
                    out[i] = last ?: 0f
                }
                if (first > 0) for (i in 0 until first) out[i] = out[first]
                return out
            }

            private fun smooth(v: FloatArray): FloatArray = FloatArray(v.size) { i ->
                var sum = 0f
                var c = 0
                for (k in (i - SMOOTH).coerceAtLeast(0)..(i + SMOOTH).coerceAtMost(v.size - 1)) {
                    sum += v[k]
                    c++
                }
                sum / c
            }
        }
    }
}
