package com.studio1950.melroad.integration.car

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.util.Log

/**
 * AAOS CarPropertyManager로 PERF_VEHICLE_SPEED(m/s, Float, 읽기 전용)를 구독한다.
 * 권한: android.car.permission.CAR_SPEED (런타임 허용 필요).
 *
 * 지원 여부는 getCarPropertyConfig가 null인지로 확인한다. 수신 주기·연속성은 가정하지 않는다.
 * 콜백은 기본적으로 메인 스레드로 전달된다.
 */
internal class CarSpeedAdapter(car: Car) {

    private val propertyManager: CarPropertyManager? =
        runCatching { car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager }.getOrNull()

    private var callback: CarPropertyManager.CarPropertyEventCallback? = null

    fun isSupported(): Boolean {
        val pm = propertyManager ?: return false
        return try {
            pm.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED) != null
        } catch (e: SecurityException) {
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "속도 속성 확인 실패: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * @param onValue (m/s 또는 신호 불가 시 null, 수신 시각 elapsedRealtime ns)
     * @return 등록 성공 여부
     */
    fun register(onValue: (Float?, Long) -> Unit, onError: () -> Unit): Boolean {
        val pm = propertyManager ?: return false
        unregister()
        val cb = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                if (value.propertyId != VehiclePropertyIds.PERF_VEHICLE_SPEED) return
                val available = value.status == CarPropertyValue.STATUS_AVAILABLE
                val speed = (value.value as? Float)?.takeIf { available && it.isFinite() }
                onValue(speed, value.timestamp)
            }

            override fun onErrorEvent(propId: Int, zone: Int) {
                onError()
            }
        }
        return try {
            val ok = pm.registerCallback(cb, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
            if (ok) callback = cb
            ok
        } catch (e: SecurityException) {
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "속도 구독 실패: ${e.javaClass.simpleName}")
            false
        }
    }

    fun unregister() {
        val cb = callback ?: return
        runCatching { propertyManager?.unregisterCallback(cb) }
        callback = null
    }

    companion object {
        private const val TAG = "MelroadSpeed"
    }
}
