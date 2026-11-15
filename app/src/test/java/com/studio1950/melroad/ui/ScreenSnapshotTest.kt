package com.studio1950.melroad.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalInspectionMode
import com.studio1950.melroad.domain.SessionPhase
import com.studio1950.melroad.domain.SessionState
import com.studio1950.melroad.domain.TripSource
import com.studio1950.melroad.domain.TripSummary
import com.studio1950.melroad.integration.DrivingUxState
import com.studio1950.melroad.music.CompositionDriver
import com.studio1950.melroad.music.DeterministicComposer
import com.studio1950.melroad.music.DriveFixtures
import com.studio1950.melroad.music.SampleFeatureProvider
import com.studio1950.melroad.music.Themes
import com.studio1950.melroad.music.DriveSample
import com.studio1950.melroad.ui.theme.AppBackground
import com.studio1950.melroad.ui.theme.MelroadTheme
import org.junit.Rule
import org.junit.Test

/**
 * 에뮬레이터 없이 화면을 그려 보는 스크린샷 테스트(Paparazzi). 결과 이미지는 app/build/reports/paparazzi 아래에 생긴다.
 * 태블릿 가로(2560×1600, xhdpi)로 그린다. 장식 애니메이션은 미리보기 모드에서 멈춘 완성 상태로 그린다.
 */
class ScreenSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.NEXUS_10,
        theme = "android:Theme.Material.Light.NoActionBar",
        maxPercentDifference = 100.0,
    )

    @Test
    fun home() {
        paparazzi.snapshot {
            // 미리보기 모드: 등장 애니메이션 없이 완성된 상태로 그린다(기기에서는 서서히 나타남)
            CompositionLocalProvider(LocalInspectionMode provides true) {
                MelroadTheme {
                    HomeScreen(onStartTrip = {}, onOurMusic = {}, onInfo = {})
                }
            }
        }
    }

    /** 심볼을 크게 그려 형태를 확인한다 */
    @Test
    fun mark() {
        paparazzi.snapshot {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                MelroadTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFFEDF1F6)), contentAlignment = Alignment.Center) {
                        MelroadMark(420.dp)
                    }
                }
            }
        }
    }
    /**
     * 주행 화면 전체(시뮬레이션 해안도로, 굽이가 다가오는 순간). 선율 그래픽은 같은 신호로 실제 작곡한 이벤트를 쓴다.
     */
    @Test
    fun running() {
        val fx = DriveFixtures.byId("coast")!!
        val samples = fx.samples
        val t = pick(samples) { s, i -> turnAhead(s, i, 12) }
        val window = { a: Long, b: Long -> samples.filter { it.timestampMs in a..b } }
        val driver = CompositionDriver(DeterministicComposer(fx.seed, Themes.WARM), SampleFeatureProvider(window))
        val events = driver.pump(t)
        val now = samples.last { it.timestampMs <= t }
        val state = SessionState(
            phase = SessionPhase.RUNNING,
            source = TripSource.DEMO,
            scenarioId = fx.id,
            elapsedMs = t,
            sceneSpeedKmh = (now.speedMps ?: 0f) * 3.6f,
            sceneHeadingDeg = now.bearingDeg,
        )
        paparazzi.snapshot {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                MelroadTheme {
                    Box(Modifier.fillMaxSize().background(AppBackground)) {
                        RunningScreen(
                            s = state,
                            events = { a, b -> events.filter { it.atMs + it.durationMs >= a && it.atMs <= b } },
                            position = { t },
                            roadSamples = window,
                            onPause = {},
                            onResume = {},
                            onFinish = {},
                        )
                    }
                }
            }
        }
    }

    /** 뮤직박스 화면: 저장된 시뮬레이션 곡 세 개(테스트 1~3) */
    @Test
    fun memory() {
        val base = 1_794_700_000_000L
        val trips = listOf(
            TripSummary(3, "테스트 1", base, 31 * 60_000L, TripSource.DEMO, "coast", "시뮬레이션 주행"),
            TripSummary(2, "테스트 2", base - 3_600_000L, 20 * 60_000L, TripSource.DEMO, "hill", "시뮬레이션 주행"),
            TripSummary(1, "테스트 3", base - 7_200_000L, 15 * 60_000L, TripSource.DEMO, "city", "시뮬레이션 주행"),
        )
        paparazzi.snapshot {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                MelroadTheme {
                    Box(Modifier.fillMaxSize().background(AppBackground)) {
                        MemoryScreen(
                            s = SessionState(),
                            trips = trips,
                            driving = DrivingUxState.PHONE,
                            onBack = {},
                            onPlay = {},
                            onPause = {},
                            onResume = {},
                            onRestart = {},
                            onStop = {},
                            onDelete = {},
                        )
                    }
                }
            }
        }
    }

    /** 첫 화면 시작 버튼('흐르는 선율')을 크게 그려 선의 굵기와 모양을 확인한다 */
    @Test
    fun startIcon() {
        paparazzi.snapshot {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                MelroadTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF8FA9C8)), contentAlignment = Alignment.Center) {
                        RoundIconButton(RoundIcon.MELODY, "여행 시작", 420.dp, onClick = {}, primary = true)
                    }
                }
            }
        }
    }

    /**
     * 주행 장면 세 컷(시뮬레이션 신호): 해안도로의 굽이가 다가오는 순간, 언덕길 오르막 직전, 언덕길 내리막·굽이.
     * 각 컷의 시각은 신호에서 앞 15~20초의 방위·고도 변화가 가장 큰 때로 고른다.
     */
    @Test
    fun drive() {
        val coast = DriveFixtures.byId("coast")!!.samples
        val hill = DriveFixtures.byId("hill")!!.samples
        val shots = listOf(
            coast to pick(coast) { s, i -> turnAhead(s, i, 12) },
            hill to pick(hill) { s, i -> climbAhead(s, i, 20) },
            hill to pick(hill) { s, i -> -climbAhead(s, i, 20) + kotlin.math.abs(turnAhead(s, i, 15)) * 0.2 },
        )
        paparazzi.snapshot {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                MelroadTheme {
                    Row(
                        Modifier.fillMaxSize().background(Color(0xFFEDF1F6)).padding(24.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        for ((samples, t) in shots) {
                            SceneCard(
                                Modifier.weight(1f).fillMaxHeight(),
                                speedKmh = 0f,
                                curve = 0f,
                                playing = true,
                                road = RoadFeed({ a, b -> samples.filter { it.timestampMs in a..b } }, { t }),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun pick(s: List<DriveSample>, score: (List<DriveSample>, Int) -> Double): Long {
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in 10 until s.size - 30) {
            if ((s[i].speedMps ?: 0f) < 5f) continue
            val v = score(s, i)
            if (v > bestScore) { bestScore = v; best = i }
        }
        return s[best].timestampMs
    }

    private fun turnAhead(s: List<DriveSample>, i: Int, n: Int): Double {
        var sum = 0.0
        for (k in i until i + n) {
            val a = s[k].bearingDeg ?: continue
            val b = s[k + 1].bearingDeg ?: continue
            var d = (b - a).toDouble() % 360.0
            if (d > 180) d -= 360
            if (d < -180) d += 360
            sum += d
        }
        return kotlin.math.abs(sum)
    }

    private fun climbAhead(s: List<DriveSample>, i: Int, n: Int): Double =
        (s[i + n].altitudeM ?: 0.0) - (s[i].altitudeM ?: 0.0)
}
