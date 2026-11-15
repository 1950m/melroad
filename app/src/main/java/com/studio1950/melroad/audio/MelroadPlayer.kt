package com.studio1950.melroad.audio

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.studio1950.melroad.domain.PlaybackItemKind
import com.studio1950.melroad.domain.SessionPhase
import com.studio1950.melroad.domain.SessionState
import com.studio1950.melroad.session.TripSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Media3 세션에 노출하는 플레이어. 실제 소리는 [AudioEngine]이 내고, 이 클래스는
 * 시스템 미디어 제어(스티어링 휠 키, 미디어 센터)를 [TripSessionController]로 연결한다.
 */
@OptIn(UnstableApi::class)
class MelroadPlayer(
    looper: Looper,
    private val controller: TripSessionController,
    private val scope: CoroutineScope,
) : SimpleBasePlayer(looper) {

    init {
        scope.launch {
            controller.state
                .map { Key(it.phase, it.itemKind, it.title, it.memoryTripId, it.durationMs) }
                .distinctUntilChanged()
                .collect { invalidateState() }
        }
    }

    private data class Key(
        val phase: SessionPhase,
        val kind: PlaybackItemKind?,
        val title: String,
        val memoryId: Long?,
        val duration: Long,
    )

    override fun getState(): SimpleBasePlayer.State {
        val s = controller.state.value
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_PREPARE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_SET_MEDIA_ITEM,
            )
            .build()
        val item = currentItem(s)
        val builder = SimpleBasePlayer.State.Builder().setAvailableCommands(commands)
        if (item == null) {
            return builder
                .setPlaybackState(Player.STATE_IDLE)
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
        }
        val isLive = s.itemKind == PlaybackItemKind.LIVE_TRIP
        val durationUs = if (isLive || s.durationMs <= 0) C.TIME_UNSET else s.durationMs * 1000
        return builder
            .setPlaylist(
                listOf(
                    SimpleBasePlayer.MediaItemData.Builder(item.mediaId)
                        .setMediaItem(item)
                        .setDurationUs(durationUs)
                        .setIsSeekable(false)
                        .setIsDynamic(isLive)
                        .build(),
                ),
            )
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(s.isAudible, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs { controller.positionMs }
            .build()
    }

    private fun currentItem(s: SessionState): MediaItem? {
        val id = when (s.itemKind) {
            PlaybackItemKind.LIVE_TRIP -> "trip:live"
            PlaybackItemKind.DEMO_TRIP -> "trip:demo:${s.scenarioId}"
            PlaybackItemKind.DRAFT -> "trip:draft"
            PlaybackItemKind.MEMORY -> "memory:${s.memoryTripId}"
            null -> return null
        }
        if (s.phase == SessionPhase.READY) return null
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(s.title)
                    .setArtist("MELROAD")
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controller.resume() else controller.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        controller.stopFromSystem()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    /** 미디어 센터에서 저장곡(memory:<id>)을 고른 경우. 여행 시작은 앱 화면에서만 가능하다. */
    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        val mediaId = mediaItems.getOrNull(startIndex.coerceAtLeast(0))?.mediaId
        val tripId = mediaId?.removePrefix("memory:")?.toLongOrNull()
        if (mediaId == null || !mediaId.startsWith("memory:") || tripId == null) return Futures.immediateVoidFuture()
        val future = SettableFuture.create<Any>()
        scope.launch {
            runCatching { controller.prepareMemory(tripId, autoPlay = false) }
            future.set(Unit)
        }
        return future
    }
}
