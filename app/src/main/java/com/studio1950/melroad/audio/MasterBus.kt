package com.studio1950.melroad.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * 최종 출력단. 앱 재생(AudioEngine)과 오프라인 렌더 테스트가 같은 처리를 쓰도록 한곳에 둔다.
 *
 * 0. 고역 셸프(4kHz 위를 약 +2dB): 합성 음색과 리버브가 먹먹해지지 않도록 공기감을 조금 더한다(날카로운 대역까지 키우지 않음).
 * 1. 부드러운 버스 컴프레서(빠르지 않은 어택·릴리스, 낮은 비율): 악기들을 한 덩어리로 묶어 완성된 곡처럼 들리게 하고,
 *    갑자기 커지는 순간을 눌러 청각 피로를 줄인다.
 * 2. tanh 근사 소프트 클립: ±1 근처를 부드럽게 눌러 디지털 클리핑 잡음을 막는다.
 */
internal class MasterBus(sampleRate: Int) {
    private val attack = (1.0 - exp(-1.0 / (ATTACK_S * sampleRate))).toFloat()
    private val release = (1.0 - exp(-1.0 / (RELEASE_S * sampleRate))).toFloat()
    private var envelope = 0f
    private val shelfCoef = (1.0 - exp(-2.0 * Math.PI * SHELF_HZ / sampleRate)).toFloat()
    private var lowL = 0f
    private var lowR = 0f

    /** [0, n) 구간을 제자리에서 처리한다. 입력은 이미 재생 음량·MASTER가 곱해진 값이다. */
    fun process(left: FloatArray, right: FloatArray, n: Int) {
        for (i in 0 until n) {
            lowL += shelfCoef * (left[i] - lowL)
            lowR += shelfCoef * (right[i] - lowR)
            left[i] += SHELF_GAIN * (left[i] - lowL)
            right[i] += SHELF_GAIN * (right[i] - lowR)
            val peak = maxOf(abs(left[i]), abs(right[i]))
            envelope += (if (peak > envelope) attack else release) * (peak - envelope)
            val gain = if (envelope > THRESHOLD) (THRESHOLD / envelope).pow(1f - 1f / RATIO) else 1f
            left[i] = softClip(left[i] * gain * MAKEUP)
            right[i] = softClip(right[i] * gain * MAKEUP)
        }
    }

    fun reset() {
        envelope = 0f
        lowL = 0f
        lowR = 0f
    }

    companion object {
        private const val THRESHOLD = 0.30f
        private const val RATIO = 2.5f
        private const val MAKEUP = 1.3f
        private const val ATTACK_S = 0.015
        private const val RELEASE_S = 0.25
        private const val SHELF_HZ = 4000.0
        private const val SHELF_GAIN = 0.3f

        fun softClip(x: Float): Float {
            val c = x.coerceIn(-3f, 3f)
            return c * (27f + c * c) / (27f + 9f * c * c)
        }
    }
}
