package com.studio1950.melroad.integration

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.studio1950.melroad.integration.car.CarConnector
import com.studio1950.melroad.integration.car.CarSpeedAdapter
import com.studio1950.melroad.integration.pleos.PleosLocationAdapter
import com.studio1950.melroad.music.DriveSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 실제 차량 신호 공급원.
 *
 * - 차량 속도: AAOS PERF_VEHICLE_SPEED(CarPropertyManager)
 * - 위치·진행 방향·고도: Pleos FusedLocation([PleosLocationAdapter])
 * 둘 중 지원·권한이 확인된 것만 연결한다. 속도만 되면 속도 기반 제한 모드이고, 위치가 없을 때 굽이·오르막을 지어내지 않는다.
 * 두 신호는 따로 들어오므로, 각 신호가 들어올 때 "최근 값(속도 2초·위치 3초 이내)"만 묶어 한 샘플로 쌓는다.
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
    private var speedAdapter: CarSpeedAdapter? = null
    private val location = PleosLocationAdapter(context)
    private var locationActive = false
    private var clock: MusicClock? = null
    private var lastValueRealtimeMs = 0L
    private var lastSpeedMps: Float? = null
    private var lastSpeedAt = 0L
    private var lastFix: PleosLocationAdapter.Fix? = null
    private var lastFixAt = 0L
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

    fun capability(): LiveCapability = LiveCapability(speedCapability(), location.capability())

    private fun speedCapability(): CapabilityState {
        if (!connector.isAutomotive) return CapabilityState.NOT_AUTOMOTIVE
        if (context.checkSelfPermission(CarConnector.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
            return CapabilityState.PERMISSION_REQUIRED
        }
        val car = connector.car() ?: return CapabilityState.ERROR
        return if (CarSpeedAdapter(car).isSupported()) CapabilityState.AVAILABLE else CapabilityState.UNSUPPORTED
    }

    override fun start(clock: MusicClock) {
        stop()
        this.clock = clock
        buffer.clear()
        val cap = capability()

        if (cap.speed == CapabilityState.AVAILABLE) {
            val car = connector.car()
            if (car != null) {
                val adapter = CarSpeedAdapter(car)
                val ok = adapter.register(
                    onValue = { speedMps, _ -> onSpeed(speedMps) },
                    onError = { _status.value = SignalSourceStatus.SIGNAL_LOST },
                )
                if (ok) speedAdapter = adapter
            }
        }
        if (cap.location == CapabilityState.AVAILABLE) {
            // 콜백 스레드가 문서에 없으므로 메인 스레드로 넘겨 버퍼를 한 스레드에서만 만진다.
            locationActive = location.start(
                onFix = { fix -> handler.post { onFix(fix) } },
                onFailure = { failure -> handler.post { onLocationFailure(failure) } },
            )
        }

        if (speedAdapter == null && !locationActive) {
            _status.value = SignalSourceStatus.ERROR
            return
        }
        lastValueRealtimeMs = SystemClock.elapsedRealtime()
        _status.value = SignalSourceStatus.RUNNING
        handler.postDelayed(watchdog, 1_000L)
    }

    override fun stop() {
        handler.removeCallbacks(watchdog)
        speedAdapter?.unregister()
        speedAdapter = null
        if (locationActive) location.stop()
        locationActive = false
        clock = null
        buffer.clear()
        lastSpeedMps = null
        lastFix = null
        if (_status.value != SignalSourceStatus.IDLE) _status.value = SignalSourceStatus.STOPPED
    }

    override fun samplesBetween(fromMs: Long, toMs: Long): List<DriveSample> =
        buffer.filter { it.timestampMs in fromMs..toMs }

    private fun onSpeed(speedMps: Float?) {
        lastSpeedMps = speedMps
        lastSpeedAt = SystemClock.elapsedRealtime()
        onAnySignal()
    }

    private fun onFix(fix: PleosLocationAdapter.Fix) {
        if (!locationActive) return // 정지 뒤 늦게 도착한 콜백은 버린다
        lastFix = fix
        lastFixAt = SystemClock.elapsedRealtime()
        onAnySignal()
    }

    private fun onLocationFailure(failure: PleosLocationAdapter.Failure) {
        // 위치가 막히면(권한·주행 중 제한·위치 꺼짐) 위치 값만 비운다. 속도가 살아 있으면 속도 기반으로 계속하고, 없으면 감시가 '신호 끊김'으로 알린다.
        Log.w(TAG, "위치 신호 사용 불가: $failure")
        lastFix = null
    }

    private fun onAnySignal() {
        val now = SystemClock.elapsedRealtime()
        lastValueRealtimeMs = now
        if (_status.value == SignalSourceStatus.SIGNAL_LOST) _status.value = SignalSourceStatus.RUNNING
        // 음악이 멈춘 동안의 샘플은 음악 시간에 놓을 곳이 없으므로 버린다.
        val musicMs = clock?.nowMs() ?: return
        val speed = lastSpeedMps.takeIf { now - lastSpeedAt <= SPEED_FRESH_MS }
        val fix = lastFix.takeIf { now - lastFixAt <= FIX_FRESH_MS }
        val sample = DriveSample(musicMs, speed, fix?.latitude, fix?.longitude, fix?.altitudeM, fix?.headingDeg)
        if (buffer.isNotEmpty() && buffer.last().timestampMs == musicMs) buffer.removeLast()
        buffer.addLast(sample)
        while (buffer.isNotEmpty() && buffer.first().timestampMs < musicMs - BUFFER_MS) buffer.removeFirst()
    }

    companion object {
        private const val TAG = "MelroadSignals"
        private const val SIGNAL_LOST_MS = 5_000L
        private const val BUFFER_MS = 90_000L
        private const val SPEED_FRESH_MS = 2_000L
        private const val FIX_FRESH_MS = 3_000L
    }
}
