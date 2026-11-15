package com.studio1950.melroad.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
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
            Box(Modifier.weight(1f)) {
                when {
                    s.isTripActive -> RunningScreen(
                        s = s,
                        events = vm::visibleEvents,
                        position = vm::position,
                        onPause = vm::pause,
                        onResume = vm::resume,
                        onFinish = vm::finishTrip,
                    )
                    showFinished -> FinishedScreen(
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
                    showMemoryPlayer || screen == Screen.MEMORY -> MemoryScreen(
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
                    screen == Screen.PREPARE -> PrepareScreen(
                        s = s,
                        driving = driving,
                        onBack = { vm.go(Screen.HOME) },
                        onRequestPermission = { permissionLauncher.launch(CarConnector.PERMISSION_SPEED) },
                        onStartLive = vm::startLive,
                        onStartDemo = vm::startDemo,
                    )
                    screen == Screen.INFO -> InfoScreen(
                        driving = driving,
                        onBack = { vm.go(Screen.HOME) },
                        onDeleteAll = vm::deleteAll,
                    )
                    else -> HomeScreen(
                        onStartTrip = { vm.go(Screen.PREPARE) },
                        onOurMusic = { vm.go(Screen.MEMORY) },
                        onInfo = { vm.go(Screen.INFO) },
                    )
                }
            }
        }
    }
}

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
