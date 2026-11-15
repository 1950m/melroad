package com.studio1950.melroad.integration.pleos

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.content.pm.PackageManager
import com.studio1950.melroad.integration.CapabilityState

/**
 * Pleos FusedLocation(위치·진행 방향·고도) 어댑터.
 *
 * 공식 문서 순서를 따른다: 지원 확인(isSupported) → 권한 확인 → initialize → 콜백 등록 / 사용 종료 시 콜백 해제 → release.
 * 메서드 이름은 설치된 SDK(FusedLocation 1.1.3)에서 확인한 공개 API만 쓴다(PleosApiProbeTest가 기록).
 *
 * 문서에 없는 것은 추측하지 않는다.
 * - LocationData의 정확도(confidence) 척도가 문서에 없어 쓰지 않는다.
 * - 진행 방향은 0~360 범위의 유한한 값, 고도는 유한한 값만 받는다(고도 단위는 미터로 보고 오르막 판단에만 쓴다 — 기기 미검증).
 * - 콜백 스레드가 문서에 없어 호출자가 메인 스레드로 넘겨 처리한다.
 * 원본 좌표는 메모리의 짧은 버퍼에서만 쓰고, 저장·전송·로그하지 않는다.
 */
class PleosLocationAdapter(private val context: Context) {

    /** SDK 값 중 음악에 쓰는 것만 옮긴 위치 정보 */
    data class Fix(val latitude: Double, val longitude: Double, val altitudeM: Double?, val headingDeg: Float?)

    enum class Failure { PERMISSION, BLOCKED, UNAVAILABLE }

    private var client: FusedLocation? = null
    private var listener: FusedLocationListener? = null

    /** 시작 전에 지원·권한만 확인한다(initialize하지 않으므로 release도 필요 없다). */
    fun capability(): CapabilityState {
        val supported = runCatching { FusedLocation(context).isSupported() }.getOrElse { return CapabilityState.ERROR }
        if (!supported) return CapabilityState.UNSUPPORTED
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) return CapabilityState.PERMISSION_REQUIRED
        return CapabilityState.AVAILABLE
    }

    /** @return 콜백 등록까지 성공하면 true. 실패하면 이미 만든 자원을 정리하고 false. */
    fun start(onFix: (Fix) -> Unit, onFailure: (Failure) -> Unit): Boolean {
        stop()
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) return false
        val location = runCatching { FusedLocation(context) }.getOrNull() ?: return false
        if (runCatching { location.isSupported() }.getOrDefault(false).not()) return false
        return runCatching {
            location.initialize()
            client = location
            val callback = object : FusedLocationListener {
                override fun onChanged(location: LocationData?) {
                    // 문서: null은 유효한 위치가 아니다(아직 계산 전 등).
                    location?.let(::toFix)?.let(onFix)
                }

                override fun onError(exception: Exception) {
                    onFailure(classify(exception))
                }
            }
            location.registerFusedLocationCallback(callback)
            listener = callback
            true
        }.getOrElse {
            stop()
            false
        }
    }

    /** 문서 순서: 콜백 해제 → release. release 뒤에는 같은 인스턴스를 다시 쓰지 않는다. */
    fun stop() {
        val c = client
        val l = listener
        if (c != null && l != null) runCatching { c.unregisterFusedLocationCallback(l) }
        if (c != null) runCatching { c.release() }
        client = null
        listener = null
    }

    private fun toFix(d: LocationData): Fix? {
        val lat = d.latitude
        val lon = d.longitude
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val heading = d.heading.takeIf { it.isFinite() && it in 0f..360f }?.let { it % 360f }
        val altitude = d.altitude.takeIf { it.isFinite() }
        return Fix(lat, lon, altitude, heading)
    }

    /**
     * SDK의 LocationException·ErrorCode는 실행 시에만 딸려 오는 모듈(fused-location-data 0.0.3)에 있어 컴파일 경로에 노출되지 않는다(PleosApiProbeTest로 확인).
     * 그래서 SDK에서 확인한 메서드(getErrorCode)와 값 이름(ERROR_*)으로 읽는다. 다른 예외는 '사용 불가'로 본다.
     */
    private fun classify(e: Exception): Failure {
        val code = runCatching { e.javaClass.getMethod("getErrorCode").invoke(e)?.toString() }.getOrNull()
        return when (code) {
            "ERROR_SECURITY_PERMISSION" -> Failure.PERMISSION
            "ERROR_DRIVING_RESTRICTED", "ERROR_LOCATION_DISABLED" -> Failure.BLOCKED
            else -> Failure.UNAVAILABLE
        }
    }

    companion object {
        /** SDK 상수 PleosPermission.FUSED_LOCATION의 값(SDK에서 확인). 문서상 보호 수준 dangerous → 실행 중 요청. */
        const val PERMISSION = "pleos.car.permission.FUSED_LOCATION"
    }
}
