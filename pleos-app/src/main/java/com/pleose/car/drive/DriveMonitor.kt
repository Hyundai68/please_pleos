package com.pleose.car.drive

import com.pleose.car.platform.Gear
import com.pleose.car.platform.NaviLocation
import com.pleose.shared.model.DriveState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.cos
import kotlin.math.hypot

/** 주행 상태 한 장면 */
data class DriveSnapshot(
    val state: DriveState = DriveState.PARKED,
    /** 차속 (AAOS PERF_VEHICLE_SPEED, 없으면 Pleos 내비 위치 변화로 계산) */
    val speedKmh: Double = 0.0,
    /** 차속을 차량 프로퍼티에서 받는지 */
    val speedFromVehicle: Boolean = false,
    val gear: Gear? = null,
    /** 테스트용 강제 상태 (에뮬레이터는 움직이지 않으므로) */
    val override: DriveState? = null,
    /** 합류 구간 등 주의 구간 (테스트) */
    val caution: Boolean = false,
    val doorOpen: Boolean = false,
    /** 차량이 주행 중 화면 제한을 요구함 (CarUxRestrictions) */
    val uxRestricted: Boolean = false,
    /** 시연에서 정한 속도 (에뮬레이터는 달리지 않으므로) */
    val simSpeedKmh: Double? = null,
) {
    /** 판단에 쓰는 속도: 시연 속도가 있으면 그것 */
    val speed: Double get() = simSpeedKmh ?: speedKmh

    /** 화면 표시: "신호 대기 · 안내 가능" / "저속 구간 · 안내 가능" / "고속 주행 · 안내 보류" */
    val momentLabel: String
        get() = when {
            caution -> "주의 구간 · 안내 보류"
            state == DriveState.PARKED -> "주차 중"
            speed >= com.pleose.shared.policy.DrivingPolicy.HIGH_SPEED_KMH -> "고속 주행 · 안내 보류"
            speed < 1 && state != DriveState.OFF -> if (state == DriveState.DRIVING || state == DriveState.STOPPED) "신호 대기 · 안내 가능" else "정차 중"
            speed < com.pleose.shared.policy.DrivingPolicy.LOW_SPEED_KMH -> "저속 구간 · 안내 가능"
            else -> "주행 중"
        }
}

/**
 * 주행 상태 판단. pleose는 자체 경로·위치를 만들지 않고 Pleos 신호만 쓴다.
 * - 기어: AAOS GEAR_SELECTION — P면 주차
 * - 차속: AAOS PERF_VEHICLE_SPEED. 받을 수 없으면 Pleos 내비 현재 위치의 변화량으로 계산
 * - 도어(Pleos Vehicle SDK): 데려와줘 탑승 감지
 * - CarUxRestrictions: 차량이 화면 제한을 요구하면 주행 중으로 본다
 */
class DriveMonitor(private val clock: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow(DriveSnapshot())
    val state: StateFlow<DriveSnapshot> = _state.asStateFlow()

    private var lastLoc: NaviLocation? = null
    private var lastLocAt = 0L

    fun onGear(g: Gear?) = update { copy(gear = g) }

    /** 차량 프로퍼티 차속 (km/h) */
    fun onVehicleSpeed(kmh: Double) = update { copy(speedKmh = kmh, speedFromVehicle = true) }

    fun onDoor(open: Boolean) = update { copy(doorOpen = open) }

    fun onUxRestricted(on: Boolean) = update { copy(uxRestricted = on) }

    fun setOverride(s: DriveState?) = update { copy(override = s) }

    fun setCaution(on: Boolean) = update { copy(caution = on) }

    /** 시연: 주행 상태와 속도를 정한다. 둘 다 null이면 차량 신호로 돌아간다 */
    fun simulate(state: DriveState?, kmh: Double?) = update { copy(override = state, simSpeedKmh = kmh) }

    fun onLocation(loc: NaviLocation) {
        val prev = lastLoc
        val now = clock()
        lastLoc = loc
        if (prev == null) {
            lastLocAt = now
            return
        }
        val dt = (now - lastLocAt) / 1000.0
        if (dt < 0.8) return
        lastLocAt = now
        val dy = (loc.location.lat - prev.location.lat) * 111_000.0
        val dx = (loc.location.lon - prev.location.lon) * 111_000.0 * cos(Math.toRadians(loc.location.lat))
        val kmh = if (dt > 10) 0.0 else hypot(dx, dy) / dt * 3.6
        if (!_state.value.speedFromVehicle) update { copy(speedKmh = speedKmh * 0.5 + kmh * 0.5) }
    }

    /** 위치가 한동안 안 오면 멈춘 것으로 본다 */
    fun tick() {
        val s = _state.value
        if (!s.speedFromVehicle && clock() - lastLocAt > 6_000 && s.speedKmh > 0) update { copy(speedKmh = 0.0) }
    }

    private inline fun update(block: DriveSnapshot.() -> DriveSnapshot) {
        _state.update { s ->
            val n = s.block()
            n.copy(state = derive(n))
        }
    }

    private fun derive(s: DriveSnapshot): DriveState = s.override ?: when {
        s.uxRestricted -> DriveState.DRIVING
        s.gear == Gear.PARK -> DriveState.PARKED
        s.speedKmh >= MOVING_KMH -> DriveState.DRIVING
        s.gear == Gear.DRIVE || s.gear == Gear.REVERSE || s.gear == Gear.NEUTRAL -> DriveState.STOPPED
        else -> DriveState.PARKED
    }

    private companion object {
        const val MOVING_KMH = 5.0
    }
}
