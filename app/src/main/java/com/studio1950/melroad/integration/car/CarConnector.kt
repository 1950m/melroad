package com.studio1950.melroad.integration.car

import android.car.Car
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * AAOS Car 서비스 연결. 차량(Automotive)이 아닌 기기에서는 android.car 클래스가 없을 수 있으므로
 * [isAutomotive]를 먼저 확인하고, Car 클래스를 참조하는 코드는 이 패키지 안에만 둔다.
 */
class CarConnector(private val context: Context) {

    private var car: Car? = null

    val isAutomotive: Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

    /** 연결된 Car 객체. 차량이 아니거나 연결에 실패하면 null. */
    @Synchronized
    fun car(): Car? {
        if (!isAutomotive) return null
        car?.let { if (it.isConnected) return it }
        car = try {
            Car.createCar(context)
        } catch (e: Throwable) {
            // NoClassDefFoundError 포함: android.car 라이브러리가 없는 이미지
            Log.w(TAG, "Car 연결 실패: ${e.javaClass.simpleName}")
            null
        }
        return car
    }

    @Synchronized
    fun disconnect() {
        runCatching { car?.disconnect() }
        car = null
    }

    companion object {
        private const val TAG = "MelroadCar"
        /** android.car.Car.PERMISSION_SPEED 와 같은 값. 차량이 아닌 기기에서 Car 클래스를 로드하지 않으려고 문자열로 둔다. */
        const val PERMISSION_SPEED = "android.car.permission.CAR_SPEED"
    }
}
