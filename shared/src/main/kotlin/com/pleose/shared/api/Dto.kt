package com.pleose.shared.api

import com.pleose.shared.model.DriveState
import com.pleose.shared.model.Errand
import com.pleose.shared.model.ErrandStatus
import com.pleose.shared.model.ErrandType
import com.pleose.shared.model.Family
import com.pleose.shared.model.Favorite
import com.pleose.shared.model.GeoPoint
import com.pleose.shared.model.Member
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val PleoseJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class CreateFamilyRequest(val memberName: String, val familyName: String? = null)

@Serializable
data class JoinRequest(val inviteCode: String, val memberName: String)

@Serializable
data class JoinResult(val family: Family, val member: Member)

/** 가족 단위 동기화 스냅숏. 보는 사람에 따라 공유 범위가 걸러진다. */
@Serializable
data class Snapshot(
    val version: Long,
    val family: Family,
    val members: List<Member>,
    val errands: List<Errand>,
    val serverTime: Long,
    /** 가족이 저장한 자주 하는 부탁 */
    val favorites: List<Favorite> = emptyList(),
) {
    fun member(id: String): Member? = members.firstOrNull { it.id == id }
    fun errand(id: String): Errand? = errands.firstOrNull { it.id == id }
}

/** 부탁 작성·전송 (F-02) */
@Serializable
data class NewErrandRequest(
    val familyId: String,
    val fromId: String,
    val toId: String,
    /** null이면 문장에서 자동 분류 */
    val type: ErrandType? = null,
    val text: String,
    /** 장소 이름 (받아 와줘·데려와줘·들러줘 필수) — 차량에서 Pleos 지도로 찾는다 */
    val placeName: String? = null,
    /** 데려와줘 약속 시각 (필수) */
    val appointmentAt: Long? = null,
    val urgent: Boolean = false,
    /** 상세 주소 (선택). 비워 두면 차량에서 근처를 찾는다 */
    val address: String? = null,
    /** 상세 주소의 좌표 (선택). 수행자 위치가 아니라 요청자가 적은 장소의 좌표다 */
    val addressLocation: GeoPoint? = null,
    /** 제목 ("서아 하원 픽업"). 비우면 문장에서 만든다 — 즐겨찾기·템플릿으로 보낼 때 */
    val title: String? = null,
    /** 데려와줘: 데려올 사람 ("서아"). 비우면 문장에서 찾는다 */
    val person: String? = null,
)

/** 수행자(차량)의 상태·도착 예정 갱신. [status]가 null이면 정보만 갱신 */
@Serializable
data class StatusUpdate(
    val actorId: String,
    val status: ErrandStatus? = null,
    val note: String? = null,
    val targetPlaceName: String? = null,
    val homeEtaAt: Long? = null,
    /** 데려와줘 도착 예정 (요청자에게만 공유) */
    val pickupEtaAt: Long? = null,
    val stopPoint: String? = null,
    /** 조건부 수락: 차량 도착 예정으로 계산한 "18:50 도착 가능" */
    val proposalEtaAt: Long? = null,
    val proposalLateMin: Int? = null,
    /** 들를 곳 도착(true) / 경로에서 다시 빠짐(false) */
    val arrived: Boolean? = null,
    /** 경로상 매장에 들르면 늘어나는 시간 (분) */
    val detourMin: Int? = null,
)

/** 조건부 수락에 대한 요청자의 답: [좋아요] = true, [다른 방법 찾을게요] = false */
@Serializable
data class ProposalAnswer(val actorId: String, val accept: Boolean)

/** 자주 하는 부탁 저장 */
@Serializable
data class NewFavoriteRequest(
    val familyId: String,
    val ownerId: String,
    val toId: String,
    val title: String,
    val type: ErrandType,
    val text: String,
    val placeName: String? = null,
    val address: String? = null,
    val addressLocation: GeoPoint? = null,
    val appointmentMinute: Int? = null,
    val days: List<Int> = emptyList(),
    val urgent: Boolean = false,
    val person: String? = null,
)

@Serializable
data class DeleteFavoriteRequest(val actorId: String)

@Serializable
data class CancelRequest(val actorId: String)

@Serializable
data class VehicleUpdate(val linked: Boolean, val driveState: DriveState)

@Serializable
data class ApiError(val error: String)

class ApiException(val code: Int, message: String) : Exception(message)

/** 즐겨찾기를 오늘 보낼 부탁으로 (약속 시각은 오늘 그 시각) */
fun Favorite.toRequest(fromId: String, todayAt: (minuteOfDay: Int) -> Long): NewErrandRequest = NewErrandRequest(
    familyId = familyId,
    fromId = fromId,
    toId = toId,
    type = type,
    text = text,
    placeName = placeName,
    appointmentAt = appointmentMinute?.let(todayAt),
    urgent = urgent,
    address = address,
    addressLocation = addressLocation,
    title = title,
    person = person,
)

/** 보낸 부탁을 즐겨찾기로 저장할 때 */
fun Errand.toFavoriteRequest(days: List<Int>, minuteOfDay: (Long) -> Int): NewFavoriteRequest = NewFavoriteRequest(
    familyId = familyId,
    ownerId = fromId,
    toId = toId,
    title = title,
    type = type,
    text = text,
    placeName = placeName,
    address = address,
    addressLocation = addressLocation,
    appointmentMinute = appointmentAt?.let(minuteOfDay),
    days = days,
    urgent = urgent,
    person = person,
)
