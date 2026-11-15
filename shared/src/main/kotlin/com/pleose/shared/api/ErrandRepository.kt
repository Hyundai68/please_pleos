package com.pleose.shared.api

import com.pleose.shared.hub.FamilyHub
import com.pleose.shared.model.Errand
import com.pleose.shared.model.Favorite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class ConnectionState(val label: String) { CONNECTING("연결 중"), ONLINE("연결됨"), OFFLINE("연결 끊김") }

/** 두 앱이 가족 데이터에 접근하는 창구. 중계 서버(HTTP) 또는 같은 프로세스 안의 [FamilyHub](단독 데모) */
interface ErrandRepository {
    val familyId: String
    val memberId: String
    val snapshot: StateFlow<Snapshot?>
    val connection: StateFlow<ConnectionState>

    fun start(scope: CoroutineScope)
    fun stop()

    suspend fun createErrand(req: NewErrandRequest): Errand
    suspend fun updateStatus(errandId: String, update: StatusUpdate): Errand
    suspend fun cancel(errandId: String): Errand
    suspend fun updateVehicle(update: VehicleUpdate)

    /** 요청자: 조건부 수락 제안에 [좋아요](true) / [다른 방법 찾을게요](false) */
    suspend fun answerProposal(errandId: String, accept: Boolean): Errand
    suspend fun saveFavorite(req: NewFavoriteRequest): Favorite
    suspend fun deleteFavorite(favoriteId: String)
}

/** 중계 서버 HTTP 클라이언트 (의존성 없이 HttpURLConnection 사용 — Android·JVM 공용) */
class PleoseApi(baseUrl: String) {
    val baseUrl: String = baseUrl.trim().trimEnd('/')

    suspend fun health(): Boolean = runCatching { request("GET", "/api/health", null, 3_000) }.isSuccess

    suspend fun createFamily(req: CreateFamilyRequest): JoinResult =
        decode(request("POST", "/api/families", PleoseJson.encodeToString(CreateFamilyRequest.serializer(), req)))

    suspend fun join(req: JoinRequest): JoinResult =
        decode(request("POST", "/api/join", PleoseJson.encodeToString(JoinRequest.serializer(), req)))

    suspend fun sync(familyId: String, memberId: String, since: Long, waitMs: Long): Snapshot = decode(
        request(
            "GET",
            "/api/sync?familyId=${enc(familyId)}&memberId=${enc(memberId)}&since=$since&waitMs=$waitMs",
            null,
            (waitMs + 10_000).toInt(),
        ),
    )

    suspend fun createErrand(req: NewErrandRequest): Errand =
        decode(request("POST", "/api/errands", PleoseJson.encodeToString(NewErrandRequest.serializer(), req)))

    suspend fun updateStatus(errandId: String, update: StatusUpdate): Errand =
        decode(request("POST", "/api/errands/${enc(errandId)}/status", PleoseJson.encodeToString(StatusUpdate.serializer(), update)))

    suspend fun cancel(errandId: String, actorId: String): Errand =
        decode(request("POST", "/api/errands/${enc(errandId)}/cancel", PleoseJson.encodeToString(CancelRequest.serializer(), CancelRequest(actorId))))

    suspend fun updateVehicle(memberId: String, update: VehicleUpdate): Unit {
        request("POST", "/api/members/${enc(memberId)}/vehicle", PleoseJson.encodeToString(VehicleUpdate.serializer(), update))
    }

    suspend fun answerProposal(errandId: String, actorId: String, accept: Boolean): Errand = decode(
        request("POST", "/api/errands/${enc(errandId)}/proposal", PleoseJson.encodeToString(ProposalAnswer.serializer(), ProposalAnswer(actorId, accept))),
    )

    suspend fun saveFavorite(req: NewFavoriteRequest): Favorite =
        decode(request("POST", "/api/favorites", PleoseJson.encodeToString(NewFavoriteRequest.serializer(), req)))

    suspend fun deleteFavorite(favoriteId: String, actorId: String): Unit {
        request("POST", "/api/favorites/${enc(favoriteId)}/delete", PleoseJson.encodeToString(DeleteFavoriteRequest.serializer(), DeleteFavoriteRequest(actorId)))
    }

    private inline fun <reified T> decode(body: String): T = PleoseJson.decodeFromString(body)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun request(method: String, path: String, body: String?, readTimeoutMs: Int = 10_000): String =
        withContext(Dispatchers.IO) {
            val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 5_000
                conn.readTimeout = readTimeoutMs
                conn.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                if (code !in 200..299) {
                    val msg = runCatching { PleoseJson.decodeFromString(ApiError.serializer(), text).error }.getOrNull()
                    throw ApiException(code, msg ?: "서버 오류 ($code)")
                }
                text
            } finally {
                conn.disconnect()
            }
        }
}

/** 중계 서버와 롱 폴링으로 동기화하는 저장소 */
class RemoteErrandRepository(
    private val api: PleoseApi,
    override val familyId: String,
    override val memberId: String,
) : ErrandRepository {
    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    override val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()
    private val _connection = MutableStateFlow(ConnectionState.CONNECTING)
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    private var job: Job? = null

    override fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                try {
                    val since = _snapshot.value?.version ?: 0
                    val snap = api.sync(familyId, memberId, since, LONG_POLL_MS)
                    publish(snap)
                    _connection.value = ConnectionState.ONLINE
                    backoff = 1_000L
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 서버 꺼짐·네트워크 오류·응답 해석 실패 모두 재시도
                    _connection.value = ConnectionState.OFFLINE
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(10_000L)
                }
            }
        }
    }

    /** 응답으로 받은 최신 상태를 바로 반영해 다음 롱 폴링까지 기다리지 않게 한다 */
    private fun publish(snap: Snapshot) {
        val cur = _snapshot.value
        if (cur == null || snap.version >= cur.version) _snapshot.value = snap
    }

    private fun applyLocal(e: Errand) {
        val cur = _snapshot.value ?: return
        val list = cur.errands.toMutableList()
        val i = list.indexOfFirst { it.id == e.id }
        if (i >= 0) list[i] = e else list.add(0, e)
        _snapshot.value = cur.copy(errands = list)
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    override suspend fun createErrand(req: NewErrandRequest): Errand = api.createErrand(req).also(::applyLocal)
    override suspend fun updateStatus(errandId: String, update: StatusUpdate): Errand = api.updateStatus(errandId, update).also(::applyLocal)
    override suspend fun cancel(errandId: String): Errand = api.cancel(errandId, memberId).also(::applyLocal)
    override suspend fun updateVehicle(update: VehicleUpdate) = api.updateVehicle(memberId, update)
    override suspend fun answerProposal(errandId: String, accept: Boolean): Errand = api.answerProposal(errandId, memberId, accept).also(::applyLocal)
    override suspend fun saveFavorite(req: NewFavoriteRequest): Favorite = api.saveFavorite(req)
    override suspend fun deleteFavorite(favoriteId: String) = api.deleteFavorite(favoriteId, memberId)

    companion object {
        const val LONG_POLL_MS = 25_000L
    }
}

/** 같은 프로세스 안의 [FamilyHub]를 쓰는 저장소 — 차량 앱 단독 데모 모드 */
class LocalErrandRepository(
    val hub: FamilyHub,
    override val familyId: String,
    override val memberId: String,
) : ErrandRepository {
    private val _snapshot = MutableStateFlow<Snapshot?>(hub.snapshot(familyId, memberId))
    override val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()
    override val connection: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.ONLINE)

    init {
        hub.addListener { fid -> if (fid == familyId) _snapshot.value = hub.snapshot(familyId, memberId) }
    }

    override fun start(scope: CoroutineScope) = Unit
    override fun stop() = Unit

    override suspend fun createErrand(req: NewErrandRequest): Errand = hub.createErrand(req)
    override suspend fun updateStatus(errandId: String, update: StatusUpdate): Errand = hub.updateStatus(errandId, update)
    override suspend fun cancel(errandId: String): Errand = hub.cancel(errandId, memberId)
    override suspend fun updateVehicle(update: VehicleUpdate) {
        hub.updateVehicle(memberId, update)
    }
    override suspend fun answerProposal(errandId: String, accept: Boolean): Errand = hub.answerProposal(errandId, memberId, accept)
    override suspend fun saveFavorite(req: NewFavoriteRequest): Favorite = hub.saveFavorite(req)
    override suspend fun deleteFavorite(favoriteId: String) = hub.deleteFavorite(favoriteId, memberId)
}
