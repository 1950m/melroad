package com.studio1950.melroad.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** TC-01~03: 세 가지 시뮬레이션이 각기 다른 음악을 만들고, 음악 규칙을 지키는지 */
class FixtureMusicTest {

    companion object {
        private val coast by lazy { OfflineComposition.compose(DriveFixtures.COAST) }
        private val hill by lazy { OfflineComposition.compose(DriveFixtures.HILL) }
        private val city by lazy { OfflineComposition.compose(DriveFixtures.CITY) }
    }

    private val all get() = listOf(coast, hill, city)

    @Test
    fun threeFixtures_produceDifferentEvents() {
        assertEquals(3, all.map { MusicEventCodec.hash(it.events) }.toSet().size)
    }

    @Test
    fun fixtures_areFullJourneys() {
        assertTrue("제주 해안도로는 약 30분", DriveFixtures.COAST.durationMs in 25 * 60_000L..35 * 60_000L)
        assertTrue("언덕길은 약 20분", DriveFixtures.HILL.durationMs in 16 * 60_000L..24 * 60_000L)
        assertTrue("도심은 약 15분", DriveFixtures.CITY.durationMs in 12 * 60_000L..18 * 60_000L)
    }

    @Test
    fun coast_reflectsCurves() {
        assertTrue(coast.decisions.any { it.progressionId == "CR" || it.progressionId == "CL" })
    }

    @Test
    fun hill_raisesRegisterOnClimb() {
        assertTrue(hill.decisions.any { it.registerStep > 0 })
    }

    @Test
    fun city_breathesAtStops() {
        assertTrue("정차에서 밀도 0", city.barDensities.any { it == 0 })
        assertTrue("정차에서 쉼(REST) 구간", city.decisions.any { it.section == SectionType.REST })
    }

    @Test
    fun longJourney_keepsChanging() {
        val sections = coast.decisions.map { it.section }.toSet()
        assertTrue("구간 종류 $sections", sections.size >= 4)
        assertTrue("조바꿈", coast.decisions.map { it.keyOffset }.toSet().size >= 3)
        // 4마디 선율이 그대로 반복되지 않아야 한다(같은 주제의 변주는 허용)
        val barMs = coast.barMs
        val phraseMelodies = coast.events
            .filter { it.instrument == Instrument.PIANO }
            .groupBy { coast.barOf(it.atMs) / 4 }
            .values
            .map { list -> list.map { it.pitch to (it.atMs % (barMs * 4)) } }
        val distinctRatio = phraseMelodies.toSet().size.toDouble() / phraseMelodies.size
        assertTrue("선율 다양성 $distinctRatio", distinctRatio > 0.6)
    }

    @Test
    fun density_changesAtMostOneStepPerUpdate() {
        for (result in all) {
            result.barDensities.zipWithNext().forEach { (a, b) -> assertTrue(abs(a - b) <= 1) }
        }
    }

    @Test
    fun majorChanges_onlyAtFourBarBoundaries() {
        for (result in all) {
            result.decisions.forEach { assertEquals(0, it.startBar % DeterministicComposer.BARS_PER_PHRASE) }
        }
    }

    @Test
    fun melody_avoidsLargeLeaps() {
        for (result in all) {
            val barMs = result.barMs
            val keyByPhrase = result.decisions.associate { it.startBar to it.keyOffset }
            fun keyAt(ms: Long): Int {
                val bar = result.barOf(ms)
                return keyByPhrase[bar - bar % 4] ?: 0
            }
            val melody = result.events.filter { it.instrument == Instrument.PIANO }.sortedBy { it.atMs }
            assertTrue(melody.isNotEmpty())
            melody.zipWithNext().forEach { (a, b) ->
                val gap = b.atMs - (a.atMs + a.durationMs)
                // 다른 악기가 선율을 맡은 구간·쉼 구간을 사이에 둔 음, 조바꿈 경계는 제외
                if (gap < barMs && keyAt(a.atMs) == keyAt(b.atMs)) {
                    assertTrue("도약 ${a.pitch}→${b.pitch} @${b.atMs}", abs(a.pitch - b.pitch) <= 7)
                }
            }
        }
    }

    @Test
    fun groove_isSoftAndRestsAtStops() {
        for (result in all) {
            val perc = result.events.filter { it.instrument == Instrument.PERC }
            assertTrue("리듬 섹션이 있어야 단조롭지 않다", perc.isNotEmpty())
            perc.forEach { assertTrue("부드러운 타악 세기 ${it.velocity}", it.velocity <= 60) }
        }
    }

    @Test
    fun timbre_rotatesAcrossTheJourney() {
        val instruments = coast.events.map { it.instrument }.toSet()
        assertTrue("악기 구성 $instruments", instruments.containsAll(listOf(Instrument.PIANO, Instrument.EPIANO, Instrument.STRINGS, Instrument.GUITAR, Instrument.PERC)))
    }

    @Test
    fun allEvents_areGentle() {
        for (result in all) {
            result.events.forEach {
                assertTrue(it.velocity in 1..DeterministicComposer.MAX_VELOCITY)
                assertTrue("음역 ${it.pitch}", it.pitch in 30..100)
                assertTrue(it.durationMs > 0)
            }
        }
    }

    @Test
    fun noEventAfterEnding() {
        for (result in all) {
            assertFalse(result.events.any { it.atMs >= result.endMs })
            assertTrue(result.events.maxOf { it.atMs + it.durationMs } <= result.endMs)
        }
    }

    @Test
    fun speed_doesNotRaiseVelocity() {
        // 밀도가 높은(빠른) 마디의 선율 평균 세기가 느린 마디보다 크지 않아야 한다(과속 보상 금지).
        for (result in all) {
            val barMs = result.barMs
            val byDensity = result.events.filter { it.instrument == Instrument.PIANO }.groupBy { e ->
                result.barDensities.getOrElse(result.barOf(e.atMs)) { 1 }
            }
            val slow = byDensity[1]?.map { it.velocity }?.average()
            val fast = byDensity[3]?.map { it.velocity }?.average()
            if (slow != null && fast != null) assertTrue(fast <= slow + 1.0)
        }
    }
}
