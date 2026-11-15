package com.pleose.shared.model

import kotlinx.serialization.Serializable

/** 부탁 유형 (PRD 1. 부탁 유형) */
@Serializable
enum class ErrandType(val label: String) {
    BUY("사 와줘"),
    PICKUP_ITEM("받아 와줘"),
    PICKUP_PERSON("데려와줘"),
    DROP_BY("들러줘");

    /** 장소 필수: 받아 와줘·데려와줘·들러줘 (F-02). 장소는 이름으로 받아 차량에서 Pleos 지도로 찾는다 */
    val requiresPlace: Boolean get() = this != BUY

    /** 시간 필수: 데려와줘 (F-02) */
    val requiresTime: Boolean get() = this == PICKUP_PERSON
}

/** 부탁 상태 (PRD 8. 부탁 상태 흐름) */
@Serializable
enum class ErrandStatus {
    CREATED,     // 생성됨
    DELIVERED,   // 전달됨
    ON_HOLD,     // 보류
    PROPOSED,    // 조건부 수락 — 운전자가 도착 예정 시각을 붙여 답했고, 요청자의 확정을 기다린다
    ACCEPTED,    // 수락
    IN_PROGRESS, // 진행 중 (지금 들르는 중)
    COMPLETED,   // 완료
    DECLINED,    // 거절
    FAILED,      // 못 처리함
    EXPIRED,     // 만료 (귀가까지 처리 못 함)
    CANCELLED;   // 취소 (요청자)

    val isTerminal: Boolean get() = this in TERMINAL
    val isActive: Boolean get() = !isTerminal

    /** 운전자가 수락해 경유지에 들어가 있는 상태 */
    val isAccepted: Boolean get() = this == ACCEPTED || this == IN_PROGRESS

    /** 운전자가 아직 답하지 않은 상태 (음성 알림 대상) */
    val isAwaitingAnswer: Boolean get() = this == CREATED || this == DELIVERED || this == ON_HOLD

    /** 조건부 수락 뒤 요청자의 [좋아요]/[다른 방법 찾을게요]를 기다리는 상태 */
    val isAwaitingRequester: Boolean get() = this == PROPOSED

    /** 요청자 앱 표시 (PRD 8. 상태 표 + 화면 시안 ⑥의 4단계 진행 바) */
    val requesterLabel: String
        get() = when (this) {
            CREATED, DELIVERED -> "전달됨"
            ON_HOLD -> "확인 전"
            PROPOSED -> "조건부 수락"
            ACCEPTED -> "수락"
            IN_PROGRESS -> "들르는 중"
            COMPLETED -> "완료"
            DECLINED, FAILED, EXPIRED -> "오늘은 어려워요"
            CANCELLED -> "취소됨"
        }

    /** 진행 바 단계 (0 전달됨 · 1 수락 · 2 들르는 중 · 3 완료). 거절·취소 등은 -1 */
    val progressStep: Int
        get() = when (this) {
            CREATED, DELIVERED, ON_HOLD, PROPOSED -> 0
            ACCEPTED -> 1
            IN_PROGRESS -> 2
            COMPLETED -> 3
            else -> -1
        }

    companion object {
        val TERMINAL = setOf(COMPLETED, DECLINED, FAILED, EXPIRED, CANCELLED)
    }
}

/** 부탁 전달 경로 (F-03) */
@Serializable
enum class Delivery(val label: String) {
    VEHICLE_VOICE("차량 음성"),
    APP_PUSH("앱 알림"),
}

/** 차량 주행 상태 (F-03, 주행 중 UX 정책) */
@Serializable
enum class DriveState(val label: String) {
    DRIVING("운전 중"),
    STOPPED("정차 중"),
    PARKED("주차 중"),
    OFF("미운행");

    /** 사람이 차 안에 있어 차량으로 전달할 수 있는 상태 */
    val inVehicle: Boolean get() = this == DRIVING || this == STOPPED
}

@Serializable
data class GeoPoint(val lat: Double, val lon: Double)

/** 장소 종류 (F-06 품목 → 장소 종류 매칭). [label]이 Pleos 지도 검색어가 된다 */
@Serializable
enum class PlaceCategory(val label: String) {
    PHARMACY("약국"),
    CONVENIENCE("편의점"),
    MART("마트"),
    SIDE_DISH("반찬가게"),
    BAKERY("빵집"),
    LAUNDRY("세탁소"),
    RESTAURANT("음식점"),
    ACADEMY("학원"),
    STATION("역"),
    HOSPITAL("병원"),
    PARCEL("무인 보관함"),
    FAMILY_HOME("가족 집"),
    HOME("집"),
    OFFICE("회사"),
}

/**
 * 영업시간 (하루 중 분). [closeMin]이 1440을 넘으면 자정 넘어 영업.
 * [open24h]면 항상 영업.
 */
@Serializable
data class OpeningHours(val openMin: Int = 0, val closeMin: Int = 24 * 60, val open24h: Boolean = false) {
    fun isOpenAt(minuteOfDay: Int): Boolean {
        if (open24h) return true
        val m = minuteOfDay
        return if (closeMin <= 24 * 60) {
            m in openMin until closeMin
        } else {
            m >= openMin || m < closeMin - 24 * 60
        }
    }

    /** 표시용: "21:00까지 영업" / "24시간" */
    val label: String
        get() = if (open24h) "24시간" else "%02d:%02d까지 영업".format((closeMin / 60) % 24, closeMin % 60)

    companion object {
        val ALWAYS = OpeningHours(open24h = true)
        fun of(open: String, close: String): OpeningHours {
            fun parse(s: String) = s.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
            val o = parse(open)
            var c = parse(close)
            if (c <= o) c += 24 * 60
            return OpeningHours(o, c)
        }
    }
}

/** 좌표를 아는 장소 (Pleos 내비의 '집' 즐겨찾기 등) */
@Serializable
data class Place(
    val id: String,
    val name: String,
    val category: PlaceCategory,
    val location: GeoPoint,
    val hours: OpeningHours = OpeningHours.ALWAYS,
    val parking: Boolean = true,
    /** 음성 안내용 짧은 이름 ("태권도", "김밥집") */
    val shortLabel: String = category.label,
    /** 정차 지점 (데려와줘: "한빛태권도 정문 앞") */
    val stopPoint: String? = null,
    val address: String = "",
)

@Serializable
data class Member(
    val id: String,
    val name: String,
    val vehicleLinked: Boolean = false,
    val driveState: DriveState = DriveState.OFF,
    val joinedAt: Long = 0,
) {
    /** "차량 연결됨 · 운전 중" / "차량 미연결" */
    val vehicleLabel: String
        get() = if (!vehicleLinked) "차량 미연결" else "차량 연결됨 · ${driveState.label}"

    val initial: String get() = name.take(1)
}

@Serializable
data class Family(val id: String, val name: String, val inviteCode: String)

/** 데려와줘 도착 예정 공유 (F-11): 요청자에게만, 수락~탑승 전까지. 실시간 좌표는 포함하지 않는다. */
@Serializable
data class PickupShare(val etaAt: Long, val stopPoint: String, val updatedAt: Long)

/**
 * 조건부 수락 제안 (기획안 3.2): 차량의 도착 예정 시각으로 계산한 "18:50 도착 가능".
 * 요청자가 [좋아요]를 누르면 [confirmedAt]이 찍히고 부탁은 수락으로 바뀐다.
 */
@Serializable
data class Proposal(val etaAt: Long, val lateMin: Int, val at: Long, val confirmedAt: Long? = null)

@Serializable
data class StatusEvent(val status: ErrandStatus, val at: Long, val note: String? = null)

@Serializable
data class Errand(
    val id: String,
    val familyId: String,
    val fromId: String,
    val fromName: String,
    val toId: String,
    val toName: String,
    val type: ErrandType,
    /** 요청자가 쓴 원문 */
    val text: String,
    /** 해석된 품목 ("해열제(시럽형)", "우유 2팩") */
    val item: String,
    /** 목록·카드 제목 ("아이 해열제 (시럽형)") */
    val title: String,
    /** 사 와줘: 품목에 맞는 장소 종류 (Pleos 지도 검색어) */
    val category: PlaceCategory? = null,
    /** 받아 와줘·데려와줘·들러줘: 요청자가 적은 장소 이름 (차량에서 Pleos 지도로 찾는다) */
    val placeName: String? = null,
    /** 요청자가 적은 상세 주소 — 있으면 근처 검색 대신 이 주소로 안내한다 */
    val address: String? = null,
    /** 상세 주소의 좌표 (요청자 휴대폰에서 변환). 있으면 차량이 검색 없이 바로 Pleos 내비 경로에 넣는다 */
    val addressLocation: GeoPoint? = null,
    /** 데려와줘: 약속 시각 */
    val appointmentAt: Long? = null,
    /** 데려와줘: 데려올 사람 ("아이") */
    val person: String? = null,
    val urgent: Boolean = false,
    val status: ErrandStatus = ErrandStatus.CREATED,
    val delivery: Delivery? = null,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val acceptedAt: Long? = null,
    val completedAt: Long? = null,
    /** 운전자가 Pleos 내비 경유지로 넣은 장소 이름 ("판교풀잎약국") — 요청자 앱 "판교풀잎약국에 들르고 있어요" */
    val targetPlaceName: String? = null,
    /** 집 도착 예정 시각 — 요청자 앱 "집 도착 예정 18:54" */
    val homeEtaAt: Long? = null,
    /** 데려와줘 도착 예정 공유 (요청자 본인 스냅숏에만 포함) */
    val pickupShare: PickupShare? = null,
    /** 요청자에게 보여 줄 한마디 ("오늘은 어렵대요", "못 샀대요") */
    val note: String? = null,
    /** 조건부 수락 제안 (요청자와 수행자에게만) */
    val proposal: Proposal? = null,
    /** 들를 곳에 도착한 때 — 요청자 앱 진행 단계 "도착" (위치 대신 단계만 공유) */
    val arrivedAt: Long? = null,
    /** 장소 미정 부탁을 경로상 매장에 들르면 늘어나는 시간 (분) */
    val detourMin: Int? = null,
    val history: List<StatusEvent> = emptyList(),
) {
    /** 음성용 이름: 괄호 수식어 제거 ("아이 해열제 (시럽형)" → "아이 해열제") */
    val spokenTitle: String get() = title.replace(Regex("\\s*\\(.*?\\)"), "").trim()

    /** 음성용 품목: "해열제(시럽형)" → "해열제" */
    val spokenItem: String get() = item.replace(Regex("\\s*\\(.*?\\)"), "").trim()

    /** Pleos 지도 검색어: 사 와줘는 장소 종류("약국"), 나머지는 요청자가 적은 장소 이름 */
    val searchKeyword: String get() = if (type == ErrandType.BUY) category?.label ?: spokenItem else placeName ?: spokenTitle

    val hasAddress: Boolean get() = !address.isNullOrBlank()

    /** 마지막 진행 단계 이름: 데려와줘 "픽업 완료", 사 와줘 "구매 완료" */
    val doneLabel: String
        get() = when (type) {
            ErrandType.PICKUP_PERSON -> "픽업 완료"
            ErrandType.BUY -> "구매 완료"
            ErrandType.PICKUP_ITEM -> "수령 완료"
            ErrandType.DROP_BY -> "방문 완료"
        }

    /** 요청자 앱 진행 단계 (기획안 3.2 진행 상태 공유: 가는 중 → 도착 → 완료). 위치는 보내지 않는다 */
    val progressLabels: List<String> get() = listOf("전달됨", "수락", "도착", doneLabel)

    /** [progressLabels]에서 지금 단계. 거절·취소 등은 -1 */
    val progressIndex: Int
        get() = when {
            status == ErrandStatus.COMPLETED -> 3
            status.isAccepted -> if (arrivedAt != null) 2 else 1
            status.isAwaitingAnswer || status.isAwaitingRequester -> 0
            else -> -1
        }
}

/**
 * 반복 부탁 즐겨찾기 (기획안 3.2): "화·목 하원 픽업"처럼 자주 보내는 부탁을 저장해 원터치로 보낸다.
 * 가족 단위로 저장해 요청자 앱과 차량 앱(자주 받는 부탁)이 함께 본다.
 */
@Serializable
data class Favorite(
    val id: String,
    val familyId: String,
    val ownerId: String,
    val ownerName: String,
    val toId: String,
    val toName: String,
    val title: String,
    val type: ErrandType,
    val text: String,
    val placeName: String? = null,
    val address: String? = null,
    val addressLocation: GeoPoint? = null,
    /** 데려와줘 약속 시각 (하루 중 분, 18:30 → 1110) */
    val appointmentMinute: Int? = null,
    /** 반복 요일 (1=월 … 7=일). 비어 있으면 요일 없이 */
    val days: List<Int> = emptyList(),
    val urgent: Boolean = false,
    val person: String? = null,
    val createdAt: Long = 0,
) {
    /** "화·목" */
    val daysLabel: String get() = days.sorted().joinToString("·") { DAY_NAMES.getOrElse(it - 1) { "" } }

    /** "해님어린이집 · 18:30까지" */
    val summary: String
        get() = listOfNotNull(
            placeName ?: address?.takeIf { it.isNotBlank() } ?: if (type == ErrandType.BUY) "장소 미정" else null,
            appointmentMinute?.let { "%02d:%02d까지".format(it / 60 % 24, it % 60) },
        ).joinToString(" · ")

    companion object {
        val DAY_NAMES = listOf("월", "화", "수", "목", "금", "토", "일")
    }
}
