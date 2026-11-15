package com.studio1950.melroad.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalInspectionMode
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
}
