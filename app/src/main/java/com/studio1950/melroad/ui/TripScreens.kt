package com.studio1950.melroad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studio1950.melroad.R
import com.studio1950.melroad.domain.SessionPhase
import com.studio1950.melroad.domain.SessionState
import com.studio1950.melroad.domain.TripSource
import com.studio1950.melroad.integration.DrivingUxState
import com.studio1950.melroad.integration.SignalSourceStatus
import com.studio1950.melroad.music.MusicEvent
import com.studio1950.melroad.ui.theme.ActiveBlue
import com.studio1950.melroad.ui.theme.HyundaiBlue

/**
 * '지금 연주' 패널: 위쪽 정보(여정·상태) / 가운데 선율 라인 그래픽(글자 없음) / 아래쪽 정보(진행·신호).
 * 현대 블루 바탕 위 흰 글자로 대비를 확보한다.
 */
@Composable
private fun NowPlayingPanel(
    modifier: Modifier,
    title: String,
    status: String,
    playing: Boolean,
    events: (Long, Long) -> List<MusicEvent>,
    position: () -> Long,
    bottom: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(36.dp)
    Column(
        modifier
            .shadow(18.dp, shape, ambientColor = HyundaiBlue, spotColor = HyundaiBlue)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF0A3F7A), HyundaiBlue, Color(0xFF001A3A))))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.Transparent)), shape)
            .padding(horizontal = 30.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 상단 정보
        Text(title, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.78f))
        Text(
            status,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        // 선율 라인 그래픽
        MelodyLines(
            events = events,
            position = position,
            playing = playing,
            modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 160.dp),
        )
        // 하단 정보
        bottom()
    }
}

/** 진행 막대(흰색 계열, 어두운 패널용) */
@Composable
private fun PanelProgress(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.18f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Brush.horizontalGradient(listOf(ActiveBlue, Color.White))),
        )
    }
}

@Composable
private fun PanelChip(text: String) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.25f), shape)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
}

/**
 * S03 여행 중. 왼쪽: 차선 위 자동차(주변 페이드아웃) / 오른쪽: '지금 연주' 패널과 조작 버튼.
 * 주행 중에도 보이는 화면이라 설정·입력 없이 '재생 상태 / 일시정지 / 마치기'만 둔다.
 */
@Composable
fun RunningScreen(
    s: SessionState,
    events: (Long, Long) -> List<MusicEvent>,
    position: () -> Long,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
) {
    val playing = s.phase != SessionPhase.PAUSED
    val status = stringResource(
        when (s.phase) {
            SessionPhase.PAUSED -> R.string.running_paused
            SessionPhase.FINALIZING -> R.string.running_finalizing
            else -> R.string.running_status
        },
    )
    BoxWithConstraints(Modifier.fillMaxSize().padding(28.dp)) {
        val wide = maxWidth > 840.dp
        val scene = @Composable { mod: Modifier ->
            SceneCard(mod, s.sceneSpeedKmh, s.sceneCurve, s.sceneClimb, playing, s.density) {
                Box(Modifier.align(Alignment.TopStart).padding(22.dp)) {
                    if (s.source == TripSource.DEMO) {
                        Badge(stringResource(R.string.badge_simulation), emphasized = true)
                    } else {
                        val speedOnly = s.liveCapability?.speedOnly == true
                        Badge(stringResource(if (speedOnly) R.string.badge_live_speed else R.string.badge_live), emphasized = false)
                    }
                }
            }
        }
        val panel = @Composable { mod: Modifier ->
            NowPlayingPanel(mod, s.title, status, playing, events, position) {
                val elapsed = formatClock(s.elapsedMs)
                val demo = s.source == TripSource.DEMO && s.durationMs > 0
                if (demo) PanelProgress(s.elapsedMs.toFloat() / s.durationMs)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (demo) "$elapsed / ${formatClock(s.durationMs)}" else elapsed,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                    )
                    Spacer(Modifier.weight(1f))
                    PanelChip(stringResource(if (s.source == TripSource.DEMO) R.string.signal_simulation else R.string.signal_speed))
                }
                if (s.source == TripSource.LIVE) {
                    when (s.signalStatus) {
                        SignalSourceStatus.SIGNAL_LOST -> Text(stringResource(R.string.signal_lost), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        SignalSourceStatus.ERROR -> Text(stringResource(R.string.signal_error), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        else -> Unit
                    }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                scene(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1.05f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    panel(Modifier.fillMaxWidth().weight(1f))
                    RunningControls(s, onPause, onResume, onFinish)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                scene(Modifier.fillMaxWidth().height(280.dp))
                panel(Modifier.fillMaxWidth().height(420.dp))
                RunningControls(s, onPause, onResume, onFinish)
            }
        }
    }
}

@Composable
private fun RunningControls(s: SessionState, onPause: () -> Unit, onResume: () -> Unit, onFinish: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (s.phase == SessionPhase.PAUSED) {
            BigButton(stringResource(R.string.action_resume), onResume, Modifier.weight(1f), icon = IconKind.PLAY)
        } else {
            BigButton(stringResource(R.string.action_pause), onPause, Modifier.weight(1f), icon = IconKind.PAUSE)
        }
        BigButton(
            stringResource(R.string.action_finish_trip),
            onFinish,
            Modifier.weight(1f),
            primary = false,
            enabled = s.phase != SessionPhase.FINALIZING,
            icon = IconKind.STOP,
        )
    }
}

/** S04 여행 종료: 다시 듣기 / 저장 / 저장하지 않기. 다시 듣는 동안 같은 선율 그래픽이 흐른다. */
@Composable
fun FinishedScreen(
    s: SessionState,
    driving: DrivingUxState,
    events: (Long, Long) -> List<MusicEvent>,
    position: () -> Long,
    onReplay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopReplay: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onHome: () -> Unit,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(driving.restricted) { if (driving.restricted) confirmDiscard = false }
    val length = s.draft?.durationMs ?: s.durationMs
    val replaying = s.isMemoryActive

    BoxWithConstraints(Modifier.fillMaxSize().padding(28.dp)) {
        val wide = maxWidth > 840.dp
        val simulationLabel = stringResource(R.string.badge_simulation)
        val panel = @Composable { mod: Modifier ->
            NowPlayingPanel(
                mod,
                title = s.title + if (s.isSimulation && !s.title.contains(simulationLabel)) " · $simulationLabel" else "",
                status = stringResource(if (replaying) R.string.finished_replaying else R.string.finished_title),
                playing = s.phase == SessionPhase.MEMORY_PLAYING,
                events = events,
                position = position,
            ) {
                if (replaying && length > 0) PanelProgress(s.elapsedMs.toFloat() / length)
                Text(
                    if (replaying) "${formatClock(s.elapsedMs)} / ${formatClock(length)}" else stringResource(R.string.finished_length, formatClock(length)),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
            }
        }
        val actions = @Composable {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (replaying) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (s.phase == SessionPhase.MEMORY_PLAYING) {
                            BigButton(stringResource(R.string.action_pause), onPause, Modifier.weight(1f), icon = IconKind.PAUSE)
                        } else {
                            BigButton(stringResource(R.string.action_resume), onResume, Modifier.weight(1f), icon = IconKind.PLAY)
                        }
                        BigButton(stringResource(R.string.action_stop_to_list), onStopReplay, Modifier.weight(1f), primary = false, icon = IconKind.STOP)
                    }
                } else {
                    BigButton(stringResource(R.string.action_replay), onReplay, icon = IconKind.PLAY)
                }
                if (s.savedTripId == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        BigButton(stringResource(R.string.action_save), onSave, Modifier.weight(1f), primary = !replaying)
                        if (!driving.restricted) {
                            BigButton(stringResource(R.string.action_discard), { confirmDiscard = true }, Modifier.weight(1f), primary = false)
                        }
                    }
                    if (driving.restricted) SubtleText(stringResource(R.string.discard_driving))
                } else {
                    BigButton(stringResource(R.string.action_home), onHome, primary = false)
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                SceneCard(Modifier.weight(1f).fillMaxHeight(), speedKmh = 0f, curve = 0f, playing = s.phase == SessionPhase.MEMORY_PLAYING)
                Column(Modifier.weight(1.05f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    panel(Modifier.fillMaxWidth().weight(1f))
                    actions()
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                panel(Modifier.fillMaxWidth().height(420.dp))
                actions()
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            text = stringResource(R.string.discard_confirm),
            confirmLabel = stringResource(R.string.action_discard),
            cancelLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                confirmDiscard = false
                onDiscard()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}
