package com.studio1950.melroad.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studio1950.melroad.domain.PlaybackItemKind
import com.studio1950.melroad.domain.SessionPhase
import com.studio1950.melroad.integration.car.CarConnector
import com.studio1950.melroad.integration.pleos.PleosLocationAdapter
import com.studio1950.melroad.ui.theme.AppBackground
import com.studio1950.melroad.ui.theme.HyundaiBlue
import kotlinx.coroutines.delay

@Composable
fun MelroadRoot(vm: MelroadViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val driving by vm.driving.collectAsStateWithLifecycle()
    val trips by vm.trips.collectAsStateWithLifecycle()
    val localMessage by vm.localMessage.collectAsStateWithLifecycle()
    val message = s.messageRes ?: localMessage

    LaunchedEffect(message) {
        if (message != null) {
            delay(6_000)
            vm.consumeMessages()
        }
    }

    // 속도·위치 권한 가운데 아직 없는 것만 한 번에 요청한다.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.recheckCapability()
    }

    val showFinished = s.phase == SessionPhase.FINISHED || s.phase == SessionPhase.SAVED ||
        (s.isMemoryActive && s.itemKind == PlaybackItemKind.DRAFT)
    val showMemoryPlayer = s.isMemoryActive && s.itemKind == PlaybackItemKind.MEMORY

    BackHandler(enabled = !s.isTripActive && !showFinished && (screen != Screen.HOME || showMemoryPlayer)) {
        if (showMemoryPlayer) vm.stopPlayback()
        vm.go(Screen.HOME)
    }

    Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
        Column(Modifier.fillMaxSize().background(AppBackground)) {
            message?.let { MessageBanner(stringResource(it)) }
            val key = when {
                s.isTripActive -> ScreenKey.RUNNING
                showFinished -> ScreenKey.FINISHED
                showMemoryPlayer || screen == Screen.MEMORY -> ScreenKey.MEMORY
                screen == Screen.PREPARE -> ScreenKey.PREPARE
                screen == Screen.INFO -> ScreenKey.INFO
                else -> ScreenKey.HOME
            }
            // 화면이 바뀔 때 툭 끊기지 않도록, 새 화면이 아주 살짝 다가오며 서서히 나타난다(기존 화면은 빠르게 옅어짐).
            AnimatedContent(
                targetState = key,
                modifier = Modifier.weight(1f).fillMaxSize(),
                transitionSpec = {
                    (fadeIn(tween(520, delayMillis = 80, easing = LinearOutSlowInEasing)) + scaleIn(tween(620, easing = FastOutSlowInEasing), initialScale = 0.985f))
                        .togetherWith(fadeOut(tween(260)))
                },
                label = "screen",
            ) { target ->
                when (target) {
                    ScreenKey.RUNNING -> RunningScreen(
                        s = s,
                        events = vm::visibleEvents,
                        position = vm::position,
                        roadSamples = vm::roadSamples,
                        onPause = vm::pause,
                        onResume = vm::resume,
                        onFinish = vm::finishTrip,
                    )
                    ScreenKey.FINISHED -> FinishedScreen(
                        s = s,
                        driving = driving,
                        events = vm::visibleEvents,
                        position = vm::position,
                        onReplay = vm::replayDraft,
                        onPause = vm::pause,
                        onResume = vm::resume,
                        onStopReplay = vm::stopPlayback,
                        onSave = vm::saveDraft,
                        onDiscard = vm::discardDraft,
                        onHome = vm::closeFinished,
                    )
                    ScreenKey.MEMORY -> MemoryScreen(
                        s = s,
                        trips = trips,
                        driving = driving,
                        onBack = {
                            vm.stopPlayback()
                            vm.go(Screen.HOME)
                        },
                        onPlay = vm::playMemory,
                        onPause = vm::pause,
                        onResume = vm::resume,
                        onRestart = vm::restartPlayback,
                        onStop = vm::stopPlayback,
                        onDelete = vm::deleteTrip,
                    )
                    ScreenKey.PREPARE -> PrepareScreen(
                        s = s,
                        driving = driving,
                        onBack = { vm.go(Screen.HOME) },
                        onRequestPermission = {
                            val missing = s.liveCapability?.missingPermissions(CarConnector.PERMISSION_SPEED, PleosLocationAdapter.PERMISSION) ?: emptyArray()
                            if (missing.isNotEmpty()) permissionLauncher.launch(missing)
                        },
                        onStartLive = vm::startLive,
                        onStartDemo = vm::startDemo,
                    )
                    ScreenKey.INFO -> InfoScreen(
                        driving = driving,
                        onBack = { vm.go(Screen.HOME) },
                        onDeleteAll = vm::deleteAll,
                    )
                    ScreenKey.HOME -> HomeScreen(
                        onStartTrip = { vm.go(Screen.PREPARE) },
                        onOurMusic = { vm.go(Screen.MEMORY) },
                        onInfo = { vm.go(Screen.INFO) },
                    )
                }
            }
        }
    }
}

/** 화면 전환 애니메이션의 기준. 세션 상태와 선택한 화면을 합쳐 지금 보일 화면 하나로 정한다. */
private enum class ScreenKey { RUNNING, FINISHED, MEMORY, PREPARE, INFO, HOME }

@Composable
private fun MessageBanner(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(HyundaiBlue)
            .padding(horizontal = 32.dp, vertical = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Color.White)
    }
}

@Composable
fun ConfirmDialog(text: String, confirmLabel: String, cancelLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel, style = MaterialTheme.typography.labelLarge) }
        },
    )
}
