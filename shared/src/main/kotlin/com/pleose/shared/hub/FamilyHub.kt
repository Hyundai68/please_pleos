package com.pleose.shared.hub

import com.pleose.shared.api.ApiException
import com.pleose.shared.api.CreateFamilyRequest
import com.pleose.shared.api.JoinRequest
import com.pleose.shared.api.JoinResult
import com.pleose.shared.api.NewErrandRequest
import com.pleose.shared.api.NewFavoriteRequest
import com.pleose.shared.api.Snapshot
import com.pleose.shared.api.StatusUpdate
import com.pleose.shared.api.VehicleUpdate
import com.pleose.shared.interpret.ErrandInterpreter
import com.pleose.shared.model.Delivery
import com.pleose.shared.model.Errand
import com.pleose.shared.model.ErrandStateMachine
import com.pleose.shared.model.ErrandStatus
import com.pleose.shared.model.ErrandType
import com.pleose.shared.model.Family
import com.pleose.shared.model.Favorite
import com.pleose.shared.model.Member
import com.pleose.shared.model.PickupShare
import com.pleose.shared.model.Proposal
import com.pleose.shared.model.StatusEvent
import com.pleose.shared.text.Kst
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

/**
 * 가족 그룹·부탁 저장소와 서버 쪽 규칙.
 * - F-01 가족 그룹 생성·초대 코드·구성원별 차량 연결
 * - F-02 필수값 검증, F-06 부탁 해석, F-03 전달 라우팅
 * - F-10 상태 공유, F-11 데려와줘 도착 예정은 요청자에게만
 * - 조건부 수락(기획안 3.2): 운전자의 제안 → 요청자의 [좋아요]/[다른 방법 찾을게요]
 * - 반복 부탁 즐겨찾기(기획안 3.2)
 *
 * 중계 서버([com.pleose.server])와 차량 앱 단독 데모 모드가 같은 규칙을 쓰도록 공용 모듈에 둔다.
 * 수행자의 위치 좌표는 받지도 저장하지도 않는다. 좌표는 요청자가 적은 상세 주소의 것만 저장하고,
 * 그 외 장소는 이름으로 다뤄 차량이 Pleos 지도에서 찾는다.
 */
class FamilyHub(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private class FamilyState(
        val family: Family,
        val members: LinkedHashMap<String, Member> = LinkedHashMap(),
        val errands: LinkedHashMap<String, Errand> = LinkedHashMap(),
        val favorites: LinkedHashMap<String, Favorite> = LinkedHashMap(),
        var version: Long = 1,
    )

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val families = HashMap<String, FamilyState>()
    private val memberFamily = HashMap<String, String>()
    private val errandFamily = HashMap<String, String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** 가족 데이터가 바뀌면 familyId로 호출된다 */
    fun addListener(listener: (String) -> Unit) {
        listeners += listener
    }

    // region F-01 가족 그룹

    fun createFamily(req: CreateFamilyRequest): JoinResult = mutate {
        val name = req.memberName.trim()
        if (name.isEmpty()) throw ApiException(400, "이름을 입력해 주세요")
        val family = Family(newId("f"), req.familyName?.trim()?.ifEmpty { null } ?: "${name}네 가족", newInviteCode())
        val state = FamilyState(family)
        families[family.id] = state
        val member = addMember(state, name)
        family.id to JoinResult(family, member)
    }

    /** 초대 코드로 참여. 같은 이름의 구성원이 있으면 그 구성원으로 연결한다 (휴대폰과 차량을 같은 사람으로 연결) */
    fun join(req: JoinRequest): JoinResult = mutate {
        val code = req.inviteCode.trim().uppercase()
        val name = req.memberName.trim()
        if (name.isEmpty()) throw ApiException(400, "이름을 입력해 주세요")
        val state = families.values.firstOrNull { it.family.inviteCode == code }
            ?: throw ApiException(404, "초대 코드를 찾을 수 없어요")
        val member = state.members.values.firstOrNull { it.name == name } ?: addMember(state, name)
        state.family.id to JoinResult(state.family, member)
    }

    private fun addMember(state: FamilyState, name: String): Member {
        val m = Member(newId("m"), name, joinedAt = clock())
        state.members[m.id] = m
        memberFamily[m.id] = state.family.id
        return m
    }

    /** 구성원별 차량 연결과 주행 상태 (F-01, F-03 입력) */
    fun updateVehicle(memberId: String, update: VehicleUpdate): Member = mutate {
        val state = stateOfMember(memberId)
        val m = state.members.getValue(memberId).copy(vehicleLinked = update.linked, driveState = update.driveState)
        state.members[memberId] = m
        state.family.id to m
    }

    // endregion

    // region 동기화

    fun snapshot(familyId: String, viewerId: String): Snapshot = lock.withLock {
        val state = families[familyId] ?: throw ApiException(404, "가족을 찾을 수 없어요")
        if (viewerId !in state.members) throw ApiException(403, "가족 구성원이 아니에요")
        Snapshot(
            version = state.version,
            family = state.family,
            members = state.members.values.toList(),
            errands = state.errands.values.sortedByDescending { it.createdAt }.map { visibleTo(it, viewerId) },
            serverTime = clock(),
            favorites = state.favorites.values.toList(),
        )
    }

    /** [since]보다 새 버전이 생길 때까지 최대 [timeoutMs] 기다렸다가 스냅숏을 돌려준다 (롱 폴링) */
    fun awaitSnapshot(familyId: String, viewerId: String, since: Long, timeoutMs: Long): Snapshot {
        lock.withLock {
            val state = families[familyId] ?: throw ApiException(404, "가족을 찾을 수 없어요")
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (state.version <= since && remaining > 0) remaining = changed.awaitNanos(remaining)
        }
        return snapshot(familyId, viewerId)
    }

    /**
     * 공유 범위 (안전과 프라이버시 원칙):
     * - 데려와줘 도착 예정·정차 지점은 요청자에게만
     * - 집 도착 예정·들르는 장소·상세 주소는 요청자와 수행자에게만
     */
    private fun visibleTo(e: Errand, viewerId: String): Errand = when (viewerId) {
        e.fromId -> e
        e.toId -> e.copy(pickupShare = null)
        else -> e.copy(pickupShare = null, homeEtaAt = null, targetPlaceName = null, address = null, addressLocation = null)
    }

    // endregion

    // region 부탁

    /** F-02 부탁 작성·전송: 필수값 검증 → 부탁 생성(생성됨) → F-03 라우팅 */
    fun createErrand(req: NewErrandRequest): Errand = mutate {
        val state = families[req.familyId] ?: throw ApiException(404, "가족을 찾을 수 없어요")
        val from = state.members[req.fromId] ?: throw ApiException(403, "가족 구성원이 아니에요")
        val to = state.members[req.toId] ?: throw ApiException(400, "받을 사람을 선택해 주세요")
        if (from.id == to.id) throw ApiException(400, "받을 사람을 다른 가족으로 선택해 주세요")
        val text = req.text.trim()
        if (text.isEmpty()) throw ApiException(400, "무엇을 부탁할지 적어 주세요")

        val placeName = req.placeName?.trim()?.take(40)?.ifEmpty { null }
        val address = req.address?.trim()?.take(120)?.ifEmpty { null }
        val title = req.title?.trim()?.take(40)?.ifEmpty { null }
        val interp = ErrandInterpreter.interpret(text, req.type, placeName)
        val type = interp.type
        if (type.requiresPlace && placeName == null) throw ApiException(400, "${type.label}는 장소 이름을 적어 주세요")
        if (type.requiresTime && req.appointmentAt == null) throw ApiException(400, "데려올 시간을 지정해 주세요")
        if (type == ErrandType.BUY && interp.ambiguous && title == null) {
            throw ApiException(422, "무엇을 사 올지 알아듣지 못했어요. 품목을 적어 주세요 (예: 해열제, 우유 2팩)")
        }

        prune(state)
        val now = clock()
        val created = Errand(
            id = newId("e"),
            familyId = state.family.id,
            fromId = from.id, fromName = from.name,
            toId = to.id, toName = to.name,
            type = type, text = text,
            item = interp.item, title = title ?: interp.title,
            category = if (type == ErrandType.BUY) interp.category else null,
            placeName = if (type == ErrandType.BUY) null else placeName,
            address = address,
            addressLocation = req.addressLocation.takeIf { address != null },
            appointmentAt = if (type == ErrandType.PICKUP_PERSON) req.appointmentAt else null,
            person = req.person?.trim()?.ifEmpty { null } ?: interp.person,
            urgent = req.urgent,
            status = ErrandStatus.CREATED,
            createdAt = now,
            history = listOf(StatusEvent(ErrandStatus.CREATED, now)),
        )
        // F-03: 수행자가 차에 있으면(주행·정차) 차량 음성으로, 주차·미운행·차량 미연결이면 앱 푸시로 전달
        val delivery = if (to.vehicleLinked && to.driveState.inVehicle) Delivery.VEHICLE_VOICE else Delivery.APP_PUSH
        val delivered = ErrandStateMachine.transition(created, ErrandStatus.DELIVERED, now).copy(delivery = delivery)
        state.errands[delivered.id] = delivered
        errandFamily[delivered.id] = state.family.id
        state.family.id to delivered
    }

    /** 수행자의 응답·진행 상황 (F-05, F-08, F-09, F-11) */
    fun updateStatus(errandId: String, update: StatusUpdate): Errand = mutate {
        val state = stateOfErrand(errandId)
        val current = state.errands.getValue(errandId)
        if (update.actorId != current.toId) throw ApiException(403, "부탁을 받은 사람만 상태를 바꿀 수 있어요")
        if (update.status == ErrandStatus.CANCELLED) throw ApiException(400, "취소는 요청자만 할 수 있어요")
        val now = clock()

        var e = current
        val to = update.status
        if (to != null && to != e.status) {
            if (!ErrandStateMachine.canTransition(e.status, to)) {
                throw ApiException(409, "지금은 바꿀 수 없는 상태예요 (${e.status} → $to)")
            }
            if (to == ErrandStatus.PROPOSED && update.proposalEtaAt == null) {
                throw ApiException(400, "조건부 수락에는 도착 예정 시각이 필요해요")
            }
            val note = update.note ?: defaultNote(e, to, update)
            e = ErrandStateMachine.transition(e, to, now, note)
            if (to == ErrandStatus.PROPOSED) {
                e = e.copy(proposal = Proposal(update.proposalEtaAt!!, (update.proposalLateMin ?: 0).coerceAtLeast(0), now))
            }
        } else if (e.status.isTerminal) {
            throw ApiException(409, "이미 끝난 부탁이에요")
        }
        e = e.copy(
            targetPlaceName = update.targetPlaceName ?: e.targetPlaceName,
            homeEtaAt = update.homeEtaAt ?: e.homeEtaAt,
            arrivedAt = when (update.arrived) {
                true -> e.arrivedAt ?: now
                false -> null
                null -> e.arrivedAt
            },
            detourMin = update.detourMin ?: e.detourMin,
            updatedAt = now,
        )
        if (e.type == ErrandType.PICKUP_PERSON && !e.status.isTerminal && update.pickupEtaAt != null) {
            val stop = update.stopPoint ?: e.targetPlaceName?.let { "$it 앞" } ?: e.placeName ?: "정차 지점"
            e = e.copy(pickupShare = PickupShare(update.pickupEtaAt, stop, now))
        }
        state.errands[errandId] = e
        state.family.id to visibleTo(e, update.actorId)
    }

    /** 요청자는 완료 전 어느 상태에서든 취소할 수 있다 */
    fun cancel(errandId: String, actorId: String): Errand = mutate {
        val state = stateOfErrand(errandId)
        val current = state.errands.getValue(errandId)
        if (actorId != current.fromId) throw ApiException(403, "부탁한 사람만 취소할 수 있어요")
        if (current.status.isTerminal) throw ApiException(409, "이미 끝난 부탁이에요")
        val e = ErrandStateMachine.transition(current, ErrandStatus.CANCELLED, clock())
        state.errands[errandId] = e
        state.family.id to e
    }

    /**
     * 조건부 수락에 대한 요청자의 답 (기획안 4.3).
     * [좋아요] → 수락 (차량에 확정 안내와 경유지 추가), [다른 방법 찾을게요] → 자동 취소 (차량 경로는 그대로)
     */
    fun answerProposal(errandId: String, actorId: String, accept: Boolean): Errand = mutate {
        val state = stateOfErrand(errandId)
        val current = state.errands.getValue(errandId)
        if (actorId != current.fromId) throw ApiException(403, "부탁한 사람만 답할 수 있어요")
        if (current.status != ErrandStatus.PROPOSED) throw ApiException(409, "답할 제안이 없어요")
        val now = clock()
        val e = if (accept) {
            val eta = current.proposal?.etaAt
            val note = eta?.let { "${Kst.hhmm(it)} ${if (current.type == ErrandType.PICKUP_PERSON) "픽업" else "도착"}으로 확정" }
            ErrandStateMachine.transition(current, ErrandStatus.ACCEPTED, now, note)
                .copy(proposal = current.proposal?.copy(confirmedAt = now))
        } else {
            ErrandStateMachine.transition(current, ErrandStatus.CANCELLED, now, "다른 방법을 찾기로 했어요")
        }
        state.errands[errandId] = e
        state.family.id to e
    }

    // endregion

    // region 반복 부탁 즐겨찾기

    fun saveFavorite(req: NewFavoriteRequest): Favorite = mutate {
        val state = families[req.familyId] ?: throw ApiException(404, "가족을 찾을 수 없어요")
        val owner = state.members[req.ownerId] ?: throw ApiException(403, "가족 구성원이 아니에요")
        val to = state.members[req.toId] ?: throw ApiException(400, "받을 사람을 선택해 주세요")
        val title = req.title.trim().take(40)
        if (title.isEmpty()) throw ApiException(400, "이름을 적어 주세요")
        // 같은 사람에게 같은 이름이면 새로 만들지 않고 고친다
        val existing = state.favorites.values.firstOrNull { it.ownerId == owner.id && it.toId == to.id && it.title == title }
        val f = Favorite(
            id = existing?.id ?: newId("fav"),
            familyId = state.family.id,
            ownerId = owner.id, ownerName = owner.name,
            toId = to.id, toName = to.name,
            title = title, type = req.type, text = req.text.trim().ifEmpty { title },
            placeName = req.placeName?.trim()?.ifEmpty { null },
            address = req.address?.trim()?.ifEmpty { null },
            addressLocation = req.addressLocation.takeIf { !req.address.isNullOrBlank() },
            appointmentMinute = req.appointmentMinute?.takeIf { it in 0 until 24 * 60 },
            days = req.days.filter { it in 1..7 }.distinct().sorted(),
            urgent = req.urgent,
            person = req.person?.trim()?.ifEmpty { null },
            createdAt = existing?.createdAt ?: clock(),
        )
        state.favorites[f.id] = f
        state.family.id to f
    }

    fun deleteFavorite(favoriteId: String, actorId: String): Unit = mutate {
        val state = families.values.firstOrNull { favoriteId in it.favorites } ?: throw ApiException(404, "즐겨찾기를 찾을 수 없어요")
        if (actorId !in state.members) throw ApiException(403, "가족 구성원이 아니에요")
        state.favorites.remove(favoriteId)
        state.family.id to Unit
    }

    // endregion

    // region 부탁 보관

    private fun defaultNote(e: Errand, to: ErrandStatus, update: StatusUpdate): String? = when (to) {
        ErrandStatus.PROPOSED -> update.proposalEtaAt?.let { "${Kst.hhmm(it)} 도착 가능" }
        ErrandStatus.DECLINED, ErrandStatus.EXPIRED -> "오늘은 어렵대요"
        ErrandStatus.FAILED -> if (e.type == ErrandType.BUY) "못 샀대요" else "못 했대요"
        else -> null
    }

    /** 부탁 기록은 완료 후 30일 보관 후 삭제 (비기능 요구사항) */
    private fun prune(state: FamilyState) {
        val cutoff = clock() - RETENTION_MS
        state.errands.values.removeIf { it.status.isTerminal && (it.completedAt ?: it.updatedAt) < cutoff }
    }

    // endregion

    private fun stateOfMember(memberId: String): FamilyState =
        memberFamily[memberId]?.let { families[it] } ?: throw ApiException(404, "구성원을 찾을 수 없어요")

    private fun stateOfErrand(errandId: String): FamilyState =
        errandFamily[errandId]?.let { families[it] } ?: throw ApiException(404, "부탁을 찾을 수 없어요")

    /** 변경을 적용하고 버전을 올린 뒤 기다리는 쪽을 깨운다 */
    private fun <T> mutate(block: () -> Pair<String, T>): T {
        val (familyId, result) = lock.withLock {
            val r = block()
            families[r.first]?.let { it.version++ }
            changed.signalAll()
            r
        }
        listeners.forEach { runCatching { it(familyId) } }
        return result
    }

    private fun newId(prefix: String) = prefix + "_" + UUID.randomUUID().toString().replace("-", "").take(12)

    private fun newInviteCode(): String {
        while (true) {
            val code = (1..6).map { CODE_CHARS[random.nextInt(CODE_CHARS.length)] }.joinToString("")
            if (families.values.none { it.family.inviteCode == code }) return code
        }
    }

    companion object {
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
