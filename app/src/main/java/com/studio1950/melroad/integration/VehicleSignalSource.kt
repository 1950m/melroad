package com.studio1950.melroad.integration

import com.studio1950.melroad.music.DriveSample
import kotlinx.coroutines.flow.StateFlow

enum class SignalSourceKind { REAL_VEHICLE, SIMULATION }

enum class SignalSourceStatus { IDLE, RUNNING, SIGNAL_LOST, ERROR, STOPPED }

/** 신호별 사용 가능 상태. 확인하지 못한 것은 "가능"으로 표시하지 않는다. */
enum class CapabilityState {
    AVAILABLE,
    PERMISSION_REQUIRED,
    UNSUPPORTED,
    NOT_AUTOMOTIVE,
    /** 앱에 아직 연동 코드가 없음 */
    NOT_INTEGRATED,
    ERROR,
}

data class LiveCapability(
    val speed: CapabilityState,
    val location: CapabilityState,
) {
    val canStartLive: Boolean get() = speed == CapabilityState.AVAILABLE || location == CapabilityState.AVAILABLE
    val speedOnly: Boolean get() = speed == CapabilityState.AVAILABLE && location != CapabilityState.AVAILABLE
    val locationOnly: Boolean get() = location == CapabilityState.AVAILABLE && speed != CapabilityState.AVAILABLE

    /** 아직 허용받지 못한 실행 중 권한(속도·위치)의 이름 목록 */
    fun missingPermissions(speedPermission: String, locationPermission: String): Array<String> = buildList {
        if (speed == CapabilityState.PERMISSION_REQUIRED) add(speedPermission)
        if (location == CapabilityState.PERMISSION_REQUIRED) add(locationPermission)
    }.toTypedArray()
}

/** 음악 재생 위치. 일시정지 중이면 null(그동안 들어온 실제 신호는 버린다). */
fun interface MusicClock {
    fun nowMs(): Long?
}

/**
 * 주행 신호 공급원. 실제 차량([RealVehicleSignalSource])과 시뮬레이션([DemoVehicleSignalSource])을 분리한다.
 *
 * 요구서의 설계 계약(samples: Flow)과 달리 "음악 시간 구간으로 조회"하는 형태로 바꿨다.
 * 작곡 판단 시점을 마디 번호로 고정해야 DEMO가 실행 타이밍과 무관하게 항상 같은 음악을 만들기 때문이다.
 */
interface VehicleSignalSource {
    val kind: SignalSourceKind
    val status: StateFlow<SignalSourceStatus>

    fun start(clock: MusicClock)
    fun stop()

    /** 음악 시간 [fromMs, toMs]에 해당하는 원본 샘플 */
    fun samplesBetween(fromMs: Long, toMs: Long): List<DriveSample>
}
