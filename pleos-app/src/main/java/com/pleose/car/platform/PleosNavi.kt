package com.pleose.car.platform

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pleose.shared.model.GeoPoint
import com.pleose.shared.model.Place
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pleos 내비게이션 연동 (NaviHelper SDK).
 *
 * - 경로: Pleos 내비가 실제로 안내 중인 목적지·남은 시간·경유지를 읽는다 (집 도착 예정, F-11 픽업 도착 예정)
 * - 주변 장소: NaviHelper에는 장소 데이터 검색 API가 없어서, Pleos 내비의 장소 검색 화면(SearchPlace)을
 *   부탁 품목에 맞는 키워드("약국")로 열어 Pleos 지도의 주변 장소에서 고르게 한다. 고른 곳이 경유지로 들어오면
 *   [waypoints]로 알 수 있다.
 * - 경유지 추가(F-08), 경유지·목적지 도착(F-09)
 */
@SuppressLint("MissingPermission") // pleos.car.permission.NAVI_* 는 Manifest에 선언 (Normal 보호 수준)
class PleosNavi(context: Context) : NaviPort {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var helper: NaviHelper? = null
    private var pollJob: Job? = null

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _routing = MutableStateFlow(false)
    /** Pleos 내비가 목적지 안내 중인지 */
    val routing: StateFlow<Boolean> = _routing.asStateFlow()
    private val _location = MutableStateFlow<NaviLocation?>(null)
    override val location: StateFlow<NaviLocation?> = _location.asStateFlow()
    private val _destination = MutableStateFlow<NaviDestination?>(null)
    override val destination: StateFlow<NaviDestination?> = _destination.asStateFlow()
    private val _waypoints = MutableStateFlow<List<NaviWaypoint>>(emptyList())
    /** Pleos 내비 경로의 경유지 (최대 2곳) */
    override val waypoints: StateFlow<List<NaviWaypoint>> = _waypoints.asStateFlow()
    private val _home = MutableStateFlow<Place?>(null)
    /** Pleos 내비에 등록된 '집' 즐겨찾기 */
    override val home: StateFlow<Place?> = _home.asStateFlow()
    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<NaviEvent> = _events

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            _routing.value = true
            _events.tryEmit(NaviEvent.RouteStarted)
            refresh()
        }

        override fun onRouteCancelled() {
            _routing.value = false
            _destination.value = null
            _waypoints.value = emptyList()
            _events.tryEmit(NaviEvent.RouteCancelled)
        }

        override fun onRouteEnded() {
            _routing.value = false
        }

        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            Log.i(TAG, "waypoint changed: ${waypointChangedInfo.cmdType} ${waypointChangedInfo.index}")
            _events.tryEmit(NaviEvent.WaypointChanged)
            request { it.getWaypointInfo() }
            request { it.getDestinationInfo() }
        }

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            Log.i(TAG, "waypoint arrived: ${waypointArrivedInfo.name}")
            _events.tryEmit(NaviEvent.WaypointArrived(waypointArrivedInfo.name))
            request { it.getWaypointInfo() }
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            _routing.value = false
            _events.tryEmit(NaviEvent.DestinationArrived)
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            _location.value = NaviLocation(
                GeoPoint(currentLocationInfo.latitude, currentLocationInfo.longitude),
                currentLocationInfo.heading, currentLocationInfo.address, System.currentTimeMillis(),
            )
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            _destination.value = NaviDestination(
                destinationInfo.name.ifBlank { destinationInfo.address },
                GeoPoint(destinationInfo.latitude, destinationInfo.longitude),
                destinationInfo.remainDistance, destinationInfo.remainTime,
            ).takeIf { destinationInfo.latitude != 0.0 || destinationInfo.longitude != 0.0 }
        }

        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
            Log.d(TAG, "waypoints: ${waypointInfoList.joinToString { "${it.name}(${it.remainTime}s)" }}")
            _waypoints.value = waypointInfoList.map {
                val loc = GeoPoint(it.latitude, it.longitude).takeIf { _ -> it.latitude != 0.0 || it.longitude != 0.0 }
                NaviWaypoint(it.name.ifBlank { it.address }, it.address, loc, it.remainDistance, it.remainTime, it.poiId)
            }
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            // isRouting은 '경로 계산 중' 의미일 수 있어 목적지 정보 유무로 안내 여부를 판단한다
        }

        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
            _home.value = bookmarkInfoList.firstOrNull { it.category == BookmarkType.HOME && (it.latitude != 0.0 || it.longitude != 0.0) }?.let {
                Place(
                    id = "navi_home", name = it.name.ifBlank { "집" },
                    category = com.pleose.shared.model.PlaceCategory.HOME,
                    location = GeoPoint(it.latitude, it.longitude), shortLabel = "집", address = it.address,
                )
            }
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "navi error: $errorCode")
        }
    }

    fun start() {
        if (helper != null) return
        try {
            val h = NaviHelper(appContext)
            helper = h
            h.addListener(listener)
            h.initialize(
                onConnected = {
                    Log.i(TAG, "Pleos NaviHelper connected")
                    _connected.value = true
                    request { it.getBookmarkInfo() }
                    startPolling()
                },
                onFailed = { e ->
                    Log.w(TAG, "Pleos NaviHelper unavailable", e)
                    _connected.value = false
                },
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Pleos NaviHelper init failed: ${e.message}")
            helper = null
        }
    }

    /** 위치·목적지·경유지를 주기적으로 요청 (이벤트로도 오지만 누락 대비) */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var tick = 0
            while (isActive) {
                request { it.getCurrentLocationInfo() }
                if (tick % 2 == 0) refresh()
                if (tick % 30 == 0) request { it.getBookmarkInfo() }
                tick++
                delay(POLL_MS)
            }
        }
    }

    private fun refresh() {
        request { it.getDestinationInfo() }
        request { it.getWaypointInfo() }
    }

    /**
     * Pleos 지도에서 주변 장소를 찾는다: Pleos 내비의 장소 검색 화면을 [keyword]로 연다.
     * 운전자가 고른 곳을 '경유지 추가'하면 [waypoints]에 나타난다.
     */
    override fun searchPlace(keyword: String): Boolean {
        val h = helper?.takeIf { _connected.value } ?: return false
        return runCatching { h.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword)) }
            .onFailure { Log.w(TAG, "searchPlace failed: ${it.message}") }.isSuccess
    }

    /** 좌표를 아는 장소(요청자가 지정한 곳)를 경유지로 추가 */
    override fun addWaypoint(place: Place, second: Boolean): Boolean {
        val h = helper?.takeIf { _connected.value } ?: return false
        return runCatching {
            h.addWaypoint(
                RequestWaypointInfo(
                    latitude = place.location.lat, longitude = place.location.lon, poiName = place.name,
                    waypointIndex = if (second) WaypointIndex.SECOND else WaypointIndex.FIRST,
                    poiId = "", address = place.address.ifEmpty { place.name }, poiSubId = "",
                ),
            )
        }.onFailure { Log.w(TAG, "addWaypoint failed: ${it.message}") }.isSuccess
    }

    /** 목적지로 경로 요청 */
    override fun routeTo(destination: Place): Boolean = requestRoute(destination, emptyList())

    /**
     * 목적지와 경유지(최대 2곳)를 Pleos 내비에 넘긴다.
     * Pleos 내비는 경유지를 2곳까지 받으므로 3곳 이상이면 앞의 2곳만 넣는다.
     */
    override fun setRoute(destination: Place, waypoints: List<Place>) {
        requestRoute(destination, waypoints)
    }

    override fun cancelRoute(): Boolean {
        val h = helper?.takeIf { _connected.value } ?: return false
        return runCatching { h.cancelRoute() }
            .onFailure { Log.w(TAG, "cancelRoute failed: ${it.message}") }.isSuccess
    }

    private fun requestRoute(destination: Place, waypoints: List<Place>): Boolean {
        val h = helper?.takeIf { _connected.value } ?: return false
        val wps = waypoints.take(2)
        return runCatching {
            val dest = RouteInfo(
                latitude = destination.location.lat, longitude = destination.location.lon,
                poiName = destination.name, poiId = "", address = destination.address.ifEmpty { destination.name }, poiSubId = "",
            )
            if (wps.isEmpty()) {
                h.requestRoute(dest)
            } else {
                h.requestRouteWithWaypoints(
                    RouteWithWaypointsRequest(
                        dest,
                        wps.mapIndexed { i, p ->
                            RequestWaypointInfo(
                                latitude = p.location.lat, longitude = p.location.lon, poiName = p.name,
                                waypointIndex = if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                                poiId = "", address = p.address.ifEmpty { p.name }, poiSubId = "",
                            )
                        },
                    ),
                )
            }
        }.onFailure { Log.w(TAG, "requestRoute failed: ${it.message}") }.isSuccess
    }

    override fun removeWaypoint(index: Int): Boolean {
        val h = helper?.takeIf { _connected.value } ?: return false
        return runCatching { h.removeWaypoint(if (index == 0) WaypointIndex.FIRST else WaypointIndex.SECOND) }
            .onFailure { Log.w(TAG, "removeWaypoint failed: ${it.message}") }.isSuccess
    }

    /** Pleos 내비 화면을 앞으로 가져온다 */
    override fun showNavigation() {
        val ok = helper?.takeIf { _connected.value }?.let {
            runCatching { it.requestNavigationScreen(NavigationScreenRequest.Foreground) }.isSuccess
        } == true
        if (!ok) {
            runCatching {
                appContext.packageManager.getLaunchIntentForPackage(NAVI_APP_PACKAGE)
                    ?.let { appContext.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    private inline fun request(block: (NaviHelper) -> Unit) {
        val h = helper?.takeIf { _connected.value } ?: return
        runCatching { block(h) }.onFailure { Log.w(TAG, "navi request failed: ${it.message}") }
    }

    fun release() {
        pollJob?.cancel()
        helper?.let { h ->
            runCatching { h.removeListener(listener) }
            runCatching { h.release() }
        }
        helper = null
        _connected.value = false
    }

    companion object {
        private const val TAG = "pleose-Navi"
        private const val POLL_MS = 1_000L
        /** Pleos Connect 기본 내비게이션 앱 */
        const val NAVI_APP_PACKAGE = "ai.umos.maps.android.navigation.app"
    }
}
