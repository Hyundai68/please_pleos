package com.pleose.shared.voice

import com.pleose.shared.interpret.ErrandInterpreter
import com.pleose.shared.model.Errand
import com.pleose.shared.model.ErrandType
import com.pleose.shared.route.ErrandPlan
import com.pleose.shared.text.Korean
import com.pleose.shared.text.Kst
import com.pleose.shared.text.formatMinutes

/**
 * 운전자 음성 응답 (F-05). [label]은 버튼 글씨로, 모든 부탁이 진행·취소·완료·경로 재검색을 똑같이 쓴다.
 * 새 부탁에는 수락·조건부 수락·거절 세 가지로 답한다 (기획안 3.2).
 * 음성으로는 버튼 글씨뿐 아니라 "응", "못 샀어" 같은 평소 말도 알아듣는다 ([ReplyParser]).
 */
enum class Reply(val label: String) {
    YES("진행"),
    /** 조건부 수락: "20분 늦어도 되는지 물어봐 줘" */
    CONDITIONAL("조건부 수락"),
    OTHER("다른 곳"),
    LATER("나중에"),
    NO("취소"),
    NOT_DONE("취소"),
    RESEARCH("경로 재검색"),
}

object ReplyParser {
    private val conditional = Regex("물어|여쭤|늦어도|조건|되는지|괜찮은지|확인해 ?봐")
    private val other = Regex("다른|딴 ?데|딴 ?곳|말고|하나씩|따로|두 ?번째|바꿔")
    private val research = Regex("재검색|재탐색|다시 ?찾|다시 ?검색|경로 ?다시")
    private val later = Regex("나중|이따|있다가|조금 ?뒤|잠깐만|좀 ?있다")
    private val notDone = Regex("못 ?샀|못 ?받|못 ?챙|못 ?했|못 ?태|안 ?샀|안 ?했|없대|없더라|없어|품절|다 ?팔")
    private val no = Regex("못 ?가|못 ?들|안 ?돼|안 ?되|오늘은|어려워|어렵|싫어|거절|취소|포기|그만|아니|(?<!다 ?)됐어|괜찮아")
    private val yes = Regex("^(응|어|네|예|웅|엉|넵|넹|오케이|ok|okay)|그래|좋아|안내|가자|부탁해|알겠|챙겼|샀어|받았|했어|태웠|들렀|다 ?됐|맞아|당연|고고|콜|찾아|넣어|진행|완료")

    /**
     * 음성 인식 문장을 응답으로 바꾼다. [expected]에 없는 응답은 그 질문에서 같은 뜻인 응답으로 옮기고, 없으면 null.
     * 완료 확인에서 "아니/취소"는 [Reply.NOT_DONE], "다른 곳"은 [Reply.RESEARCH]로 본다.
     */
    fun parse(text: String, expected: Set<Reply> = Reply.entries.toSet()): Reply? {
        val t = text.trim().lowercase().replace(Regex("[.,!?~]"), "")
        if (t.isEmpty()) return null
        val raw = when {
            conditional.containsMatchIn(t) -> Reply.CONDITIONAL
            other.containsMatchIn(t) -> Reply.OTHER
            research.containsMatchIn(t) -> Reply.RESEARCH
            later.containsMatchIn(t) -> Reply.LATER
            notDone.containsMatchIn(t) -> Reply.NOT_DONE
            no.containsMatchIn(t) -> Reply.NO
            yes.containsMatchIn(t) -> Reply.YES
            else -> null
        } ?: return null
        if (raw in expected) return raw
        val alternatives = when (raw) {
            Reply.NO -> listOf(Reply.NOT_DONE, Reply.LATER)
            Reply.NOT_DONE -> listOf(Reply.NO)
            Reply.OTHER -> listOf(Reply.RESEARCH)
            Reply.RESEARCH -> listOf(Reply.YES)
            else -> emptyList()
        }
        return alternatives.firstOrNull { it in expected }
    }
}

/** 음성 안내 한 번: 화면에는 [headline]을 크게, [detail]은 보조로 보여 주고, 음성은 [spoken] 전체를 읽는다. */
data class Script(val headline: String, val detail: String = "", val question: String = "") {
    val spoken: String get() = listOf(headline, detail, question).filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * 유형별 대표 스크립트 (PRD 9). 원칙: 한 번의 안내는 15초 이내, 질문은 하나, 요청자 이름을 먼저 말한다.
 *
 * 들를 곳은 pleose가 따로 정하지 않고 Pleos 지도(내비)에서 찾는다: 사 와줘는 장소 종류("약국"),
 * 나머지는 요청자가 적은 장소 이름으로 검색하고, 운전자가 고른 곳이 Pleos 내비 경유지가 된다.
 */
object VoiceScripts {

    /** 음성용 장소: 사 와줘는 "약국", 나머지는 짧은 이름("태권도", "김밥집") */
    fun placeLabel(e: Errand): String =
        if (e.type == ErrandType.BUY) e.category?.label ?: "가게" else ErrandInterpreter.shortLabelOf(e.placeName ?: e.spokenTitle)

    /**
     * 새 부탁 안내. [followUp]이면 같은 요청자의 추가 부탁 ("우유 2팩도 부탁했어요.").
     * 들를 곳을 미리 알면([plan]) 도착 예정·추가 시간을 함께 말한다 (기획안 4.2·4.3):
     * "현우님이 서아 하원 픽업을 부탁했어요. 지금 경로로는 18시 50분에 도착해서, 20분쯤 늦을 것 같아요."
     */
    fun offer(errand: Errand, followUp: Boolean = false, plan: ErrandPlan? = null): Script {
        if (plan != null && (plan.etaAt != null || plan.detourMin != null)) return planned(errand, plan)
        val script = offerByType(errand, followUp)
        return if (errand.hasAddress) script.copy(question = "요청한 주소로 안내할까요?") else script
    }

    private fun planned(e: Errand, plan: ErrandPlan): Script {
        val late = plan.lateMin(e)
        return Script(
            "${Korean.i(Korean.nim(e.fromName))} ${Korean.eul(e.spokenTitle)} 부탁했어요.",
            planDetail(e, plan),
            if (late != null) "어떻게 할까요?" else "안내할까요?",
        )
    }

    /** "18시 30분까지예요. 지금 가면 18시 20분에 도착해요." / "가는 길에 있는 마트에 들르면 3분 더 걸려요." */
    private fun planDetail(e: Errand, plan: ErrandPlan, withPlace: Boolean = false): String {
        val eta = plan.etaAt
        val appt = e.appointmentAt
        val late = plan.lateMin(e)
        val where = if (withPlace) "${placeLabel(e)}에는 " else ""
        return when {
            late != null && eta != null -> "${where}지금 경로로는 ${Kst.spokenHm(eta)}에 도착해서, ${late}분쯤 늦을 것 같아요."
            appt != null && eta != null -> "${if (withPlace) "${Korean.eun(placeLabel(e))} " else ""}${Kst.spokenHm(appt)}까지예요. 지금 가면 ${Kst.spokenHm(eta)}에 도착해요."
            plan.detourMin != null -> "가는 길에 있는 ${detourPlace(e)}에 들르면 ${plan.detourMin}분 더 걸려요."
            eta != null -> "${where}지금 가면 ${Kst.spokenHm(eta)}에 도착해요."
            else -> ""
        }
    }

    private fun detourPlace(e: Errand): String = if (e.type == ErrandType.BUY) e.category?.label ?: "가게" else placeLabel(e)

    private fun offerByType(errand: Errand, followUp: Boolean): Script {
        val from = Korean.nim(errand.fromName)
        val place = errand.placeName ?: ""
        return when (errand.type) {
            ErrandType.BUY -> Script(
                if (followUp) "${errand.spokenItem}도 부탁했어요." else "${Korean.i(from)} ${Korean.eul(errand.spokenTitle)} 부탁했어요.",
                "",
                "경로 근처 ${Korean.eul(errand.category?.label ?: "가게")} Pleos 지도에서 찾아 드릴까요?",
            )

            ErrandType.PICKUP_ITEM -> Script(
                "${Korean.i(from)} $place ${Korean.eul(errand.spokenItem)} 받아 와 달래요.",
                "",
                "Pleos 지도에서 찾아 경유지로 넣을까요?",
            )

            ErrandType.PICKUP_PERSON -> {
                val time = errand.appointmentAt?.let { "${Kst.spoken(it)}에 " } ?: ""
                Script(
                    "${Korean.i(from)} $time${placeLabel(errand)}에서 ${Korean.eul(errand.person ?: "가족")} 데려와 달래요.",
                    "",
                    "Pleos 지도에서 찾아 경유지로 넣을까요?",
                )
            }

            ErrandType.DROP_BY -> Script(
                if (errand.item == "반찬") "${Korean.i(from)} 반찬을 챙겨두셨대요." else "${Korean.i(from)} ${place}에 들러 달래요.",
                "",
                "Pleos 지도에서 찾아 경유지로 넣을까요?",
            )
        }
    }

    /**
     * 여러 건 묶음 안내: "부탁이 2개 있어요. 태권도 먼저, 김밥집은 그다음이에요."
     * 순서: 약속 시각이 있는 부탁 먼저(데려와줘), 나머지는 받은 순서. 실제 경로·도착 시각은 Pleos 내비가 계산한다.
     */
    fun batch(ordered: List<Errand>, plans: Map<String, ErrandPlan> = emptyMap()): Script {
        if (ordered.isNotEmpty() && ordered.all { it.id in plans }) return plannedBatch(ordered, plans)
        val names = ordered.map(::placeLabel)
        val order = when (names.size) {
            0, 1 -> ""
            2 -> "${names[0]} 먼저, ${Korean.eun(names[1])} 그다음이에요."
            else -> "${names[0]} 먼저, 그다음 ${names.drop(1).dropLast(1).joinToString(", ")}, 마지막으로 ${names.last()}이에요."
        }
        return Script("부탁이 ${ordered.size}개 있어요. $order".trim(), "", "차례로 Pleos 지도에서 찾아 경유지로 넣을까요?")
    }

    /** "지은님이 부탁 두 개를 보냈어요. 서아 하원 픽업과 기저귀 구매예요. 가는 길에 있는 마트에 들르면 3분 더 걸려요. 안내할까요?" */
    private fun plannedBatch(ordered: List<Errand>, plans: Map<String, ErrandPlan>): Script {
        val from = ordered.map { it.fromName }.distinct()
        val head = if (from.size == 1) {
            "${Korean.i(Korean.nim(from[0]))} 부탁 ${Korean.count(ordered.size)} 개를 보냈어요."
        } else {
            "부탁이 ${Korean.count(ordered.size)} 개 있어요."
        }
        val details = ordered.mapNotNull { e ->
            val p = plans.getValue(e.id)
            when {
                p.lateMin(e) != null -> planDetail(e, p, withPlace = true)
                p.detourMin != null -> planDetail(e, p)
                else -> null
            }
        }
        val anyLate = ordered.any { plans.getValue(it.id).lateMin(it) != null }
        return Script(
            head,
            (listOf(Korean.ieyo(Korean.and(ordered.map { it.spokenTitle })) + ".") + details).joinToString(" "),
            if (anyLate) "어떻게 할까요?" else "안내할까요?",
        )
    }

    /** 수락 뒤 경유지를 한 번에 넣을 때 */
    fun acceptedRoute(names: List<String>): Script = when (names.size) {
        1 -> Script("수락했어요. ${Korean.eul(names[0])} 경유지로 넣고 안내할게요.")
        else -> Script("수락했어요. 경유지 ${names.size}곳을 추가하고 방문 순서를 정리했어요.")
    }

    /** 조건부 수락을 보낸 뒤 */
    fun proposalSent(e: Errand, etaAt: Long) = Script(
        "${Korean.nim(e.fromName)}께 ${Kst.spokenHm(etaAt)} 도착 가능하다고 물어볼게요.",
        "답이 올 때까지 기존 경로로 계속 안내해요.",
    )

    /** 요청자가 [좋아요]로 확정 */
    fun proposalConfirmed(e: Errand, etaAt: Long?) = Script(
        "${etaAt?.let { "${Kst.spokenHm(it)} " } ?: ""}${if (e.type == ErrandType.PICKUP_PERSON) "픽업" else "도착"}으로 확정됐어요.",
        "${Korean.euro(placeLabel(e))} 안내할게요.",
    )

    /** 요청자가 [다른 방법 찾을게요] → 자동 취소 */
    fun proposalDeclined(e: Errand) = Script("${Korean.i(Korean.nim(e.fromName))} 다른 방법을 찾기로 했어요.", "경로는 그대로예요.")

    /** 들를 곳 도착을 요청자에게 알린 뒤 */
    fun arrivedShared(placeName: String, e: Errand): Script = if (e.type == ErrandType.PICKUP_PERSON) {
        Script("${placeName}에 도착했어요.", "${Korean.nim(e.fromName)}께 알렸어요. ${Korean.i(e.person ?: "가족")} 타면 출발해 주세요.")
    } else {
        Script("${placeName}에 도착했어요.", "${Korean.nim(e.fromName)}께 알렸어요.")
    }

    /** 들른 곳에서 출발 → 자동 완료 (기획안 4.2) */
    fun departedDone(e: Errand, placeName: String, allDoneCount: Int = 0): Script {
        val done = when (e.type) {
            ErrandType.PICKUP_PERSON -> "출발했어요. 픽업 완료를 알렸어요."
            else -> "${Korean.eseo(placeName)} 출발했어요. ${Korean.eul(e.doneLabel)} 알렸어요."
        }
        return if (allDoneCount >= 2) {
            Script(done, "부탁 ${allDoneCount}건을 모두 마쳤어요.")
        } else {
            Script(done)
        }
    }

    /** "남은 부탁 읽어 줘" (Gleo '남은 부탁 읽기') */
    fun remaining(list: List<Errand>): Script = when (list.size) {
        0 -> Script("지금은 남은 부탁이 없어요.")
        else -> Script(
            "남은 부탁은 ${Korean.count(list.size)} 개예요.",
            list.joinToString(" ") { "${Korean.nim(it.fromName)}의 ${it.spokenTitle}, ${it.status.requesterLabel}." },
        )
    }

    /** Pleos 지도 검색 화면을 띄운 뒤 */
    fun searchOpened(e: Errand): Script = Script(
        if (e.type == ErrandType.BUY) {
            "Pleos 지도에 경로 근처 ${Korean.eul(e.searchKeyword)} 띄웠어요."
        } else {
            "Pleos 지도에 ${e.searchKeyword} 검색 결과를 띄웠어요."
        },
        "고른 곳을 경유지로 넣으면 ${Korean.nim(e.fromName)}께 알려 드릴게요.",
    )

    /** 좌표를 몰라 상세 주소로 Pleos 지도를 검색한 뒤 */
    fun addressSearchOpened(e: Errand) = Script(
        "요청한 주소를 Pleos 지도에 띄웠어요.",
        "고른 곳을 경유지로 넣으면 ${Korean.nim(e.fromName)}께 알려 드릴게요.",
    )

    /** 상세 주소를 Pleos 내비 경로에 바로 넣은 뒤 */
    fun routeRequested(e: Errand) = Script("요청한 주소로 Pleos 내비 경로를 잡을게요.")

    /** 목적지가 없던 경로에서 고른 곳이 목적지가 된 뒤 */
    fun destinationSet(name: String, etaAt: Long?) = Script(
        "${Korean.euro(name)} 안내를 시작했어요.",
        etaAt?.let { "${Kst.spoken(it)}에 도착해요." } ?: "",
    )

    /** 경유지가 들어온 뒤: "판교풀잎약국을 경유지로 넣었어요. 7시 3분에 도착해요." */
    fun waypointAdded(waypointName: String, etaAt: Long?, lateMin: Int? = null): Script = Script(
        "${Korean.eul(waypointName)} 경유지로 넣었어요.",
        listOfNotNull(
            etaAt?.let { "${Kst.spoken(it)}에 도착해요." },
            lateMin?.takeIf { it > 0 }?.let { "약속보다 ${it}분 늦어요." },
        ).joinToString(" "),
    )

    fun nextInBatch(e: Errand) = Script("다음은 ${placeLabel(e)}이에요.", "Pleos 지도에서 찾아 드릴게요.")

    fun routingHome(homeName: String) = Script("${Korean.euro(homeName)} 가는 경로를 Pleos 내비에 먼저 잡을게요.")

    fun routeChanging() = Script("경로를 다시 잡고 있어요.", "잠시 뒤 Pleos 지도를 띄울게요.")

    fun waypointsFull() = Script("Pleos 내비 경유지는 2곳까지예요.", "앞 경유지를 지나면 이어서 찾아 드릴게요.")

    fun later() = Script("알겠어요. 조금 뒤에 다시 알려 드릴게요.")

    fun declined(errand: Errand) = Script("${Korean.nim(errand.fromName)}께 '오늘은 어려워요'라고 전했어요.", "경로는 그대로예요.")

    fun noAnswer() = Script("정차하면 화면으로 다시 보여 드릴게요.")

    /** 경유지 출발 후 완료 확인 (F-09) */
    fun completionQuestion(errand: Errand): Script = Script(
        headline = when (errand.type) {
            ErrandType.BUY, ErrandType.PICKUP_ITEM -> "${errand.spokenItem} 챙겼어요?"
            ErrandType.DROP_BY -> when {
                errand.item == "반찬" -> "반찬 챙겼어요?"
                errand.title.endsWith("들르기") -> "${errand.placeName ?: "부탁한 곳"} 잘 들렀어요?"
                else -> "${errand.spokenTitle}했어요?"
            }
            ErrandType.PICKUP_PERSON -> "${Korean.eul(errand.person ?: "가족")} 태웠어요?"
        },
    )

    fun completed(errand: Errand) = Script("${Korean.nim(errand.fromName)}께 완료를 알렸어요.")

    fun pickupDone(errand: Errand) =
        Script("${errand.person ?: "가족"} 탑승을 확인했어요.", "${Korean.nim(errand.fromName)}께 픽업 완료를 알렸어요.")

    fun notDone(errand: Errand) = Script(
        "${Korean.nim(errand.fromName)}께 ${if (errand.type == ErrandType.BUY) "못 샀다고" else "못 했다고"} 전할게요.",
    )

    /** 부탁을 끝낸 뒤 다음 부탁을 할지 묻는다. 안 하겠다고 하면 Please 버튼에 그대로 남는다 */
    fun nextQuest(e: Errand, remaining: Int): Script = Script(
        if (remaining > 1) "남은 부탁이 ${remaining}개 있어요. 다음은 ${Korean.ieyo(e.spokenTitle)}." else "남은 부탁은 ${Korean.ieyo(e.spokenTitle)}.",
        "",
        "이어서 안내할까요?",
    )

    fun leftOnBoard() = Script("알겠어요. 오는 길에 부탁해 버튼에 남겨 둘게요.")

    fun noOtherQuest() = Script("다른 부탁은 없어요.")

    /** 주행 중 Please 버튼을 눌렀을 때: 화면 대신 음성으로 알려 준다 */
    fun onTheWay(placeName: String, remainSec: Int, others: Int) = Script(
        "${placeName}까지 ${formatMinutes(remainSec)} 남았어요.",
        if (others > 0) "남은 부탁은 ${others}개예요." else "",
    )

    fun noQuest() = Script("지금은 받은 부탁이 없어요.")

    fun stopRemoved(name: String) = Script("$name 경유지가 빠졌어요.", "다시 하려면 오는 길에 부탁해 버튼에서 골라 주세요.")

    fun expired(count: Int) = Script("처리하지 못한 부탁 ${count}개는 오늘은 어렵다고 알렸어요.")

    fun arrived(placeName: String) = Script("${placeName}에 도착했어요.")
}
