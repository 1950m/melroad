package com.studio1950.melroad.integration

import com.studio1950.melroad.integration.car.CarConnector
import com.studio1950.melroad.integration.car.CarUxRestrictionsAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 주행 중 UX 제한 상태.
 * restricted가 true면 텍스트 입력·설정 편집·삭제 확인·권한 요청 같은 조작을 숨긴다.
 */
data class DrivingUxState(
    /** 상태를 실제로 확인했는지. 차량인데 확인하지 못하면 보수적으로 제한한다. */
    val known: Boolean,
    val requiresDistractionOptimization: Boolean,
    val noKeyboard: Boolean,
    val noSetup: Boolean,
    val isAutomotive: Boolean,
) {
    val restricted: Boolean get() = requiresDistractionOptimization || (isAutomotive && !known)

    companion object {
        val PHONE = DrivingUxState(known = true, requiresDistractionOptimization = false, noKeyboard = false, noSetup = false, isAutomotive = false)
        val UNKNOWN_CAR = DrivingUxState(known = false, requiresDistractionOptimization = true, noKeyboard = true, noSetup = true, isAutomotive = true)
    }
}

class DrivingRestrictionMonitor(private val connector: CarConnector) {

    private val _state = MutableStateFlow(
        if (connector.isAutomotive) DrivingUxState.UNKNOWN_CAR else DrivingUxState.PHONE,
    )
    val state: StateFlow<DrivingUxState> = _state

    private var adapter: CarUxRestrictionsAdapter? = null

    fun start() {
        if (!connector.isAutomotive || adapter != null) return
        val car = connector.car() ?: run {
            _state.value = DrivingUxState.UNKNOWN_CAR
            return
        }
        val a = CarUxRestrictionsAdapter(car)
        val ok = a.register { _state.value = it }
        if (ok) adapter = a else _state.value = DrivingUxState.UNKNOWN_CAR
    }

    fun stop() {
        adapter?.unregister()
        adapter = null
    }
}
