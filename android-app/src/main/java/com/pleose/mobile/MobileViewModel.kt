package com.pleose.mobile

import android.app.Application
import android.location.Geocoder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pleose.shared.api.ConnectionState
import com.pleose.shared.api.CreateFamilyRequest
import com.pleose.shared.api.JoinRequest
import com.pleose.shared.api.JoinResult
import com.pleose.shared.api.NewErrandRequest
import com.pleose.shared.api.PleoseApi
import com.pleose.shared.api.Snapshot
import com.pleose.shared.api.StatusUpdate
import com.pleose.shared.api.toFavoriteRequest
import com.pleose.shared.api.toRequest
import com.pleose.shared.model.Errand
import com.pleose.shared.model.Favorite
import com.pleose.shared.text.Kst
import com.pleose.shared.model.ErrandStatus
import com.pleose.shared.model.ErrandType
import com.pleose.shared.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

data class MobileUi(
    val session: MobileSession? = null,
    val snapshot: Snapshot? = null,
    val connection: ConnectionState = ConnectionState.CONNECTING,
)

/** 부탁 작성 화면 입력값 (F-02) */
data class Draft(
    val toId: String? = null,
    /** null이면 문장에서 자동 분류 */
    val type: ErrandType? = null,
    val text: String = "",
    /** 장소 이름 — 차량에서 Pleos 지도로 찾는다 */
    val placeName: String = "",
    val appointmentAt: Long? = null,
    val urgent: Boolean = false,
    /** 상세 주소 (선택) — 있으면 차에서 근처 검색 대신 이 주소로 안내한다 */
    val address: String = "",
    /** 보내면서 자주 하는 부탁으로도 저장 */
    val saveFavorite: Boolean = false,
)

class MobileViewModel(app: Application) : AndroidViewModel(app) {
    private val mobile = app as MobileApp

    val ui: StateFlow<MobileUi> = combine(mobile.session, mobile.snapshot, mobile.connection) { s, snap, c -> MobileUi(s, snap, c) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MobileUi(mobile.session.value, mobile.snapshot.value, mobile.connection.value))

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    val lastServerUrl: String get() = mobile.lastServerUrl

    fun clearError() {
        _error.value = null
    }

    fun consumeToast() {
        _toast.value = null
    }

    // region F-01 가족 그룹

    fun createFamily(server: String, name: String) = onboard(server) { api -> api.createFamily(CreateFamilyRequest(name)) }

    fun joinFamily(server: String, code: String, name: String) = onboard(server) { api -> api.join(JoinRequest(code, name)) }

    private fun onboard(server: String, call: suspend (PleoseApi) -> JoinResult) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            val api = PleoseApi(server)
            runCatching { call(api) }
                .onSuccess { r ->
                    mobile.lastServerUrl = api.baseUrl
                    mobile.connect(MobileSession(api.baseUrl, r.family.id, r.family.name, r.family.inviteCode, r.member.id, r.member.name))
                }
                .onFailure { _error.value = it.message ?: "서버에 연결하지 못했어요" }
            _busy.value = false
        }
    }

    fun logout() = mobile.logout()

    // endregion

    // region F-02 부탁 작성·전송

    fun send(draft: Draft, onSent: () -> Unit) {
        val s = mobile.session.value ?: return
        val repo = mobile.repo ?: return
        val to = draft.toId ?: return
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            val address = draft.address.trim().ifEmpty { null }
            runCatching {
                repo.createErrand(
                    NewErrandRequest(
                        s.familyId, s.memberId, to, draft.type, draft.text.trim(),
                        draft.placeName.trim().ifEmpty { null }, draft.appointmentAt, draft.urgent,
                        address, address?.let { locate(it) },
                    ),
                )
            }.onSuccess { e ->
                _toast.value = "${e.toName}님께 부탁을 보냈어요"
                if (draft.saveFavorite) saveFavoriteNow(e)
                onSent()
            }.onFailure { _error.value = it.message ?: "부탁을 보내지 못했어요" }
            _busy.value = false
        }
    }

    // endregion

    // region 조건부 수락 확정 (기획안 4.3)

    /** [좋아요] → 차량에 확정 안내와 경유지 추가, [다른 방법 찾을게요] → 자동 취소 (차량 경로는 그대로) */
    fun answerProposal(errandId: String, accept: Boolean) {
        val repo = mobile.repo ?: return
        viewModelScope.launch {
            runCatching { repo.answerProposal(errandId, accept) }
                .onSuccess { _toast.value = if (accept) "확정했어요. 차량에서 바로 안내해요" else "부탁을 취소했어요. 차량 경로는 그대로예요" }
                .onFailure { _toast.value = it.message ?: "답하지 못했어요" }
        }
    }

    // endregion

    // region 반복 부탁 즐겨찾기

    /** 원터치로 보내기: 약속 시각은 오늘 그 시각 */
    fun sendFavorite(f: Favorite) {
        val s = mobile.session.value ?: return
        val repo = mobile.repo ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            runCatching { repo.createErrand(f.toRequest(s.memberId) { m -> Kst.todayAt(m) }) }
                .onSuccess { e -> _toast.value = "${e.toName}님께 '${f.title}'을 보냈어요" }
                .onFailure { _toast.value = it.message ?: "보내지 못했어요" }
            _busy.value = false
        }
    }

    /** 끝난 부탁을 자주 하는 부탁으로 저장 (오늘 요일로 반복) */
    fun saveFavorite(e: Errand) {
        viewModelScope.launch { saveFavoriteNow(e) }
    }

    private suspend fun saveFavoriteNow(e: Errand) {
        val repo = mobile.repo ?: return
        val days = listOf(Kst.dayOfWeek(System.currentTimeMillis()))
        runCatching { repo.saveFavorite(e.toFavoriteRequest(days) { Kst.minuteOfDay(it) }) }
            .onSuccess { f -> _toast.value = "'${f.title}'을 즐겨찾기에 저장했어요" }
            .onFailure { _toast.value = it.message ?: "저장하지 못했어요" }
    }

    fun deleteFavorite(id: String) {
        val repo = mobile.repo ?: return
        viewModelScope.launch {
            runCatching { repo.deleteFavorite(id) }
                .onSuccess { _toast.value = "즐겨찾기에서 지웠어요" }
                .onFailure { _toast.value = it.message ?: "지우지 못했어요" }
        }
    }

    /**
     * 상세 주소를 좌표로 바꾼다. 차량에는 주소 검색 API가 없어 좌표가 있어야 검색 없이 바로 안내할 수 있다.
     * 바꾸지 못해도 부탁은 그대로 보낸다: 차에서 이 주소로 Pleos 지도를 검색한다.
     */
    private suspend fun locate(address: String): GeoPoint? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(getApplication(), Locale.KOREA).getFromLocationName(address, 1)?.firstOrNull()
                    ?.let { GeoPoint(it.latitude, it.longitude) }
            }.getOrNull()
        }
    }

    /** 요청자는 완료 전 어느 상태에서든 취소할 수 있다 */
    fun cancel(errandId: String) {
        val repo = mobile.repo ?: return
        viewModelScope.launch {
            runCatching { repo.cancel(errandId) }
                .onSuccess { _toast.value = "부탁을 취소했어요" }
                .onFailure { _toast.value = it.message ?: "취소하지 못했어요" }
        }
    }

    /** 받은 부탁 (차가 아닐 때 휴대폰으로 받은 경우): 오늘은 못 가요 */
    fun declineReceived(errandId: String) {
        val repo = mobile.repo ?: return
        val me = mobile.session.value?.memberId ?: return
        viewModelScope.launch {
            runCatching { repo.updateStatus(errandId, StatusUpdate(me, ErrandStatus.DECLINED)) }
                .onSuccess { _toast.value = "오늘은 어렵다고 전했어요" }
                .onFailure { _toast.value = it.message ?: "전하지 못했어요" }
        }
    }

    // endregion

    private companion object {
        const val GEOCODE_TIMEOUT_MS = 3_000L
    }
}
