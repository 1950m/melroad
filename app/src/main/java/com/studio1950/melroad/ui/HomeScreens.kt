package com.studio1950.melroad.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studio1950.melroad.BuildConfig
import com.studio1950.melroad.R
import com.studio1950.melroad.domain.SessionPhase
import com.studio1950.melroad.domain.SessionState
import com.studio1950.melroad.integration.CapabilityState
import com.studio1950.melroad.integration.DrivingUxState
import com.studio1950.melroad.music.DriveFixtures
import com.studio1950.melroad.ui.theme.HyundaiBlue
import com.studio1950.melroad.ui.theme.Subtle

/** 장면 카드: 둥근 모서리 + 깊은 그림자로 창 너머 풍경처럼 보이게 한다. */
@Composable
fun SceneCard(
    modifier: Modifier = Modifier,
    speedKmh: Float,
    curve: Float,
    climb: Float = 0f,
    playing: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(40.dp)
    Box(
        modifier
            .shadow(16.dp, shape, ambientColor = HyundaiBlue.copy(alpha = 0.6f), spotColor = HyundaiBlue.copy(alpha = 0.6f))
            .clip(shape)
            .border(1.dp, Color.White, shape),
    ) {
        DriveScene(speedKmh, curve, climb, playing, Modifier.fillMaxSize())
        overlay()
    }
}

/**
 * S01 첫 화면. 주행 장면을 화면 가득 깔고 글자 버튼 없이 아이콘으로만 둔다.
 * - 왼쪽 위: 멜로드 심볼과 이름 / 오른쪽 위: 정보(i)
 * - 아래 가운데: 숨 쉬는 빛을 두른 큰 재생 버튼(여행 시작), 그 오른쪽에 뮤직박스(재생 목록) 버튼
 * 아이콘 버튼은 화면 낭독기가 '여행 시작', '뮤직박스', '정보'로 읽는다.
 */
@Composable
fun HomeScreen(onStartTrip: () -> Unit, onOurMusic: () -> Unit, onInfo: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 840.dp
        val playSize = if (wide) 156.dp else 128.dp
        val sideSize = if (wide) 100.dp else 84.dp
        DriveScene(
            speedKmh = 30f,
            curve = 0.15f,
            climb = 0f,
            playing = false,
            modifier = Modifier.fillMaxSize(),
            carYFraction = if (wide) 0.58f else 0.52f,
            carScale = 0.62f,
            palette = ScenePalette.DAWN,
            horizonFraction = 0.24f,
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.30f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFFEDF1F6).copy(alpha = 0.55f)))),
        )
        MelroadBrand(
            name = stringResource(R.string.app_name),
            wordmark = stringResource(R.string.brand),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 40.dp, top = 36.dp),
            markSize = if (wide) 68.dp else 56.dp,
        )
        RoundIconButton(
            RoundIcon.INFO,
            description = stringResource(R.string.action_info),
            size = 76.dp,
            onClick = onInfo,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 40.dp, top = 32.dp),
        )
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = if (wide) 44.dp else 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            Box(contentAlignment = Alignment.Center) {
                BreathingGlow(playSize * 1.7f)
                RoundIconButton(RoundIcon.PLAY, stringResource(R.string.action_start_trip), playSize, onStartTrip, primary = true)
            }
            Box(Modifier.weight(1f)) {
                RoundIconButton(
                    RoundIcon.PLAYLIST,
                    description = stringResource(R.string.action_our_music),
                    size = sideSize,
                    onClick = onOurMusic,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ScrollPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.widthIn(max = 1100.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp), content = content)
    }
}

/** S02 시작 준비 + S07 상태 안내(신호 불가·권한 필요) */
@Composable
fun PrepareScreen(
    s: SessionState,
    driving: DrivingUxState,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    onStartLive: () -> Unit,
    onStartDemo: (String) -> Unit,
) {
    // 실제로 주행 중임이 확인된 경우에만 새 여행 시작을 막는다(정차 상태에서 시작: 요구서 S02).
    val drivingConfirmed = driving.known && driving.requiresDistractionOptimization
    ScrollPage {
        BackRow(onBack, stringResource(R.string.action_back), stringResource(R.string.prepare_title))
        if (drivingConfirmed) StatusText(stringResource(R.string.prepare_start_driving), large = false)

        SectionCard {
            Text(stringResource(R.string.prepare_live_title), style = MaterialTheme.typography.titleLarge)
            val cap = s.liveCapability
            val needsPermission = cap != null &&
                (cap.speed == CapabilityState.PERMISSION_REQUIRED || cap.location == CapabilityState.PERMISSION_REQUIRED)
            when {
                s.phase == SessionPhase.CHECKING || cap == null ->
                    StatusText(stringResource(R.string.prepare_checking), large = false)
                cap.canStartLive -> {
                    // 실제로 연결될 신호만 적는다(위치가 없으면 굽이·오르막을 반영하지 않는다는 뜻)
                    val label = when {
                        cap.speedOnly -> R.string.prepare_live_speed_only
                        cap.locationOnly -> R.string.prepare_live_location_only
                        else -> R.string.prepare_live_speed_location
                    }
                    StatusText(stringResource(label), large = false)
                    BigButton(stringResource(R.string.action_start_live), onStartLive, enabled = !drivingConfirmed, icon = IconKind.PLAY)
                }
                cap.speed == CapabilityState.NOT_AUTOMOTIVE && !needsPermission ->
                    StatusText(stringResource(R.string.prepare_live_not_car), large = false)
                cap.speed == CapabilityState.UNSUPPORTED && cap.location == CapabilityState.UNSUPPORTED ->
                    StatusText(stringResource(R.string.prepare_live_unsupported), large = false)
                needsPermission -> StatusText(stringResource(R.string.prepare_live_permission), large = false)
                else -> StatusText(stringResource(R.string.prepare_live_error), large = false)
            }
            // 권한이 빠진 신호가 있으면(이미 시작할 수 있어도 위치를 더하려면) 허용 버튼을 보인다. 주행 중에는 권한 화면을 띄우지 않는다.
            if (needsPermission) {
                if (driving.restricted) {
                    SubtleText(stringResource(R.string.prepare_permission_driving))
                } else {
                    BigButton(stringResource(R.string.action_allow_permission), onRequestPermission, primary = false)
                }
            }
            if (cap != null && !cap.canStartLive && !needsPermission) {
                SubtleText(stringResource(R.string.prepare_demo_hint))
            }
        }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.prepare_demo_title), style = MaterialTheme.typography.titleLarge)
            }
            SubtleText(stringResource(R.string.prepare_demo_desc))
            for (fixture in DriveFixtures.all) {
                val minutes = ((fixture.durationMs + 30_000) / 60_000).toInt()
                BigButton(
                    text = stringResource(R.string.demo_item, fixture.title, minutes),
                    onClick = { onStartDemo(fixture.id) },
                    primary = fixture.id == "coast",
                    enabled = !drivingConfirmed,
                    icon = IconKind.PLAY,
                )
                SubtleText(fixture.description)
            }
        }
    }
}

/** S06 정보/설정 */
@Composable
fun InfoScreen(driving: DrivingUxState, onBack: () -> Unit, onDeleteAll: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(driving.restricted) { if (driving.restricted) confirm = false }
    ScrollPage {
        BackRow(onBack, stringResource(R.string.action_back), stringResource(R.string.info_title))
        InfoCard(stringResource(R.string.info_data_title), stringResource(R.string.info_data))
        InfoCard(stringResource(R.string.info_access_title), stringResource(R.string.info_access))
        InfoCard(stringResource(R.string.info_limits_title), stringResource(R.string.info_limits))
        SubtleText(stringResource(R.string.info_version, BuildConfig.VERSION_NAME))
        if (driving.restricted) {
            StatusText(stringResource(R.string.info_driving_locked), large = false)
        } else {
            BigButton(stringResource(R.string.info_delete_all), { confirm = true }, primary = false)
        }
    }
    if (confirm) {
        ConfirmDialog(
            text = stringResource(R.string.info_delete_all_confirm),
            confirmLabel = stringResource(R.string.action_confirm_delete),
            cancelLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                confirm = false
                onDeleteAll()
            },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    SectionCard {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        SubtleText(body)
    }
}
