package com.pleose.car.demo

import android.util.Log
import com.pleose.car.agent.CarAgent
import com.pleose.car.agent.CarUi
import com.pleose.car.agent.Prompt
import com.pleose.car.agent.QuestStage
import com.pleose.car.drive.DriveMonitor
import com.pleose.car.platform.NaviPort
import com.pleose.car.platform.VoicePort
import com.pleose.car.platform.VoiceStatus
import com.pleose.shared.api.NewErrandRequest
import com.pleose.shared.api.PleoseApi
import com.pleose.shared.api.JoinRequest
import com.pleose.shared.demo.DemoErrand
import com.pleose.shared.demo.DemoOutcome
import com.pleose.shared.demo.DemoScenario
import com.pleose.shared.demo.DemoStep
import com.pleose.shared.demo.DriverReply
import com.pleose.shared.demo.RequesterReply
import com.pleose.shared.hub.FamilyHub
import com.pleose.shared.model.DriveState
import com.pleose.shared.model.Errand
import com.pleose.shared.model.ErrandStatus
import com.pleose.shared.model.ErrandType
import com.pleose.shared.model.GeoPoint
import com.pleose.shared.model.PlaceCategory
import com.pleose.shared.route.AddressPlanner
import com.pleose.shared.route.ErrandPlan
import com.pleose.shared.route.ErrandPlanner
import com.pleose.shared.route.PlanOption
import com.pleose.shared.voice.Reply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 시연 진행 상황 (설정 화면·테스트 도구에 보여 준다) */
data class DemoProgress(
    val scenarioId: String,
    val title: String,
    val running: Boolean,
    /** 0부터. 시작 전 대기 중이면 -1 */
    val step: Int,
    val stepCount: Int,
    val phase: String,
    val error: String? = null,
)

/** 시연에서 요청자 역할을 하는 쪽: 차 안에 만든 시연용 가족 또는 연결된 가족(중계 서버) */
interface DemoFamily {
    suspend fun memberId(name: String): String
    suspend fun send(req: NewErrandRequest): Errand
    suspend fun answerProposal(errandId: String, requesterId: String, accept: Boolean)
}

class LocalDemoFamily(private val hub: FamilyHub, private val familyId: String, private val inviteCode: String) : DemoFamily {
    override suspend fun memberId(name: String): String = hub.join(JoinRequest(inviteCode, name)).member.id
    override suspend fun send(req: NewErrandRequest): Errand = hub.createErrand(req.copy(familyId = familyId))
    override suspend fun answerProposal(errandId: String, requesterId: String, accept: Boolean) {
        hub.answerProposal(errandId, requesterId, accept)
    }
}

/** 연결된 가족: 같은 이름으로 참여하면 같은 사람이므로, 요청자 앱을 쓰는 가족 이름을 시나리오에 적으면 된다 */
class RemoteDemoFamily(private val api: PleoseApi, private val familyId: String, private val inviteCode: String) : DemoFamily {
    private val ids = mutableMapOf<String, String>()
    override suspend fun memberId(name: String): String = ids.getOrPut(name) { api.join(JoinRequest(inviteCode, name)).member.id }
    override suspend fun send(req: NewErrandRequest): Errand = api.createErrand(req.copy(familyId = familyId))
    override suspend fun answerProposal(errandId: String, requesterId: String, accept: Boolean) {
        api.answerProposal(errandId, requesterId, accept)
    }
}

/**
 * 시연 부탁의 장소·도착 예정: 시나리오에 적은 곳으로 바로 안내하고, 조건부 수락은 시나리오의 도착 예정으로 계산한다.
 * 시나리오에 없는 부탁(직접 보낸 것)은 평소대로 상세 주소 좌표를 쓴다.
 */
class DemoPlanner(private val clock: () -> Long) : ErrandPlanner {
    private val byId = mutableMapOf<String, DemoErrand>()

    fun register(errandId: String, d: DemoErrand) {
        byId[errandId] = d
    }

    override fun plan(errand: Errand, here: GeoPoint?, now: Long): ErrandPlan? {
        val d = byId[errand.id] ?: return AddressPlanner.plan(errand, here, now)
        val category = errand.category ?: if (errand.type == ErrandType.PICKUP_PERSON) PlaceCategory.ACADEMY else PlaceCategory.FAMILY_HOME
        val options = d.candidates.map { PlanOption(it.toPlace(category, "demo_${errand.id}_${it.name}"), it.detourMin) }
        val target = d.place?.let { PlanOption(it.toPlace(category, "demo_${errand.id}"), it.detourMin) } ?: options.firstOrNull() ?: return null
        val eta = d.etaMin?.let { clock() + it * 60_000L }
            ?: here?.let { now + com.pleose.shared.route.EtaEstimator.travelSec(it, target.place.location) * 1000L }
        return ErrandPlan(target.place, eta, target.detourMin, options)
    }
}

/**
 * 시연 진행자: [DemoScenario]대로 가족 부탁을 보내고, 주행 상태를 바꾸고, 운전자·요청자 대신 정한 시간에 답하고,
 * 도착·출발을 만들어 처음부터 끝까지 자동으로 진행한다. 경로와 경유지는 실제 Pleos 내비에 넣는다.
 *
 * [DriverReply.MANUAL]·[RequesterReply.MANUAL]이면 그 단계에서 사람이 직접 답할 때까지 기다린다.
 */
class DemoDirector(
    private val scope: CoroutineScope,
    private val scenario: DemoScenario,
    private val agent: CarAgent,
    private val drive: DriveMonitor,
    private val voice: VoicePort,
    private val navi: NaviPort,
    private val family: DemoFamily,
    private val planner: DemoPlanner,
    /** 진행 화면을 띄운다 (패널) */
    private val showPanel: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onFinished: () -> Unit = {},
) {
    private val _progress = MutableStateFlow(DemoProgress(scenario.id, scenario.title, true, -1, scenario.steps.size, "준비 중"))
    val progress: StateFlow<DemoProgress> = _progress.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            try {
                do {
                    runOnce()
                } while (scenario.loop)
                phase("시연이 끝났어요", running = false)
            } catch (e: CancellationException) {
                phase("시연을 멈췄어요", running = false)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "demo failed", e)
                _progress.value = _progress.value.copy(running = false, phase = "시연을 이어 가지 못했어요", error = e.message)
            } finally {
                onFinished()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runOnce() {
        agent.clearTrip()
        drive.simulate(DriveState.DRIVING, scenario.cruiseKmh.toDouble())
        val home = scenario.home?.toPlace(PlaceCategory.HOME, "demo_home")
        if (scenario.showPanel) showPanel()
        // 내비가 앞으로 나온 뒤에 집 경로를 잡는다 (뒤에 있을 때 보낸 경로 요청은 무시될 수 있다)
        delay(ROUTE_AFTER_MS)
        routeHome(home)
        for (sec in scenario.startDelaySec downTo 1) {
            phase("${sec}초 뒤 시작", step = -1)
            delay(1_000)
        }
        routeHome(home)
        scenario.steps.forEachIndexed { i, step -> runStep(i, step) }
        scenario.arriveHomeAfterSec?.let { sec ->
            phase("${home?.name ?: "목적지"}로 가는 중", step = scenario.steps.size - 1)
            delay(sec * 1_000L)
            drive.simulate(DriveState.STOPPED, 0.0)
            agent.testArriveHome()
        }
        if (scenario.loop) delay(LOOP_PAUSE_MS)
    }

    private suspend fun runStep(i: Int, step: DemoStep) {
        val label = step.title.ifBlank { "${i + 1}단계" }
        phase("$label · ${step.delaySec}초 뒤 부탁", i)
        delay(step.delaySec * 1_000L)

        // 고속 주행 중에 부탁이 오면 차량은 안내를 미룬다 (기획안 3.3)
        if (step.holdSec > 0) drive.simulate(DriveState.DRIVING, step.holdKmh.toDouble())
        val sent = step.errands.map { d -> send(d) }
        val ids = sent.map { it.id }
        if (scenario.showPanel) showPanel()
        if (step.holdSec > 0) {
            phase("$label · 고속 주행이라 안내 보류", i)
            delay(step.holdSec * 1_000L)
        }
        // 신호 대기(0) · 저속 구간에 들어서면 안내
        drive.simulate(if (step.safeKmh <= 0) DriveState.STOPPED else DriveState.DRIVING, step.safeKmh.toDouble())

        phase("$label · 음성 안내", i)
        val prompt = awaitUi(PROMPT_WAIT_MS) { ui -> ui.prompt?.takeIf { p -> p.errandIds.any { it in ids } } }
        when {
            prompt == null -> phase("$label · 안내가 오지 않았어요", i)
            step.driverReply == DriverReply.MANUAL -> {
                phase("$label · 운전자의 답을 기다려요", i)
                awaitUi(MANUAL_WAIT_MS) { ui -> ui.takeIf { ids.none { id -> ui.quest(id)?.stage == QuestStage.NEW } } }
            }
            else -> {
                // 안내 문장을 다 읽고 나서 답한다
                awaitIdleVoice()
                delay(step.replyAfterSec * 1_000L)
                answer(prompt, step.driverReply)
            }
        }

        // 조건부 수락: 요청자가 [좋아요] / [다른 방법 찾을게요]
        if (step.driverReply == DriverReply.CONDITIONAL &&
            awaitUi(STATE_WAIT_MS) { ui -> ui.takeIf { ids.any { id -> ui.quest(id)?.stage == QuestStage.PROPOSED } } } != null
        ) {
            drive.simulate(DriveState.DRIVING, scenario.cruiseKmh.toDouble())
            if (step.requesterReply == RequesterReply.MANUAL) {
                phase("$label · 요청자 앱에서 답을 기다려요", i)
                awaitUi(MANUAL_WAIT_MS) { ui -> ui.takeIf { ids.none { id -> ui.quest(id)?.stage == QuestStage.PROPOSED } } }
            } else {
                phase("$label · ${step.requesterReplyAfterSec}초 뒤 요청자가 '${step.requesterReply.label}'", i)
                delay(step.requesterReplyAfterSec * 1_000L)
                val accept = step.requesterReply == RequesterReply.CONFIRM
                sent.filter { agent.ui.value.quest(it.id)?.stage == QuestStage.PROPOSED }.forEach { e ->
                    runCatching { family.answerProposal(e.id, e.fromId, accept) }.onFailure { Log.w(TAG, "answer proposal failed: ${it.message}") }
                }
            }
        }

        // 수락한 부탁: 경로 순서대로 도착 → 출발(자동 완료)
        drive.simulate(DriveState.DRIVING, scenario.cruiseKmh.toDouble())
        val byId = step.errands.zip(sent).associate { (d, e) -> e.id to d }
        // 확정 안내·경유지 요청이 반영될 틈
        delay(SETTLE_MS)
        var visits = 0
        while (visits++ < ids.size * 2) {
            val ui = agent.ui.value
            val pending = ids.filter { id -> ui.quest(id)?.errand?.status?.isAccepted == true }
            if (pending.isEmpty()) break
            // 경로 순서상 아직 도착하지 않은 첫 부탁 → 없으면 아직 경로에 들어오지 않은 부탁
            val next = ui.stops.firstOrNull { s -> s.errandId in pending && !s.arrived }?.errandId ?: pending.first()
            val d = byId.getValue(next)
            val title = ui.quest(next)?.errand?.title ?: d.title
            // 경유지로 들어올 때까지 (Pleos 지도에서 고르는 부탁은 운전자가 고를 때까지)
            phase("$title · 경유지로 넣는 중", i)
            val bound = awaitUi(BIND_WAIT_MS) { u -> u.takeIf { u.quest(next)?.stop != null || u.quest(next)?.errand?.status?.isTerminal == true } }
            if (bound == null || bound.quest(next)?.stop == null) {
                if (bound?.quest(next)?.errand?.status?.isTerminal == true) continue
                phase("$title · 경유지가 들어오지 않았어요", i)
                break
            }
            phase("$title · ${d.driveSec}초 뒤 도착", i)
            delay(d.driveSec * 1_000L)
            drive.simulate(DriveState.STOPPED, 0.0)
            if (!agent.simulateArrive(next)) {
                phase("$title · 도착 처리를 하지 못했어요", i)
                break
            }
            phase("$title · 도착 · ${d.stopSec}초 뒤 출발", i)
            if (d.type == ErrandType.PICKUP_PERSON) {
                delay(DOOR_MS)
                drive.onDoor(true)
                delay(DOOR_MS)
                drive.onDoor(false)
                delay((d.stopSec * 1_000L - 2 * DOOR_MS).coerceAtLeast(500))
            } else {
                delay(d.stopSec * 1_000L)
            }
            drive.simulate(DriveState.DRIVING, scenario.cruiseKmh.toDouble())
            if (d.outcome == DemoOutcome.FAILED) agent.simulateFail(next) else agent.simulateDepart(next)
            awaitUi(STATE_WAIT_MS) { u -> u.takeIf { u.quest(next)?.errand?.status?.isTerminal != false } }
        }
        // 다음 부탁을 음성으로 묻는 질문이 떠 있으면 시연은 '나중에'로 넘긴다 (다음 단계가 이어지도록)
        agent.ui.value.prompt?.takeIf { it is Prompt.NextQuest }?.let { awaitIdleVoice(); agent.onChip(Reply.LATER) }
        drive.simulate(DriveState.DRIVING, scenario.cruiseKmh.toDouble())
    }

    private fun routeHome(home: com.pleose.shared.model.Place?) {
        if (home == null || !scenario.routeHomeAtStart || !navi.connected.value) return
        if (navi.destination.value?.name != home.name || navi.waypoints.value.isNotEmpty()) navi.routeTo(home)
    }

    private suspend fun send(d: DemoErrand): Errand {
        val now = clock()
        val fromId = family.memberId(d.from.trim())
        val toId = agent.memberId
        val target = d.place
        val e = family.send(
            NewErrandRequest(
                familyId = "",
                fromId = fromId,
                toId = toId,
                type = d.type,
                text = d.text.ifBlank { d.title },
                placeName = target?.name ?: if (d.type == ErrandType.BUY) null else d.title,
                // 도착 예정(지금 + N분)과 같은 기준으로 재야 "20분 늦음"이 정확히 맞는다
                appointmentAt = d.appointmentInMin?.let { now + it * 60_000L },
                urgent = d.urgent,
                address = target?.address?.ifBlank { null } ?: target?.name,
                addressLocation = target?.location,
                title = d.title.ifBlank { null },
                person = d.person.ifBlank { null },
            ),
        )
        planner.register(e.id, d)
        return e
    }

    private fun answer(p: Prompt, r: DriverReply) {
        val reply = when (r) {
            DriverReply.ACCEPT -> Reply.YES
            DriverReply.CONDITIONAL -> if (p.enabled(Reply.CONDITIONAL)) Reply.CONDITIONAL else Reply.YES
            DriverReply.DECLINE -> Reply.NO
            DriverReply.LATER -> Reply.LATER
            DriverReply.MANUAL -> return
        }
        agent.onChip(reply)
    }

    private suspend fun awaitIdleVoice() {
        withTimeoutOrNull(VOICE_WAIT_MS) { voice.status.first { it != VoiceStatus.SPEAKING } }
    }

    private suspend fun <T : Any> awaitUi(timeoutMs: Long, pick: (CarUi) -> T?): T? =
        withTimeoutOrNull(timeoutMs) {
            var found: T? = null
            agent.ui.first { ui -> pick(ui)?.also { found = it } != null }
            found
        }

    private fun phase(text: String, step: Int = _progress.value.step, running: Boolean = true) {
        _progress.value = _progress.value.copy(phase = text, step = step, running = running)
    }

    private companion object {
        const val TAG = "pleose-Demo"
        const val PROMPT_WAIT_MS = 60_000L
        const val STATE_WAIT_MS = 4_000L
        const val BIND_WAIT_MS = 45_000L
        const val MANUAL_WAIT_MS = 10 * 60_000L
        const val VOICE_WAIT_MS = 30_000L
        const val DOOR_MS = 1_500L
        const val SETTLE_MS = 600L
        const val ROUTE_AFTER_MS = 1_500L
        const val LOOP_PAUSE_MS = 5_000L
    }
}
