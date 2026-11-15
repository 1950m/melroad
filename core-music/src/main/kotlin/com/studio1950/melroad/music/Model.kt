package com.studio1950.melroad.music

/**
 * 차량 또는 시뮬레이션에서 들어온 신호 1건.
 *
 * timestampMs는 단조 증가하는 시계 기준이다(DEMO는 음악 타임라인 ms, LIVE는 수신 시점의 음악 재생 위치).
 * 실제 벽시계 시각이나 원본 좌표를 저장소에 남기기 위한 모델이 아니다.
 */
data class DriveSample(
    val timestampMs: Long,
    val speedMps: Float?,
    val latitude: Double?,
    val longitude: Double?,
    val altitudeM: Double?,
    val bearingDeg: Float?,
)

/**
 * PIANO는 선율, PIANO_SOFT는 분산화음(같은 음색을 더 여리게), PAD는 지속 화음, BASS는 저음,
 * EPIANO는 부드러운 일렉트릭 피아노 화음, STRINGS는 현악(후렴 화음·대선율·브리지 선율),
 * GUITAR는 현을 튕기는 소리(핑거피킹·간주 선율), PERC는 부드러운 리듬(pitch 36 킥, 37 림, 38 스네어, 42 닫힌 하이햇, 46 열린 하이햇, 49 심벌 스웰).
 * 저장된 곡과의 호환을 위해 이름을 바꾸지 않는다.
 */
enum class Instrument { PIANO, PIANO_SOFT, PAD, BASS, EPIANO, STRINGS, GUITAR, PERC }

/** 저장·재생의 최소 단위. MEMORY는 이 목록을 그대로 다시 연주한다. */
data class MusicEvent(
    val atMs: Long,
    val durationMs: Long,
    val instrument: Instrument,
    /** MIDI 음 번호 */
    val pitch: Int,
    /** 1..127 */
    val velocity: Int,
    /** 0..127, 음색 밝기(렌더러가 배음 비율로 해석) */
    val expression: Int,
)

enum class SignalQuality { FULL, SPEED_ONLY, LOCATION_ONLY, MISSING }

/**
 * 약 12초 창으로 누적한 주행 특징. 1초 이하의 급변은 여기서 이미 걸러진다.
 * null은 "알 수 없음"이며, 작곡기는 null을 0으로 바꿔 가짜 변화를 만들지 않는다.
 */
data class PhraseFeature(
    val speedKmh: Float?,
    val stoppedRatio: Float,
    /** 창 안의 누적 진행 방향 변화(도). 시계 방향(오른쪽)이 양수. */
    val turnDeg: Float?,
    /** 창 앞부분 대비 뒷부분 고도 변화(m). 고도 품질이 낮으면 null. */
    val climbM: Float?,
    val quality: SignalQuality,
) {
    companion object {
        val MISSING = PhraseFeature(null, 0f, null, null, SignalQuality.MISSING)
    }
}
