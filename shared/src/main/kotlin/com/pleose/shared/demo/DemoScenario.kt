package com.pleose.shared.demo

import com.pleose.shared.api.PleoseJson
import com.pleose.shared.model.ErrandType
import com.pleose.shared.model.GeoPoint
import com.pleose.shared.model.Place
import com.pleose.shared.model.PlaceCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * 시연 시나리오: 영상으로 담을 흐름을 처음부터 끝까지 정해 두면 차량 앱이 그대로 진행한다.
 *
 * 어디로 가는지([home], 부탁마다 [DemoErrand.place]), 어떤 부탁을 보내는지([DemoStep.errands]),
 * 운전자·요청자가 몇 초 뒤 어떻게 답하는지, 도착·출발(자동 완료)까지 몇 초 걸리는지를 모두 정한다.
 * JSON으로 주고받을 수 있어 PC에서 고쳐 넣어도 된다.
 */
@Serializable
data class DemoScenario(
    val id: String,
    val title: String,
    val description: String = "",
    /** 이 차를 운전하는 사람 (시연용 가족을 만들 때) */
    val driverName: String = "현우",
    /** 최종 목적지 (집). 시작할 때 Pleos 내비에 이 경로를 잡는다 */
    val home: DemoPlace? = null,
    val routeHomeAtStart: Boolean = true,
    /** 시작을 누른 뒤 첫 단계까지 (화면 녹화를 켤 시간, 초) */
    val startDelaySec: Int = 5,
    /** 평소 주행 속도 (km/h) */
    val cruiseKmh: Int = 45,
    /** 진행 화면(Please 패널)을 계속 띄워 둔다 */
    val showPanel: Boolean = true,
    val steps: List<DemoStep> = emptyList(),
    /** 마지막 단계 뒤 집 도착까지 (초). null이면 도착시키지 않는다 */
    val arriveHomeAfterSec: Int? = null,
    /** 끝나면 처음부터 다시 */
    val loop: Boolean = false,
    /** 연결된 가족(요청자 앱)으로 시연한다. 요청자 이름이 가족 이름과 같아야 한다. false면 차 안에 시연용 가족을 만든다 */
    val useConnectedFamily: Boolean = false,
) {
    /** 시나리오에 나오는 요청자 이름 */
    val requesters: List<String> get() = steps.flatMap { s -> s.errands.map { it.from.trim() } }.filter { it.isNotEmpty() }.distinct()

    /** 대략 걸리는 시간 (초) — 목록에 보여 준다 */
    val estimatedSec: Int
        get() = startDelaySec + steps.sumOf { s ->
            s.delaySec + s.holdSec + s.replyAfterSec + 8 +
                (if (s.driverReply == DriverReply.CONDITIONAL) s.requesterReplyAfterSec + 6 else 0) +
                (if (s.driverReply == DriverReply.ACCEPT || s.driverReply == DriverReply.CONDITIONAL) s.errands.sumOf { it.driveSec + it.stopSec + 4 } else 0)
        } + (arriveHomeAfterSec ?: 0)

    /** 바로 쓸 수 없는 값을 알려 준다 (비어 있으면 시작할 수 있다) */
    fun problems(): List<String> = buildList {
        if (title.isBlank()) add("시나리오 이름을 적어 주세요")
        if (steps.isEmpty()) add("단계를 하나 이상 넣어 주세요")
        if (driverName.isBlank()) add("운전자 이름을 적어 주세요")
        steps.forEachIndexed { i, s ->
            if (s.errands.isEmpty()) add("${i + 1}단계에 부탁이 없어요")
            s.errands.forEachIndexed { j, e ->
                val at = "${i + 1}단계 ${j + 1}번째 부탁"
                if (e.from.isBlank()) add("$at: 요청자 이름을 적어 주세요")
                if (e.from.trim() == driverName.trim()) add("$at: 요청자와 운전자가 같아요")
                if (e.text.isBlank() && e.title.isBlank()) add("$at: 부탁 내용을 적어 주세요")
                if (e.type.requiresPlace && e.place == null) add("$at: ${e.type.label}는 들를 곳이 필요해요")
                if (e.type.requiresTime && e.appointmentInMin == null) add("$at: 데려와줘는 약속 시각이 필요해요")
                if (s.driverReply == DriverReply.CONDITIONAL && e.appointmentInMin != null && e.etaMin != null && e.etaMin <= e.appointmentInMin) {
                    add("$at: 조건부 수락은 도착 예정이 약속보다 늦어야 해요")
                }
            }
        }
    }

    companion object {
        fun fromJson(text: String): List<DemoScenario> {
            val t = text.trim()
            return if (t.startsWith("[")) {
                PleoseJson.decodeFromString(ListSerializer(serializer()), t)
            } else {
                listOf(PleoseJson.decodeFromString(serializer(), t))
            }
        }

        fun toJson(list: List<DemoScenario>): String = PrettyJson.encodeToString(ListSerializer(serializer()), list)
        fun toJson(s: DemoScenario): String = PrettyJson.encodeToString(serializer(), s)

        private val PrettyJson = kotlinx.serialization.json.Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

/** 시연의 한 단계: 부탁을 보내고, 운전자가 답하고, 들렀다가 끝날 때까지 */
@Serializable
data class DemoStep(
    val title: String = "",
    /** 앞 단계가 끝나고 이 단계의 부탁을 보내기까지 (초) */
    val delaySec: Int = 3,
    /** 함께 보내는 부탁 (두 건이면 한 번에 묶어 안내) */
    val errands: List<DemoErrand> = emptyList(),
    /** 고속 주행으로 알림을 미루는 시간 (초). 0이면 바로 안내 */
    val holdSec: Int = 0,
    val holdKmh: Int = 72,
    /** 안내하는 순간의 속도: 0 = 신호 대기, 18 = 저속 구간 */
    val safeKmh: Int = 0,
    val driverReply: DriverReply = DriverReply.ACCEPT,
    /** 음성 안내가 끝나고 운전자가 답하기까지 (초) */
    val replyAfterSec: Int = 3,
    /** 조건부 수락일 때 요청자의 답 */
    val requesterReply: RequesterReply = RequesterReply.CONFIRM,
    /** 조건부 수락을 보내고 요청자가 답하기까지 (초) */
    val requesterReplyAfterSec: Int = 6,
)

@Serializable
enum class DriverReply(val label: String) {
    ACCEPT("수락"),
    CONDITIONAL("조건부 수락"),
    DECLINE("거절"),
    LATER("나중에"),
    /** 화면·음성으로 직접 답할 때까지 기다린다 */
    MANUAL("직접 답하기"),
}

@Serializable
enum class RequesterReply(val label: String) {
    CONFIRM("좋아요"),
    DECLINE("다른 방법 찾을게요"),
    /** 요청자 앱에서 직접 누를 때까지 기다린다 (연결된 가족으로 시연할 때) */
    MANUAL("직접 답하기"),
}

@Serializable
enum class DemoOutcome(val label: String) { DONE("완료"), FAILED("못 함") }

/** 시연에서 보낼 부탁 하나와 그 부탁의 진행 시간 */
@Serializable
data class DemoErrand(
    /** 요청자 이름 */
    val from: String = "지은",
    val type: ErrandType = ErrandType.BUY,
    /** 요청자가 쓴 문장 */
    val text: String = "",
    /** 화면·음성에 쓸 제목 ("서아 하원 픽업"). 비우면 문장에서 만든다 */
    val title: String = "",
    /** 데려와줘: 데려올 사람 */
    val person: String = "",
    /** 들를 곳 (좌표가 있으면 검색 없이 바로 경로에 넣는다). 사 와줘에서 비우면 [candidates]의 첫 곳 */
    val place: DemoPlace? = null,
    /** 장소 미정 부탁의 경로상 후보 (추가 시간 순) */
    val candidates: List<DemoPlace> = emptyList(),
    /** 약속 시각: 부탁을 보낸 때부터 N분 뒤 */
    val appointmentInMin: Int? = null,
    /** 지금 경로로 들를 곳까지 걸리는 시간 (분). 약속보다 늦으면 조건부 수락을 쓸 수 있다 */
    val etaMin: Int? = null,
    val urgent: Boolean = false,
    /** 수락(확정) 뒤 도착까지 (초) */
    val driveSec: Int = 12,
    /** 도착 뒤 출발까지 (초). 출발하면 자동 완료 */
    val stopSec: Int = 6,
    val outcome: DemoOutcome = DemoOutcome.DONE,
) {
    /** 경로에 넣을 곳 */
    val target: DemoPlace? get() = place ?: candidates.firstOrNull()
}

@Serializable
data class DemoPlace(
    val name: String,
    val lat: Double,
    val lon: Double,
    val address: String = "",
    /** 경로에 들르면 늘어나는 시간 (분) */
    val detourMin: Int? = null,
) {
    val location: GeoPoint get() = GeoPoint(lat, lon)

    fun toPlace(category: PlaceCategory = PlaceCategory.FAMILY_HOME, id: String = "demo_$name"): Place =
        Place(id = id, name = name, category = category, location = location, address = address.ifBlank { name })
}

/**
 * 기본 시연 시나리오 (서비스 기획안 4장 + 디자인 시안).
 * 좌표는 판교·분당 퇴근길 예시이며, 장소 이름은 Pleos 내비 경유지 이름으로 그대로 쓰인다.
 */
object DemoPresets {
    private val home = DemoPlace("집", 37.3595, 127.1086, "경기도 성남시 분당구 정자동")
    private val daycare = DemoPlace("해님어린이집", 37.3851, 127.1123, "경기도 성남시 분당구 서현동")
    private val hanbit = DemoPlace("한빛마트", 37.3762, 127.1118, "경기도 성남시 분당구", detourMin = 3)
    private val daon = DemoPlace("다온약국", 37.3790, 127.1152, "경기도 성남시 분당구", detourMin = 6)
    private val saebom = DemoPlace("새봄마트 율현점", 37.3701, 127.1181, "경기도 성남시 분당구", detourMin = 9)
    private val pharmacy = DemoPlace("판교풀잎약국", 37.3948, 127.1101, "경기도 성남시 분당구 판교역로", detourMin = 2)
    private val kimbap = DemoPlace("행복김밥", 37.3921, 127.1112, "경기도 성남시 분당구 판교로")
    private val taekwondo = DemoPlace("한빛태권도", 37.3880, 127.1061, "경기도 성남시 분당구 백현동")
    private val parents = DemoPlace("부모님 댁", 37.3731, 127.1002, "경기도 성남시 분당구 정자일로")

    private fun pickupSeoa(appt: Int, eta: Int) = DemoErrand(
        from = "", type = ErrandType.PICKUP_PERSON, text = "서아 하원 픽업 부탁해", title = "서아 하원 픽업", person = "서아",
        place = daycare, appointmentInMin = appt, etaMin = eta, driveSec = 14, stopSec = 8,
    )

    /** 시나리오 A: 즉시 수락 (하원 픽업 + 장소 미정 장보기) */
    val scenarioA = DemoScenario(
        id = "preset-a",
        title = "시나리오 A · 즉시 수락",
        description = "지은이 퇴근길 현우에게 하원 픽업과 기저귀 구매를 보낸다. 고속 주행 중엔 미뤘다가 신호 대기에서 안내 → 수락 한 번으로 경유지 2곳 → 도착·출발 자동 공유",
        driverName = "현우",
        home = home,
        steps = listOf(
            DemoStep(
                title = "하원 픽업 + 기저귀 구매",
                delaySec = 2,
                holdSec = 6, holdKmh = 72, safeKmh = 0,
                errands = listOf(
                    pickupSeoa(appt = 35, eta = 25).copy(from = "지은"),
                    DemoErrand(
                        from = "지은", type = ErrandType.BUY, text = "기저귀 좀 사 와줘", title = "기저귀 구매",
                        candidates = listOf(hanbit, daon, saebom), driveSec = 12, stopSec = 8,
                    ),
                ),
                driverReply = DriverReply.ACCEPT, replyAfterSec = 3,
            ),
        ),
    )

    /** 시나리오 B: 조건부 수락 → 요청자 확정 */
    val scenarioB = DemoScenario(
        id = "preset-b",
        title = "시나리오 B · 조건부 수락 → 확정",
        description = "현우가 이동 중인 지은에게 하원 픽업을 부탁한다. 지금 가면 20분 늦어 '늦어도 되는지 물어봐 줘' → 현우가 [좋아요] → 어린이집 안내",
        driverName = "지은",
        home = home,
        steps = listOf(
            DemoStep(
                title = "하원 픽업 (20분 늦음)",
                delaySec = 2,
                holdSec = 4, holdKmh = 72, safeKmh = 18,
                errands = listOf(pickupSeoa(appt = 25, eta = 45).copy(from = "현우")),
                driverReply = DriverReply.CONDITIONAL, replyAfterSec = 4,
                requesterReply = RequesterReply.CONFIRM, requesterReplyAfterSec = 7,
            ),
        ),
    )

    /** 시나리오 B′: 조건부 수락 → 요청자가 다른 방법을 찾는다 */
    val scenarioBDeclined = scenarioB.copy(
        id = "preset-b2",
        title = "시나리오 B′ · 조건부 수락 → 다른 방법",
        description = "같은 상황에서 현우가 [다른 방법 찾을게요]를 고른다. 부탁은 자동 취소되고 지은의 경로는 그대로",
        steps = scenarioB.steps.map { it.copy(requesterReply = RequesterReply.DECLINE) },
    )

    /** 네 가지 부탁 유형을 차례로 */
    val tour = DemoScenario(
        id = "preset-tour",
        title = "네 가지 부탁 한 바퀴",
        description = "사 와줘 → 받아 와줘 → 데려와줘 → 들러줘를 차례로 수락하고, 마지막에 거절도 보여 준다",
        driverName = "지훈",
        home = home,
        steps = listOf(
            DemoStep(
                title = "사 와줘 · 해열제",
                errands = listOf(DemoErrand(from = "수진", type = ErrandType.BUY, text = "아이가 열이 나서 해열제 좀 사 와줘. 시럽형으로!", candidates = listOf(pharmacy), urgent = true)),
            ),
            DemoStep(
                title = "받아 와줘 · 김밥 포장",
                errands = listOf(DemoErrand(from = "수진", type = ErrandType.PICKUP_ITEM, text = "저녁 포장 주문해뒀어, 받아 와줘", title = "행복김밥 포장", place = kimbap.copy(detourMin = 3))),
            ),
            DemoStep(
                title = "데려와줘 · 태권도",
                errands = listOf(
                    DemoErrand(
                        from = "수진", type = ErrandType.PICKUP_PERSON, text = "아이 태권도 끝나면 데려와줘", title = "태권도 끝난 아이 픽업", person = "아이",
                        place = taekwondo, appointmentInMin = 20, etaMin = 15,
                    ),
                ),
            ),
            DemoStep(
                title = "들러줘 · 반찬",
                errands = listOf(DemoErrand(from = "어머님", type = ErrandType.DROP_BY, text = "반찬 챙겨뒀으니 들러서 가져가", place = parents.copy(detourMin = 6))),
            ),
            DemoStep(
                title = "거절 · 세탁소",
                errands = listOf(DemoErrand(from = "수진", type = ErrandType.PICKUP_ITEM, text = "세탁소에 맡긴 옷 찾아와줘", title = "세탁물 찾기", place = DemoPlace("크린토피아 율현점", 37.3722, 127.1150))),
                driverReply = DriverReply.DECLINE,
            ),
        ),
        arriveHomeAfterSec = 10,
    )

    val all: List<DemoScenario> = listOf(scenarioA, scenarioB, scenarioBDeclined, tour)

    /** 새 시나리오의 빈 틀 */
    fun blank(id: String) = DemoScenario(
        id = id,
        title = "새 시나리오",
        driverName = "현우",
        home = home,
        steps = listOf(
            DemoStep(
                title = "1단계",
                errands = listOf(DemoErrand(from = "지은", type = ErrandType.BUY, text = "우유 2팩 사 와줘", candidates = listOf(hanbit))),
            ),
        ),
    )

    /** 단계에 새 부탁을 넣을 때의 기본값 */
    fun newErrand(from: String) = DemoErrand(from = from, type = ErrandType.BUY, text = "우유 2팩 사 와줘", candidates = listOf(hanbit))
}
