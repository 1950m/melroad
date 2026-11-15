package com.studio1950.melroad.integration.car

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.util.Log
import com.studio1950.melroad.integration.DrivingUxState

/**
 * AAOS CarUxRestrictionsManager 연동.
 * 현재 상태를 먼저 읽고 onUxRestrictionsChanged 변경을 구독한다(Pleos 주행 규제 개발 가이드).
 */
internal class CarUxRestrictionsAdapter(car: Car) {

    private val manager: CarUxRestrictionsManager? =
        runCatching { car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager }.getOrNull()

    fun register(onChange: (DrivingUxState) -> Unit): Boolean {
        val m = manager ?: return false
        return try {
            m.currentCarUxRestrictions?.let { onChange(toState(it)) }
            m.registerListener { restrictions -> onChange(toState(restrictions)) }
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "주행 제한 구독 실패: ${e.javaClass.simpleName}")
            false
        }
    }

    fun unregister() {
        runCatching { manager?.unregisterListener() }
    }

    private fun toState(r: CarUxRestrictions): DrivingUxState {
        val active = r.activeRestrictions
        return DrivingUxState(
            known = true,
            requiresDistractionOptimization = r.isRequiresDistractionOptimization,
            noKeyboard = active and CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD != 0,
            noSetup = active and CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP != 0,
            isAutomotive = true,
        )
    }

    companion object {
        private const val TAG = "MelroadUx"
    }
}
