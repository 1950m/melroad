package com.studio1950.melroad.integration

import com.studio1950.melroad.music.DriveFixture
import com.studio1950.melroad.music.DriveSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 사전 제작한 합성 주행 데이터(시뮬레이션). 실제 차량 신호와 절대 섞지 않는다.
 * 샘플 시각이 곧 음악 시간이라, 재생 타이밍과 관계없이 같은 음악이 만들어진다.
 */
class DemoVehicleSignalSource(val fixture: DriveFixture) : VehicleSignalSource {
    override val kind = SignalSourceKind.SIMULATION

    private val _status = MutableStateFlow(SignalSourceStatus.IDLE)
    override val status: StateFlow<SignalSourceStatus> = _status

    override fun start(clock: MusicClock) {
        _status.value = SignalSourceStatus.RUNNING
    }

    override fun stop() {
        _status.value = SignalSourceStatus.STOPPED
    }

    override fun samplesBetween(fromMs: Long, toMs: Long): List<DriveSample> =
        fixture.samples.filter { it.timestampMs in fromMs..toMs }
}
