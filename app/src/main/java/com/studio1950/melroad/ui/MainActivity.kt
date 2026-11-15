package com.studio1950.melroad.ui

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.studio1950.melroad.audio.MelroadMediaService
import com.studio1950.melroad.ui.theme.MelroadTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MelroadViewModel by viewModels()
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MelroadTheme {
                MelroadRoot(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStart()
        // 미디어 세션 서비스에 연결해 두면, 재생 중 화면을 벗어나도 Media3가 포그라운드 재생을 유지한다.
        // 연결만으로는 재생하지 않는다(자동 재생 금지).
        val token = SessionToken(this, ComponentName(this, MelroadMediaService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }
}
