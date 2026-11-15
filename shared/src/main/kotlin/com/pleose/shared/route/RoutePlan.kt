package com.pleose.shared.route

import com.pleose.shared.model.Errand
import com.pleose.shared.model.GeoPoint
import com.pleose.shared.model.Place
import com.pleose.shared.model.PlaceCategory
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

object Geo {
    /** 가까운 두 점 사이 거리 (m). 도시 안 거리라 평면 근사로 충분하다 */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val dy = (b.lat - a.lat) * 111_000.0
        val dx = (b.lon - a.lon) * 111_000.0 * cos(Math.toRadians(a.lat))
        return hypot(dx, dy)
    }
}

/**
 * 경로에 넣기 전의 도착 예정 어림 (조건부 수락의 지연 계산).
 * Pleos 내비는 경로에 넣어야 시간을 알려 주므로, 넣기 전에는 직선거리에 우회 계수와 도심 평균 속도를 곱해 어림한다.
 */
object EtaEstimator {
    const val CITY_KMH = 30.0
    const val DETOUR_FACTOR = 1.35
    const val MIN_SEC = 60

    fun travelSec(from: GeoPoint, to: GeoPoint): Int =
        (Geo.distanceM(from, to) * DETOUR_FACTOR / (CITY_KMH / 3.6)).roundToInt().coerceAtLeast(MIN_SEC)
}

/** 장소 미정 부탁의 경로상 후보 한 곳 */
data class PlanOption(val place: Place, val detourMin: Int?)

/**
 * 부탁을 어디서 처리할지 미리 알 때의 계획: 들를 곳, 그곳 도착 예정, 들르면 늘어나는 시간.
 * 계획이 있으면 Pleos 지도 검색 없이 바로 경로에 넣고, 음성 안내에 도착 예정·추가 시간을 함께 말한다.
 */
data class ErrandPlan(
    val place: Place,
    /** 지금 경로로 그곳에 도착하는 시각 (모르면 null) */
    val etaAt: Long? = null,
    /** 경로에 들르면 늘어나는 시간 (분) */
    val detourMin: Int? = null,
    /** 장소 미정 부탁: 경로상 후보 (추가 시간 순). [place]는 그중 고른 곳 */
    val options: List<PlanOption> = emptyList(),
) {
    /** 약속 시각보다 몇 분 늦는지 (늦지 않으면 null) */
    fun lateMin(e: Errand): Int? {
        val appt = e.appointmentAt ?: return null
        val eta = etaAt ?: return null
        return ((eta - appt) / 60_000.0).roundToInt().takeIf { it > 0 }
    }

    /** "다른 곳": 다음 후보로 바꾼 계획 (후보가 하나뿐이면 null) */
    fun nextOption(): ErrandPlan? {
        if (options.size < 2) return null
        val i = options.indexOfFirst { it.place.name == place.name }
        val next = options[(i + 1).mod(options.size)]
        return copy(place = next.place, detourMin = next.detourMin)
    }
}

/** 부탁마다 계획을 세운다. 세울 수 없으면 null — 차량이 Pleos 지도에서 찾는다 */
fun interface ErrandPlanner {
    fun plan(errand: Errand, here: GeoPoint?, now: Long): ErrandPlan?
}

/** 요청자가 적은 상세 주소의 좌표가 있으면 그곳으로. 현재 위치를 알면 도착 예정도 어림한다 */
object AddressPlanner : ErrandPlanner {
    override fun plan(errand: Errand, here: GeoPoint?, now: Long): ErrandPlan? {
        val loc = errand.addressLocation ?: return null
        val place = Place(
            id = "errand_${errand.id}",
            name = errand.placeName ?: errand.address.orEmpty(),
            category = errand.category ?: PlaceCategory.FAMILY_HOME,
            location = loc,
            address = errand.address.orEmpty(),
        )
        return ErrandPlan(place, etaAt = here?.let { now + EtaEstimator.travelSec(it, loc) * 1000L })
    }
}
