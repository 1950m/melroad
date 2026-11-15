package com.studio1950.melroad.ui

import com.studio1950.melroad.music.DriveSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 앞길 모양이 신호를 따르고, 없는 굽이·오르막을 지어내지 않는지 확인한다. */
class RoadGeometryTest {

    private fun track(seconds: Int, speed: Float = 15f, bearing: (Int) -> Float?, alt: (Int) -> Double?) =
        (0..seconds).map { DriveSample(it * 1000L, speed, null, null, alt(it), bearing(it)) }

    private fun farLateral(g: RoadGeometry) = g.lateral[g.count - 1]
    private fun farHeight(g: RoadGeometry) = g.height[g.count - 1]

    @Test
    fun speedOnlyMakesStraightFlatRoad() {
        val g = RoadBuilder.build(track(60, bearing = { null }, alt = { null }), 10_000L, 0f, 0f, 0f)
        assertEquals(0f, farLateral(g), 0.001f)
        assertEquals(0f, farHeight(g), 0.001f)
        assertNull(g.headingDeg)
    }

    @Test
    fun rightTurnAheadBendsRoadRight() {
        // 20초부터 초당 3도씩 오른쪽으로 돈다(지금은 10초).
        val g = RoadBuilder.build(track(60, bearing = { if (it < 20) 90f else 90f + (it - 20) * 3f }, alt = { 50.0 }), 10_000L, 0f, 0f, 0f)
        assertTrue("오른쪽(+)으로 휘어야 한다: ${farLateral(g)}", farLateral(g) > 20f)
        assertEquals(0f, farHeight(g), 0.01f)
    }

    @Test
    fun wrapAroundNorthDoesNotJump() {
        val g = RoadBuilder.build(track(60, bearing = { ((350f + it * 0.5f) % 360f) }, alt = { 0.0 }), 10_000L, 0f, 0f, 0f)
        assertTrue("359→0도에서 끊기지 않고 완만히 오른쪽: ${farLateral(g)}", farLateral(g) > 0f && farLateral(g) < 60f)
    }

    @Test
    fun climbAheadRaisesRoad() {
        val g = RoadBuilder.build(track(60, bearing = { 0f }, alt = { if (it < 15) 100.0 else 100.0 + (it - 15) * 1.2 }), 10_000L, 0f, 0f, 0f)
        assertTrue("앞길이 올라가야 한다: ${farHeight(g)}", farHeight(g) > 10f)
        assertEquals(0f, farLateral(g), 0.5f)
    }

    @Test
    fun liveWithoutFutureFadesRecentTurnInsteadOfLooping() {
        // 실제 주행: 지금(30초)까지의 신호만 있다. 최근 초당 6도로 도는 중.
        val past = track(30, bearing = { it * 6f }, alt = { 0.0 })
        val g = RoadBuilder.build(past, 30_000L, 0f, 0f, 0f)
        val lastAngleDeg = Math.toDegrees(g.angle[g.count - 1].toDouble())
        assertTrue("최근 굽이를 이어 그린다: $lastAngleDeg", lastAngleDeg > 5.0)
        assertTrue("멀어질수록 풀려서 한 바퀴 돌지 않는다: $lastAngleDeg", abs(lastAngleDeg) < 120.0)
    }

    @Test
    fun odometerSlidesGridSmoothly() {
        val s = track(60, bearing = { 0f }, alt = { 0.0 })
        val a = RoadBuilder.build(s, 10_000L, 0f, 0f, 0f)
        val b = RoadBuilder.build(s, 10_000L, 1f, 0f, 0f)
        assertEquals(a.forward[2] - 1f, b.forward[2], 0.01f)
    }
}
