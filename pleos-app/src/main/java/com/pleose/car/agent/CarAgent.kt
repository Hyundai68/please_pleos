package com.pleose.car.agent

import android.util.Log
import com.pleose.car.drive.DriveMonitor
import com.pleose.car.drive.DriveSnapshot
import com.pleose.car.gleo.GleoBridge
import com.pleose.car.gleo.GleoResult
import com.pleose.car.platform.NaviDestination
import com.pleose.car.platform.NaviEvent
import com.pleose.car.platform.NaviPort
import com.pleose.car.platform.NaviWaypoint
import com.pleose.car.platform.VoicePort
import com.pleose.car.platform.VoiceStatus
import com.pleose.shared.api.ErrandRepository
import com.pleose.shared.api.StatusUpdate
import com.pleose.shared.api.VehicleUpdate
import com.pleose.shared.model.DriveState
import com.pleose.shared.model.Errand
import com.pleose.shared.model.ErrandStatus
import com.pleose.shared.model.Favorite
import com.pleose.shared.model.ErrandType
import com.pleose.shared.model.GeoPoint
import com.pleose.shared.model.Place
import com.pleose.shared.model.PlaceCategory
import com.pleose.shared.policy.DrivingContext
import com.pleose.shared.policy.DrivingPolicy
import com.pleose.shared.policy.EtaSharePolicy
import com.pleose.shared.policy.PickupSharePolicy
import com.pleose.shared.route.AddressPlanner
import com.pleose.shared.route.ErrandPlan
import com.pleose.shared.route.ErrandPlanner
import com.pleose.shared.route.Geo
import com.pleose.shared.text.Korean
import com.pleose.shared.text.Kst
import com.pleose.shared.voice.Reply
import com.pleose.shared.voice.ReplyParser
import com.pleose.shared.voice.Script
import com.pleose.shared.voice.VoiceScripts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import kotlin.math.roundToInt

/** Pleos 내비 경유지(또는 목적지) 하나와 연결된 부탁 */
data class NaviStop(
    val errandId: String,
    val waypointName: String,
    val poiId: String,
    val boundAt: Long,
    val arrivedAt: Long? = null,
    val arrivedLocation: GeoPoint? = null,
    val doorOpened: Boolean = false,
    val boarded: Boolean = false,
    /** 경로가 없을 때 '목적지로' 고른 곳 — 경유지 목록이 아니라 내비 목적지에 해당한다 */
    val isDestination: Boolean = false,
)

/** 화면용 경유지 (Pleos 내비 순서) */
data class StopUi(
    val errandId: String?,
    val name: String,
    val remainDistanceM: Int,
    val remainTimeSec: Int,
    val arrived: Boolean,
)

/** 퀘스트(부탁) 진행 단계 */
enum class QuestStage {
    /** 아직 답하지 않은 새 부탁 */
    NEW,
    /** 조건부 수락을 보내고 요청자의 답을 기다리는 중 */
    PROPOSED,
    /** 수락했지만 아직 경로에 없음 — 언제든 다시 시작할 수 있다 */
    READY,
    /** Pleos 지도에서 고르는 중이거나 경로를 잡는 중 */
    PICKING,
    /** Pleos 내비 경로에 들어가 있음 */
    ON_ROUTE,
    /** 도착함 — Please 버튼에서 완료를 누를 차례 */
    ARRIVED,
    DONE,
    /** 거절·못 함·만료·취소 */
    CLOSED;

    val startable: Boolean get() = this == NEW || this == READY
    val running: Boolean get() = this == PICKING || this == ON_ROUTE || this == ARRIVED
}

data class Quest(val errand: Errand, val stage: QuestStage, val stop: StopUi? = null, val plan: ErrandPlan? = null)

/** 진행 화면에 남겨 두는 마지막 소식 (음성 안내 문장) */
enum class NoticeKind { ACCEPTED, PROPOSED, CONFIRMED, DECLINED, CANCELLED, ARRIVED, DONE, INFO }

data class Notice(val kind: NoticeKind, val title: String, val quote: String, val detail: String? = null, val at: Long)

/** 이번 주행에서 끝낸 부탁 (완료 화면) */
data class DoneItem(val title: String, val label: String, val at: Long)

enum class BadgeKind { NONE, NEW, ACTIVE, CONFIRM }

/** Please 버튼 오른쪽 아래 표시 */
data class Badge(val kind: BadgeKind, val count: Int = 0)

/** 버튼 옆에 잠깐 뜨는 안내 */
data class Callout(val kind: BadgeKind, val title: String, val body: String)

data class AgentOptions(
    /** 출발 감지 후 "해열제 챙겼어요?"로 음성 확인 (false면 기획안대로 출발하면 자동 완료) */
    val confirmOnDepart: Boolean = false,
)

/** 차량 앱 화면 상태 */
data class CarUi(
    val drive: DriveSnapshot,
    /** 오늘의 퀘스트: 진행 중 → 도전 가능(추천 순서) → 끝난 것 */
    val quests: List<Quest> = emptyList(),
    val prompt: Prompt? = null,
    val voice: VoiceStatus = VoiceStatus.IDLE,
    val heard: String? = null,
    val caption: String? = null,
    /** 알림을 주의 구간이라 미루는 중 */
    val holding: Boolean = false,
    /** Pleos 내비 경유지 (내비 순서) */
    val stops: List<StopUi> = emptyList(),
    val destination: NaviDestination? = null,
    val naviConnected: Boolean = false,
    val homeName: String? = null,
    /** 운전자가 아직 확인하지 않은 알림이 있음 (버튼이 깜빡인다) */
    val attention: Boolean = false,
    val callout: Callout? = null,
    /** 방금 끝낸 부탁 — 다음 퀘스트를 고를 차례 */
    val justCompletedId: String? = null,
    val message: String? = null,
    val now: Long = System.currentTimeMillis(),
    /** 마지막 소식 (수락·확정·도착·완료 등) */
    val notice: Notice? = null,
    /** 이번 주행에서 끝낸 부탁 */
    val doneItems: List<DoneItem> = emptyList(),
    /** 나에게 보내도록 저장된 자주 하는 부탁 */
    val favorites: List<Favorite> = emptyList(),
) {
    val activeErrands: List<Errand> get() = quests.filter { it.errand.status.isActive }.map { it.errand }
    val driving: Boolean get() = !DrivingPolicy.canTouch(drive.state)
    fun quest(id: String): Quest? = quests.firstOrNull { it.errand.id == id }

    /** 지금 하고 있는 퀘스트 */
    val current: Quest? get() = quests.firstOrNull { it.stage.running }
    val startable: List<Quest> get() = quests.filter { it.stage.startable }
    val doneCount: Int get() = quests.count { it.stage == QuestStage.DONE }
    /** 진행 바 전체 수 (거절·취소한 것은 뺀다) */
    val totalCount: Int get() = quests.count { it.stage != QuestStage.CLOSED }

    /** 조건부 수락을 보내고 기다리는 부탁 */
    val proposed: List<Quest> get() = quests.filter { it.stage == QuestStage.PROPOSED }

    /** 아직 답하지 않은 새 부탁 */
    val newCount: Int get() = quests.count { it.stage == QuestStage.NEW }

    /** 경로에 있거나 도착한 부탁 (경로 순서) */
    val onRoute: List<Quest> get() = quests.filter { it.stage == QuestStage.ON_ROUTE || it.stage == QuestStage.ARRIVED }.sortedBy { q -> stops.indexOfFirst { it.errandId == q.errand.id }.let { if (it < 0) Int.MAX_VALUE else it } }

    val badge: Badge
        get() {
            val confirm = quests.count { it.stage == QuestStage.ARRIVED }
            return when {
                confirm > 0 -> Badge(BadgeKind.CONFIRM, confirm)
                startable.isNotEmpty() -> Badge(BadgeKind.NEW, startable.size)
                current != null || proposed.isNotEmpty() -> Badge(BadgeKind.ACTIVE)
                else -> Badge(BadgeKind.NONE)
            }
        }
}

/**
 * 차량 쪽 '오는 길에 부탁해' 진행자. 지도·경로는 모두 Pleos 내비가 맡는다.
 *
 * 부탁 수신(F-03) → Please 버튼 배지 + 안전 구간이면 음성 알림(F-04)
 * → 수락·조건부 수락·거절(기획안 3.2, F-05) 또는 퀘스트 선택. 조건부 수락은 요청자가 확정하면 안내한다
 * → 상세 주소가 있으면 Pleos 내비 경로에 바로 넣고, 없으면 Pleos 지도 검색을 자동으로 연다(F-06·F-07)
 * → 운전자가 고른 곳이 경유지·목적지로 들어오면 부탁에 연결(F-08)
 * → 도착을 요청자에게 알리고, 출발하면 자동 완료(기획안 4.2). [AgentOptions.confirmOnDepart]면 출발 후 음성 확인(F-09)
 * → 요청자에게 상태·도착 예정 공유(F-10, F-11) → 다음 퀘스트를 할지 운전자가 고른다.
 * 모든 상태는 메인 스레드에서만 바꾼다.
 */
class CarAgent(
    private val scope: CoroutineScope,
    private val repo: ErrandRepository,
    private val drive: DriveMonitor,
    private val voice: VoicePort,
    private val navi: NaviPort,
    private val clock: () -> Long = System::currentTimeMillis,
    private val options: AgentOptions = AgentOptions(),
) {
    private val me = repo.memberId

    /** 이 차를 운전하는 가족 구성원 */
    val memberId: String get() = me

    /** 들를 곳을 미리 정하는 방법 — 기본은 요청자가 적은 상세 주소 좌표, 시연에서는 시나리오의 장소 */
    var planner: ErrandPlanner = AddressPlanner

    /** 시연에서 쓰는 집 (없으면 Pleos 내비의 '집' 즐겨찾기) */
    var homeOverride: Place? = null

    /** 음성 응답을 기다리는 시간 (시연에서는 길게) */
    var listenMs: Long = LISTEN_MS

    private val plans = mutableMapOf<String, ErrandPlan?>()
    /** 한 번에 경로에 넣은 곳 이름 → 부탁 (내비가 경유지를 알려 주면 이름으로 연결) */
    private val expectBind = linkedMapOf<String, String>()
    private var expectSince = 0L
    /** 안내 문장을 이미 말한 부탁 — 경유지가 들어와도 또 말하지 않는다 */
    private val quietBind = mutableSetOf<String>()
    private var notice: Notice? = null
    private val doneItems = mutableListOf<DoneItem>()
    private var favorites: List<Favorite> = emptyList()

    private var serverErrands: List<Errand> = emptyList()
    private val pendingStatus = mutableMapOf<String, ErrandStatus>()
    private val knownStatus = mutableMapOf<String, ErrandStatus>()

    private val prompted = mutableSetOf<String>()
    private val forceSingle = mutableSetOf<String>()
    private val remindAt = mutableMapOf<String, Long>()
    private val awaitingConfirm = linkedSetOf<String>()
    private val pendingCompletions = ArrayDeque<String>()
    private val sharedHomeEta = mutableMapOf<String, Long>()
    private val sharedPickupEta = mutableMapOf<String, Long>()
    private var snapshotSeen = false
    /** 운전자가 패널을 열어 확인한 알림 */
    private val seenIds = mutableSetOf<String>()
    private val seenArrivals = mutableSetOf<String>()
    /** 상세 주소가 있어도 근처에서 찾겠다고 고른 부탁 */
    private val nearbyOverride = mutableSetOf<String>()
    /** 음성으로 부탁을 끝낸 뒤 다음 부탁을 물어볼 차례 */
    private var offerNextPending = false
    private var justCompletedId: String? = null

    /** 차례로 경로에 넣을 부탁 (순서대로) */
    private val pendingPlace = ArrayDeque<String>()
    /** 다음에 내비 경유지·목적지로 들어오는 곳을 연결할 부탁 */
    private var searchingFor: String? = null
    /** 내비 목적지가 바뀌면 [searchingFor]에 연결할지 (집 경로를 직접 잡을 때는 연결하지 않는다) */
    private var destBindAllowed = true
    private var lastDestKey: String? = null
    /** 목적지가 생기거나 경유지 자리가 나면 이어서 검색 */
    private var waitingForRoute = false
    /**
     * 우리가 요청한 경로 변경. Pleos 내비는 경유지를 뺄 때도 경로를 끝냈다가 약 5초 미리보기 뒤 다시 잡는다.
     * 끝나기 전에 검색을 열면 검색 화면이 덮여 사라지고, 목적지도 비어 보이므로 끝날 때까지 기다린다.
     */
    private enum class RouteChange { CANCEL, REMOVE_WAYPOINT, COMING_BACK }
    private var routeChange: RouteChange? = null
    private var routeChangeAt = 0L
    /**
     * Pleos 내비가 경로를 끝낸 때. 경로 중 '경유지로'를 고를 때도 내비는 경로를 끝냈다가 미리보기 뒤 다시 잡으므로,
     * 잠시 기다려 보고 돌아오지 않을 때만 운전자가 끝낸 것으로 본다.
     */
    private var routeGoneAt: Long? = null
    /** 검색을 열 때의 내비 목적지 — 경로를 다시 잡으며 돌아온 같은 목적지는 새로 고른 곳이 아니다 */
    private var destAtBinding: String? = null
    private val stops = mutableListOf<NaviStop>()
    /**
     * 이미 본 경유지 — 검색을 연 뒤 '처음 나타난' 경유지만 부탁에 연결한다.
     * (운전자가 따로 넣은 경유지, 지나온 경유지, 경로 재계산 중 다시 보내온 경유지는 연결하지 않는다)
     */
    private val seenKeys = mutableSetOf<String>()
    /** 방금 경로에서 뺀 경유지 — 내비 목록에서 사라지면 잊어서, 경로 재검색으로 같은 곳을 다시 골라도 연결되게 한다 */
    private val removingKeys = mutableSetOf<String>()
    /** 연결된 경유지가 목록에서 안 보이기 시작한 때 — 재계산 중 잠깐 빠지는 것과 구분 */
    private val missingSince = mutableMapOf<String, Long>()
    private var lastWaypoints: List<NaviWaypoint> = emptyList()

    private var prompt: Prompt? = null
    private var promptSeq = 0L
    private var unheardRetries = 0
    private var caption: String? = null
    private var captionAt = 0L
    private var holding = false
    private var message: String? = null
    private var messageAt = 0L
    private var callout: Callout? = null
    private var calloutAt = 0L
    private var reportedState: DriveState? = null
    private var lastEtaShareAt = 0L
    private var lastDoorOpen = false

    private val sendLock = Mutex()
    private var job: Job? = null

    private val _ui = MutableStateFlow(buildUi())
    val ui: StateFlow<CarUi> = _ui.asStateFlow()

    fun start() {
        if (job != null) return
        GleoBridge.handler = ::handleGleo
        job = scope.launch {
            launch {
                repo.snapshot.collect { snap ->
                    snap ?: return@collect
                    if (snap.favorites.filter { it.toId == me } != favorites) onFavorites(snap.favorites)
                    onErrands(snap.errands.filter { e -> e.toId == me })
                }
            }
            launch { voice.status.collect { publish() } }
            launch { voice.heard.collect { publish() } }
            launch { navi.waypoints.collect(::onNaviWaypoints) }
            launch { navi.destination.collect { onDestination(it) } }
            launch { navi.events.collect(::onNaviEvent) }
            launch { navi.location.collect { it?.let(drive::onLocation) } }
            launch { drive.state.collect { onDrive(it) } }
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        voice.stopListening()
        if (GleoBridge.handler == ::handleGleo) GleoBridge.handler = null
    }

    // region Gleo Interface (assets/schema.json): Gleo AI가 운전자 음성 명령으로 부르는 함수

    /** Gleo Action 처리. 메시지는 Gleo 규칙대로 영어 한 문장, 자세한 내용은 data로 */
    fun handleGleo(name: String, params: JSONObject): GleoResult {
        val result = when (name) {
            GleoBridge.ANSWER_ERRAND_REQUEST -> gleoAnswer(params.optString("answer").lowercase())
            GleoBridge.CONFIRM_ERRAND_DONE -> gleoConfirm(params)
            GleoBridge.READ_FAMILY_ERRANDS -> gleoRead()
            else -> GleoResult.fail("This function is not supported.")
        }
        publish()
        return result
    }

    private fun errandData(e: Errand) = mapOf(
        "from" to e.fromName, "type" to e.type.label, "errand" to e.title,
        "status" to status(e).requesterLabel,
        "place" to (stops.firstOrNull { it.errandId == e.id }?.waypointName ?: e.searchKeyword),
    )

    private fun gleoAnswer(answer: String): GleoResult {
        val reply = when (answer) {
            "accept" -> Reply.YES
            "conditional" -> Reply.CONDITIONAL
            "later" -> Reply.LATER
            "decline" -> Reply.NO
            else -> return GleoResult.asking("Should the errand be accepted, accepted on condition, or declined?")
        }
        val p = prompt
        if (p is Prompt.Offer || p is Prompt.Batch || p is Prompt.NextQuest) {
            if (reply !in p.voiceReplies || !p.enabled(reply)) {
                return GleoResult.asking("The driver will arrive on time, so no condition is needed. Should the errand be accepted or declined?")
            }
            val list = p.errandIds.mapNotNull(::errand)
            voice.stopListening()
            handle(p, reply)
            return GleoResult.success("The answer was shared with the family.", list.map(::errandData))
        }
        // 묻는 중이 아니면 가장 최근에 받은, 아직 답하지 않은 부탁에 답한다
        val e = effective().filter { status(it).isAwaitingAnswer }.maxByOrNull { it.createdAt }
            ?: return GleoResult.fail("There is no family errand waiting for an answer.")
        when (reply) {
            Reply.YES -> accept(listOf(e))
            Reply.CONDITIONAL -> if (lateOf(e) != null) propose(e) else return GleoResult.asking("No condition is needed. Should the errand be accepted or declined?")
            Reply.LATER -> hold(e)
            else -> decline(e)
        }
        return GleoResult.success("The answer was shared with the family.", listOf(errandData(e)))
    }

    private fun gleoConfirm(params: JSONObject): GleoResult {
        val done = when (val v = params.opt("done")) {
            is Boolean -> v
            is String -> v.toBooleanStrictOrNull()
            else -> null
        } ?: return GleoResult.asking("Was the errand finished?")
        val e = (prompt as? Prompt.Completion)?.errand?.let { errand(it.id) }
            ?: awaitingConfirm.firstOrNull()?.let(::errand)
            ?: return GleoResult.fail("There is no errand waiting for confirmation.")
        dropPromptFor(e.id)
        if (done) complete(e, byVoice = true) else giveUp(e)
        return GleoResult.success("The result was shared with the family.", listOf(errandData(e)))
    }

    private fun gleoRead(): GleoResult {
        val open = effective().filter { it.status.isActive }
        return if (open.isEmpty()) {
            GleoResult.success("There are no open family errands.")
        } else {
            val message = if (open.size == 1) "There is 1 open family errand." else "There are ${open.size} open family errands."
            GleoResult.success(message, open.map(::errandData))
        }
    }

    // endregion

    // region 부탁 스냅숏

    private fun onErrands(list: List<Errand>) {
        serverErrands = list
        list.forEach { e ->
            val p = pendingStatus[e.id]
            // 조건부 수락을 보내자마자 요청자가 확정하면 제안 상태를 건너뛰고 수락이 온다
            if (p != null && (e.status == p || e.status.isTerminal || (p == ErrandStatus.PROPOSED && e.status.isAccepted))) pendingStatus.remove(e.id)
        }
        val fresh = if (snapshotSeen) list.filter { it.id !in knownStatus && it.status.isAwaitingAnswer } else emptyList()
        list.forEach { e ->
            val before = knownStatus[e.id]
            when {
                before == ErrandStatus.PROPOSED && e.status.isAccepted -> onProposalConfirmed(e)
                before != null && before != ErrandStatus.CANCELLED && e.status == ErrandStatus.CANCELLED -> onCancelled(e, wasProposal = before == ErrandStatus.PROPOSED)
            }
            knownStatus[e.id] = e.status
        }
        snapshotSeen = true
        val safe = drive.state.value.let { d -> DrivingPolicy.canAlert(DrivingContext(d.state, d.speed, cautionZone = d.caution)) }
        when (fresh.size) {
            0 -> Unit
            1 -> showCallout(BadgeKind.NEW, "${Korean.nim(fresh[0].fromName)}의 새 부탁", if (safe) fresh[0].title else "새 부탁 1건 · 안전할 때 안내")
            else -> showCallout(BadgeKind.NEW, "새 부탁 ${fresh.size}건", if (safe) fresh.joinToString(" · ") { it.spokenTitle } else "안전할 때 음성으로 안내할게요")
        }
        publish()
    }

    /** 요청자의 스냅숏에서 가족 즐겨찾기 중 나에게 보내는 것 */
    fun onFavorites(list: List<Favorite>) {
        favorites = list.filter { it.toId == me }
        publish()
    }

    private fun onCancelled(e: Errand, wasProposal: Boolean = false) {
        pendingStatus.remove(e.id)
        forgetRouting(e.id)
        dropPromptFor(e.id)
        // Pleos 내비에 넣어 둔 경유지도 뺀다
        stops.firstOrNull { it.errandId == e.id }?.let(::removeFromRoute)
        val script = if (wasProposal) VoiceScripts.proposalDeclined(e) else Script("${Korean.i(Korean.nim(e.fromName))} ${Korean.eul(e.spokenTitle)} 취소했어요.")
        say(script)
        setNotice(NoticeKind.CANCELLED, "부탁이 취소됐어요", script.spoken, if (wasProposal) "부탁한 사람이 최종 판단을 내리면, 운전자는 거절에 대한 부담 없이 원래 길을 계속 가요." else null)
        publish()
    }

    /** 요청자가 조건부 수락을 [좋아요]로 확정: "18시 50분 픽업으로 확정됐어요. 어린이집으로 안내할게요." */
    private fun onProposalConfirmed(e: Errand) {
        if (stops.any { it.errandId == e.id } || e.id in pendingPlace) return
        val script = VoiceScripts.proposalConfirmed(e, e.proposal?.etaAt ?: planFor(e)?.etaAt)
        setNotice(NoticeKind.CONFIRMED, "확정", script.spoken, e.note)
        accept(listOf(e), speech = script)
    }

    private fun status(e: Errand): ErrandStatus = pendingStatus[e.id] ?: e.status

    private fun effective(): List<Errand> = serverErrands.map { e -> pendingStatus[e.id]?.let { e.copy(status = it) } ?: e }

    private fun errand(id: String): Errand? = effective().firstOrNull { it.id == id }

    // endregion

    // region Pleos 내비 경유지 ↔ 부탁 연결 (F-08)

    private fun key(poiId: String, name: String) = poiId.ifBlank { name }

    private fun matches(w: NaviWaypoint, s: NaviStop) = key(w.poiId, w.name) == key(s.poiId, s.waypointName)

    private fun onNaviWaypoints(list: List<NaviWaypoint>) {
        val now = clock()
        lastWaypoints = list
        removingKeys.toList().filter { k -> list.none { key(it.poiId, it.name) == k } }.forEach { k ->
            removingKeys -= k
            seenKeys -= k
        }
        bindExpected(list, now)
        // 새로 들어온 경유지(처음 보는 것)만 지금 안내를 시작한 부탁에 연결한다
        searchingFor?.let(::errand)?.let { e ->
            list.firstOrNull { w -> key(w.poiId, w.name) !in seenKeys && stops.none { matches(w, it) } }
                ?.let { w -> bindStop(e, w.name, w.poiId, w.remainTimeSec, isDestination = false) }
        }
        seenKeys += list.map { key(it.poiId, it.name) }
        stops.forEach { s -> if (isOnRoute(s)) missingSince.remove(s.errandId) else missingSince.putIfAbsent(s.errandId, now) }
        checkMissing(now)
        // 경유지 순서대로: 첫 경유지는 들르는 중, 나머지는 수락
        applyStopStatuses(list)
        shareEta(force = true)
        // 자리가 났으면 대기 중인 검색을 이어서
        if (waitingForRoute && list.size < MAX_WAYPOINTS) openNextSearch()
        publish()
    }

    /**
     * 한 번에 경로에 넣은 곳을 부탁에 연결한다: 이름이 같은 경유지 → 좌표가 가까운 경유지 →
     * 잠시 기다려도 못 찾으면 새로 나타난 경유지를 경로 순서대로.
     */
    private fun bindExpected(list: List<NaviWaypoint>, now: Long) {
        if (expectBind.isEmpty()) return
        fun free(w: NaviWaypoint) = stops.none { matches(w, it) }
        expectBind.toList().forEach { (name, id) ->
            val plan = plans[id]
            val w = list.firstOrNull { free(it) && it.name == name }
                ?: list.firstOrNull { w -> free(w) && plan != null && w.location?.let { Geo.distanceM(it, plan.place.location) < NEAR_M } == true }
                ?: return@forEach
            expectBind.remove(name)
            errand(id)?.let { bindStop(it, w.name, w.poiId, w.remainTimeSec, isDestination = false) }
        }
        if (expectBind.isNotEmpty() && now - expectSince > EXPECT_GRACE_MS) {
            val unseen = list.filter { free(it) && key(it.poiId, it.name) !in seenKeys }
            if (unseen.size >= expectBind.size) {
                expectBind.toList().zip(unseen).forEach { (pair, w) ->
                    expectBind.remove(pair.first)
                    errand(pair.second)?.let { bindStop(it, w.name, w.poiId, w.remainTimeSec, isDestination = false) }
                }
            }
        }
    }

    /** 운전자가 고른 곳(또는 바로 넣은 주소)을 부탁에 연결한다 */
    private fun bindStop(e: Errand, name: String, poiId: String, remainSec: Int, isDestination: Boolean) {
        val now = clock()
        pendingPlace.remove(e.id)
        if (searchingFor == e.id) searchingFor = null
        stops += NaviStop(e.id, name, poiId, now, isDestination = isDestination)
        val eta = now + remainSec * 1000L
        val late = e.appointmentAt?.let { ((eta - it) / 60_000.0).roundToInt() }?.takeIf { e.type == ErrandType.PICKUP_PERSON && it > 0 }
        if (!quietBind.remove(e.id)) {
            say(if (isDestination) VoiceScripts.destinationSet(name, eta) else VoiceScripts.waypointAdded(name, eta, late))
        }
        planFor(e)?.detourMin?.let { d -> setStatus(e, null, StatusUpdate(me, targetPlaceName = name, detourMin = d)) }
        showCallout(BadgeKind.ACTIVE, name, "${Korean.nim(e.fromName)}의 부탁 · ${e.title}")
        // 함께 수락한 부탁이 있으면 다음 장소도 이어서 찾는다
        if (pendingPlace.isNotEmpty()) {
            scope.launch {
                delay(NEXT_SEARCH_DELAY_MS)
                pendingPlace.firstOrNull()?.let(::errand)?.let { next -> say(VoiceScripts.nextInBatch(next)) }
                openNextSearch()
            }
        }
    }

    /** 이 경유지가 아직 Pleos 내비 경로에 있는지 */
    private fun isOnRoute(s: NaviStop): Boolean =
        if (s.isDestination) navi.destination.value?.name == s.waypointName else lastWaypoints.any { matches(it, s) }

    /**
     * 목록에서 한동안 안 보이는 경유지:
     * 들른 곳이면 실제로 움직이기 시작했을 때 출발로 보고(내비는 도착하면 경유지를 바로 뺄 수 있다),
     * 아직 들르지 않은 곳이면 운전자가 뺀 것 → 퀘스트 목록으로 되돌린다.
     */
    private fun checkMissing(now: Long) {
        routeGoneAt?.let { if (now - it < ROUTE_REBUILD_GRACE_MS) return else routeGoneAt = null }
        stops.toList().forEach { s ->
            val since = missingSince[s.errandId] ?: return@forEach
            if (now - since < MISSING_GRACE_MS) return@forEach
            if (s.arrivedAt != null) {
                if (!s.isDestination && drive.state.value.state == DriveState.DRIVING) {
                    missingSince.remove(s.errandId)
                    departed(s)
                }
            } else {
                missingSince.remove(s.errandId)
                releaseStop(s)
                say(VoiceScripts.stopRemoved(s.waypointName))
            }
        }
    }

    /** 경로에서 빠진 부탁은 다시 고를 수 있게 목록으로 되돌린다 */
    private fun releaseStop(s: NaviStop) {
        stops.remove(s)
        errand(s.errandId)?.takeIf { status(it) == ErrandStatus.IN_PROGRESS }?.let { setStatus(it, ErrandStatus.ACCEPTED) }
    }

    /** 부탁을 Pleos 내비 경로에서 뺀다. 목적지였다면 경로를 끝낸다 */
    private fun removeFromRoute(s: NaviStop) {
        stops.remove(s)
        missingSince.remove(s.errandId)
        if (s.isDestination) {
            if (s.arrivedAt == null && navi.cancelRoute()) beginRouteChange(RouteChange.CANCEL)
        } else {
            removingKeys += key(s.poiId, s.waypointName)
            val idx = lastWaypoints.indexOfFirst { matches(it, s) }
            if (idx >= 0 && navi.removeWaypoint(idx)) beginRouteChange(RouteChange.REMOVE_WAYPOINT)
        }
    }

    private fun forgetRouting(id: String) {
        awaitingConfirm.remove(id)
        pendingCompletions.remove(id)
        pendingPlace.remove(id)
        nearbyOverride.remove(id)
        if (searchingFor == id) searchingFor = null
    }

    private fun orderedStops(list: List<NaviWaypoint> = lastWaypoints): List<NaviStop> =
        stops.sortedBy { s -> list.indexOfFirst { matches(it, s) }.let { if (it < 0) Int.MAX_VALUE else it } }

    private fun applyStopStatuses(list: List<NaviWaypoint>) {
        orderedStops(list).forEachIndexed { i, s ->
            val e = errand(s.errandId) ?: return@forEachIndexed
            val want = if (i == 0) ErrandStatus.IN_PROGRESS else ErrandStatus.ACCEPTED
            if (status(e) != want && status(e).isAccepted) {
                setStatus(e, want, StatusUpdate(me, targetPlaceName = s.waypointName))
            } else if (e.targetPlaceName != s.waypointName) {
                setStatus(e, null, StatusUpdate(me, targetPlaceName = s.waypointName))
            }
        }
    }

    private fun onDestination(d: NaviDestination?) {
        val key = d?.name
        val changed = key != lastDestKey
        lastDestKey = key
        if (d == null) {
            if (routeChange == RouteChange.CANCEL) finishRouteChange()
        } else {
            if (routeGoneAt != null) {
                // 경로가 돌아왔다: 경유지 목록도 곧 다시 오므로 빠진 시각을 새로 잰다
                routeGoneAt = null
                val now = clock()
                missingSince.replaceAll { _, _ -> now }
            }
            if (routeChange == RouteChange.COMING_BACK) finishRouteChange()
        }
        when {
            d == null -> Unit
            waitingForRoute -> openNextSearch()
            !changed -> Unit
            !destBindAllowed -> destBindAllowed = true // 직접 잡은 집 경로: 부탁에 연결하지 않는다
            // 경로가 없을 때 Pleos 내비는 '목적지로'만 준다: 그렇게 고른 곳도 부탁의 장소다
            d.name == destAtBinding -> Unit
            d.name in expectBind -> expectBind.remove(d.name)?.let(::errand)?.let { bindStop(it, d.name, "", d.remainTimeSec, isDestination = true) }
            else -> searchingFor?.let(::errand)?.let { bindStop(it, d.name, "", d.remainTimeSec, isDestination = true) }
        }
        val now = clock()
        stops.filter { it.isDestination }.forEach { s ->
            if (isOnRoute(s)) missingSince.remove(s.errandId) else missingSince.putIfAbsent(s.errandId, now)
        }
        applyStopStatuses(lastWaypoints)
        publish()
    }

    /**
     * 다음 부탁을 Pleos 내비에서 안내한다.
     * 상세 주소의 좌표가 있으면 경로에 바로 넣고, 없으면 Pleos 지도 검색을 자동으로 연다
     * (상세 주소 → 장소 이름 → 품목에 맞는 장소 종류 순으로 검색어를 정한다).
     */
    private fun openNextSearch() {
        val id = pendingPlace.firstOrNull() ?: run { waitingForRoute = false; return }
        val e = errand(id) ?: run { pendingPlace.remove(id); return }
        if (!navi.connected.value) {
            showMessage("Pleos 내비에 연결되지 않았어요")
            return
        }
        if (routeChange != null) {
            if (routeChange != RouteChange.CANCEL && !waitingForRoute) {
                say(VoiceScripts.routeChanging())
                showCallout(BadgeKind.ACTIVE, "경로를 다시 잡고 있어요", "잠시 뒤 Pleos 지도를 띄울게요")
            }
            waitingForRoute = true
            publish()
            return
        }
        val hasDestination = navi.destination.value != null
        if (hasDestination && lastWaypoints.size >= MAX_WAYPOINTS) {
            if (!waitingForRoute) say(VoiceScripts.waypointsFull())
            waitingForRoute = true
            publish()
            return
        }
        val byAddress = e.hasAddress && id !in nearbyOverride
        // 들를 곳을 미리 알면(상세 주소 좌표·시연 장소) 검색 없이 경로에 바로 넣는다. 경로 재검색은 근처에서 다시 찾는다
        val plan = planFor(e)?.takeIf { id !in nearbyOverride }
        val home = home()
        if (plan != null) {
            waitingForRoute = false
            beginBinding(e)
            val place = plan.place
            when {
                hasDestination -> navi.addWaypoint(place, second = lastWaypoints.isNotEmpty())
                home != null -> {
                    destBindAllowed = false
                    navi.setRoute(home, listOf(place))
                }
                else -> navi.routeTo(place)
            }
            if (e.id !in quietBind) say(if (e.hasAddress) VoiceScripts.routeRequested(e) else Script("${Korean.eul(place.name)} 경유지로 넣을게요."))
            publish()
            return
        }
        // 집이 등록돼 있으면 '오는 길에' 들르도록 집 경로를 먼저 잡고, 아니면 고른 곳이 목적지가 된다
        if (!hasDestination && home != null) {
            if (waitingForRoute) return
            waitingForRoute = true
            say(VoiceScripts.routingHome(home.name))
            navi.routeTo(home)
            publish()
            return
        }
        waitingForRoute = false
        beginBinding(e)
        navi.searchPlace(if (byAddress) e.address.orEmpty() else e.searchKeyword)
        say(if (byAddress) VoiceScripts.addressSearchOpened(e) else VoiceScripts.searchOpened(e))
        publish()
    }

    private fun beginRouteChange(change: RouteChange) {
        routeChange = change
        routeChangeAt = clock()
    }

    /** 경로 변경이 끝났으면 기다리던 안내를 이어서 한다 */
    private fun finishRouteChange() {
        if (routeChange == null) return
        routeChange = null
        if (waitingForRoute) {
            waitingForRoute = false
            openNextSearch()
        }
    }

    /** 지금부터 내비에 새로 들어오는 곳을 이 부탁에 연결한다. 이미 있던 경유지는 연결하지 않는다 */
    private fun beginBinding(e: Errand) {
        searchingFor = e.id
        destBindAllowed = true
        destAtBinding = navi.destination.value?.name
        seenKeys.clear()
        seenKeys += lastWaypoints.map { key(it.poiId, it.name) }
    }

    // endregion

    // region 주기 처리

    private fun onDrive(s: DriveSnapshot) {
        if (s.state != reportedState) {
            reportedState = s.state
            send { repo.updateVehicle(VehicleUpdate(linked = true, driveState = s.state)) }
        }
        // 경유지에 도착해 있는 동안 문이 열렸다 닫히면 탑승으로 본다 (데려와줘)
        if (s.doorOpen != lastDoorOpen) {
            lastDoorOpen = s.doorOpen
            stops.replaceAll { st ->
                when {
                    st.arrivedAt == null -> st
                    s.doorOpen -> st.copy(doorOpened = true)
                    else -> st.copy(boarded = st.boarded || st.doorOpened)
                }
            }
        }
        publish()
    }

    private fun tick() {
        val now = clock()
        drive.tick()
        // 위치로 출발 감지: 도착 지점에서 150m 넘게 벗어나면 출발
        val loc = navi.location.value?.location
        if (loc != null) {
            stops.filter { s -> s.arrivedLocation?.let { distanceM(it, loc) > DEPART_DISTANCE_M } == true }.forEach(::departed)
        }
        checkMissing(now)
        // 끝났다는 소식이 오지 않아도 검색이 막혀 있지 않게 한다
        if (routeChange != null && now - routeChangeAt > ROUTE_CHANGE_WAIT_MS) finishRouteChange()
        if (now - lastEtaShareAt > ETA_SHARE_MS) shareEta()
        scheduleAlerts(now)
        if (caption != null && now - captionAt > CAPTION_MS && voice.status.value == VoiceStatus.IDLE) caption = null
        if (callout != null && now - calloutAt > CALLOUT_MS) callout = null
        if (message != null && now - messageAt > CAPTION_MS) message = null
        publish()
    }

    /** 집 도착 예정·픽업 도착 예정을 요청자에게 (3분 이상 바뀔 때만) */
    private fun shareEta(force: Boolean = false) {
        val now = clock()
        lastEtaShareAt = now
        // 부탁 장소가 목적지면 내비 목적지는 집이 아니다: 집 도착 예정을 공유하지 않는다
        val homeEta = navi.destination.value?.takeIf { stops.none { s -> s.isDestination } }?.let { now + it.remainTimeSec * 1000L }
        stops.forEach { s ->
            val e = errand(s.errandId) ?: return@forEach
            val remainSec = if (s.isDestination) navi.destination.value?.remainTimeSec else lastWaypoints.firstOrNull { matches(it, s) }?.remainTimeSec
            val h = homeEta?.takeIf { (force && sharedHomeEta[e.id] == null) || EtaSharePolicy.shouldUpdate(sharedHomeEta[e.id], it) }
            val p = remainSec?.let { now + it * 1000L }?.takeIf {
                e.type == ErrandType.PICKUP_PERSON && s.arrivedAt == null &&
                    ((force && sharedPickupEta[e.id] == null) || PickupSharePolicy.shouldUpdate(sharedPickupEta[e.id], it))
            }
            if (h == null && p == null) return@forEach
            h?.let { sharedHomeEta[e.id] = it }
            p?.let { sharedPickupEta[e.id] = it }
            setStatus(e, null, StatusUpdate(me, targetPlaceName = s.waypointName, homeEtaAt = h, pickupEtaAt = p, stopPoint = "${s.waypointName} 앞"))
        }
    }

    private fun scheduleAlerts(now: Long) {
        holding = false
        if (prompt != null || voice.status.value != VoiceStatus.IDLE) return

        pendingCompletions.removeFirstOrNull()?.let { id ->
            errand(id)?.takeIf { status(it).isAccepted }?.let { return presentCompletion(it) }
        }

        val d = drive.state.value
        if (d.state == DriveState.PARKED) return // 주차 중에는 Please 버튼 배지로만 알린다
        val eligible = effective().filter { e ->
            val s = e.status
            (s == ErrandStatus.DELIVERED || s == ErrandStatus.CREATED) && e.id !in prompted ||
                s == ErrandStatus.ON_HOLD && (remindAt[e.id] ?: Long.MAX_VALUE) <= now
        }
        if (eligible.isEmpty() && !offerNextPending) return

        if (!DrivingPolicy.canAlert(DrivingContext(d.state, d.speed, cautionZone = d.caution))) {
            holding = true // 주의 구간: 안전 구간까지 알림 보류 (F-04)
            return
        }
        if (eligible.isEmpty()) {
            offerNextPending = false
            // 이미 다음 경유지로 가는 중이면 묻지 않는다
            if (stops.none { it.arrivedAt == null }) startableErrands().firstOrNull()?.let(::presentNext)
            return
        }
        // 급하지 않으면 잠깐 기다렸다가 함께 온 부탁을 한 번에 묶어 안내한다
        if (eligible.none { it.urgent } && eligible.any { now - it.createdAt < BATCH_WINDOW_MS }) return

        eligible.filter { it.status == ErrandStatus.ON_HOLD }.forEach { e ->
            remindAt.remove(e.id)
            setStatus(e, ErrandStatus.DELIVERED) // 재알림
        }
        val (singles, group) = eligible.partition { it.id in forceSingle }
        if (group.size >= 2) {
            val ordered = batchOrder(group)
            val batchPlans = ordered.mapNotNull { e -> planFor(e)?.let { e.id to it } }.toMap()
            val late = ordered.mapNotNull { e -> batchPlans[e.id]?.lateMin(e)?.let { e.id to it } }.toMap()
            return present(Prompt.Batch(++promptSeq, ordered, VoiceScripts.batch(ordered, batchPlans), batchPlans, late))
        }
        val first = (group + singles).sortedWith(compareByDescending<Errand> { it.urgent }.thenBy { it.createdAt }).first()
        presentOffer(first)
    }

    private fun presentOffer(e: Errand, plan: ErrandPlan? = planFor(e)) {
        val now = clock()
        val followUp = effective().any { o ->
            o.id != e.id && o.fromId == e.fromId &&
                (status(o).isAccepted || (status(o) == ErrandStatus.COMPLETED && now - o.updatedAt < 60 * 60_000))
        }
        present(Prompt.Offer(++promptSeq, e, VoiceScripts.offer(e, followUp, plan), plan, plan?.lateMin(e)))
    }

    /** 들를 곳 계획 (한 번 세우면 그대로 쓴다 — 안내한 도착 예정과 제안 시각이 같도록) */
    private fun planFor(e: Errand): ErrandPlan? {
        if (plans.containsKey(e.id)) return plans[e.id]
        val p = runCatching { planner.plan(e, navi.location.value?.location, clock()) }.getOrNull()
        plans[e.id] = p
        return p
    }

    /** 약속보다 몇 분 늦는지 (조건부 수락을 쓸 수 있는지) */
    private fun lateOf(e: Errand): Int? = planFor(e)?.lateMin(e)

    private fun home(): Place? = homeOverride ?: navi.home.value

    /** 지금 시작할 수 있는 부탁 (추천 순서) */
    private fun startableErrands(): List<Errand> = batchOrder(
        effective().filter { e ->
            e.status.isActive && e.id != searchingFor && e.id !in pendingPlace && e.id !in awaitingConfirm &&
                e.id !in pendingCompletions && stops.none { it.errandId == e.id }
        },
    )

    private fun presentNext(e: Errand) {
        present(Prompt.NextQuest(++promptSeq, e, VoiceScripts.nextQuest(e, startableErrands().size)))
    }

    /** 약속 시각이 있는 부탁(데려와줘) 먼저, 나머지는 급한 것·받은 순서. 실제 경로는 Pleos 내비가 계산한다 */
    private fun batchOrder(list: List<Errand>): List<Errand> =
        list.sortedWith(compareBy<Errand>({ it.appointmentAt ?: Long.MAX_VALUE }, { !it.urgent }, { it.createdAt }))

    private fun presentCompletion(e: Errand) {
        present(Prompt.Completion(++promptSeq, e, VoiceScripts.completionQuestion(e)))
    }

    // endregion

    // region 묻고 듣기

    private fun present(p: Prompt) {
        prompt = p
        unheardRetries = 0
        p.errandIds.forEach { prompted += it }
        speakThenListen(p, p.script.spoken)
        publish()
    }

    private fun speakThenListen(p: Prompt, text: String) {
        caption = text
        captionAt = clock()
        voice.speak(text) {
            if (prompt?.id != p.id) return@speak
            voice.listen(listenMs) { results -> onHeard(p.id, results) }
        }
    }

    private fun onHeard(promptId: Long, results: List<String>?) {
        val p = prompt ?: return
        if (p.id != promptId) return
        if (results == null) return onNoAnswer(p)
        val expected = p.voiceReplies.filter(p::enabled).toSet()
        val reply = results.firstNotNullOfOrNull { ReplyParser.parse(it, expected) }
        if (reply != null) return handle(p, reply)
        if (unheardRetries++ < 1) {
            speakThenListen(p, "다시 한 번 말씀해 주세요. ${p.script.question.ifEmpty { p.script.headline }}")
        } else {
            onNoAnswer(p)
        }
    }

    /** 화면의 응답 칩 (음성 대신 — 에뮬레이터 테스트용) */
    fun onChip(reply: Reply) {
        val p = prompt ?: return
        if (reply !in p.voiceReplies || !p.enabled(reply)) return
        voice.stopListening()
        voice.setHeard(p.label(reply))
        handle(p, reply)
    }

    /** 무응답 10초 (F-05): 보류 처리 후 정차 시 화면으로 재안내 */
    private fun onNoAnswer(p: Prompt) {
        prompt = null
        when (p) {
            is Prompt.Completion -> awaitingConfirm += p.errand.id
            is Prompt.NextQuest -> Unit // 답이 없으면 그대로 버튼에 남는다
            else -> {
                p.errandIds.mapNotNull(::errand).forEach { setStatus(it, ErrandStatus.ON_HOLD) }
                say(VoiceScripts.noAnswer())
            }
        }
        publish()
    }

    private fun handle(p: Prompt, r: Reply) {
        prompt = null
        when (p) {
            is Prompt.Offer -> errand(p.errand.id)?.let { e ->
                when (r) {
                    Reply.YES -> accept(listOf(e))
                    Reply.CONDITIONAL -> propose(e)
                    Reply.LATER -> hold(e)
                    Reply.NO -> decline(e)
                    // "다른 곳": 경로상 다음 후보로 바꿔 다시 묻는다
                    Reply.OTHER -> p.plan?.nextOption()?.let { next -> plans[e.id] = next; presentOffer(e, next) }
                    else -> Unit
                }
            }
            is Prompt.Batch -> {
                val list = p.errands.mapNotNull { errand(it.id) }
                when (r) {
                    Reply.YES -> accept(list)
                    // 늦는 부탁만 조건을 붙여 묻고 나머지는 수락
                    Reply.CONDITIONAL -> {
                        val (late, onTime) = list.partition { it.id in p.late }
                        if (onTime.isNotEmpty()) accept(onTime)
                        late.forEach(::propose)
                    }
                    Reply.OTHER -> list.forEach { forceSingle += it.id; prompted -= it.id }
                    Reply.LATER -> list.forEach(::hold)
                    Reply.NO -> list.forEach(::decline)
                    else -> Unit
                }
            }
            is Prompt.Completion -> errand(p.errand.id)?.let { e ->
                when (r) {
                    Reply.YES -> complete(e, byVoice = true)
                    Reply.RESEARCH -> routeQuest(e, nearby = true)
                    else -> giveUp(e)
                }
            }
            is Prompt.NextQuest -> errand(p.errand.id)?.let { e ->
                when (r) {
                    Reply.YES -> accept(listOf(e))
                    Reply.OTHER -> {
                        val list = startableErrands()
                        val next = list.getOrNull(list.indexOfFirst { it.id == e.id } + 1) ?: list.firstOrNull()
                        if (next == null || next.id == e.id) say(VoiceScripts.noOtherQuest()) else presentNext(next)
                    }
                    else -> say(VoiceScripts.leftOnBoard())
                }
            }
        }
        publish()
    }

    // endregion

    // region 동작

    /**
     * 수락 → Pleos 내비에서 안내 (여러 건이면 순서대로).
     * 들를 곳을 모두 미리 알면 경유지를 한 번에 넣고 방문 순서를 정리한다 (기획안 4.2). [speech]는 대신 말할 안내.
     */
    private fun accept(list: List<Errand>, speech: Script? = null) {
        justCompletedId = null
        val fresh = batchOrder(list.filter { e -> e.id !in pendingPlace && stops.none { it.errandId == e.id } && e.id != searchingFor })
        list.forEach { e ->
            remindAt.remove(e.id)
            if (!status(e).isAccepted) setStatus(e, ErrandStatus.ACCEPTED, StatusUpdate(me, detourMin = planFor(e)?.detourMin))
        }
        if (fresh.size >= 2 && routeAllAtOnce(fresh, speech)) return
        if (speech != null) {
            say(speech)
            quietBind += fresh.map { it.id }
        }
        fresh.forEach { pendingPlace.addLast(it.id) }
        openNextSearch()
    }

    /** 미리 정한 곳 여러 곳을 한 번의 경로 요청으로 넣는다. 넣을 수 없으면 false (차례로 넣는다) */
    private fun routeAllAtOnce(list: List<Errand>, speech: Script?): Boolean {
        if (!navi.connected.value || routeChange != null || lastWaypoints.isNotEmpty() || expectBind.isNotEmpty()) return false
        val planned = list.map { it to (planFor(it)?.takeIf { _ -> it.id !in nearbyOverride } ?: return false) }
        val now = clock()
        val here = planned.take(MAX_WAYPOINTS)
        val dest = navi.destination.value
        // 시연에서 정한 집이 있으면 그곳이 최종 목적지, 아니면 지금 내비 목적지를 그대로 둔다
        val destination = homeOverride ?: when {
            dest != null -> home()?.takeIf { it.name == dest.name } ?: Place("navi_dest", dest.name, PlaceCategory.HOME, dest.location)
            else -> home() ?: return false
        }
        if (dest == null) destBindAllowed = false
        seenKeys.clear()
        expectSince = now
        here.forEach { (e, p) -> expectBind[p.place.name] = e.id; quietBind += e.id }
        navi.setRoute(destination, here.map { it.second.place })
        planned.drop(MAX_WAYPOINTS).forEach { (e, _) -> pendingPlace.addLast(e.id) }
        if (pendingPlace.isNotEmpty()) waitingForRoute = true
        val script = speech ?: VoiceScripts.acceptedRoute(here.map { it.second.place.name })
        say(script)
        setNotice(NoticeKind.ACCEPTED, "진행 중", script.spoken, acceptedDetail(here.map { it.first }))
        publish()
        return true
    }

    /** "지은님께 '어린이집 도착 예정 18:20'을 보냈어요" */
    private fun acceptedDetail(list: List<Errand>): String? {
        val pickup = list.firstOrNull { it.type == ErrandType.PICKUP_PERSON } ?: return list.firstOrNull()?.let { "${Korean.nim(it.fromName)}께 수락을 알렸어요" }
        val eta = planFor(pickup)?.etaAt ?: return "${Korean.nim(pickup.fromName)}께 수락을 알렸어요"
        return "${Korean.nim(pickup.fromName)}께 '${VoiceScripts.placeLabel(pickup)} 도착 예정 ${Kst.hhmm(eta)}'을 보냈어요"
    }

    /** 조건부 수락: 차량 도착 예정으로 계산한 "18:50 도착 가능"을 요청자에게 묻는다. 그동안 경로는 그대로 */
    private fun propose(e: Errand) {
        val plan = planFor(e)
        val eta = plan?.etaAt
        val late = plan?.lateMin(e)
        if (eta == null || late == null) return accept(listOf(e))
        remindAt.remove(e.id)
        setStatus(e, ErrandStatus.PROPOSED, StatusUpdate(me, proposalEtaAt = eta, proposalLateMin = late))
        val script = VoiceScripts.proposalSent(e, eta)
        say(script)
        setNotice(NoticeKind.PROPOSED, "답변 대기", script.spoken, "${Kst.hhmm(eta)} 도착 가능 · 요청보다 약 ${late}분 늦음")
    }

    private fun hold(e: Errand) {
        remindAt[e.id] = clock() + REMIND_MS
        setStatus(e, ErrandStatus.ON_HOLD)
        say(VoiceScripts.later())
    }

    private fun decline(e: Errand) {
        setStatus(e, ErrandStatus.DECLINED)
        val script = VoiceScripts.declined(e)
        say(script)
        setNotice(NoticeKind.DECLINED, "거절했어요", script.spoken)
    }

    /** [byVoice]: 음성으로 끝냈으면 다음 부탁도 음성으로 묻는다. 화면으로 끝냈으면 패널에서 고른다 */
    private fun complete(e: Errand, script: Script = VoiceScripts.completed(e), byVoice: Boolean = false, label: String = e.doneLabel) {
        forgetRouting(e.id)
        stops.firstOrNull { it.errandId == e.id }?.let(::removeFromRoute)
        setStatus(e, ErrandStatus.COMPLETED)
        say(script)
        justCompletedId = e.id
        if (byVoice) offerNextPending = true
        val now = clock()
        doneItems += DoneItem(e.title, "${Kst.hhmm(now)} $label", now)
        val left = effective().count { it.id != e.id && status(it).isAccepted }
        setNotice(NoticeKind.DONE, if (left == 0) "완료" else "${e.title} 완료", script.spoken)
        applyStopStatuses(lastWaypoints)
    }

    /** 포기: 경로에서 빼고 요청자에게 "못 샀대요/못 했대요" */
    private fun giveUp(e: Errand) {
        forgetRouting(e.id)
        stops.firstOrNull { it.errandId == e.id }?.let(::removeFromRoute)
        setStatus(e, ErrandStatus.FAILED)
        val script = VoiceScripts.notDone(e)
        say(script)
        setNotice(NoticeKind.INFO, "${e.title} · 못 했어요", script.spoken)
    }

    // endregion

    // region 경유지 도착·출발 (F-09)

    private fun onNaviEvent(ev: NaviEvent) {
        when (ev) {
            is NaviEvent.WaypointArrived -> arrive(ev.name)
            NaviEvent.DestinationArrived -> onDestinationArrived()
            NaviEvent.RouteCancelled -> {
                // 경로를 다시 잡으며 돌아올 수 있다. 돌아오지 않으면 checkMissing이 목록으로 되돌린다
                val now = clock()
                routeGoneAt = now
                stops.filter { it.arrivedAt == null }.forEach { missingSince.putIfAbsent(it.errandId, now) }
                publish()
            }
            NaviEvent.RouteStarted -> Unit
            NaviEvent.WaypointChanged -> if (routeChange == RouteChange.REMOVE_WAYPOINT) {
                // 내비는 경유지 변경을 알린 뒤에 목적지를 다시 알려 준다
                if (navi.destination.value != null) finishRouteChange() else routeChange = RouteChange.COMING_BACK
            }
        }
    }

    private fun arrive(name: String) {
        val named = stops.indexOfFirst { it.waypointName == name && it.arrivedAt == null }
        // 운전자가 따로 넣은 경유지에 도착한 것이면 부탁과 상관없다
        if (named < 0 && lastWaypoints.any { it.name == name }) return
        val idx = named.takeIf { it >= 0 } ?: stops.indexOfFirst { it.arrivedAt == null }.takeIf { it >= 0 } ?: return
        val now = clock()
        val s = stops[idx].copy(arrivedAt = now, arrivedLocation = navi.location.value?.location, doorOpened = false, boarded = false)
        stops[idx] = s
        seenArrivals.remove(s.errandId)
        val e = errand(s.errandId)
        if (e != null) {
            // 위치 대신 '도착' 단계만 요청자에게 (기획안 3.2 진행 상태 공유)
            setStatus(e, null, StatusUpdate(me, targetPlaceName = s.waypointName, arrived = true))
            val script = VoiceScripts.arrivedShared(s.waypointName, e)
            if (prompt == null) say(script)
            setNotice(NoticeKind.ARRIVED, "${s.waypointName}에 도착했어요", script.spoken, "따로 누르지 않아도 돼요. 출발하면 '${e.doneLabel}'로 자동으로 바뀌어요.")
        }
        showCallout(
            BadgeKind.CONFIRM, "${s.waypointName} 도착",
            if (options.confirmOnDepart) "끝나면 버튼을 눌러 완료해 주세요" else "출발하면 자동으로 완료를 알려요",
        )
        publish()
    }

    private fun departed(s: NaviStop) {
        val cur = stops.firstOrNull { it.errandId == s.errandId && it.waypointName == s.waypointName } ?: return
        stops.remove(cur)
        missingSince.remove(cur.errandId)
        val e = errand(cur.errandId) ?: return
        when {
            // 데려와줘: 정차 후 문 열림·닫힘과 출발 → 자동 완료 (음성 확인 생략)
            e.type == ErrandType.PICKUP_PERSON && cur.boarded && options.confirmOnDepart -> complete(e, VoiceScripts.pickupDone(e), byVoice = true)
            options.confirmOnDepart -> pendingCompletions += e.id
            // 기획안 4.2: 들렀다 출발하면 운전자의 입력 없이 완료를 알린다
            else -> {
                val left = effective().count { it.id != e.id && status(it).isAccepted }
                val tripCount = doneItems.count { clock() - it.at < TRIP_MS } + 1
                complete(e, VoiceScripts.departedDone(e, cur.waypointName, if (left == 0) tripCount else 0), byVoice = true)
            }
        }
        applyStopStatuses(lastWaypoints)
        publish()
    }

    private fun onDestinationArrived() {
        // 부탁 장소가 목적지였으면 그 부탁에 도착한 것: 다른 부탁은 그대로 둔다
        val stop = stops.firstOrNull { it.isDestination && it.arrivedAt == null }
        if (stop != null) arrive(stop.waypointName) else onArrivedHome()
    }

    /** 목적지(집) 도착: 수락했지만 처리하지 못한 부탁은 만료 */
    private fun onArrivedHome() {
        val left = effective().filter { e ->
            e.status.isAccepted && e.id !in awaitingConfirm && e.id !in pendingCompletions &&
                stops.none { it.errandId == e.id && it.arrivedAt != null }
        }
        left.forEach { setStatus(it, ErrandStatus.EXPIRED) }
        stops.removeAll { it.arrivedAt == null }
        pendingPlace.clear()
        searchingFor = null
        missingSince.clear()
        if (left.isNotEmpty()) say(VoiceScripts.expired(left.size)) else say(Script("목적지에 도착했어요."))
        publish()
    }

    // endregion

    // region Please 버튼·퀘스트 패널 (주행 중에는 화면 입력을 받지 않는다)

    /** 패널을 열어 알림을 확인했다 */
    fun markSeen() {
        seenIds += effective().filter { it.status.isActive }.map { it.id }
        seenArrivals += stops.filter { it.arrivedAt != null }.map { it.errandId }
        callout = null
        publish()
    }

    /** 주행 중 버튼을 누르면 화면 대신 음성으로 알려 주고, 할 부탁이 있으면 음성으로 묻는다 */
    fun briefByVoice() {
        if (prompt != null) return
        val next = orderedStops().firstOrNull { it.arrivedAt == null }
        val startable = startableErrands()
        when {
            next != null -> {
                val remain = if (next.isDestination) navi.destination.value?.remainTimeSec else lastWaypoints.firstOrNull { matches(it, next) }?.remainTimeSec
                say(VoiceScripts.onTheWay(next.waypointName, remain ?: 0, startable.size))
            }
            startable.isNotEmpty() -> presentNext(startable.first())
            else -> say(VoiceScripts.noQuest())
        }
    }

    /** "남은 부탁 읽어 줘" */
    fun readRemaining() {
        if (prompt != null) return
        say(VoiceScripts.remaining(effective().filter { status(it).isActive }))
        publish()
    }

    private fun touchAllowed(): Boolean = DrivingPolicy.canTouch(drive.state.value.state)

    // 모든 부탁은 종류와 상관없이 진행·취소·완료·경로 재검색 네 가지로만 다룬다

    /** 진행: 상세 주소가 있으면 Pleos 내비 경로에 바로 넣고, 없으면 Pleos 지도 검색을 자동으로 연다 */
    fun startQuest(errandId: String) {
        if (!touchAllowed()) return
        errand(errandId)?.let { routeQuest(it, nearby = false) }
        publish()
    }

    /**
     * 취소: 언제 누르든 이 부탁을 포기한다. 경로에 있으면 빼고 요청자에게 알린다.
     * 잠깐 멈추기만 하려면 Pleos 내비에서 안내를 끝내면 된다 (경로에서 빠진 부탁은 목록으로 돌아간다).
     */
    fun cancelQuest(errandId: String) {
        if (!touchAllowed()) return
        val e = errand(errandId) ?: return
        if (!status(e).isActive) return
        dropPromptFor(errandId)
        if (status(e).isAccepted) giveUp(e) else decline(e)
        publish()
    }

    /** 완료 */
    fun confirmDone(errandId: String) {
        if (!touchAllowed()) return
        val e = errand(errandId) ?: return
        dropPromptFor(errandId)
        complete(e)
        publish()
    }

    /** 경로 재검색: 지금 경로에서 빼고 Pleos 지도에서 다시 고른다. 요청한 주소가 틀렸을 수 있으니 장소 이름·종류로 찾는다 */
    fun researchRoute(errandId: String) {
        if (!touchAllowed()) return
        errand(errandId)?.let { routeQuest(it, nearby = true) }
        publish()
    }

    /** Pleos 내비에서 안내를 시작한다. 이미 경로에 있던 부탁이면 그 경유지를 빼고 다시 안내한다 */
    private fun routeQuest(e: Errand, nearby: Boolean) {
        if (!status(e).isActive) return
        dropPrompt()
        justCompletedId = null
        stops.firstOrNull { it.errandId == e.id }?.let { s ->
            if (s.arrivedAt != null) setStatus(e, null, StatusUpdate(me, arrived = false))
            removeFromRoute(s)
        }
        expectBind.entries.removeAll { it.value == e.id }
        awaitingConfirm.remove(e.id)
        pendingCompletions.remove(e.id)
        pendingPlace.remove(e.id)
        if (nearby) nearbyOverride += e.id else nearbyOverride -= e.id
        searchingFor = null
        waitingForRoute = false
        pendingPlace.addFirst(e.id)
        accept(listOf(e))
    }

    /** 다음 퀘스트 선택을 마쳤다 ("오늘은 여기까지") */
    fun dismissCompleted() {
        justCompletedId = null
        publish()
    }

    /** 모두 끝낸 뒤 Pleos 내비의 집으로 */
    fun routeHome() {
        if (!touchAllowed()) return
        home()?.let(navi::routeTo)
    }

    private fun dropPromptFor(id: String) {
        if (prompt?.errandIds?.contains(id) == true) dropPrompt()
    }

    private fun dropPrompt() {
        if (prompt != null) {
            prompt = null
            voice.stopListening()
        }
    }

    // endregion

    // region 테스트 조작 (에뮬레이터는 실제로 움직이지 않는다)

    /** 첫 경유지에 도착한 것으로 (Pleos 내비의 경유지 도착 이벤트 대신) */
    fun testArrive() {
        orderedStops().firstOrNull { it.arrivedAt == null }?.let { arrive(it.waypointName) }
    }

    /** 도착해 있는 경유지에서 출발한 것으로 */
    fun testDepart() {
        stops.firstOrNull { it.arrivedAt != null }?.let(::departed)
    }

    /** 목적지 도착 */
    fun testArriveHome() = onDestinationArrived()

    /** 시연: 이 부탁의 장소에 도착한 것으로 */
    fun simulateArrive(errandId: String): Boolean {
        val s = stops.firstOrNull { it.errandId == errandId && it.arrivedAt == null } ?: return false
        arrive(s.waypointName)
        return true
    }

    /** 시연: 이 부탁의 장소에서 출발한 것으로 (자동 완료). 실제로 지나간 것처럼 그 경유지도 Pleos 내비 경로에서 뺀다 */
    fun simulateDepart(errandId: String): Boolean {
        val s = stops.firstOrNull { it.errandId == errandId && it.arrivedAt != null } ?: return false
        val idx = if (s.isDestination) -1 else lastWaypoints.indexOfFirst { matches(it, s) }
        departed(s)
        if (idx >= 0) {
            removingKeys += key(s.poiId, s.waypointName)
            if (navi.removeWaypoint(idx)) beginRouteChange(RouteChange.REMOVE_WAYPOINT)
        }
        publish()
        return true
    }

    /** 시연: 들렀지만 못 했다 ("못 샀대요") */
    fun simulateFail(errandId: String) {
        val e = errand(errandId) ?: return
        dropPromptFor(errandId)
        giveUp(e)
        publish()
    }

    /** 시연을 처음부터: 지난 소식과 완료 기록을 지운다 */
    fun clearTrip() {
        notice = null
        doneItems.clear()
        publish()
    }

    // endregion

    // region 공통

    private fun setStatus(e: Errand, to: ErrandStatus?, info: StatusUpdate = StatusUpdate(me)) {
        if (to != null) {
            if (status(e).isTerminal) return
            pendingStatus[e.id] = to
            knownStatus[e.id] = to
        }
        val update = info.copy(actorId = me, status = to)
        send {
            runCatching { repo.updateStatus(e.id, update) }.onFailure { err ->
                Log.w(TAG, "status update failed: ${err.message}")
                if (to != null && pendingStatus[e.id] == to) pendingStatus.remove(e.id)
                showMessage("가족 앱에 반영하지 못했어요: ${err.message}")
            }
        }
    }

    /** 서버 호출은 보낸 순서대로 하나씩 */
    private fun send(block: suspend () -> Unit) {
        scope.launch {
            sendLock.withLock {
                runCatching { block() }.onFailure { Log.w(TAG, "send failed: ${it.message}") }
            }
        }
    }

    private fun say(s: Script) {
        caption = s.spoken
        captionAt = clock()
        voice.speak(s.spoken)
    }

    private fun setNotice(kind: NoticeKind, title: String, quote: String, detail: String? = null) {
        notice = Notice(kind, title, quote, detail, clock())
    }

    private fun showCallout(kind: BadgeKind, title: String, body: String) {
        callout = Callout(kind, title, body)
        calloutAt = clock()
    }

    private fun showMessage(m: String) {
        message = m
        messageAt = clock()
        publish()
    }

    private fun distanceM(a: GeoPoint, b: GeoPoint): Double = Geo.distanceM(a, b)

    private fun buildUi(): CarUi {
        val now = clock()
        val wps = lastWaypoints
        val dest = navi.destination.value
        val stopUis = wps.map { w ->
            val s = stops.firstOrNull { matches(w, it) }
            StopUi(s?.errandId, w.name, w.remainDistanceM, w.remainTimeSec, s?.arrivedAt != null)
        } + stops.filter { s -> wps.none { matches(it, s) } }.map { s ->
            val d = dest?.takeIf { s.isDestination && it.name == s.waypointName }
            StopUi(s.errandId, s.waypointName, d?.remainDistanceM ?: 0, d?.remainTimeSec ?: 0, s.arrivedAt != null)
        }
        val list = effective().filter { it.status.isActive || (it.completedAt ?: 0) > now - TODAY_MS }
        val order = batchOrder(list).map { it.id }
        val quests = list.map { e ->
            val stop = stopUis.firstOrNull { it.errandId == e.id }
            Quest(e, stageOf(e, stop), stop, plans[e.id])
        }.sortedWith(compareBy<Quest>({ rankOf(it.stage) }, { stopUis.indexOf(it.stop) }, { order.indexOf(it.errand.id) }))
        val attention = list.any { it.status.isAwaitingAnswer && it.id !in seenIds } ||
            stops.any { it.arrivedAt != null && it.errandId !in seenArrivals }
        return CarUi(
            drive = drive.state.value,
            quests = quests,
            prompt = prompt,
            voice = voice.status.value,
            heard = voice.heard.value,
            caption = caption,
            holding = holding,
            stops = stopUis,
            destination = dest,
            naviConnected = navi.connected.value,
            homeName = home()?.name,
            attention = attention,
            callout = callout,
            justCompletedId = justCompletedId,
            message = message,
            now = now - now % 1000,
            notice = notice,
            doneItems = doneItems.filter { now - it.at < TRIP_MS },
            favorites = favorites,
        )
    }

    private fun stageOf(e: Errand, stop: StopUi?): QuestStage = when {
        e.status == ErrandStatus.COMPLETED -> QuestStage.DONE
        e.status.isTerminal -> QuestStage.CLOSED
        e.status.isAwaitingAnswer -> QuestStage.NEW
        e.status.isAwaitingRequester -> QuestStage.PROPOSED
        e.id in awaitingConfirm || e.id in pendingCompletions || stop?.arrived == true ||
            (prompt as? Prompt.Completion)?.errand?.id == e.id -> QuestStage.ARRIVED
        stop != null -> QuestStage.ON_ROUTE
        e.id == searchingFor || e.id in expectBind.values || (waitingForRoute && pendingPlace.firstOrNull() == e.id) -> QuestStage.PICKING
        else -> QuestStage.READY
    }

    private fun rankOf(s: QuestStage): Int = when (s) {
        QuestStage.ARRIVED -> 0
        QuestStage.ON_ROUTE -> 1
        QuestStage.PICKING, QuestStage.PROPOSED -> 2
        QuestStage.NEW, QuestStage.READY -> 3
        QuestStage.DONE -> 4
        QuestStage.CLOSED -> 5
    }

    private fun publish() {
        _ui.value = buildUi()
    }

    // endregion

    companion object {
        /** 음성 응답을 기다리는 기본 시간 (무응답 10초, F-05) */
        const val DEFAULT_LISTEN_MS = 10_000L
        private const val TAG = "pleose-Agent"
        private const val TICK_MS = 300L
        private const val LISTEN_MS = DEFAULT_LISTEN_MS
        /** 급하지 않은 부탁은 이 시간 동안 함께 온 부탁을 모아 한 번에 안내 */
        private const val BATCH_WINDOW_MS = 6_000L
        /** "나중에" → 이 시간 뒤 재알림 (데모 기준) */
        private const val REMIND_MS = 90_000L
        private const val CAPTION_MS = 8_000L
        private const val CALLOUT_MS = 7_000L
        private const val TODAY_MS = 12 * 60 * 60_000L
        private const val ETA_SHARE_MS = 5_000L
        private const val DEPART_DISTANCE_M = 150.0
        private const val NEXT_SEARCH_DELAY_MS = 3_500L
        /** 경유지가 이 시간 넘게 목록에서 안 보이면 빠진 것으로 본다 (경로 재계산 중 잠깐 비는 것 무시) */
        private const val MISSING_GRACE_MS = 3_000L
        private const val ROUTE_CHANGE_WAIT_MS = 10_000L
        /** 경로 중 경유지를 고르면 내비가 약 5초 미리보기 뒤 경로를 다시 잡는다 */
        private const val ROUTE_REBUILD_GRACE_MS = 15_000L
        /** Pleos 내비 경유지 최대 개수 */
        private const val MAX_WAYPOINTS = 2
        /** 한 번에 넣은 경유지를 이름으로 못 찾으면 이만큼 기다렸다가 순서대로 연결 */
        private const val EXPECT_GRACE_MS = 8_000L
        /** 넣은 곳과 이만큼 가까운 경유지는 같은 곳으로 본다 */
        private const val NEAR_M = 150.0
        /** 완료 화면에 모아 보여 주는 이번 주행 */
        private const val TRIP_MS = 3 * 60 * 60_000L
    }
}
