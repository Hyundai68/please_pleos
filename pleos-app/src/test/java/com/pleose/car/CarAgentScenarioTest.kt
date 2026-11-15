package com.pleose.car

import com.pleose.car.agent.Badge
import com.pleose.car.agent.BadgeKind
import com.pleose.car.agent.AgentOptions
import com.pleose.car.agent.CarAgent
import com.pleose.car.agent.Prompt
import com.pleose.car.agent.QuestStage
import com.pleose.car.drive.DriveMonitor
import com.pleose.car.platform.NaviDestination
import com.pleose.car.platform.NaviEvent
import com.pleose.car.platform.NaviLocation
import com.pleose.car.platform.NaviPort
import com.pleose.car.platform.NaviWaypoint
import com.pleose.car.platform.VoicePort
import com.pleose.car.platform.VoiceStatus
import com.pleose.shared.api.CreateFamilyRequest
import com.pleose.shared.api.JoinRequest
import com.pleose.shared.api.LocalErrandRepository
import com.pleose.shared.api.NewErrandRequest
import com.pleose.shared.hub.FamilyHub
import com.pleose.shared.model.DriveState
import com.pleose.shared.model.Errand
import com.pleose.shared.model.ErrandStatus
import com.pleose.shared.model.ErrandType
import com.pleose.shared.model.GeoPoint
import com.pleose.shared.model.Place
import com.pleose.shared.model.PlaceCategory
import com.pleose.shared.text.Kst
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** 말한 문장을 기록하고, 듣기는 테스트가 대답을 넣어 줄 때까지 기다리는 가짜 음성 */
class FakeVoice : VoicePort {
    val spoken = mutableListOf<String>()
    private var pending: ((List<String>?) -> Unit)? = null
    override val status = MutableStateFlow(VoiceStatus.IDLE)
    override val heard = MutableStateFlow<String?>(null)

    override fun speak(text: String, onDone: () -> Unit) {
        spoken += text
        status.value = VoiceStatus.IDLE
        onDone()
    }

    override fun listen(timeoutMs: Long, onResult: (List<String>?) -> Unit) {
        status.value = VoiceStatus.LISTENING
        pending = onResult
    }

    val listening: Boolean get() = pending != null

    fun answer(text: String?) {
        val p = checkNotNull(pending) { "듣고 있지 않아요. 마지막 안내: ${spoken.lastOrNull()}" }
        pending = null
        status.value = VoiceStatus.IDLE
        heard.value = text
        p(text?.let(::listOf))
    }

    override fun stopListening() {
        pending = null
        if (status.value == VoiceStatus.LISTENING) status.value = VoiceStatus.IDLE
    }

    override fun setHeard(text: String) {
        heard.value = text
    }
}

/** 가짜 Pleos 내비: 검색 요청을 기록하고, 테스트가 '운전자가 고른 경유지'를 넣는다 */
class FakeNavi : NaviPort {
    override val connected = MutableStateFlow(true)
    override val destination = MutableStateFlow<NaviDestination?>(null)
    override val waypoints = MutableStateFlow<List<NaviWaypoint>>(emptyList())
    override val location = MutableStateFlow<NaviLocation?>(null)
    override val home = MutableStateFlow<Place?>(null)
    override val events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 8)
    val searches = mutableListOf<String>()
    val routes = mutableListOf<String>()
    val removed = mutableListOf<Int>()
    val added = mutableListOf<Place>()
    val routesWithStop = mutableListOf<Pair<String, List<String>>>()
    var cancelled = 0
    var shown = 0
    /** 실제 내비처럼 경로 취소가 바로 끝나지 않게 한다 (테스트가 destination을 비울 때 끝난다) */
    var slowCancel = false
    /** 실제 내비처럼 경유지 삭제가 바로 끝나지 않게 한다 (테스트가 경로를 다시 잡아 준다) */
    var slowRemove = false

    override fun setRoute(destination: Place, waypoints: List<Place>) {
        routesWithStop += destination.name to waypoints.map { it.name }
    }
    override fun routeTo(destination: Place): Boolean {
        routes += destination.name
        return true
    }
    override fun addWaypoint(place: Place, second: Boolean): Boolean {
        added += place
        return true
    }
    override fun cancelRoute(): Boolean {
        cancelled++
        if (slowCancel) return true
        destination.value = null
        waypoints.value = emptyList()
        return true
    }
    override fun searchPlace(keyword: String): Boolean {
        searches += keyword
        return true
    }
    override fun removeWaypoint(index: Int): Boolean {
        removed += index
        if (slowRemove) return true
        waypoints.value = waypoints.value.filterIndexed { i, _ -> i != index }
        events.tryEmit(NaviEvent.WaypointChanged)
        return true
    }
    override fun showNavigation() {
        shown++
    }

    /** 운전자가 Pleos 지도 검색 결과에서 한 곳을 '경유지로' */
    fun pick(name: String, remainSec: Int, poiId: String = name.hashCode().toString()) {
        waypoints.value = waypoints.value + NaviWaypoint(name, "수정구", null, remainSec * 8, remainSec, poiId)
    }

    fun drop(name: String) {
        waypoints.value = waypoints.value.filter { it.name != name }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CarAgentScenarioTest {

    /**
     * 시험 세계. 지금까지의 시나리오는 출발 후 음성으로 완료를 확인하는 흐름(F-09)을 시험하므로 [confirmOnDepart]가 기본으로 켜져 있다.
     * 기획안의 자동 완료는 [confirmOnDepart] = false로 시험한다.
     */
    private class World(scope: TestScope, startHour: Int, startMinute: Int, confirmOnDepart: Boolean = true) {
        val base = ZonedDateTime.of(2026, 11, 3, startHour, startMinute, 0, 0, Kst.zone).toInstant().toEpochMilli()
        val clock = { base + scope.testScheduler.currentTime }
        val hub = FamilyHub(clock = clock)
        val sujin = hub.createFamily(CreateFamilyRequest("수진"))
        val jihun = hub.join(JoinRequest(sujin.family.inviteCode, "지훈"))
        val repo = LocalErrandRepository(hub, sujin.family.id, jihun.member.id)
        val drive = DriveMonitor(clock)
        val voice = FakeVoice()
        val navi = FakeNavi()
        val agent = CarAgent(scope.backgroundScope, repo, drive, voice, navi, clock, AgentOptions(confirmOnDepart = confirmOnDepart))

        fun send(
            text: String,
            type: ErrandType? = null,
            placeName: String? = null,
            appt: Long? = null,
            urgent: Boolean = false,
            address: String? = null,
            addressLocation: GeoPoint? = null,
        ): Errand = hub.createErrand(NewErrandRequest(sujin.family.id, sujin.member.id, jihun.member.id, type, text, placeName, appt, urgent, address, addressLocation))

        /** 요청자(수진) 화면에서 보는 부탁 */
        fun asSujin(id: String) = hub.snapshot(sujin.family.id, sujin.member.id).errand(id)!!
        fun asJihun(id: String) = hub.snapshot(sujin.family.id, jihun.member.id).errand(id)!!
        fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 3, h, m, 0, 0, Kst.zone).toInstant().toEpochMilli()

        /** 운전 중, Pleos 내비가 집으로 안내 중 */
        fun driving(homeRemainSec: Int = 19 * 60) {
            navi.destination.value = NaviDestination("집", GeoPoint(37.48, 127.05), 10_000, homeRemainSec)
            drive.setOverride(DriveState.DRIVING)
        }
    }

    /** 조건이 맞을 때까지 가상 시간을 흘린다 */
    private fun TestScope.until(maxMs: Long = 5 * 60_000L, what: String, cond: () -> Boolean) {
        var t = 0L
        while (!cond()) {
            advanceTimeBy(150)
            runCurrent()
            t += 150
            check(t < maxMs) { "시간 안에 '$what' 상태가 되지 않았어요" }
        }
    }

    @Test
    fun `시나리오 1 - 사 와줘 - Pleos 지도에서 약국, 경유지 연결, 완료 공유`() = runTest {
        val w = World(this, 18, 40)
        w.agent.start()
        w.driving()
        runCurrent()

        // 집에서 부탁 전송 → 차 안 음성 알림
        val med = w.send("아이가 열이 나서 해열제 좀 사 와줘. 시럽형으로!", urgent = true)
        until(what = "해열제 안내") { w.voice.listening }
        assertEquals("수진님이 아이 해열제를 부탁했어요. 경로 근처 약국을 Pleos 지도에서 찾아 드릴까요?", w.voice.spoken.last())

        // "응" → 수락 공유 + Pleos 지도 약국 검색
        w.voice.answer("응")
        runCurrent()
        assertEquals(listOf("약국"), w.navi.searches)
        assertEquals("Pleos 지도에 경로 근처 약국을 띄웠어요. 고른 곳을 경유지로 넣으면 수진님께 알려 드릴게요.", w.voice.spoken.last())
        assertEquals("수락", w.asSujin(med.id).status.requesterLabel)

        // 운전자가 Pleos 지도에서 판교풀잎약국을 '경유지로' → 부탁에 연결, "들르는 중"
        w.navi.pick("판교풀잎약국", 180)
        runCurrent()
        assertTrue(w.voice.spoken.last(), w.voice.spoken.last().startsWith("판교풀잎약국을 경유지로 넣었어요. 6시 43분에 도착해요."))
        val shared = w.asSujin(med.id)
        assertEquals(ErrandStatus.IN_PROGRESS, shared.status)
        assertEquals("판교풀잎약국", shared.targetPlaceName)
        assertNotNull(shared.homeEtaAt)

        // 두 번째 부탁: 우유도 → 편의점
        val milk = w.send("우유 2팩도 사 와줘")
        until(what = "우유 안내") { w.voice.listening }
        assertEquals("우유 2팩도 부탁했어요. 경로 근처 편의점을 Pleos 지도에서 찾아 드릴까요?", w.voice.spoken.last())
        w.voice.answer("응 찾아줘")
        runCurrent()
        assertEquals(listOf("약국", "편의점"), w.navi.searches)
        w.navi.pick("GS25 판교점", 600)
        runCurrent()
        assertEquals(ErrandStatus.ACCEPTED, w.asSujin(milk.id).status)
        assertEquals("GS25 판교점", w.asSujin(milk.id).targetPlaceName)

        // 약국 도착 → 출발(내비가 지난 경유지를 뺀다) → "해열제 챙겼어요?" → "응"
        w.navi.events.emit(NaviEvent.WaypointArrived("판교풀잎약국"))
        runCurrent()
        assertEquals("판교풀잎약국에 도착했어요. 수진님께 알렸어요.", w.voice.spoken.last())
        assertEquals("요청자에게는 위치 대신 '도착' 단계만", "도착", w.asSujin(med.id).let { it.progressLabels[it.progressIndex] })
        w.navi.drop("판교풀잎약국")
        until(what = "해열제 완료 확인") { w.agent.ui.value.prompt is Prompt.Completion }
        assertEquals("해열제 챙겼어요?", w.voice.spoken.last())
        w.voice.answer("응")
        runCurrent()
        assertEquals(ErrandStatus.COMPLETED, w.asSujin(med.id).status)
        assertEquals("수진님께 완료를 알렸어요.", w.voice.spoken.last())
        // 우유가 첫 경유지가 되어 들르는 중
        assertEquals(ErrandStatus.IN_PROGRESS, w.asSujin(milk.id).status)

        // 편의점 → 완료 → 목적지 도착
        w.agent.testArrive()
        w.agent.testDepart()
        until(what = "우유 완료 확인") { w.agent.ui.value.prompt is Prompt.Completion }
        assertEquals("우유 2팩 챙겼어요?", w.voice.spoken.last())
        w.voice.answer("응 샀어")
        runCurrent()
        w.navi.events.emit(NaviEvent.DestinationArrived)
        runCurrent()
        assertEquals(ErrandStatus.COMPLETED, w.asSujin(milk.id).status)
        assertTrue(w.agent.ui.value.activeErrands.isEmpty())
        assertEquals("목적지에 도착했어요.", w.voice.spoken.last())
    }

    @Test
    fun `시나리오 2 - 데려와줘 + 받아 와줘 - 순서 안내, 차례로 검색, 요청자에게만 도착 예정, 탑승 자동 완료`() = runTest {
        val w = World(this, 16, 45)
        w.agent.start()
        w.driving()
        runCurrent()

        val gimbap = w.send("저녁 포장 주문해뒀어, 받아 와줘", ErrandType.PICKUP_ITEM, "할매김밥")
        val tkd = w.send("아이 태권도 5시에 끝나, 데려와줘", ErrandType.PICKUP_PERSON, "한빛태권도", w.at(17, 0))

        until(what = "여러 건 순서 안내") { w.voice.listening }
        assertEquals("부탁이 2개 있어요. 태권도 먼저, 김밥집은 그다음이에요. 차례로 Pleos 지도에서 찾아 경유지로 넣을까요?", w.voice.spoken.last())
        assertTrue(w.agent.ui.value.prompt is Prompt.Batch)

        // "응" → 약속 시각이 있는 태권도부터 Pleos 지도에서 찾는다
        w.voice.answer("응")
        runCurrent()
        assertEquals(listOf("한빛태권도"), w.navi.searches)
        w.navi.pick("한빛태권도 판교점", 17 * 60 + 30) // 17:02:30 도착 예정
        runCurrent()
        assertTrue(w.voice.spoken.last(), w.voice.spoken.last().contains("약속보다 3분 늦어요"))
        // 이어서 김밥집 검색
        until(what = "다음 장소 검색") { w.navi.searches.size == 2 }
        assertEquals("할매김밥", w.navi.searches[1])
        w.navi.pick("할매김밥", 25 * 60)
        runCurrent()

        // 기다리는 쪽 안내: 도착 예정·정차 지점은 요청자에게만
        val forSujin = w.asSujin(tkd.id)
        assertEquals(ErrandStatus.IN_PROGRESS, forSujin.status)
        assertEquals("한빛태권도 판교점 앞", forSujin.pickupShare?.stopPoint)
        assertNull(w.asJihun(tkd.id).pickupShare)
        assertEquals(ErrandStatus.ACCEPTED, w.asSujin(gimbap.id).status)

        // 픽업: 도착 → 문 열림·닫힘 → 출발 → 자동 완료, 도착 예정 공유 즉시 종료
        w.navi.events.emit(NaviEvent.WaypointArrived("한빛태권도 판교점"))
        runCurrent()
        w.drive.onDoor(true)
        runCurrent()
        w.drive.onDoor(false)
        runCurrent()
        w.navi.drop("한빛태권도 판교점")
        until(what = "픽업 자동 완료") { w.asSujin(tkd.id).status == ErrandStatus.COMPLETED }
        assertNull(w.asSujin(tkd.id).pickupShare)
        assertEquals("아이 탑승을 확인했어요. 수진님께 픽업 완료를 알렸어요.", w.voice.spoken.last())
        assertEquals(ErrandStatus.IN_PROGRESS, w.asSujin(gimbap.id).status)

        // 포장 픽업 → 음성 확인
        w.agent.testArrive()
        w.agent.testDepart()
        until(what = "포장 완료 확인") { w.agent.ui.value.prompt is Prompt.Completion }
        assertEquals("포장 주문 챙겼어요?", w.voice.spoken.last())
        w.voice.answer("응")
        runCurrent()
        assertEquals(ErrandStatus.COMPLETED, w.asSujin(gimbap.id).status)
    }

    @Test
    fun `목적지가 없으면 Pleos 내비의 집으로 경로를 먼저 잡고 이어서 검색`() = runTest {
        val w = World(this, 18, 40)
        w.navi.home.value = Place("h", "집", PlaceCategory.HOME, GeoPoint(37.48, 127.05))
        w.agent.start()
        w.drive.setOverride(DriveState.DRIVING)
        runCurrent()
        w.send("해열제 사 와줘", urgent = true)
        until(what = "안내") { w.voice.listening }
        w.voice.answer("응")
        runCurrent()
        assertEquals(listOf("집"), w.navi.routes)
        assertTrue(w.navi.searches.isEmpty())
        w.navi.destination.value = NaviDestination("집", GeoPoint(37.48, 127.05), 10_000, 1140)
        runCurrent()
        assertEquals(listOf("약국"), w.navi.searches)
    }

    @Test
    fun `나중에 → 보류 후 재알림, 무응답 → 보류, 정차 화면에서 못 가요 → 오늘은 어렵대요`() = runTest {
        val w = World(this, 18, 40)
        w.agent.start()
        w.driving()
        runCurrent()

        val e = w.send("해열제 사 와줘", urgent = true)
        until(what = "안내") { w.voice.listening }
        w.voice.answer("나중에")
        runCurrent()
        assertEquals(ErrandStatus.ON_HOLD, w.asSujin(e.id).status)
        assertEquals("알겠어요. 조금 뒤에 다시 알려 드릴게요.", w.voice.spoken.last())

        until(what = "재알림") { w.voice.listening }
        assertEquals(ErrandStatus.DELIVERED, w.asSujin(e.id).status)
        w.voice.answer(null) // 무응답 10초
        runCurrent()
        assertEquals(ErrandStatus.ON_HOLD, w.asSujin(e.id).status)
        assertEquals("정차하면 화면으로 다시 보여 드릴게요.", w.voice.spoken.last())

        w.drive.setOverride(DriveState.STOPPED)
        runCurrent()
        w.agent.cancelQuest(e.id)
        runCurrent()
        val declined = w.asSujin(e.id)
        assertEquals("오늘은 어려워요", declined.status.requesterLabel)
        assertEquals("오늘은 어렵대요", declined.note)
    }

    @Test
    fun `완료 확인에서 경로 재검색 → 다른 약국을 Pleos 지도에서 찾기, 요청자 취소 → 내비 경유지도 뺀다`() = runTest {
        val w = World(this, 18, 40)
        w.agent.start()
        w.driving()
        runCurrent()

        val med = w.send("해열제 사 와줘", urgent = true)
        until(what = "안내") { w.voice.listening }
        w.voice.answer("응")
        runCurrent()
        w.navi.pick("판교풀잎약국", 180)
        runCurrent()
        w.agent.testArrive()
        w.agent.testDepart()
        until(what = "완료 확인") { w.agent.ui.value.prompt is Prompt.Completion }
        w.voice.answer("다른 곳 찾아줘")
        runCurrent()
        assertEquals(listOf("약국", "약국"), w.navi.searches)
        w.navi.pick("팜스퀘어약국", 300)
        runCurrent()
        assertEquals("팜스퀘어약국", w.asSujin(med.id).targetPlaceName)

        // 요청자가 취소하면 Pleos 내비 경유지에서도 뺀다
        w.hub.cancel(med.id, w.sujin.member.id)
        runCurrent()
        assertTrue(w.navi.removed.isNotEmpty())
        assertTrue(w.voice.spoken.last().endsWith("취소했어요."))
    }

    @Test
    fun `검색 전부터 있던 경유지와 경로 재계산 중 빈 목록은 부탁에 연결하지 않는다`() = runTest {
        val w = World(this, 18, 40)
        w.agent.start()
        w.driving()
        w.navi.pick("운전자가 넣은 주유소", 120, poiId = "gas")
        runCurrent()
        val milk = w.send("우유 2팩 사 와줘", urgent = true)
        until(what = "안내") { w.voice.listening }
        w.voice.answer("응")
        runCurrent()
        // 경유지를 넣는 동안 내비가 잠깐 빈 목록 → 두 곳을 한꺼번에
        w.navi.waypoints.value = emptyList()
        runCurrent()
        w.navi.waypoints.value = listOf(
            NaviWaypoint("운전자가 넣은 주유소", "수정구", null, 900, 120, "gas"),
            NaviWaypoint("GS25 판교지식센터점", "수정구", null, 3000, 480, "gs25"),
        )
        runCurrent()
        assertEquals("GS25 판교지식센터점", w.asSujin(milk.id).targetPlaceName)
        // 잠깐 빠졌다 돌아오면 출발로 보지 않는다
        w.agent.testArrive()
        w.navi.waypoints.value = emptyList()
        advanceTimeBy(1_000)
        runCurrent()
        w.navi.waypoints.value = listOf(NaviWaypoint("GS25 판교지식센터점", "수정구", null, 0, 0, "gs25"))
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(w.agent.ui.value.prompt !is Prompt.Completion)
        // 정말 빠지면(출발) 3초 뒤 완료 확인
        w.navi.waypoints.value = emptyList()
        until(what = "완료 확인") { w.agent.ui.value.prompt is Prompt.Completion }
    }

    @Test
    fun `Gleo Interface - 음성 명령으로 수락·완료 확인·목록 읽기`() = runTest {
        val w = World(this, 18, 40)
        w.agent.start()
        w.driving()
        runCurrent()
        val e = w.send("해열제 사 와줘", urgent = true)
        until(what = "안내") { w.voice.listening }
        val read = w.agent.handleGleo("READ_FAMILY_ERRANDS", org.json.JSONObject())
        assertEquals("success", read.status)
        assertEquals("해열제", read.data.single()["errand"])

        val ok = w.agent.handleGleo("ANSWER_ERRAND_REQUEST", org.json.JSONObject().put("answer", "accept"))
        assertEquals("success", ok.status)
        assertEquals(listOf("약국"), w.navi.searches)
        w.navi.pick("판교풀잎약국", 180)
        runCurrent()
        w.agent.testArrive()
        w.agent.testDepart()
        until(what = "완료 확인") { w.agent.ui.value.prompt is Prompt.Completion }
        val done = w.agent.handleGleo("CONFIRM_ERRAND_DONE", org.json.JSONObject().put("done", true))
        assertEquals("success", done.status)
        runCurrent()
        assertEquals(ErrandStatus.COMPLETED, w.asSujin(e.id).status)
        assertEquals("fail", w.agent.handleGleo("CONFIRM_ERRAND_DONE", org.json.JSONObject().put("done", true)).status)
        assertEquals("asking", w.agent.handleGleo("ANSWER_ERRAND_REQUEST", org.json.JSONObject()).status)
    }

    @Test
    fun `주의 구간·주차 중에는 음성 알림을 미룬다`() = runTest {
        val w = World(this, 18, 40)
        w.agent.start()
        w.driving()
        w.drive.setCaution(true)
        runCurrent()
        w.send("해열제 사 와줘", urgent = true)
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(w.agent.ui.value.holding)
        assertTrue(w.voice.spoken.isEmpty())
        w.drive.setCaution(false)
        until(what = "안전 구간 알림") { w.voice.listening }

        val w2 = World(this, 18, 40)
        w2.agent.start()
        w2.drive.setOverride(DriveState.PARKED)
        runCurrent()
        w2.send("우유 사 와줘", urgent = true)
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(w2.voice.spoken.isEmpty())
    }

    // region 261107: Pleos 내비 위 Please 버튼·퀘스트

    private val parking = GeoPoint(37.41210277, 127.09516111)

    /** 정차 중, 내비가 집으로 안내 중 */
    private fun World.stopped() {
        navi.destination.value = NaviDestination("집", GeoPoint(37.48, 127.05), 10_000, 1140)
        drive.setOverride(DriveState.STOPPED)
    }

    private val World.ui get() = agent.ui.value

    @Test
    fun `버튼 배지 - 새 부탁은 숫자, 패널을 열면 깜빡임이 멈추고, 도착하면 체크`() = runTest {
        val w = World(this, 18, 40)
        w.drive.setOverride(DriveState.PARKED)
        w.navi.destination.value = NaviDestination("집", GeoPoint(37.48, 127.05), 10_000, 1140)
        w.agent.start()
        runCurrent()
        assertEquals(BadgeKind.NONE, w.ui.badge.kind)

        val e = w.send("우유 2팩 사 와줘")
        w.send("해열제 사 와줘")
        runCurrent()
        assertEquals(Badge(BadgeKind.NEW, 2), w.ui.badge)
        assertTrue("새 부탁이 오면 알림", w.ui.attention)
        assertEquals("새 부탁이 왔다는 안내", BadgeKind.NEW, w.ui.callout?.kind)
        assertTrue("주차 중에는 음성으로 묻지 않는다", w.voice.spoken.isEmpty())

        w.agent.markSeen()
        runCurrent()
        assertTrue(!w.ui.attention)
        assertEquals("확인해도 숫자는 남는다", 2, w.ui.badge.count)

        w.agent.startQuest(e.id)
        w.navi.pick("GS25 판교점", 180)
        runCurrent()
        assertEquals(QuestStage.ON_ROUTE, w.ui.quest(e.id)?.stage)
        assertEquals("남은 것 숫자", Badge(BadgeKind.NEW, 1), w.ui.badge)

        w.agent.testArrive()
        runCurrent()
        assertEquals(QuestStage.ARRIVED, w.ui.quest(e.id)?.stage)
        assertEquals(BadgeKind.CONFIRM, w.ui.badge.kind)
        assertTrue("도착하면 다시 알림", w.ui.attention)
    }

    @Test
    fun `어떤 것부터 할지 고른다 - 추천 순서와 상관없이 고른 퀘스트의 장소를 Pleos 지도에서 연다`() = runTest {
        val w = World(this, 18, 40)
        w.stopped()
        w.agent.start()
        runCurrent()
        val urgent = w.send("해열제 사 와줘", urgent = true)
        val milk = w.send("우유 2팩 사 와줘")
        runCurrent()
        assertEquals("급한 것이 먼저 추천된다", listOf(urgent.id, milk.id), w.ui.startable.map { it.errand.id })

        w.agent.startQuest(milk.id)
        runCurrent()
        assertEquals(listOf("편의점"), w.navi.searches)
        assertEquals("수락", w.asSujin(milk.id).status.requesterLabel)
        assertEquals("고른 것만 진행된다", "확인 전", w.asSujin(urgent.id).status.let { if (it.isAwaitingAnswer) "확인 전" else "진행" })
        assertEquals(QuestStage.PICKING, w.ui.quest(milk.id)?.stage)

        w.navi.pick("GS25 판교점", 180)
        runCurrent()
        assertEquals("GS25 판교점", w.asSujin(milk.id).targetPlaceName)
        assertEquals(milk.id, w.ui.current?.errand?.id)
    }

    @Test
    fun `상세 주소 좌표가 있으면 검색 없이 Pleos 내비 경로에 바로 넣는다`() = runTest {
        val w = World(this, 18, 40)
        w.stopped()
        w.agent.start()
        runCurrent()
        val e = w.send("택배 찾아와줘", ErrandType.PICKUP_ITEM, "GS25", address = "경기도 성남시 수정구 창업로 42", addressLocation = parking)
        runCurrent()
        w.agent.startQuest(e.id)
        runCurrent()
        assertTrue("검색 화면을 열지 않는다", w.navi.searches.isEmpty())
        assertEquals("GS25", w.navi.added.single().name)
        assertEquals(parking, w.navi.added.single().location)

        w.navi.pick("GS25", 120)
        runCurrent()
        assertEquals(ErrandStatus.IN_PROGRESS, w.asSujin(e.id).status)
        assertEquals("GS25", w.asSujin(e.id).targetPlaceName)
    }

    @Test
    fun `상세 주소 경로 - 집이 있으면 집 경로에 경유지로, 없으면 목적지로`() = runTest {
        val withHome = World(this, 18, 40)
        withHome.drive.setOverride(DriveState.PARKED)
        withHome.navi.home.value = Place("h", "집", PlaceCategory.HOME, GeoPoint(37.48, 127.05))
        withHome.agent.start()
        runCurrent()
        val a = withHome.send("택배 찾아와줘", ErrandType.PICKUP_ITEM, "GS25", address = "창업로 42", addressLocation = parking)
        runCurrent()
        withHome.agent.startQuest(a.id)
        runCurrent()
        assertEquals("집" to listOf("GS25"), withHome.navi.routesWithStop.single())
        // 내비가 집 경로를 잡아도 집은 부탁 장소가 아니다
        withHome.navi.destination.value = NaviDestination("집", GeoPoint(37.48, 127.05), 10_000, 1140)
        runCurrent()
        assertEquals(QuestStage.PICKING, withHome.ui.quest(a.id)?.stage)
        withHome.navi.pick("GS25", 120)
        runCurrent()
        assertEquals("GS25", withHome.ui.quest(a.id)?.stop?.name)

        val noHome = World(this, 18, 40)
        noHome.drive.setOverride(DriveState.PARKED)
        noHome.agent.start()
        runCurrent()
        val b = noHome.send("택배 찾아와줘", ErrandType.PICKUP_ITEM, "GS25", address = "창업로 42", addressLocation = parking)
        runCurrent()
        noHome.agent.startQuest(b.id)
        runCurrent()
        assertEquals(listOf("GS25"), noHome.navi.routes)
        noHome.navi.destination.value = NaviDestination("GS25", parking, 500, 120)
        runCurrent()
        assertEquals(QuestStage.ON_ROUTE, noHome.ui.quest(b.id)?.stage)
    }

    @Test
    fun `상세 주소 좌표가 없으면 주소로 검색하고, 근처에서 찾기를 고르면 장소 종류로 검색`() = runTest {
        val w = World(this, 18, 40)
        w.stopped()
        w.agent.start()
        runCurrent()
        val e = w.send("이 약국에서 해열제 사 와줘", address = "창업로 17")
        runCurrent()
        w.agent.startQuest(e.id)
        runCurrent()
        assertEquals(listOf("창업로 17"), w.navi.searches)
        assertEquals("요청한 주소를 Pleos 지도에 띄웠어요. 고른 곳을 경유지로 넣으면 수진님께 알려 드릴게요.", w.voice.spoken.last())

        // 주소를 못 찾았으면 경로 재검색: 근처에서 찾는다
        w.agent.researchRoute(e.id)
        runCurrent()
        assertEquals(listOf("창업로 17", "약국"), w.navi.searches)
    }

    @Test
    fun `경로가 없을 때 목적지로 고른 곳도 부탁 장소다 - 도착해도 다른 부탁은 만료되지 않는다`() = runTest {
        val w = World(this, 18, 40)
        w.drive.setOverride(DriveState.PARKED)
        w.agent.start()
        runCurrent()
        val med = w.send("해열제 사 와줘")
        val milk = w.send("우유 2팩 사 와줘")
        runCurrent()

        // 집도 경로도 없으면 검색을 바로 연다 (경로를 먼저 정하라고 막지 않는다)
        w.agent.startQuest(med.id)
        runCurrent()
        assertEquals(listOf("약국"), w.navi.searches)

        // Pleos 내비는 경로가 없으면 '목적지로'만 준다
        w.navi.destination.value = NaviDestination("판교풀잎약국", parking, 310, 180)
        runCurrent()
        assertEquals("판교풀잎약국", w.ui.quest(med.id)?.stop?.name)
        assertEquals(ErrandStatus.IN_PROGRESS, w.asSujin(med.id).status)
        assertTrue("판교풀잎약국으로 안내", w.voice.spoken.last().startsWith("판교풀잎약국으로 안내를 시작했어요."))
        assertNull("집 도착 예정으로 공유하지 않는다", w.asSujin(med.id).homeEtaAt)

        w.navi.events.emit(NaviEvent.DestinationArrived)
        runCurrent()
        assertEquals(QuestStage.ARRIVED, w.ui.quest(med.id)?.stage)
        assertTrue(w.asSujin(milk.id).status.isActive)

        w.agent.confirmDone(med.id)
        runCurrent()
        assertEquals(ErrandStatus.COMPLETED, w.asSujin(med.id).status)
        assertEquals("도착해서 끝낸 경로를 취소하지 않는다", 0, w.navi.cancelled)
    }

    @Test
    fun `완료 후 다음 퀘스트는 저절로 시작되지 않고, 고르면 시작한다`() = runTest {
        val w = World(this, 18, 40)
        w.stopped()
        w.agent.start()
        runCurrent()
        val med = w.send("해열제 사 와줘", urgent = true)
        val milk = w.send("우유 2팩 사 와줘")
        runCurrent()
        until(what = "음성 질문") { w.voice.listening }
        w.voice.answer(null) // 정차 중 음성 질문은 답하지 않고 패널로
        runCurrent()
        w.agent.startQuest(med.id)
        w.navi.pick("판교풀잎약국", 180)
        runCurrent()
        w.navi.events.emit(NaviEvent.WaypointArrived("판교풀잎약국"))
        runCurrent()

        w.agent.confirmDone(med.id)
        runCurrent()
        assertEquals(ErrandStatus.COMPLETED, w.asSujin(med.id).status)
        assertEquals("다음 퀘스트를 고를 차례", med.id, w.ui.justCompletedId)
        assertEquals("내비 경유지에서도 뺀다", listOf(0), w.navi.removed)
        assertEquals(listOf(milk.id), w.ui.startable.map { it.errand.id })
        assertEquals(1, w.ui.doneCount)

        // 시간이 지나도 스스로 다음 검색을 열거나 묻지 않는다 (화면으로 끝냈으면 화면에서 고른다)
        val before = w.navi.searches.toList()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(before, w.navi.searches)
        assertTrue("다음 퀘스트를 묻지 않는다", w.ui.prompt !is Prompt.NextQuest)

        // 고르면 시작
        w.agent.startQuest(milk.id)
        runCurrent()
        assertEquals("편의점", w.navi.searches.last())
        assertNull(w.ui.justCompletedId)
    }

    @Test
    fun `음성으로 끝내면 다음 부탁을 음성으로 묻는다 - 나중에는 그대로, 다른 부탁은 다음 것, 응은 시작`() = runTest {
        val w = World(this, 18, 40)
        w.agent.start()
        w.driving()
        runCurrent()
        val med = w.send("해열제 사 와줘", urgent = true)
        until(what = "해열제 안내") { w.voice.listening }
        w.voice.answer("응")
        runCurrent()
        w.navi.pick("판교풀잎약국", 180)
        runCurrent()

        val milk = w.send("우유 2팩 사 와줘", urgent = true)
        until(what = "우유 안내") { w.voice.listening }
        w.voice.answer("나중에")
        runCurrent()
        val mom = w.send("빵 사 와줘", urgent = true)
        until(what = "빵 안내") { w.voice.listening }
        w.voice.answer("나중에")
        runCurrent()

        w.agent.testArrive()
        w.agent.testDepart()
        until(what = "완료 확인") { w.ui.prompt is Prompt.Completion }
        w.voice.answer("응")
        runCurrent()
        assertEquals(ErrandStatus.COMPLETED, w.asSujin(med.id).status)

        until(what = "다음 부탁 질문") { w.ui.prompt is Prompt.NextQuest }
        val first = (w.ui.prompt as Prompt.NextQuest).errand.id
        assertEquals("남은 부탁이 2개 있어요. 다음은 우유 2팩이에요. 이어서 안내할까요?", w.voice.spoken.last())
        w.voice.answer("다른 부탁")
        runCurrent()
        val second = (w.ui.prompt as Prompt.NextQuest).errand.id
        assertTrue("다른 부탁을 묻는다", first != second)
        assertEquals(setOf(milk.id, mom.id), setOf(first, second))

        w.voice.answer("나중에")
        runCurrent()
        assertEquals("알겠어요. 오는 길에 부탁해 버튼에 남겨 둘게요.", w.voice.spoken.last())
        assertNull(w.ui.prompt)
        assertEquals("시작하지 않고 그대로 남는다", 2, w.ui.startable.size)

        // 나중에 고른 부탁의 재알림 "응" → 시작
        until(what = "재알림") { w.voice.listening }
        w.voice.answer("응")
        runCurrent()
        assertTrue(w.navi.searches.size >= 2)
    }

    @Test
    fun `주행 중에는 화면으로 시작·완료할 수 없고, 버튼을 누르면 음성으로 알려 준다`() = runTest {
        val w = World(this, 18, 40)
        w.agent.start()
        w.driving()
        w.drive.setCaution(true) // 음성 질문을 미뤄 두고
        runCurrent()
        val e = w.send("해열제 사 와줘", urgent = true)
        runCurrent()
        w.agent.startQuest(e.id)
        runCurrent()
        assertTrue("주행 중 화면 시작 불가", w.navi.searches.isEmpty())
        w.agent.cancelQuest(e.id)
        runCurrent()
        assertTrue("주행 중 화면 취소 불가", w.asSujin(e.id).status.isActive)

        w.drive.setCaution(false)
        runCurrent()
        w.agent.briefByVoice()
        runCurrent()
        assertTrue(w.ui.prompt is Prompt.NextQuest)
        assertEquals("남은 부탁은 해열제예요. 이어서 안내할까요?", w.voice.spoken.last())
        w.voice.answer("응")
        runCurrent()
        assertEquals(listOf("약국"), w.navi.searches)
        w.navi.pick("판교풀잎약국", 180)
        runCurrent()

        w.agent.confirmDone(e.id)
        runCurrent()
        assertTrue("주행 중 화면 완료 불가", w.asSujin(e.id).status.isActive)
        w.agent.researchRoute(e.id)
        runCurrent()
        assertEquals("주행 중 화면 경로 재검색 불가", listOf("약국"), w.navi.searches)
        w.agent.briefByVoice()
        runCurrent()
        assertEquals("판교풀잎약국까지 3분 남았어요.", w.voice.spoken.last())
    }

    @Test
    fun `도착 후 내비가 경유지를 빼도 움직이기 전에는 완료를 묻지 않는다`() = runTest {
        val w = World(this, 18, 40)
        w.stopped()
        w.agent.start()
        runCurrent()
        val e = w.send("해열제 사 와줘")
        runCurrent()
        w.agent.startQuest(e.id)
        w.navi.pick("판교풀잎약국", 180)
        runCurrent()
        w.navi.events.emit(NaviEvent.WaypointArrived("판교풀잎약국"))
        runCurrent()
        w.navi.drop("판교풀잎약국")
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue("가게 안에 있는 동안 묻지 않는다", w.ui.prompt !is Prompt.Completion)
        assertEquals(QuestStage.ARRIVED, w.ui.quest(e.id)?.stage)

        // 다시 달리기 시작하면 음성으로 확인
        w.drive.setOverride(DriveState.DRIVING)
        w.navi.waypoints.value = emptyList()
        until(what = "완료 확인") { w.ui.prompt is Prompt.Completion }
    }

    @Test
    fun `경유지가 빠지면 목록으로 돌아가고, 다른 경유지가 생겨도 멋대로 연결하지 않는다`() = runTest {
        val w = World(this, 18, 40)
        w.stopped()
        w.agent.start()
        runCurrent()
        val e = w.send("해열제 사 와줘")
        runCurrent()
        w.agent.startQuest(e.id)
        w.navi.pick("판교풀잎약국", 180)
        runCurrent()
        assertEquals(ErrandStatus.IN_PROGRESS, w.asSujin(e.id).status)

        w.navi.drop("판교풀잎약국")
        until(what = "목록 복귀") { w.ui.quest(e.id)?.stage == QuestStage.READY }
        assertEquals(ErrandStatus.ACCEPTED, w.asSujin(e.id).status)

        w.navi.pick("운전자가 넣은 주유소", 120, poiId = "gas")
        runCurrent()
        assertEquals(QuestStage.READY, w.ui.quest(e.id)?.stage)
        assertTrue("운전자가 넣은 경유지 도착은 부탁 도착이 아니다", run {
            w.navi.events.emit(NaviEvent.WaypointArrived("운전자가 넣은 주유소"))
            runCurrent()
            w.ui.quest(e.id)?.stage == QuestStage.READY
        })

        // 같은 곳을 다시 골라도 연결된다
        w.navi.drop("운전자가 넣은 주유소")
        w.agent.startQuest(e.id)
        runCurrent()
        w.navi.pick("판교풀잎약국", 180)
        runCurrent()
        assertEquals("판교풀잎약국", w.ui.quest(e.id)?.stop?.name)
    }

    @Test
    fun `가는 중 취소 - 목적지였으면 경로를 끝내고 가족에게 알린다`() = runTest {
        val w = World(this, 18, 40)
        w.drive.setOverride(DriveState.PARKED)
        w.agent.start()
        runCurrent()
        val e = w.send("해열제 사 와줘")
        runCurrent()
        w.agent.startQuest(e.id)
        w.navi.destination.value = NaviDestination("판교풀잎약국", parking, 310, 180)
        runCurrent()
        w.agent.cancelQuest(e.id)
        runCurrent()
        assertEquals(1, w.navi.cancelled)
        assertEquals(ErrandStatus.FAILED, w.asSujin(e.id).status)
        assertEquals("못 샀대요", w.asSujin(e.id).note)
        assertEquals("검색을 다시 열지 않는다", 1, w.navi.searches.size)
    }

    // endregion

    // region 모든 부탁 공통: 진행 · 취소 · 완료 · 경로 재검색

    /** 부탁 네 종류를 보내고 차량에 도착할 때까지 기다린다 (부탁, 검색어) */
    private fun TestScope.allTypes(w: World): List<Pair<Errand, String>> = listOf(
        w.send("해열제 사 와줘") to "약국",
        w.send("김밥 포장 주문해뒀어, 받아 와줘", ErrandType.PICKUP_ITEM, "김밥천국") to "김밥천국",
        w.send("아이 태권도 끝나면 데려와줘", ErrandType.PICKUP_PERSON, "한빛태권도", w.at(19, 30)) to "한빛태권도",
        w.send("반찬 챙겨뒀으니 들러서 가져가", ErrandType.DROP_BY, "부모님 댁") to "부모님 댁",
    ).also { runCurrent() }

    @Test
    fun `부탁 종류와 상관없이 진행 → 경로 재검색 → 완료가 똑같이 동작한다`() = runTest {
        val w = World(this, 18, 40)
        w.stopped()
        w.agent.start()
        runCurrent()
        allTypes(w).forEach { (e, keyword) ->
            val place = "$keyword 1호점"
            w.agent.startQuest(e.id)
            runCurrent()
            assertEquals(e.title, keyword, w.navi.searches.last())
            assertEquals(e.title, QuestStage.PICKING, w.ui.quest(e.id)?.stage)

            // 고르던 검색 화면을 닫았어도 경로 재검색으로 다시 찾는다
            val before = w.navi.searches.size
            w.agent.researchRoute(e.id)
            runCurrent()
            assertEquals(e.title, before + 1, w.navi.searches.size)
            assertEquals(e.title, keyword, w.navi.searches.last())

            w.navi.pick(place, 180)
            runCurrent()
            assertEquals(e.title, QuestStage.ON_ROUTE, w.ui.quest(e.id)?.stage)

            // 가는 중 경로 재검색: 경유지를 빼고 다시 고른 곳으로
            w.agent.researchRoute(e.id)
            runCurrent()
            assertEquals(e.title, QuestStage.PICKING, w.ui.quest(e.id)?.stage)
            assertTrue(e.title, w.navi.waypoints.value.none { it.name == place })
            w.navi.pick("$keyword 2호점", 240)
            runCurrent()
            assertEquals(e.title, "$keyword 2호점", w.ui.quest(e.id)?.stop?.name)
            assertEquals(e.title, "$keyword 2호점", w.asSujin(e.id).targetPlaceName)

            w.agent.confirmDone(e.id)
            runCurrent()
            assertEquals(e.title, ErrandStatus.COMPLETED, w.asSujin(e.id).status)
            assertTrue(e.title, w.navi.waypoints.value.isEmpty())
        }
    }

    @Test
    fun `부탁 종류와 상관없이 취소 한 번이면 경로에서 빼고 가족에게 알린다`() = runTest {
        val w = World(this, 18, 40)
        w.stopped()
        w.agent.start()
        runCurrent()
        val types = allTypes(w)
        // 가는 중 취소
        types.take(2).forEach { (e, keyword) ->
            w.agent.startQuest(e.id)
            runCurrent()
            w.navi.pick("$keyword 1호점", 180)
            runCurrent()
            assertEquals(e.title, QuestStage.ON_ROUTE, w.ui.quest(e.id)?.stage)

            w.agent.cancelQuest(e.id)
            runCurrent()
            assertEquals(e.title, ErrandStatus.FAILED, w.asSujin(e.id).status)
            assertNotNull(e.title, w.asSujin(e.id).note)
            assertTrue(e.title, w.navi.waypoints.value.none { it.name == "$keyword 1호점" })
        }
        // 장소 고르는 중 취소
        types.drop(2).forEach { (e, _) ->
            w.agent.startQuest(e.id)
            runCurrent()
            assertEquals(e.title, QuestStage.PICKING, w.ui.quest(e.id)?.stage)

            w.agent.cancelQuest(e.id)
            runCurrent()
            assertEquals(e.title, ErrandStatus.FAILED, w.asSujin(e.id).status)
            assertNotNull(e.title, w.asSujin(e.id).note)
        }
    }

    @Test
    fun `진행 전 취소는 거절, 도착 후 취소는 포기로 가족에게 바로 알린다`() = runTest {
        val w = World(this, 18, 40)
        w.stopped()
        w.agent.start()
        runCurrent()
        val fresh = w.send("우유 사 와줘")
        val arrived = w.send("아이 태권도 끝나면 데려와줘", ErrandType.PICKUP_PERSON, "한빛태권도", w.at(19, 30))
        runCurrent()

        w.agent.cancelQuest(fresh.id)
        runCurrent()
        assertEquals(ErrandStatus.DECLINED, w.asSujin(fresh.id).status)

        w.agent.startQuest(arrived.id)
        runCurrent()
        w.navi.pick("한빛태권도 판교점", 180)
        runCurrent()
        w.navi.events.emit(NaviEvent.WaypointArrived("한빛태권도 판교점"))
        runCurrent()
        assertEquals(QuestStage.ARRIVED, w.ui.quest(arrived.id)?.stage)

        w.agent.cancelQuest(arrived.id)
        runCurrent()
        assertEquals(ErrandStatus.FAILED, w.asSujin(arrived.id).status)
        assertEquals("못 했대요", w.asSujin(arrived.id).note)
        assertTrue(w.navi.waypoints.value.isEmpty())
    }

    @Test
    fun `경로 재검색에서 같은 곳을 다시 골라도 부탁에 연결된다`() = runTest {
        val w = World(this, 18, 40)
        w.stopped()
        w.agent.start()
        runCurrent()
        val e = w.send("해열제 사 와줘")
        runCurrent()
        w.agent.startQuest(e.id)
        w.navi.pick("판교풀잎약국", 180)
        runCurrent()

        w.agent.researchRoute(e.id)
        runCurrent()
        assertEquals(QuestStage.PICKING, w.ui.quest(e.id)?.stage)
        w.navi.pick("판교풀잎약국", 180)
        runCurrent()
        assertEquals("판교풀잎약국", w.ui.quest(e.id)?.stop?.name)
        assertEquals(QuestStage.ON_ROUTE, w.ui.quest(e.id)?.stage)
    }

    /** 실제 Pleos 내비: 경로 중 '경유지로'를 고르면 경로를 취소하고, 미리보기 뒤 경유지를 넣어 다시 안내한다 */
    private suspend fun TestScope.pickOnRoute(w: World, name: String, remainSec: Int) {
        val dest = checkNotNull(w.navi.destination.value) { "안내 중인 경로가 없어요" }
        val kept = w.navi.waypoints.value
        w.navi.destination.value = null
        w.navi.waypoints.value = emptyList()
        w.navi.events.emit(NaviEvent.RouteCancelled)
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        w.navi.destination.value = dest.copy(remainTimeSec = dest.remainTimeSec + remainSec)
        runCurrent()
        w.navi.waypoints.value = kept
        w.navi.pick(name, remainSec)
        runCurrent()
    }

    @Test
    fun `경로 중 경유지를 고르면 내비가 경로를 다시 잡아도 각 부탁의 연결이 유지된다`() = runTest {
        val w = World(this, 18, 40)
        w.drive.setOverride(DriveState.PARKED)
        w.agent.start()
        runCurrent()
        val parcel = w.send("택배 찾아와줘", ErrandType.PICKUP_ITEM, "GS25", address = "창업로 42", addressLocation = parking)
        val med = w.send("해열제 사 와줘")
        runCurrent()
        w.agent.startQuest(parcel.id)
        w.navi.destination.value = NaviDestination("GS25", parking, 500, 120)
        runCurrent()
        assertEquals("GS25", w.ui.quest(parcel.id)?.stop?.name)

        w.agent.startQuest(med.id)
        runCurrent()
        pickOnRoute(w, "판교풀잎약국", 180)
        assertEquals("약국은 고른 약국에 연결", "판교풀잎약국", w.ui.quest(med.id)?.stop?.name)
        assertEquals("택배는 그대로 GS25", "GS25", w.ui.quest(parcel.id)?.stop?.name)
        assertEquals(QuestStage.ON_ROUTE, w.ui.quest(med.id)?.stage)
        assertEquals(QuestStage.ON_ROUTE, w.ui.quest(parcel.id)?.stage)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(QuestStage.ON_ROUTE, w.ui.quest(med.id)?.stage)
        assertEquals(QuestStage.ON_ROUTE, w.ui.quest(parcel.id)?.stage)
        assertTrue(w.voice.spoken.none { it.contains("빠졌어요") })

        // 경유지가 이미 있어도 마찬가지
        val milk = w.send("우유 2팩 사 와줘")
        runCurrent()
        w.agent.startQuest(milk.id)
        runCurrent()
        pickOnRoute(w, "GS25 수정점", 240)
        assertEquals("GS25 수정점", w.ui.quest(milk.id)?.stop?.name)
        assertEquals("판교풀잎약국", w.ui.quest(med.id)?.stop?.name)
    }

    @Test
    fun `운전자가 안내를 끝내면 잠시 뒤 부탁이 목록으로 돌아온다`() = runTest {
        val w = World(this, 18, 40)
        w.drive.setOverride(DriveState.PARKED)
        w.agent.start()
        runCurrent()
        val e = w.send("택배 찾아와줘", ErrandType.PICKUP_ITEM, "GS25", address = "창업로 42", addressLocation = parking)
        runCurrent()
        w.agent.startQuest(e.id)
        w.navi.destination.value = NaviDestination("GS25", parking, 500, 120)
        runCurrent()

        w.navi.destination.value = null
        w.navi.events.emit(NaviEvent.RouteCancelled)
        runCurrent()
        assertEquals("다시 잡는 중일 수 있어 바로 빼지 않는다", QuestStage.ON_ROUTE, w.ui.quest(e.id)?.stage)
        until(what = "목록 복귀") { w.ui.quest(e.id)?.stage == QuestStage.READY }
        assertEquals(ErrandStatus.ACCEPTED, w.asSujin(e.id).status)
    }

    @Test
    fun `경유지의 경로 재검색은 내비가 경로를 다시 잡은 뒤에 검색을 열고, 목적지는 건드리지 않는다`() = runTest {
        val w = World(this, 18, 40)
        w.stopped()
        w.navi.home.value = Place("home", "집", PlaceCategory.HOME, GeoPoint(37.48, 127.05))
        w.agent.start()
        runCurrent()
        val e = w.send("해열제 사 와줘")
        runCurrent()
        w.agent.startQuest(e.id)
        runCurrent()
        w.navi.pick("판교풀잎약국", 180)
        runCurrent()
        val dest = w.navi.destination.value!!
        val searchesBefore = w.navi.searches.size

        w.navi.slowRemove = true
        w.agent.researchRoute(e.id)
        runCurrent()
        assertEquals(listOf(0), w.navi.removed)
        assertEquals(QuestStage.PICKING, w.ui.quest(e.id)?.stage)
        assertEquals("경로를 다시 잡고 있어요. 잠시 뒤 Pleos 지도를 띄울게요.", w.voice.spoken.last())

        // 실제 내비 순서: 경로 취소 → 약 5초 미리보기 → 경유지 변경 알림 → 목적지 복구
        w.navi.waypoints.value = emptyList()
        w.navi.destination.value = null
        w.navi.events.emit(NaviEvent.RouteCancelled)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("다시 잡는 동안 검색을 열지 않는다", searchesBefore, w.navi.searches.size)
        w.navi.events.emit(NaviEvent.WaypointChanged)
        runCurrent()
        assertEquals("목적지가 돌아올 때까지 기다린다", searchesBefore, w.navi.searches.size)
        assertTrue("집으로 경로를 새로 잡지 않는다", w.navi.routes.isEmpty())
        w.navi.destination.value = dest
        runCurrent()
        assertEquals(searchesBefore + 1, w.navi.searches.size)
        assertEquals("약국", w.navi.searches.last())
        assertTrue("집으로 경로를 새로 잡지 않는다", w.navi.routes.isEmpty())

        pickOnRoute(w, "판교풀잎약국", 180)
        assertEquals("같은 곳을 다시 골라도 연결된다", "판교풀잎약국", w.ui.quest(e.id)?.stop?.name)
    }

    @Test
    fun `완료 직후 다음 부탁을 진행해도 내비가 경로를 다시 잡은 뒤에 검색을 연다`() = runTest {
        val w = World(this, 18, 40)
        w.stopped()
        w.agent.start()
        runCurrent()
        val a = w.send("해열제 사 와줘")
        val b = w.send("우유 2팩 사 와줘")
        runCurrent()
        w.agent.startQuest(a.id)
        runCurrent()
        w.navi.pick("판교풀잎약국", 180)
        runCurrent()
        val searchesBefore = w.navi.searches.size

        w.navi.slowRemove = true
        w.agent.confirmDone(a.id)
        w.agent.startQuest(b.id)
        runCurrent()
        assertEquals("다시 잡는 동안 검색을 열지 않는다", searchesBefore, w.navi.searches.size)
        // 끝났다는 소식이 끝내 오지 않아도 막혀 있지 않는다
        until(what = "검색 열기") { w.navi.searches.size == searchesBefore + 1 }
        assertEquals("편의점", w.navi.searches.last())
    }

    @Test
    fun `목적지 경로의 경로 재검색은 경로가 끝난 뒤에 검색을 연다`() = runTest {
        val w = World(this, 18, 40)
        w.drive.setOverride(DriveState.PARKED)
        w.agent.start()
        runCurrent()
        val e = w.send("택배 찾아와줘", ErrandType.PICKUP_ITEM, "GS25", address = "창업로 42", addressLocation = parking)
        runCurrent()
        w.agent.startQuest(e.id)
        w.navi.destination.value = NaviDestination("GS25", parking, 500, 120)
        runCurrent()
        assertEquals(QuestStage.ON_ROUTE, w.ui.quest(e.id)?.stage)

        w.navi.slowCancel = true
        w.agent.researchRoute(e.id)
        runCurrent()
        assertEquals(1, w.navi.cancelled)
        assertTrue("취소가 끝나기 전에 열면 검색 화면이 닫힌다", w.navi.searches.isEmpty())
        assertEquals(QuestStage.PICKING, w.ui.quest(e.id)?.stage)

        w.navi.destination.value = null
        runCurrent()
        assertEquals(listOf("GS25"), w.navi.searches)
        assertEquals(QuestStage.PICKING, w.ui.quest(e.id)?.stage)

        w.navi.destination.value = NaviDestination("GS25 판교점", parking, 700, 180)
        runCurrent()
        assertEquals("GS25 판교점", w.ui.quest(e.id)?.stop?.name)
    }

    @Test
    fun `경로가 끝났다는 소식이 오지 않아도 잠시 뒤 검색을 연다`() = runTest {
        val w = World(this, 18, 40)
        w.drive.setOverride(DriveState.PARKED)
        w.agent.start()
        runCurrent()
        val e = w.send("택배 찾아와줘", ErrandType.PICKUP_ITEM, "GS25", address = "창업로 42", addressLocation = parking)
        runCurrent()
        w.agent.startQuest(e.id)
        w.navi.destination.value = NaviDestination("GS25", parking, 500, 120)
        runCurrent()

        w.navi.slowCancel = true
        w.agent.researchRoute(e.id)
        until(what = "검색 열기") { w.navi.searches.isNotEmpty() }
        assertEquals(listOf("GS25"), w.navi.searches)
    }

    @Test
    fun `음성 완료 확인은 부탁 종류와 상관없이 완료·취소·경로 재검색으로 답한다`() = runTest {
        for ((answer, want) in listOf("태웠어" to ErrandStatus.COMPLETED, "못 태웠어" to ErrandStatus.FAILED, "취소" to ErrandStatus.FAILED)) {
            val w = World(this, 18, 40)
            w.agent.start()
            w.driving()
            runCurrent()
            val e = w.send("아이 태권도 끝나면 데려와줘", ErrandType.PICKUP_PERSON, "한빛태권도", w.at(19, 30))
            until(what = "안내") { w.voice.listening }
            w.voice.answer("응")
            runCurrent()
            w.navi.pick("한빛태권도 판교점", 180)
            runCurrent()
            w.agent.testArrive()
            w.agent.testDepart()
            until(what = "완료 확인") { w.ui.prompt is Prompt.Completion }
            val p = w.ui.prompt as Prompt.Completion
            assertEquals(listOf("취소", "완료", "경로 재검색"), p.replies.map(p::label))
            w.voice.answer(answer)
            runCurrent()
            assertEquals(answer, want, w.asSujin(e.id).status)
            w.agent.stop()
        }
    }

    // endregion
}
