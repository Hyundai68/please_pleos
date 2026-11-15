package com.pleose.shared

import com.pleose.shared.api.ApiException
import com.pleose.shared.api.CreateFamilyRequest
import com.pleose.shared.api.JoinRequest
import com.pleose.shared.api.NewErrandRequest
import com.pleose.shared.api.StatusUpdate
import com.pleose.shared.api.toFavoriteRequest
import com.pleose.shared.api.toRequest
import com.pleose.shared.demo.DemoPresets
import com.pleose.shared.demo.DemoScenario
import com.pleose.shared.demo.DriverReply
import com.pleose.shared.hub.FamilyHub
import com.pleose.shared.model.ErrandStateMachine
import com.pleose.shared.model.ErrandStatus
import com.pleose.shared.model.ErrandType
import com.pleose.shared.model.GeoPoint
import com.pleose.shared.model.Place
import com.pleose.shared.model.PlaceCategory
import com.pleose.shared.policy.DrivingContext
import com.pleose.shared.policy.DrivingPolicy
import com.pleose.shared.model.DriveState
import com.pleose.shared.route.ErrandPlan
import com.pleose.shared.route.EtaEstimator
import com.pleose.shared.route.PlanOption
import com.pleose.shared.text.Korean
import com.pleose.shared.text.Kst
import com.pleose.shared.voice.Reply
import com.pleose.shared.voice.ReplyParser
import com.pleose.shared.voice.VoiceScripts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** 서비스 기획안: 조건부 수락, 진행 단계 공유, 반복 부탁 즐겨찾기, 계획된 음성 안내, 시연 시나리오 */
class PlanFeaturesTest {
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 3, h, m, 0, 0, Kst.zone).toInstant().toEpochMilli()

    private var now = at(17, 30)
    private val hub = FamilyHub(clock = { now })
    private val hyunwoo = hub.createFamily(CreateFamilyRequest("현우"))
    private val jieun = hub.join(JoinRequest(hyunwoo.family.inviteCode, "지은"))
    private val familyId = hyunwoo.family.id
    private val daycare = GeoPoint(37.3851, 127.1123)

    private fun pickup() = hub.createErrand(
        NewErrandRequest(
            familyId, hyunwoo.member.id, jieun.member.id, ErrandType.PICKUP_PERSON, "서아 하원 픽업 부탁해", "해님어린이집", at(18, 30),
            address = "성남시 분당구 서현동", addressLocation = daycare, title = "서아 하원 픽업", person = "서아",
        ),
    )

    @Test
    fun `제목과 데려올 사람을 정해 보낼 수 있다 (즐겨찾기·템플릿)`() {
        val e = pickup()
        assertEquals("서아 하원 픽업", e.title)
        assertEquals("서아", e.person)
        assertEquals(listOf("전달됨", "수락", "도착", "픽업 완료"), e.progressLabels)
        assertEquals(0, e.progressIndex)
    }

    @Test
    fun `조건부 수락 - 제안 → 요청자 좋아요 → 수락, 다른 방법 → 자동 취소`() {
        val e = pickup()
        val proposed = hub.updateStatus(e.id, StatusUpdate(jieun.member.id, ErrandStatus.PROPOSED, proposalEtaAt = at(18, 50), proposalLateMin = 20))
        assertEquals(ErrandStatus.PROPOSED, proposed.status)
        assertEquals("18:50 도착 가능", proposed.note)
        assertEquals("조건부 수락", proposed.status.requesterLabel)
        val seen = hub.snapshot(familyId, hyunwoo.member.id).errand(e.id)!!
        assertEquals(20, seen.proposal?.lateMin)

        // 운전자는 확정할 수 없고, 요청자만 답한다
        assertThrows(ApiException::class.java) { hub.answerProposal(e.id, jieun.member.id, true) }
        now = at(17, 45)
        val ok = hub.answerProposal(e.id, hyunwoo.member.id, true)
        assertEquals(ErrandStatus.ACCEPTED, ok.status)
        assertEquals("18:50 픽업으로 확정", ok.note)
        assertEquals(at(17, 45), ok.proposal?.confirmedAt)
        assertEquals(at(17, 45), ok.acceptedAt)
        assertThrows(ApiException::class.java) { hub.answerProposal(e.id, hyunwoo.member.id, true) }

        val e2 = pickup()
        hub.updateStatus(e2.id, StatusUpdate(jieun.member.id, ErrandStatus.PROPOSED, proposalEtaAt = at(18, 50), proposalLateMin = 20))
        val no = hub.answerProposal(e2.id, hyunwoo.member.id, false)
        assertEquals(ErrandStatus.CANCELLED, no.status)
        assertEquals("다른 방법을 찾기로 했어요", no.note)

        // 도착 예정 없이는 제안할 수 없다
        val e3 = pickup()
        assertThrows(ApiException::class.java) { hub.updateStatus(e3.id, StatusUpdate(jieun.member.id, ErrandStatus.PROPOSED)) }
        assertTrue(ErrandStateMachine.canTransition(ErrandStatus.ON_HOLD, ErrandStatus.PROPOSED))
        assertFalse(ErrandStateMachine.canTransition(ErrandStatus.PROPOSED, ErrandStatus.COMPLETED))
    }

    @Test
    fun `도착은 단계로만 공유하고, 경로에서 다시 빠지면 지운다`() {
        val e = pickup()
        hub.updateStatus(e.id, StatusUpdate(jieun.member.id, ErrandStatus.ACCEPTED, detourMin = 3))
        assertEquals(1, hub.snapshot(familyId, hyunwoo.member.id).errand(e.id)!!.progressIndex)
        now = at(18, 20)
        hub.updateStatus(e.id, StatusUpdate(jieun.member.id, ErrandStatus.IN_PROGRESS, targetPlaceName = "해님어린이집", arrived = true))
        val arrived = hub.snapshot(familyId, hyunwoo.member.id).errand(e.id)!!
        assertEquals(at(18, 20), arrived.arrivedAt)
        assertEquals("도착", arrived.progressLabels[arrived.progressIndex])
        assertEquals(3, arrived.detourMin)
        hub.updateStatus(e.id, StatusUpdate(jieun.member.id, arrived = false))
        assertNull(hub.snapshot(familyId, hyunwoo.member.id).errand(e.id)!!.arrivedAt)
        hub.updateStatus(e.id, StatusUpdate(jieun.member.id, ErrandStatus.COMPLETED))
        assertEquals("픽업 완료", hub.snapshot(familyId, hyunwoo.member.id).errand(e.id)!!.let { it.progressLabels[it.progressIndex] })
    }

    @Test
    fun `반복 부탁 즐겨찾기 - 저장, 같은 이름은 고치기, 원터치로 보내기, 지우기`() {
        val e = pickup()
        val f = hub.saveFavorite(e.toFavoriteRequest(listOf(2, 4)) { Kst.minuteOfDay(it) })
        assertEquals("화·목", f.daysLabel)
        assertEquals("해님어린이집 · 18:30까지", f.summary)
        assertEquals(18 * 60 + 30, f.appointmentMinute)
        val again = hub.saveFavorite(e.toFavoriteRequest(listOf(2)) { Kst.minuteOfDay(it) })
        assertEquals(f.id, again.id)
        assertEquals(1, hub.snapshot(familyId, jieun.member.id).favorites.size)

        now = at(9, 0)
        val sent = hub.createErrand(again.toRequest(hyunwoo.member.id) { m -> Kst.todayAt(m, now) })
        assertEquals("서아 하원 픽업", sent.title)
        assertEquals(at(18, 30), sent.appointmentAt)
        assertEquals(daycare, sent.addressLocation)

        assertThrows(ApiException::class.java) { hub.deleteFavorite(f.id, "누구") }
        hub.deleteFavorite(f.id, hyunwoo.member.id)
        assertTrue(hub.snapshot(familyId, jieun.member.id).favorites.isEmpty())
    }

    @Test
    fun `음성 - 3단계 응답과 조건부 수락을 알아듣는다`() {
        val offer = setOf(Reply.YES, Reply.CONDITIONAL, Reply.NO, Reply.LATER)
        assertEquals(Reply.CONDITIONAL, ReplyParser.parse("20분 늦어도 되는지 물어봐 줘", offer))
        assertEquals(Reply.CONDITIONAL, ReplyParser.parse("늦어도 괜찮은지 확인해 봐", offer))
        assertEquals(Reply.YES, ReplyParser.parse("응, 안내해 줘", offer))
        assertEquals(Reply.NO, ReplyParser.parse("지금은 어려워", offer))
        assertEquals("늦지 않으면 조건부 수락은 없다", null, ReplyParser.parse("물어봐 줘", setOf(Reply.YES, Reply.NO, Reply.LATER)))
        assertEquals(Reply.OTHER, ReplyParser.parse("두 번째로 바꿔 줘"))
    }

    @Test
    fun `음성 - 도착 예정과 추가 시간을 함께 말한다 - 기획안 시나리오 A·B`() {
        val e = pickup()
        val home = Place("p", "해님어린이집", PlaceCategory.ACADEMY, daycare)
        assertEquals(
            "현우님이 서아 하원 픽업을 부탁했어요. 지금 경로로는 18시 50분에 도착해서, 20분쯤 늦을 것 같아요. 어떻게 할까요?",
            VoiceScripts.offer(e, plan = ErrandPlan(home, etaAt = at(18, 50))).spoken,
        )
        assertEquals(
            "현우님이 서아 하원 픽업을 부탁했어요. 18시 30분까지예요. 지금 가면 18시 20분에 도착해요. 안내할까요?",
            VoiceScripts.offer(e, plan = ErrandPlan(home, etaAt = at(18, 20))).spoken,
        )
        assertEquals(20, ErrandPlan(home, etaAt = at(18, 50)).lateMin(e))
        assertNull(ErrandPlan(home, etaAt = at(18, 20)).lateMin(e))

        val diaper = hub.createErrand(NewErrandRequest(familyId, hyunwoo.member.id, jieun.member.id, ErrandType.BUY, "기저귀 좀 사 와줘", title = "기저귀 구매"))
        val mart = Place("m", "한빛마트", PlaceCategory.MART, GeoPoint(37.37, 127.11))
        val plans = mapOf(
            e.id to ErrandPlan(home, etaAt = at(18, 20)),
            diaper.id to ErrandPlan(mart, detourMin = 3, options = listOf(PlanOption(mart, 3), PlanOption(mart.copy(name = "다온약국"), 6))),
        )
        assertEquals(
            "현우님이 부탁 두 개를 보냈어요. 서아 하원 픽업과 기저귀 구매예요. 가는 길에 있는 마트에 들르면 3분 더 걸려요. 안내할까요?",
            VoiceScripts.batch(listOf(e, diaper), plans).spoken,
        )
        assertEquals("다온약국", plans.getValue(diaper.id).nextOption()?.place?.name)
        assertEquals("18시 50분 픽업으로 확정됐어요. 어린이집으로 안내할게요.", VoiceScripts.proposalConfirmed(e, at(18, 50)).spoken)
        assertEquals("현우님이 다른 방법을 찾기로 했어요. 경로는 그대로예요.", VoiceScripts.proposalDeclined(e).spoken)
        assertEquals("수락했어요. 경유지 2곳을 추가하고 방문 순서를 정리했어요.", VoiceScripts.acceptedRoute(listOf("a", "b")).spoken)
        assertEquals("한빛마트에서 출발했어요. 구매 완료를 알렸어요. 부탁 2건을 모두 마쳤어요.", VoiceScripts.departedDone(diaper, "한빛마트", 2).spoken)
        assertEquals("남은 부탁은 두 개예요.", VoiceScripts.remaining(listOf(e, diaper)).headline)
        assertEquals("A, B와 C", Korean.and(listOf("A", "B", "C")))
    }

    @Test
    fun `고속 주행 중엔 알림을 미루고 저속 구간·신호 대기에서 안내한다`() {
        assertFalse(DrivingPolicy.canAlert(DrivingContext(DriveState.DRIVING, 72.0)))
        assertTrue(DrivingPolicy.canAlert(DrivingContext(DriveState.DRIVING, 18.0)))
        assertTrue(DrivingPolicy.canAlert(DrivingContext(DriveState.STOPPED, 0.0)))
        val sec = EtaEstimator.travelSec(GeoPoint(37.40, 127.10), GeoPoint(37.38, 127.11))
        assertTrue("2.4km 남짓은 몇 분", sec in 240..900)
    }

    @Test
    fun `시연 시나리오 - 기본 시나리오는 바로 쓸 수 있고 JSON으로 주고받는다`() {
        DemoPresets.all.forEach { assertTrue("${it.title}: ${it.problems()}", it.problems().isEmpty()) }
        val json = DemoScenario.toJson(DemoPresets.all)
        assertEquals(DemoPresets.all, DemoScenario.fromJson(json))
        assertEquals(DemoPresets.scenarioB, DemoScenario.fromJson(DemoScenario.toJson(DemoPresets.scenarioB)).single())
        assertEquals(listOf("지은"), DemoPresets.scenarioA.requesters)
        assertTrue(DemoPresets.scenarioA.estimatedSec in 30..300)

        val bad = DemoPresets.scenarioB.copy(steps = DemoPresets.scenarioB.steps.map { s -> s.copy(errands = s.errands.map { it.copy(etaMin = 10) }) })
        assertTrue(bad.problems().any { "조건부 수락" in it })
        val noPlace = DemoPresets.blank("x").let { b -> b.copy(steps = b.steps.map { s -> s.copy(errands = s.errands.map { it.copy(type = ErrandType.DROP_BY, candidates = emptyList()) }) }) }
        assertTrue(noPlace.problems().any { "들를 곳" in it })
        assertEquals(DriverReply.CONDITIONAL, DemoPresets.scenarioB.steps.single().driverReply)
        // 알 수 없는 키가 있어도 읽는다 (PC에서 고친 JSON)
        assertNotNull(DemoScenario.fromJson("""{"id":"x","title":"t","memo":"무시","steps":[]}""").single())
    }
}
