package com.studio1950.melroad.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * 오디오 포커스 요청·해제. 내비 안내·통화·경고음이 항상 우선이다.
 * 포커스 변화에 대한 실제 동작(정지/일시정지/감쇄/재개)은 [listener]가 결정한다.
 */
class AudioFocusController(context: Context) {

    enum class Change { GAIN, LOSS, LOSS_TRANSIENT, DUCK }

    var listener: ((Change) -> Unit)? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var hasFocus = false

    private val request: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAcceptsDelayedFocusGain(false)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> listener?.invoke(Change.GAIN)
                AudioManager.AUDIOFOCUS_LOSS -> {
                    hasFocus = false
                    listener?.invoke(Change.LOSS)
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> listener?.invoke(Change.LOSS_TRANSIENT)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> listener?.invoke(Change.DUCK)
            }
        }, Handler(Looper.getMainLooper()))
        .build()

    /** @return 포커스를 얻었으면 true. 실패하면 재생하지 않는다. */
    fun request(): Boolean {
        if (hasFocus) return true
        hasFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    fun abandon() {
        if (!hasFocus) return
        audioManager.abandonAudioFocusRequest(request)
        hasFocus = false
    }
}
