package com.studio1950.melroad.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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

/** S01 첫 화면: 왼쪽 주행 장면 위에 멜로드 로고를 얹고, 오른쪽에 큰 버튼 세 개만 둔다. */
@Composable
fun HomeScreen(onStartTrip: () -> Unit, onOurMusic: () -> Unit, onInfo: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(36.dp)) {
        val wide = maxWidth > 840.dp
        val logo: @Composable BoxScope.() -> Unit = {
            MelroadLogo(
                name = stringResource(R.string.app_name),
                wordmark = stringResource(R.string.brand),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp),
            )
        }
        val actions: @Composable ColumnScope.() -> Unit = {
            BigButton(stringResource(R.string.action_start_trip), onStartTrip, icon = IconKind.PLAY)
            BigButton(stringResource(R.string.action_our_music), onOurMusic, primary = false)
            BigButton(stringResource(R.string.action_info), onInfo, primary = false)
        }
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
                SceneCard(Modifier.weight(1.25f).fillMaxHeight(), speedKmh = 30f, curve = 0.15f, overlay = logo)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    actions()
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                SceneCard(Modifier.fillMaxWidth().height(320.dp), speedKmh = 30f, curve = 0.15f, overlay = logo)
                actions()
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
            when {
                s.phase == SessionPhase.CHECKING || cap == null ->
                    StatusText(stringResource(R.string.prepare_checking), large = false)
                cap.speed == CapabilityState.AVAILABLE -> {
                    StatusText(stringResource(R.string.prepare_live_speed_only), large = false)
                    BigButton(stringResource(R.string.action_start_live), onStartLive, enabled = !drivingConfirmed, icon = IconKind.PLAY)
                }
                cap.speed == CapabilityState.PERMISSION_REQUIRED -> {
                    StatusText(stringResource(R.string.prepare_live_permission), large = false)
                    if (driving.restricted) {
                        SubtleText(stringResource(R.string.prepare_permission_driving))
                    } else {
                        BigButton(stringResource(R.string.action_allow_permission), onRequestPermission, primary = false)
                    }
                }
                cap.speed == CapabilityState.NOT_AUTOMOTIVE ->
                    StatusText(stringResource(R.string.prepare_live_not_car), large = false)
                cap.speed == CapabilityState.UNSUPPORTED ->
                    StatusText(stringResource(R.string.prepare_live_unsupported), large = false)
                else -> StatusText(stringResource(R.string.prepare_live_error), large = false)
            }
            if (cap != null && !cap.canStartLive && cap.speed != CapabilityState.PERMISSION_REQUIRED) {
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
