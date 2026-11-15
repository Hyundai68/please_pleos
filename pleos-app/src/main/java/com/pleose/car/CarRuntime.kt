package com.pleose.car

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.pleose.car.agent.CarAgent
import com.pleose.car.agent.QuestStage
import com.pleose.car.demo.DemoDirector
import com.pleose.car.demo.DemoPlanner
import com.pleose.car.demo.DemoProgress
import com.pleose.car.demo.DemoStore
import com.pleose.car.demo.LocalDemoFamily
import com.pleose.car.demo.RemoteDemoFamily
import com.pleose.car.drive.DriveMonitor
import com.pleose.car.overlay.PleaseOverlayService
import com.pleose.car.platform.CarSession
import com.pleose.car.platform.CarSignals
import com.pleose.car.platform.PleosNavi
import com.pleose.car.platform.SessionStore
import com.pleose.car.platform.VoiceIO
import com.pleose.shared.api.ConnectionState
import com.pleose.shared.api.CreateFamilyRequest
import com.pleose.shared.api.ErrandRepository
import com.pleose.shared.api.JoinRequest
import com.pleose.shared.api.LocalErrandRepository
import com.pleose.shared.api.NewErrandRequest
import com.pleose.shared.api.PleoseApi
import com.pleose.shared.api.RemoteErrandRepository
import com.pleose.shared.demo.DemoScenario
import com.pleose.shared.hub.FamilyHub
import com.pleose.shared.route.AddressPlanner
import com.pleose.shared.model.ErrandType
import com.pleose.shared.model.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CarAppState(
    val session: CarSession? = null,
    val agent: CarAgent? = null,
    val connecting: Boolean = false,
    val error: String? = null,
    val vehicleSdk: Boolean = false,
    val carApi: Boolean = false,
    val naviSdk: Boolean = false,
    val canListen: Boolean = false,
    val connection: ConnectionState = ConnectionState.CONNECTING,
    val lastServerUrl: String = SessionStore.DEFAULT_SERVER,
)

/** Please 패널에서 지금 보여 주는 화면 (닫혀 있으면 null) */
sealed interface PanelPage {
    data object Board : PanelPage
    data class Detail(val errandId: String) : PanelPage
    /** 완료 후 다음 퀘스트 고르기 */
    data class Done(val errandId: String) : PanelPage
    /** 지금 상황 카드 (주행 중 기본 화면, 시연 진행 화면) */
    data object Status : PanelPage
    data object Test : PanelPage
}

/**
 * 앱 전체에 하나만 있는 실행 상태: Pleos SDK, 가족 연결, 진행자([CarAgent]).
 * 화면(액티비티)이 없어도 Pleos 내비 위 버튼([PleaseOverlayService])과 음성이 돌아가도록 프로세스 범위에 둔다.
 */
class CarRuntime(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = SessionStore(app)
    val voice = VoiceIO(app)
    private val car = CarSignals(app)
    val navi = PleosNavi(app)
    val drive = DriveMonitor()

    private val _state = MutableStateFlow(CarAppState(lastServerUrl = store.lastServerUrl))
    val state: StateFlow<CarAppState> = _state.asStateFlow()

    private val _panel = MutableStateFlow<PanelPage?>(null)
    val panel: StateFlow<PanelPage?> = _panel.asStateFlow()

    private var repo: ErrandRepository? = null
    private var localHub: FamilyHub? = null
    private var demoFamily: DemoFamily? = null
    private var connJob: Job? = null
    private var platformStarted = false

    /** 시연 시나리오 (설정 화면에서 만들고 고친다) */
    val demoStore = DemoStore(app)
    private var director: DemoDirector? = null
    private var demoJob: Job? = null
    /** 시연을 위해 차 안에 시연용 가족을 만들었으면, 끝나고 원래 연결로 돌아간다 */
    private var demoReplacedSession = false
    private val _demo = MutableStateFlow<DemoProgress?>(null)
    /** 진행 중이거나 방금 끝난 시연 */
    val demo: StateFlow<DemoProgress?> = _demo.asStateFlow()

    /** 단독 데모 가족: 수진(요청자) · 지훈(이 차의 운전자) · 어머님 */
    private data class DemoFamily(val familyId: String, val sujin: String, val jihun: String, val mom: String)

    init {
        scope.launch { car.vehicleSdk.collect { c -> _state.update { it.copy(vehicleSdk = c) } } }
        scope.launch { car.carApi.collect { c -> _state.update { it.copy(carApi = c) } } }
        scope.launch { navi.connected.collect { c -> _state.update { it.copy(naviSdk = c) } } }
        scope.launch { car.gear.collect { drive.onGear(it) } }
        scope.launch { car.speedKmh.filterNotNull().collect { drive.onVehicleSpeed(it) } }
        scope.launch { car.uxRestricted.collect { drive.onUxRestricted(it) } }
        scope.launch { car.anyPassengerDoorOpen.filterNotNull().distinctUntilChanged().collect { drive.onDoor(it) } }
        val saved = store.load()
        when {
            saved != null -> startWith(saved, RemoteErrandRepository(PleoseApi(saved.serverUrl), saved.familyId, saved.memberId))
            store.demo -> startLocalDemo()
        }
    }

    /** 가족 연결과 내비 위 표시 권한이 모두 준비됐는지 */
    fun isReady(context: Context): Boolean = _state.value.session != null && Settings.canDrawOverlays(context)

    /** 권한 요청 이후 차량 SDK들을 시작한다 */
    fun startPlatform() {
        if (platformStarted) return
        platformStarted = true
        car.start()
        navi.start()
        voice.start()
        scope.launch {
            // Pleos STT 초기화는 비동기라 잠시 뒤 다시 확인
            repeat(10) {
                _state.update { it.copy(canListen = voice.canListen) }
                delay(1_000)
            }
        }
    }

    // region Please 버튼·패널

    fun onPleaseTapped() {
        val agent = _state.value.agent ?: return
        if (_panel.value != null) return closePanel()
        val ui = agent.ui.value
        agent.markSeen()
        if (ui.driving) {
            // 주행 중: 화면은 지금 상황 카드만, 내용은 음성으로
            agent.briefByVoice()
            _panel.value = PanelPage.Status
            return
        }
        // 도착한 퀘스트가 있으면 바로 완료 화면으로
        _panel.value = ui.quests.firstOrNull { it.stage == QuestStage.ARRIVED }?.let { PanelPage.Detail(it.errand.id) }
            ?: ui.justCompletedId?.let { PanelPage.Done(it) }
            ?: PanelPage.Board
    }

    fun showPanel(page: PanelPage) {
        _panel.value = page
    }

    fun closePanel() {
        if (_panel.value is PanelPage.Done) _state.value.agent?.dismissCompleted()
        _panel.value = null
    }

    fun startQuest(errandId: String) {
        _state.value.agent?.startQuest(errandId)
        _panel.value = null // Pleos 내비의 경로·검색 결과가 보이도록 닫는다
    }

    /** 취소 뒤에는 목록으로: 같은 자리를 또 눌러도 곧바로 포기(가족에게 알림)되지 않는다 */
    fun cancelQuest(errandId: String) {
        _state.value.agent?.cancelQuest(errandId)
        _panel.value = PanelPage.Board
    }

    fun confirmDone(errandId: String) {
        _state.value.agent?.confirmDone(errandId)
        _panel.value = PanelPage.Done(errandId)
    }

    fun researchRoute(errandId: String) {
        _state.value.agent?.researchRoute(errandId)
        _panel.value = null
    }

    fun routeHome() {
        _state.value.agent?.routeHome()
        closePanel()
    }

    fun readRemaining() {
        _state.value.agent?.readRemaining()
    }

    // endregion

    // region 시연

    /**
     * 시연 시작: 시나리오대로 부탁을 보내고 답하고 도착·출발까지 자동으로 진행한다.
     * 연결된 가족으로 하지 않으면 시나리오의 운전자·요청자로 차 안에 시연용 가족을 새로 만든다 (끝나면 원래 연결로 돌아간다).
     */
    fun startDemo(scenario: DemoScenario): String? {
        scenario.problems().firstOrNull()?.let { return it }
        // 연결된 가족으로 하려면 시연용 가족에서 원래 연결로 먼저 돌아간다
        stopDemo(restore = scenario.useConnectedFamily)
        startPlatform()
        val session = _state.value.session
        val family: com.pleose.car.demo.DemoFamily
        if (scenario.useConnectedFamily && session?.remote == true) {
            family = RemoteDemoFamily(PleoseApi(session.serverUrl), session.familyId, session.inviteCode)
            demoReplacedSession = false
        } else {
            val hub = FamilyHub()
            val driver = hub.createFamily(CreateFamilyRequest(scenario.driverName.trim(), "${scenario.driverName.trim()}네 가족 (시연)"))
            scenario.requesters.forEach { hub.join(JoinRequest(driver.family.inviteCode, it)) }
            val s = CarSession(false, "", driver.family.id, driver.family.name, driver.family.inviteCode, driver.member.id, driver.member.name)
            demoReplacedSession = true
            localHub = hub
            demoFamily = null
            startWith(s, LocalErrandRepository(hub, s.familyId, s.memberId))
            family = LocalDemoFamily(hub, driver.family.id, driver.family.inviteCode)
        }
        val agent = _state.value.agent ?: return "차량 앱이 준비되지 않았어요"
        val planner = DemoPlanner(System::currentTimeMillis)
        agent.planner = planner
        agent.homeOverride = scenario.home?.toPlace(com.pleose.shared.model.PlaceCategory.HOME, "demo_home")
        agent.listenMs = DEMO_LISTEN_MS
        val d = DemoDirector(
            scope, scenario, agent, drive, voice, navi, family, planner,
            showPanel = { if (_panel.value == null || _panel.value == PanelPage.Board) _panel.value = PanelPage.Status },
        )
        director = d
        demoStore.selectedId = scenario.id
        demoJob = scope.launch { d.progress.collect { _demo.value = it } }
        d.start()
        return null
    }

    /** 시연을 멈춘다. [restore]면 시연 전 연결(가족·단독 데모)로 돌아간다 */
    fun stopDemo(restore: Boolean = true) {
        director?.stop()
        director = null
        demoJob?.cancel()
        demoJob = null
        drive.simulate(null, null)
        drive.onDoor(false)
        _state.value.agent?.let { a ->
            a.planner = AddressPlanner
            a.homeOverride = null
            a.listenMs = CarAgent.DEFAULT_LISTEN_MS
        }
        _demo.value = _demo.value?.copy(running = false)
        if (restore && demoReplacedSession) {
            demoReplacedSession = false
            _panel.value = null
            val saved = store.load()
            when {
                saved != null -> startWith(saved, RemoteErrandRepository(PleoseApi(saved.serverUrl), saved.familyId, saved.memberId))
                store.demo -> startLocalDemo()
                else -> {
                    _state.value.agent?.stop()
                    repo?.stop()
                    repo = null
                    localHub = null
                    _state.update { it.copy(session = null, agent = null) }
                }
            }
        }
    }

    // endregion

    // region 연결

    fun connectRemote(serverUrl: String, inviteCode: String, name: String) {
        if (_state.value.connecting) return
        _state.update { it.copy(connecting = true, error = null) }
        scope.launch {
            val api = PleoseApi(serverUrl)
            runCatching { api.join(JoinRequest(inviteCode, name)) }
                .onSuccess { r ->
                    store.lastServerUrl = api.baseUrl
                    store.demo = false
                    val s = CarSession(true, api.baseUrl, r.family.id, r.family.name, r.family.inviteCode, r.member.id, r.member.name)
                    store.save(s)
                    startWith(s, RemoteErrandRepository(api, s.familyId, s.memberId))
                    _state.update { it.copy(connecting = false, lastServerUrl = api.baseUrl) }
                }
                .onFailure { e ->
                    _state.update { it.copy(connecting = false, error = "연결하지 못했어요: ${e.message ?: "서버를 확인해 주세요"}") }
                }
        }
    }

    /** 가족 앱 없이 차량 안에서만 도는 데모 (가족 부탁은 패널의 테스트 도구에서 보낸다) */
    fun startLocalDemo() {
        store.demo = true
        val hub = FamilyHub()
        val sujin = hub.createFamily(CreateFamilyRequest("수진", "지훈네 가족"))
        val jihun = hub.join(JoinRequest(sujin.family.inviteCode, "지훈"))
        val mom = hub.join(JoinRequest(sujin.family.inviteCode, "어머님"))
        localHub = hub
        demoFamily = DemoFamily(sujin.family.id, sujin.member.id, jihun.member.id, mom.member.id)
        val s = CarSession(false, "", sujin.family.id, sujin.family.name, sujin.family.inviteCode, jihun.member.id, "지훈")
        startWith(s, LocalErrandRepository(hub, s.familyId, s.memberId))
    }

    private fun startWith(session: CarSession, repository: ErrandRepository) {
        _state.value.agent?.stop()
        repo?.stop()
        repo = repository
        repository.start(scope)
        val agent = CarAgent(scope, repository, drive, voice, navi)
        agent.start()
        connJob?.cancel()
        connJob = scope.launch { repository.connection.collect { c -> _state.update { it.copy(connection = c) } } }
        _panel.value = null
        _state.update { it.copy(session = session, agent = agent, error = null) }
    }

    /** 처음부터 (단독 데모는 가족 부탁도 새로) */
    fun restart() {
        val s = _state.value.session ?: return
        stopDemo()
        drive.setOverride(null)
        _panel.value = null
        if (!s.remote) return startLocalDemo()
        val r = repo ?: return
        _state.value.agent?.stop()
        val agent = CarAgent(scope, r, drive, voice, navi)
        agent.start()
        _state.update { it.copy(agent = agent) }
    }

    fun disconnect() {
        stopDemo(restore = false)
        demoReplacedSession = false
        _state.value.agent?.stop()
        repo?.stop()
        repo = null
        localHub = null
        demoFamily = null
        connJob?.cancel()
        store.clear()
        _panel.value = null
        _state.update { it.copy(session = null, agent = null) }
        app.stopService(Intent(app, PleaseOverlayService::class.java))
    }

    // endregion

    // region 단독 데모: 가족이 보내는 부탁 (시나리오의 부탁들)

    val canInject: Boolean get() = localHub != null

    private fun inject(
        from: (DemoFamily) -> String,
        text: String,
        type: ErrandType? = null,
        placeName: String? = null,
        appt: Long? = null,
        urgent: Boolean = false,
        address: String? = null,
        addressLocation: GeoPoint? = null,
    ) {
        val hub = localHub ?: return
        val f = demoFamily ?: return
        runCatching { hub.createErrand(NewErrandRequest(f.familyId, from(f), f.jihun, type, text, placeName, appt, urgent, address, addressLocation)) }
    }

    /** 시나리오 1: 수진 → "아이 해열제(시럽형)" (급해요) */
    fun injectMedicine() = inject({ it.sujin }, "아이가 열이 나서 해열제 좀 사 와줘. 시럽형으로!", urgent = true)

    /** 시나리오 1: 가는 동안 추가 부탁 "우유도" */
    fun injectMilk() = inject({ it.sujin }, "우유 2팩도 사 와줘")

    /** 상세 주소가 있는 부탁: 검색 없이 Pleos 내비 경로에 바로 들어간다 */
    fun injectAddressed() = inject(
        { it.sujin }, "편의점에 온 택배 좀 찾아와줘", ErrandType.PICKUP_ITEM, "GS25 판교지식센터점",
        address = "경기도 성남시 수정구 창업로 42", addressLocation = GeoPoint(37.41210277, 127.09516111),
    )

    /** 시나리오 2: 태권도 끝난 아이 데려와줘 + 포장 주문 받아 와줘 (함께 보내 순서 안내) */
    fun injectPickupPair() {
        val now = System.currentTimeMillis()
        val appt = (now + 15 * 60_000L).let { it - it % (5 * 60_000L) }
        inject({ it.sujin }, "아이 태권도 끝나면 데려와줘", ErrandType.PICKUP_PERSON, "태권도", appt)
        inject({ it.sujin }, "저녁 포장 주문해뒀어, 받아 와줘", ErrandType.PICKUP_ITEM, "김밥")
    }

    /** 들러줘: 어머님 → 반찬 */
    fun injectSideDish() = inject({ it.mom }, "반찬 챙겨뒀으니 들러서 가져가", ErrandType.DROP_BY, "부모님 댁")

    /** 수진이 방금 보낸 부탁을 취소 */
    fun cancelLatestFromSujin() {
        val hub = localHub ?: return
        val f = demoFamily ?: return
        val e = hub.snapshot(f.familyId, f.sujin).errands.firstOrNull { it.fromId == f.sujin && it.status.isActive } ?: return
        runCatching { hub.cancel(e.id, f.sujin) }
    }

    // endregion

    private companion object {
        const val DEMO_LISTEN_MS = 10 * 60_000L
    }
}
