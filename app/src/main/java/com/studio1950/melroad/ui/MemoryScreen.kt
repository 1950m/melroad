package com.studio1950.melroad.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studio1950.melroad.R
import com.studio1950.melroad.domain.PlaybackItemKind
import com.studio1950.melroad.domain.SessionPhase
import com.studio1950.melroad.domain.SessionState
import com.studio1950.melroad.domain.TripSource
import com.studio1950.melroad.domain.TripSummary
import com.studio1950.melroad.integration.DrivingUxState
import com.studio1950.melroad.ui.theme.Subtle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** S05 MEMORY: 저장곡 목록·재생·삭제 */
@Composable
fun MemoryScreen(
    s: SessionState,
    trips: List<TripSummary>,
    driving: DrivingUxState,
    onBack: () -> Unit,
    onPlay: (Long) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onStop: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<TripSummary?>(null) }
    LaunchedEffect(driving.restricted) { if (driving.restricted) pendingDelete = null }
    val dateFormat = remember { SimpleDateFormat("M월 d일 (E) a h:mm", Locale.KOREAN) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { BackRow(onBack, stringResource(R.string.action_back), stringResource(R.string.memory_title)) }

        if (s.isMemoryActive && s.itemKind == PlaybackItemKind.MEMORY) {
            item {
                SectionCard {
                    Text(
                        stringResource(if (s.phase == SessionPhase.MEMORY_PLAYING) R.string.memory_now_playing else R.string.memory_paused),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Subtle,
                    )
                    Text(s.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    StatusText("${formatClock(s.elapsedMs)} / ${formatClock(s.durationMs)}", large = false)
                    if (s.phase == SessionPhase.MEMORY_PLAYING) {
                        BigButton(stringResource(R.string.action_pause), onPause, icon = IconKind.PAUSE)
                    } else {
                        BigButton(stringResource(R.string.action_resume), onResume, icon = IconKind.PLAY)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        BigButton(stringResource(R.string.action_restart), onRestart, Modifier.weight(1f), primary = false)
                        BigButton(stringResource(R.string.action_stop_to_list), onStop, Modifier.weight(1f), primary = false, icon = IconKind.STOP)
                    }
                }
            }
        }

        if (driving.restricted) {
            item { Text(stringResource(R.string.info_driving_locked), style = MaterialTheme.typography.bodyLarge, color = Subtle) }
        }

        if (trips.isEmpty()) {
            item { StatusText(stringResource(R.string.memory_empty), large = false) }
        }

        items(trips, key = { it.id }) { trip ->
            SectionCard {
                Text(trip.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(
                        R.string.memory_item_meta,
                        dateFormat.format(Date(trip.startedAt)),
                        formatClock(trip.durationMs),
                        stringResource(if (trip.source == TripSource.LIVE) R.string.source_live else R.string.source_demo),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Subtle,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BigButton(
                        stringResource(R.string.action_play),
                        { onPlay(trip.id) },
                        Modifier.weight(1f),
                        icon = IconKind.PLAY,
                        contentDescription = stringResource(R.string.play_item_cd, trip.title),
                    )
                    if (!driving.restricted) {
                        BigButton(
                            stringResource(R.string.action_delete),
                            { pendingDelete = trip },
                            Modifier.weight(1f),
                            primary = false,
                            contentDescription = stringResource(R.string.delete_item_cd, trip.title),
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { trip ->
        ConfirmDialog(
            text = stringResource(R.string.delete_confirm, trip.title),
            confirmLabel = stringResource(R.string.action_confirm_delete),
            cancelLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                onDelete(trip.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}
