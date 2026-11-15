package com.pleose.car.platform

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Gear(val label: String) { PARK("P"), REVERSE("R"), NEUTRAL("N"), DRIVE("D"), UNKNOWN("-") }

/**
 * 차량 신호.
 * - 기어·차속: AAOS 표준 프로퍼티 GEAR_SELECTION, PERF_VEHICLE_SPEED (CarPropertyManager).
 *   Pleos Vehicle SDK의 Gear API는 deprecated이며 SDK 명세가 GEAR_SELECTION 사용을 권장한다.
 *   (android.car.permission.CAR_POWERTRAIN, android.car.permission.CAR_SPEED)
 * - 도어 열림·닫힘: Pleos Vehicle SDK. 먼저 checkDoorPositionCapabilities로 지원하는 문을 확인한 뒤 그 문만 구독한다.
 *   (pleos.car.permission.CAR_DOORS) — 데려와줘 탑승 감지(F-09)
 *
 * 에뮬레이터에서는 Pleos Connect Virtual Console(adb forward tcp:8088 tcp:8088 → http://127.0.0.1:8088)로
 * 기어·도어를 바꿔 시험할 수 있다.
 */
class CarSignals(context: Context) {
    private val appContext = context.applicationContext
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private var vehicle: Vehicle? = null
    private val unregisters = mutableListOf<() -> Unit>()

    private val _vehicleSdk = MutableStateFlow(false)
    val vehicleSdk: StateFlow<Boolean> = _vehicleSdk.asStateFlow()
    private val _carApi = MutableStateFlow(false)
    val carApi: StateFlow<Boolean> = _carApi.asStateFlow()

    private val _gear = MutableStateFlow<Gear?>(null)
    val gear: StateFlow<Gear?> = _gear.asStateFlow()

    /** 차속 (km/h). 받을 수 없으면 null */
    private val _speedKmh = MutableStateFlow<Double?>(null)
    val speedKmh: StateFlow<Double?> = _speedKmh.asStateFlow()

    /**
     * 차량이 주행 중 화면 제한을 요구하는지 (CarUxRestrictions). 오버레이 창은 시스템의 액티비티 차단을 받지 않으므로
     * Pleos 주행 안전 가이드대로 이 값을 직접 확인해 화면 조작을 막는다.
     */
    private val _uxRestricted = MutableStateFlow(false)
    val uxRestricted: StateFlow<Boolean> = _uxRestricted.asStateFlow()

    private val doorStates = mutableMapOf<DoorArea, Boolean>()
    private val _anyPassengerDoorOpen = MutableStateFlow<Boolean?>(null)
    val anyPassengerDoorOpen: StateFlow<Boolean?> = _anyPassengerDoorOpen.asStateFlow()

    fun start() {
        startCarProperties()
        startVehicleSdk()
    }

    // region AAOS 차량 프로퍼티: 기어·차속

    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.GEAR_SELECTION -> (value.value as? Int)?.let { _gear.value = it.toGear() }
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> (value.value as? Float)?.let { _speedKmh.value = it * 3.6 }
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "property error: $propertyId")
        }
    }

    private fun startCarProperties() {
        if (car != null) return
        runCatching {
            val c = Car.createCar(appContext) ?: return
            car = c
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            props = pm
            subscribe(pm, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            subscribe(pm, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
            _carApi.value = true
            startUxRestrictions(c)
        }.onFailure { Log.w(TAG, "Car API unavailable: ${it.message}") }
    }

    private fun startUxRestrictions(c: Car) {
        runCatching {
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            _uxRestricted.value = m.currentCarUxRestrictions?.isRequiresDistractionOptimization == true
            m.registerListener { r -> _uxRestricted.value = r.isRequiresDistractionOptimization }
        }.onFailure { Log.w(TAG, "CarUxRestrictions unavailable: ${it.message}") }
    }

    private fun subscribe(pm: CarPropertyManager, id: Int, rate: Float) {
        runCatching {
            if (pm.getCarPropertyConfig(id) == null) return
            pm.getProperty<Any>(id, 0)?.let(propertyCallback::onChangeEvent)
            pm.registerCallback(propertyCallback, id, rate)
        }.onFailure { Log.w(TAG, "subscribe $id failed: ${it.message}") }
    }

    private fun Int.toGear(): Gear = when (this) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.DRIVE // GEAR_DRIVE, GEAR_1..9
    }

    // endregion

    // region Pleos Vehicle SDK: 도어

    private fun startVehicleSdk() {
        if (vehicle != null) return
        val v = try {
            Vehicle(appContext).also { it.initialize() }
        } catch (e: Throwable) {
            Log.w(TAG, "Vehicle SDK unavailable: ${e.message}")
            return
        }
        vehicle = v
        _vehicleSdk.value = true
        runCatching {
            val api = v.getDoor()
            // 지원하는 문 확인 (도메인 Capability 확인): 읽기 권한이 있을 때만, 운전석을 뺀 탑승 문만 구독한다.
            // 문 열림 정도의 최대값은 차종마다 달라서, 문별 최소값(완전히 닫힘)보다 크면 열린 것으로 본다.
            api.checkDoorPositionCapabilities(
                { config, areas ->
                    if (config.accessType != AccessType.READ && config.accessType != AccessType.READ_WRITE) {
                        Log.w(TAG, "door position not readable: ${config.accessType}")
                        return@checkDoorPositionCapabilities
                    }
                    areas.filterKeys { a -> a.values.any { it in PASSENGER_DOORS } }
                        .forEach { (area, bounds) -> bindDoor(v, area, bounds.minValue) }
                },
                { e -> Log.w(TAG, "door capability: ${e.message}") },
            )
        }.onFailure { Log.w(TAG, "door: ${it.message}") }
    }

    private fun bindDoor(v: Vehicle, area: DoorArea, closed: Int) {
        val api = v.getDoor()
        api.getCurrentDoorPosition(area, { p -> setDoor(area, p, closed) }, { Log.w(TAG, "door: ${it.message}") })
        val l = object : DoorPositionListener {
            override fun onPositionUpdated(pos: Int?) = setDoor(area, pos, closed)
            override fun onFailed(e: Exception) {
                Log.w(TAG, "door: ${e.message}")
            }
        }
        api.registerDoorPosition(area, l)
        unregisters += { api.unregisterDoorPosition(area, l) }
    }

    @Synchronized
    private fun setDoor(area: DoorArea, pos: Int?, closed: Int) {
        if (pos == null) return
        doorStates[area] = pos > closed
        _anyPassengerDoorOpen.value = doorStates.values.any { it }
    }

    // endregion

    fun stop() {
        runCatching { props?.unregisterCallback(propertyCallback) }
        runCatching { car?.disconnect() }
        car = null
        props = null
        unregisters.forEach { runCatching(it) }
        unregisters.clear()
        runCatching { vehicle?.release() }
        vehicle = null
        _vehicleSdk.value = false
        _carApi.value = false
    }

    private companion object {
        const val TAG = "pleose-Car"
        val PASSENGER_DOORS = setOf(DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT, DoorValue.ROW_3_LEFT, DoorValue.ROW_3_RIGHT)
    }
}
