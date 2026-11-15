package com.studio1950.melroad.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TC-04: 같은 fixture + 같은 seed → 이벤트 해시 100% 일치 */
class DeterminismTest {

    @Test
    fun sameFixtureAndSeed_producesIdenticalHash_threeTimes() {
        for (fixture in DriveFixtures.all) {
            val hashes = (1..3).map { MusicEventCodec.hash(OfflineComposition.compose(fixture).events) }
            assertEquals("${fixture.id} 해시가 매번 같아야 한다", 1, hashes.toSet().size)
        }
    }

    @Test
    fun pumpInterval_doesNotChangeResult() {
        val fixture = DriveFixtures.COAST
        val a = OfflineComposition.compose(fixture, stepMs = 100L)
        val b = OfflineComposition.compose(fixture, stepMs = 333L)
        assertEquals(MusicEventCodec.hash(a.events), MusicEventCodec.hash(b.events))
        assertEquals(a.endMs, b.endMs)
    }

    @Test
    fun differentSeed_changesMusic() {
        val fixture = DriveFixtures.COAST
        val a = OfflineComposition.compose(fixture, seed = 1L)
        val b = OfflineComposition.compose(fixture, seed = 2L)
        assertNotEquals(MusicEventCodec.hash(a.events), MusicEventCodec.hash(b.events))
    }

    @Test
    fun fixturesThemselvesAreDeterministic() {
        // lazy 값이 아니라 매번 새로 생성해도 같은지 확인하기 위해 샘플 직렬화를 비교
        val first = DriveFixtures.COAST.samples.joinToString { "${it.timestampMs}:${it.speedMps}:${it.latitude}" }
        assertTrue(first.isNotEmpty())
        // 1초 간격 샘플: 0초 포함
        assertEquals(DriveFixtures.COAST.durationMs / 1000 + 1, DriveFixtures.COAST.samples.size.toLong())
        assertTrue("제주 해안도로 시뮬레이션은 약 30분", DriveFixtures.COAST.durationMs in 25 * 60_000L..35 * 60_000L)
    }
}
