package com.studio1950.melroad.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalNormalizerTest {

    private val normalizer = SignalNormalizer()
    private val extractor = FeatureExtractor()

    private fun straightDrive(seconds: Int, speedMps: Float = 14f, bearing: Double = 90.0): List<DriveSample> {
        var lat = 33.5
        var lon = 126.5
        return (0..seconds).map { s ->
            if (s > 0) {
                val p = GeoMath.offset(lat, lon, bearing, speedMps.toDouble())
                lat = p.first
                lon = p.second
            }
            DriveSample(s * 1000L, speedMps, lat, lon, 20.0, bearing.toFloat())
        }
    }

    @Test
    fun sortsAndRemovesDuplicateTimestamps() {
        val raw = straightDrive(5)
        val shuffled = listOf(raw[3], raw[0], raw[2], raw[2].copy(speedMps = 10f), raw[1], raw[4], raw[5])
        val clean = normalizer.normalize(shuffled)
        assertEquals(6, clean.size)
        assertEquals(listOf(0L, 1000L, 2000L, 3000L, 4000L, 5000L), clean.map { it.timestampMs })
    }

    @Test
    fun gpsJump_isMarkedInvalid() {
        val raw = straightDrive(10).toMutableList()
        raw[5] = raw[5].copy(latitude = raw[5].latitude!! + 0.02) // 2km 순간 이동
        val clean = normalizer.normalize(raw)
        assertNull(clean[5].latitude)
        assertTrue(clean[6].latitude != null)
    }

    @Test
    fun impossibleSpeed_isDropped_andReverseIsSeparated() {
        val raw = straightDrive(6).toMutableList()
        raw[3] = raw[3].copy(speedMps = 500f)
        raw[4] = raw[4].copy(speedMps = -3f)
        val clean = normalizer.normalize(raw)
        assertNull(clean[3].speedMps)
        assertTrue(clean[4].reversing)
        assertTrue(clean[4].speedMps!! >= 0f)
    }

    /** TC-06: 위치가 60초 동안 null → 결측 상태, 가짜 커브·오르막을 만들지 않는다 */
    @Test
    fun locationNullFor60s_givesSpeedOnlyFeature_withoutFakeCurve() {
        val raw = straightDrive(70).map { it.copy(latitude = null, longitude = null, altitudeM = null, bearingDeg = null) }
        val clean = normalizer.normalize(raw)
        val feature = extractor.extract(clean, 40_000L, 60_000L)
        assertEquals(SignalQuality.SPEED_ONLY, feature.quality)
        assertNull(feature.turnDeg)
        assertNull(feature.climbM)
        assertTrue(feature.speedKmh!! > 40f)
    }

    @Test
    fun staleLocation_doesNotCreateBearingAcrossGap() {
        val raw = straightDrive(30).mapIndexed { i, s ->
            if (i in 10..19) s.copy(latitude = null, longitude = null, bearingDeg = null) else s.copy(bearingDeg = null)
        }
        val clean = normalizer.normalize(raw)
        assertNull("끊김 직후 첫 위치에서는 방향을 계산하지 않는다", clean[20].bearingDeg)
    }

    @Test
    fun steadyTurn_isAccumulated_andSpikesIgnored() {
        var lat = 33.5
        var lon = 126.5
        var bearing = 0.0
        val raw = (0..20).map { s ->
            if (s > 0) {
                bearing += 5.0
                val p = GeoMath.offset(lat, lon, bearing, 12.0)
                lat = p.first
                lon = p.second
            }
            val reported = if (s == 10) ((bearing + 120.0) % 360.0) else bearing
            DriveSample(s * 1000L, 12f, lat, lon, 10.0, reported.toFloat())
        }
        val feature = extractor.extract(normalizer.normalize(raw), 0L, 20_000L)
        // 20초 동안 초당 5도 → 약 100도. 튐 샘플(초당 120도)은 버려지므로 그 근처 구간만큼 줄어든다.
        assertTrue("turn=${feature.turnDeg}", feature.turnDeg!! in 70f..105f)
    }

    @Test
    fun stoppedVehicle_hasHighStoppedRatio_andZeroTurn() {
        val raw = (0..15).map { s -> DriveSample(s * 1000L, 0f, 33.5, 126.5, 10.0, null) }
        val feature = extractor.extract(normalizer.normalize(raw), 0L, 15_000L)
        assertTrue(feature.stoppedRatio > 0.9f)
        assertEquals(0f, feature.turnDeg!!, 0.001f)
    }
}
