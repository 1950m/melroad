package com.studio1950.melroad.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import com.studio1950.melroad.ui.theme.SkyBlue

/**
 * '지금 연주' 패널: 오른쪽 위 진행 시간 / 가운데 선율 그래픽(글자 없음) / 필요할 때만 아래쪽 신호 안내.
 * 여정 이름·재생 상태는 화면에 쓰지 않고 화면 낭독기 알림([announcement])으로만 전달한다.
 * 바탕에는 아주 느리게 흐르는 빛을 깔아 정지 화면처럼 딱딱해 보이지 않게 한다.
 */
@Composable
private fun NowPlayingPanel(
    modifier: Modifier,
    time: String,
    timeDescription: String,
    announcement: String,
    playing: Boolean,
    events: (Long, Long) -> List<MusicEvent>,
    position: () -> Long,
    bottom: @Composable ColumnScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(36.dp)
    val reducedMotion = rememberReducedMotion()
    val drift by rememberInfiniteTransition(label = "aurora").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    Column(
        modifier
            .shadow(18.dp, shape, ambientColor = HyundaiBlue, spotColor = HyundaiBlue)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF0A3F7A), HyundaiBlue, Color(0xFF001A3A))))
            .drawBehind {
                val d = if (reducedMotion) 0.5f else drift
                val c1 = Offset(size.width * (0.15f + 0.7f * d), size.height * 0.18f)
                drawCircle(Brush.radialGradient(listOf(ActiveBlue.copy(alpha = 0.20f), Color.Transparent), center = c1, radius = size.width * 0.55f), radius = size.width * 0.55f, center = c1)
                val c2 = Offset(size.width * (0.85f - 0.6f * d), size.height * 0.92f)
                drawCircle(Brush.radialGradient(listOf(SkyBlue.copy(alpha = 0.10f), Color.Transparent), center = c2, radius = size.width * 0.5f), radius = size.width * 0.5f, center = c2)
            }
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.Transparent)), shape)
            .padding(horizontal = 30.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 보이지 않는 상태 알림(재생·일시정지·마무리 등). 글자 대신 화면 낭독기가 읽는다.
            Box(Modifier.size(1.dp).semantics { contentDescription = announcement; liveRegion = LiveRegionMode.Polite })
            Spacer(Modifier.weight(1f))
            Text(
                time,
                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                color = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.clearAndSetSemantics { contentDescription = timeDescription },
            )
        }
        MelodyLines(
            events = events,
            position = position,
            playing = playing,
            modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 160.dp),
        )
        bottom()
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
            SceneCard(mod, s.sceneSpeedKmh, s.sceneCurve, s.sceneClimb, playing) {
                // 방향 신호가 있을 때만 방위를 보인다(속도만 있는 실제 주행에서는 표시하지 않음).
                s.sceneHeadingDeg?.let { HeadingCompass(it, Modifier.align(Alignment.TopStart).padding(22.dp)) }
            }
        }
        val elapsed = formatClock(s.elapsedMs)
        val simulation = stringResource(R.string.badge_simulation)
        val announcement = if (s.isSimulation) "$status, $simulation" else status
        val panel = @Composable { mod: Modifier ->
            NowPlayingPanel(
                mod,
                time = elapsed,
                timeDescription = stringResource(R.string.elapsed_desc, elapsed),
                announcement = announcement,
                playing = playing,
                events = events,
                position = position,
            ) {
                if (s.source == TripSource.LIVE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PanelChip(stringResource(R.string.signal_speed))
                    }
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
        val statusText = stringResource(if (replaying) R.string.finished_replaying else R.string.finished_title)
        val shownTime = formatClock(if (replaying) s.elapsedMs else length)
        val lengthText = stringResource(R.string.finished_length, formatClock(length))
        val panel = @Composable { mod: Modifier ->
            NowPlayingPanel(
                mod,
                time = shownTime,
                timeDescription = if (replaying) stringResource(R.string.elapsed_desc, shownTime) else lengthText,
                announcement = "$statusText, $lengthText",
                playing = s.phase == SessionPhase.MEMORY_PLAYING,
                events = events,
                position = position,
            )
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
