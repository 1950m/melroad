package com.studio1950.melroad.integration

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.studio1950.melroad.integration.car.CarConnector
import com.studio1950.melroad.integration.car.CarSpeedAdapter
import com.studio1950.melroad.music.DriveSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 실제 차량 신호 공급원.
 *
 * 현재 연결된 신호: AAOS 차량 속도(PERF_VEHICLE_SPEED).
 * 위치·방향·고도(Pleos FusedLocation)는 SDK 필드 확인 전이라 연결하지 않았다 → 속도 기반 제한 모드.
 * 신호가 없을 때 시뮬레이션으로 몰래 바꾸지 않는다.
 */
class RealVehicleSignalSource(
    private val context: Context,
    private val connector: CarConnector,
) : VehicleSignalSource {

    override val kind = SignalSourceKind.REAL_VEHICLE

    private val _status = MutableStateFlow(SignalSourceStatus.IDLE)
    override val status: StateFlow<SignalSourceStatus> = _status

    private val buffer = ArrayDeque<DriveSample>()
    private var adapter: CarSpeedAdapter? = null
    private var clock: MusicClock? = null
    private var lastValueRealtimeMs = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val watchdog = object : Runnable {
        override fun run() {
            if (_status.value == SignalSourceStatus.RUNNING || _status.value == SignalSourceStatus.SIGNAL_LOST) {
                val silentFor = SystemClock.elapsedRealtime() - lastValueRealtimeMs
                _status.value = if (silentFor > SIGNAL_LOST_MS) SignalSourceStatus.SIGNAL_LOST else SignalSourceStatus.RUNNING
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    fun capability(): LiveCapability {
        val location = CapabilityState.NOT_INTEGRATED
        if (!connector.isAutomotive) return LiveCapability(CapabilityState.NOT_AUTOMOTIVE, location)
        if (context.checkSelfPermission(CarConnector.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
            return LiveCapability(CapabilityState.PERMISSION_REQUIRED, location)
        }
        val car = connector.car() ?: return LiveCapability(CapabilityState.ERROR, location)
        val speed = if (CarSpeedAdapter(car).isSupported()) CapabilityState.AVAILABLE else CapabilityState.UNSUPPORTED
        return LiveCapability(speed, location)
    }

    override fun start(clock: MusicClock) {
        stop()
        this.clock = clock
        buffer.clear()
        val car = connector.car()
        if (car == null) {
            _status.value = SignalSourceStatus.ERROR
            return
        }
        val speedAdapter = CarSpeedAdapter(car)
        val ok = speedAdapter.register(
            onValue = { speedMps, _ -> onSpeed(speedMps) },
            onError = { _status.value = SignalSourceStatus.SIGNAL_LOST },
        )
        if (!ok) {
            _status.value = SignalSourceStatus.ERROR
            return
        }
        adapter = speedAdapter
        lastValueRealtimeMs = SystemClock.elapsedRealtime()
        _status.value = SignalSourceStatus.RUNNING
        handler.postDelayed(watchdog, 1_000L)
    }

    override fun stop() {
        handler.removeCallbacks(watchdog)
        adapter?.unregister()
        adapter = null
        clock = null
        buffer.clear()
        if (_status.value != SignalSourceStatus.IDLE) _status.value = SignalSourceStatus.STOPPED
    }

    override fun samplesBetween(fromMs: Long, toMs: Long): List<DriveSample> =
        buffer.filter { it.timestampMs in fromMs..toMs }

    private fun onSpeed(speedMps: Float?) {
        lastValueRealtimeMs = SystemClock.elapsedRealtime()
        if (_status.value == SignalSourceStatus.SIGNAL_LOST) _status.value = SignalSourceStatus.RUNNING
        // 음악이 멈춘 동안의 샘플은 음악 시간에 놓을 곳이 없으므로 버린다.
        val musicMs = clock?.nowMs() ?: return
        val sample = DriveSample(musicMs, speedMps, null, null, null, null)
        if (buffer.isNotEmpty() && buffer.last().timestampMs == musicMs) buffer.removeLast()
        buffer.addLast(sample)
        while (buffer.isNotEmpty() && buffer.first().timestampMs < musicMs - BUFFER_MS) buffer.removeFirst()
    }

    companion object {
        private const val SIGNAL_LOST_MS = 5_000L
        private const val BUFFER_MS = 90_000L
    }
}
