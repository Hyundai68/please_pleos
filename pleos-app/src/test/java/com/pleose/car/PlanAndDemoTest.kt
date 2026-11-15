package com.pleose.car

import com.pleose.car.agent.CarAgent
import com.pleose.car.agent.Prompt
import com.pleose.car.agent.QuestStage
import com.pleose.car.demo.DemoDirector
import com.pleose.car.demo.DemoPlanner
import com.pleose.car.demo.LocalDemoFamily
import com.pleose.car.drive.DriveMonitor
import com.pleose.car.platform.NaviDestination
import com.pleose.car.platform.NaviEvent
import com.pleose.car.platform.NaviLocation
import com.pleose.car.platform.NaviPort
import com.pleose.car.platform.NaviWaypoint
import com.pleose.shared.api.CreateFamilyRequest
import com.pleose.shared.api.JoinRequest
import com.pleose.shared.api.LocalErrandRepository
import com.pleose.shared.demo.DemoPresets
import com.pleose.shared.demo.DemoScenario
import com.pleose.shared.hub.FamilyHub
import com.pleose.shared.model.DriveState
import com.pleose.shared.model.Errand
import com.pleose.shared.model.ErrandStatus
import com.pleose.shared.model.PlaceCategory
import com.pleose.shared.model.Place
import com.pleose.shared.text.Kst
import com.pleose.shared.voice.Reply
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** 경로 요청을 바로 받아들이는 가짜 Pleos 내비: 넣은 곳이 그 이름 그대로 경유지가 된다 */
class RoutingNavi : NaviPort {
    override val connected = MutableStateFlow(true)
    override val destination = MutableStateFlow<NaviDestination?>(null)
    override val waypoints = MutableStateFlow<List<NaviWaypoint>>(emptyList())
    override val location = MutableStateFlow<NaviLocation?>(null)
    override val home = MutableStateFlow<Place?>(null)
    override val events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 16)
    val requests = mutableListOf<String>()

    override fun setRoute(destination: Place, waypoints: List<Place>) {
        requests += "경로 ${destination.name} ${waypoints.map { it.name }}"
        this.destination.value = NaviDestination(destination.name, destination.location, 9_000, 1_800)
        this.waypoints.value = waypoints.mapIndexed { i, p -> NaviWaypoint(p.name, p.address, p.location, 2_000 * (i + 1), 400 * (i + 1), "") }
    }

    override fun routeTo(destination: Place): Boolean {
        setRoute(destination, emptyList())
        return true
    }

    override fun addWaypoint(place: Place, second: Boolean): Boolean {
        requests += "경유지 ${place.name}"
        waypoints.value = waypoints.value + NaviWaypoint(place.name, place.address, place.location, 3_000, 600, "")
        return true
    }

    override fun searchPlace(keyword: String): Boolean {
        requests += "검색 $keyword"
        return true
    }

    override fun removeWaypoint(index: Int): Boolean {
        requests += "빼기 $index"
        waypoints.value = waypoints.value.filterIndexed { i, _ -> i != index }
        events.tryEmit(NaviEvent.WaypointChanged)
        return true
    }

    override fun cancelRoute(): Boolean {
        destination.value = null
        waypoints.value = emptyList()
        return true
    }

    override fun showNavigation() = Unit
}

/**
 * 서비스 기획안(4장 시나리오 A·B)과 시연 진행자.
 * 수락·조건부 수락·거절, 경유지 한 번에 넣기, 도착 공유, 출발 자동 완료, 요청자 확정을 시험한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanAndDemoTest {

    private class World(scope: TestScope, driverName: String, requester: String, h: Int, m: Int) {
        val base = ZonedDateTime.of(2026, 11, 3, h, m, 0, 0, Kst.zone).toInstant().toEpochMilli()
        val clock = { base + scope.testScheduler.currentTime }
        val hub = FamilyHub(clock = clock)
        val driver = hub.createFamily(CreateFamilyRequest(driverName))
        val req = hub.join(JoinRequest(driver.family.inviteCode, requester))
        val repo = LocalErrandRepository(hub, driver.family.id, driver.member.id)
        val drive = DriveMonitor(clock)
        val voice = FakeVoice()
        val navi = RoutingNavi()
        val agent = CarAgent(scope.backgroundScope, repo, drive, voice, navi, clock)
        val planner = DemoPlanner(clock)
        val family = LocalDemoFamily(hub, driver.family.id, driver.family.inviteCode)

        init {
            agent.planner = planner
        }

        fun asRequester(id: String): Errand = hub.snapshot(driver.family.id, req.member.id).errand(id)!!
        fun errands(): List<Errand> = hub.snapshot(driver.family.id, req.member.id).errands

        fun director(s: DemoScenario) = DemoDirector(
            scopeRef, s, agent, drive, voice, navi, family, planner, showPanel = {}, clock = clock,
        )

        lateinit var scopeRef: kotlinx.coroutines.CoroutineScope
    }

    private fun TestScope.until(maxMs: Long = 10 * 60_000L, what: String, cond: () -> Boolean) {
        var t = 0L
        while (!cond()) {
            advanceTimeBy(200)
            runCurrent()
            t += 200
            check(t < maxMs) { "시간 안에 '$what' 상태가 되지 않았어요" }
        }
    }

    private fun TestScope.world(driver: String, requester: String, h: Int, m: Int) =
        World(this, driver, requester, h, m).also { it.scopeRef = backgroundScope }

    @Test
    fun `시나리오 A - 고속 주행 중엔 미루고, 한 번에 안내, 수락 한 번으로 경유지 2곳, 도착 공유, 출발하면 자동 완료`() = runTest {
        val w = world("현우", "지은", 17, 40)
        w.agent.start()
        val d = w.director(DemoPresets.scenarioA.copy(startDelaySec = 1))
        d.start()

        until(what = "고속 주행 보류") { w.agent.ui.value.holding }
        assertTrue("고속 주행 중엔 읽지 않는다", w.voice.spoken.isEmpty())
        assertEquals(2, w.agent.ui.value.newCount)

        until(what = "묶음 안내") { w.agent.ui.value.prompt is Prompt.Batch }
        assertEquals(
            "지은님이 부탁 두 개를 보냈어요. 서아 하원 픽업과 기저귀 구매예요. 가는 길에 있는 마트에 들르면 3분 더 걸려요. 안내할까요?",
            w.voice.spoken.last(),
        )
        val p = w.agent.ui.value.prompt as Prompt.Batch
        assertEquals(listOf("수락", "조건부 수락", "거절"), p.replies.map(p::label))
        assertFalse("늦지 않으면 조건부 수락은 쓸 수 없다", p.enabled(Reply.CONDITIONAL))

        until(what = "경유지 2곳") { w.agent.ui.value.stops.count { it.errandId != null } == 2 }
        assertTrue(w.voice.spoken.contains("수락했어요. 경유지 2곳을 추가하고 방문 순서를 정리했어요."))
        assertEquals("한 번의 경로 요청으로 방문 순서대로", "경로 집 [해님어린이집, 한빛마트]", w.navi.requests.last { it.startsWith("경로") })
        val (pickup, diaper) = w.errands().sortedBy { it.title != "서아 하원 픽업" }
        assertEquals(ErrandStatus.IN_PROGRESS, pickup.status)
        assertEquals(ErrandStatus.ACCEPTED, diaper.status)
        assertEquals("한빛마트", diaper.targetPlaceName)
        assertEquals(3, diaper.detourMin)

        until(what = "어린이집 도착") { w.asRequester(pickup.id).arrivedAt != null }
        assertEquals("도착", w.asRequester(pickup.id).let { it.progressLabels[it.progressIndex] })
        assertTrue(w.voice.spoken.contains("해님어린이집에 도착했어요. 지은님께 알렸어요. 서아가 타면 출발해 주세요."))

        until(what = "픽업 완료") { w.asRequester(pickup.id).status == ErrandStatus.COMPLETED }
        assertTrue(w.voice.spoken.contains("출발했어요. 픽업 완료를 알렸어요."))
        assertEquals("지나간 경유지는 내비에서 뺀다", listOf("한빛마트"), w.navi.waypoints.value.map { it.name })

        until(what = "구매 완료") { w.asRequester(diaper.id).status == ErrandStatus.COMPLETED }
        assertEquals("한빛마트에서 출발했어요. 구매 완료를 알렸어요. 부탁 2건을 모두 마쳤어요.", w.voice.spoken.last())
        until(what = "시연 끝") { !d.progress.value.running }
        assertEquals(2, w.agent.ui.value.doneItems.size)
        assertTrue("운전자는 한 번도 화면을 누르지 않았다", w.navi.requests.none { it.startsWith("검색") })
    }

    @Test
    fun `시나리오 B - 20분 늦으면 조건부 수락, 요청자가 좋아요를 누르면 확정 안내와 경유지`() = runTest {
        val w = world("지은", "현우", 17, 30)
        w.agent.start()
        // 약속 18:30, 지금 경로로 18:50 도착 (기획안 4.3)
        val s = DemoPresets.scenarioB.let { b ->
            b.copy(startDelaySec = 0, steps = b.steps.map { st -> st.copy(delaySec = 0, errands = st.errands.map { it.copy(appointmentInMin = 60, etaMin = 80) }) })
        }
        val d = w.director(s)
        d.start()

        until(what = "지연 안내") { w.agent.ui.value.prompt is Prompt.Offer }
        val offer = w.agent.ui.value.prompt as Prompt.Offer
        assertEquals(20, offer.lateMin)
        assertTrue(offer.enabled(Reply.CONDITIONAL))
        assertEquals("“20분 늦어도 되는지 물어봐 줘”", offer.hint(Reply.CONDITIONAL))
        assertEquals(
            "현우님이 서아 하원 픽업을 부탁했어요. 지금 경로로는 18시 50분에 도착해서, 20분쯤 늦을 것 같아요. 어떻게 할까요?",
            w.voice.spoken.last(),
        )

        until(what = "제안 전송") { w.agent.ui.value.proposed.isNotEmpty() }
        val e = w.errands().single()
        assertEquals(ErrandStatus.PROPOSED, w.asRequester(e.id).status)
        assertEquals("18:50", Kst.hhmm(w.asRequester(e.id).proposal!!.etaAt))
        assertEquals(20, w.asRequester(e.id).proposal!!.lateMin)
        assertEquals("답이 올 때까지 경로는 그대로", 0, w.navi.waypoints.value.size)

        until(what = "확정") { w.asRequester(e.id).status.isAccepted }
        until(what = "확정 안내") { w.voice.spoken.contains("18시 50분 픽업으로 확정됐어요. 어린이집으로 안내할게요.") }
        until(what = "경유지 연결") { w.agent.ui.value.quest(e.id)?.stage == QuestStage.ON_ROUTE || w.agent.ui.value.quest(e.id)?.stage == QuestStage.ARRIVED }
        assertEquals("경유지 해님어린이집", w.navi.requests.last { it.startsWith("경유지") })
        until(what = "픽업 완료") { w.asRequester(e.id).status == ErrandStatus.COMPLETED }
        until(what = "시연 끝") { !d.progress.value.running }
    }

    @Test
    fun `시나리오 B 다른 방법 - 요청자가 다른 방법을 고르면 자동 취소, 경로는 그대로`() = runTest {
        val w = world("지은", "현우", 17, 30)
        w.agent.start()
        val d = w.director(DemoPresets.scenarioBDeclined.copy(startDelaySec = 0))
        d.start()
        until(what = "제안 전송") { w.agent.ui.value.proposed.isNotEmpty() }
        val e = w.errands().single()
        until(what = "취소") { w.asRequester(e.id).status == ErrandStatus.CANCELLED }
        runCurrent()
        assertEquals("다른 방법을 찾기로 했어요", w.asRequester(e.id).note)
        assertTrue(w.voice.spoken.contains("현우님이 다른 방법을 찾기로 했어요. 경로는 그대로예요."))
        until(what = "시연 끝") { !d.progress.value.running }
        assertTrue("경유지를 넣지 않았다", w.navi.requests.none { it.startsWith("경유지") } && w.navi.waypoints.value.isEmpty())
    }

    @Test
    fun `네 가지 부탁 한 바퀴 - 차례로 수락하고 마지막은 거절, 집 도착`() = runTest {
        val w = world("지훈", "수진", 18, 0)
        w.hub.join(JoinRequest(w.driver.family.inviteCode, "어머님"))
        w.agent.start()
        val d = w.director(DemoPresets.tour.copy(startDelaySec = 0))
        d.start()
        until(maxMs = 20 * 60_000L, what = "시연 끝") { !d.progress.value.running }
        val all = w.hub.snapshot(w.driver.family.id, w.driver.member.id).errands
        assertEquals(5, all.size)
        assertEquals(4, all.count { it.status == ErrandStatus.COMPLETED })
        assertEquals(1, all.count { it.status == ErrandStatus.DECLINED })
        assertEquals("목적지에 도착했어요.", w.voice.spoken.last())
        assertTrue(DemoPresets.all.all { it.problems().isEmpty() })
    }

    @Test
    fun `직접 답하기 - 정한 답 대신 운전자의 음성을 기다린다`() = runTest {
        val w = world("현우", "지은", 17, 40)
        w.agent.start()
        val s = DemoPresets.scenarioA.let { a ->
            a.copy(startDelaySec = 0, steps = a.steps.map { it.copy(holdSec = 0, driverReply = com.pleose.shared.demo.DriverReply.MANUAL) })
        }
        val d = w.director(s)
        d.start()
        until(what = "안내") { w.voice.listening }
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue("시연은 답하지 않는다", w.agent.ui.value.prompt is Prompt.Batch)
        w.voice.answer("응 안내해 줘")
        until(what = "끝") { !d.progress.value.running }
        assertEquals(2, w.errands().count { it.status == ErrandStatus.COMPLETED })
    }

    @Test
    fun `시연을 멈추면 그 자리에서 끝난다`() = runTest {
        val w = world("현우", "지은", 17, 40)
        w.agent.start()
        val d = w.director(DemoPresets.scenarioA)
        d.start()
        until(what = "보류") { w.agent.ui.value.holding }
        d.stop()
        runCurrent()
        assertFalse(d.progress.value.running)
        assertEquals("시연을 멈췄어요", d.progress.value.phase)
        w.drive.simulate(null, null)
        assertEquals(DriveState.PARKED, w.drive.state.value.state)
        assertEquals(PlaceCategory.HOME, DemoPresets.scenarioA.home!!.toPlace(PlaceCategory.HOME).category)
    }
}
