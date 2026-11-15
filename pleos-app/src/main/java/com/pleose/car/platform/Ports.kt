package com.pleose.car.platform

import com.pleose.shared.model.GeoPoint
import com.pleose.shared.model.Place
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 음성 입출력 창구 — 차량에서는 [VoiceIO](Pleos TTS + Gleo AI 음성 인식), 테스트에서는 가짜 구현 */
interface VoicePort {
    val status: StateFlow<VoiceStatus>
    val heard: StateFlow<String?>
    fun speak(text: String, onDone: () -> Unit = {})
    fun listen(timeoutMs: Long, onResult: (List<String>?) -> Unit)
    fun stopListening()
    fun setHeard(text: String)
}

sealed interface NaviEvent {
    data class WaypointArrived(val name: String) : NaviEvent
    data object DestinationArrived : NaviEvent
    data object RouteStarted : NaviEvent
    data object RouteCancelled : NaviEvent
    /** 경유지 추가·삭제가 끝났다 (내비가 경로를 다시 잡은 뒤에 온다) */
    data object WaypointChanged : NaviEvent
}

/** Pleos 내비가 알려 준 경유지 (Pleos 지도 POI). 좌표는 내비가 주지 않을 때가 있다 */
data class NaviWaypoint(
    val name: String,
    val address: String,
    val location: GeoPoint?,
    /** 남은 거리 (m) */
    val remainDistanceM: Int,
    /** 남은 시간 (초) */
    val remainTimeSec: Int,
    val poiId: String,
)

/** Pleos 내비의 목적지와 남은 거리·시간 */
data class NaviDestination(
    val name: String,
    val location: GeoPoint,
    val remainDistanceM: Int,
    val remainTimeSec: Int,
)

data class NaviLocation(val location: GeoPoint, val headingDeg: Double, val address: String, val at: Long)

/** 내비 창구 — 차량에서는 [PleosNavi](NaviHelper SDK) */
interface NaviPort {
    val connected: StateFlow<Boolean>
    val destination: StateFlow<NaviDestination?>
    val waypoints: StateFlow<List<NaviWaypoint>>
    val location: StateFlow<NaviLocation?>
    /** Pleos 내비에 등록된 '집' */
    val home: StateFlow<Place?>
    val events: SharedFlow<NaviEvent>

    /** 목적지와 경유지를 한 번에 요청 */
    fun setRoute(destination: Place, waypoints: List<Place>)

    /** 목적지로 경로 요청 */
    fun routeTo(destination: Place): Boolean

    /** 좌표를 아는 장소를 경유지로 추가 */
    fun addWaypoint(place: Place, second: Boolean = false): Boolean

    /** Pleos 지도의 주변 장소 검색 화면을 연다 (운전자가 골라 '경유지로' 추가) */
    fun searchPlace(keyword: String): Boolean

    /** [index]번째(0부터) 경유지를 뺀다 — 부탁을 취소했거나 끝냈을 때 */
    fun removeWaypoint(index: Int): Boolean

    /** 안내 중인 경로를 끝낸다 — 부탁 장소가 목적지였던 경로를 그만둘 때 */
    fun cancelRoute(): Boolean

    /** Pleos 내비 화면을 앞으로 */
    fun showNavigation()
}
