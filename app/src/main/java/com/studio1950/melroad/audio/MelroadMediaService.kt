package com.studio1950.melroad.audio

import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.studio1950.melroad.MelroadApp
import com.studio1950.melroad.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * MediaLibraryService(=MBS 호환). 미디어 센터·스티어링 휠 키 연동용.
 *
 * 탐색 트리에는 저장된 음악(MEMORY)만 노출한다. LIVE/DEMO 여행 시작은 정차 상태 확인과 사용자 선택이
 * 필요하므로 앱 화면에서만 가능하다.
 */
@OptIn(UnstableApi::class)
class MelroadMediaService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as MelroadApp).container
        val player = MelroadPlayer(Looper.getMainLooper(), container.sessionController, container.scope)
        val activityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(activityIntent)
            .build()
        // Pleos 음악 앱 가이드: bind만으로는 onTaskRemoved를 못 받을 수 있어 Started 상태를 유지한다.
        try {
            startService(Intent(this, MelroadMediaService::class.java))
        } catch (e: IllegalStateException) {
            // 앱이 백그라운드일 때 시스템 브라우저가 먼저 연결한 경우 등
            Log.w(TAG, "startService 생략: ${e.javaClass.simpleName}")
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Pleos 가이드 순서: 내부 정지 → stopSelf → super
        (application as MelroadApp).container.sessionController.stopAll()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        private val repository get() = (application as MelroadApp).container.repository
        private val scope get() = (application as MelroadApp).container.scope

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT_ID, "MELROAD"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != ROOT_ID) {
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
            }
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                val items = runCatching { repository.allTrips() }.getOrDefault(emptyList()).map { memoryItem(it.id, it.title) }
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val id = mediaId.removePrefix("memory:").toLongOrNull()
                ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            scope.launch {
                val trip = runCatching { repository.trip(id) }.getOrNull()
                future.set(
                    if (trip != null) {
                        LibraryResult.ofItem(memoryItem(trip.id, trip.title), null)
                    } else {
                        LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                    },
                )
            }
            return future
        }

        /** mediaId만으로 충분하다. 실제 이벤트 로드는 MelroadPlayer.handleSetMediaItems에서 한다. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(mediaItems)

        private fun folder(id: String, title: String) = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build(),
            )
            .build()

        private fun memoryItem(id: Long, title: String) = MediaItem.Builder()
            .setMediaId("memory:$id")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist("MELROAD")
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()
    }

    companion object {
        private const val TAG = "MelroadService"
        private const val ROOT_ID = "root"
    }
}
