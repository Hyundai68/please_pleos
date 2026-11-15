package com.pleose.shared.model

import com.pleose.shared.model.ErrandStatus.ACCEPTED
import com.pleose.shared.model.ErrandStatus.CANCELLED
import com.pleose.shared.model.ErrandStatus.COMPLETED
import com.pleose.shared.model.ErrandStatus.CREATED
import com.pleose.shared.model.ErrandStatus.DECLINED
import com.pleose.shared.model.ErrandStatus.DELIVERED
import com.pleose.shared.model.ErrandStatus.EXPIRED
import com.pleose.shared.model.ErrandStatus.FAILED
import com.pleose.shared.model.ErrandStatus.IN_PROGRESS
import com.pleose.shared.model.ErrandStatus.ON_HOLD
import com.pleose.shared.model.ErrandStatus.PROPOSED

/**
 * 부탁 상태 흐름 (PRD 8).
 *
 * 전달된 부탁은 운전자의 음성 응답으로 수락·조건부 수락·보류·거절이 갈리고, 보류는 안전한 시점에 재알림된다.
 * 조건부 수락은 요청자가 확정하면 수락, 다른 방법을 찾겠다고 하면 취소된다.
 * 수락한 부탁을 귀가할 때까지 처리하지 못하면 만료되며, 요청자는 완료 전 어느 상태에서든 취소할 수 있다.
 */
object ErrandStateMachine {

    private val transitions: Map<ErrandStatus, Set<ErrandStatus>> = mapOf(
        CREATED to setOf(DELIVERED, CANCELLED),
        DELIVERED to setOf(ACCEPTED, PROPOSED, ON_HOLD, DECLINED, CANCELLED),
        ON_HOLD to setOf(DELIVERED, ACCEPTED, PROPOSED, DECLINED, CANCELLED),
        // 조건부 수락: 요청자가 [좋아요] → 수락, [다른 방법 찾을게요] → 취소. 운전자는 제안을 거둘 수 있다(거절)
        PROPOSED to setOf(ACCEPTED, DECLINED, CANCELLED, EXPIRED),
        ACCEPTED to setOf(IN_PROGRESS, COMPLETED, FAILED, EXPIRED, CANCELLED),
        // 진행 중 → 수락: 여러 건 순서가 바뀌어 다음 차례로 밀린 경우
        IN_PROGRESS to setOf(ACCEPTED, COMPLETED, FAILED, EXPIRED, CANCELLED),
    )

    fun canTransition(from: ErrandStatus, to: ErrandStatus): Boolean =
        from == to || transitions[from]?.contains(to) == true

    /** 상태를 바꾼 새 부탁을 돌려준다. 허용되지 않은 전이면 [IllegalStateException] */
    fun transition(errand: Errand, to: ErrandStatus, at: Long, note: String? = null): Errand {
        check(canTransition(errand.status, to)) { "허용되지 않은 상태 변경: ${errand.status} → $to" }
        if (errand.status == to) return errand
        return errand.copy(
            status = to,
            updatedAt = at,
            acceptedAt = if (to == ACCEPTED && errand.acceptedAt == null) at else errand.acceptedAt,
            completedAt = if (to.isTerminal) at else errand.completedAt,
            // 데려와줘 도착 예정 공유는 탑승(완료) 또는 종료 즉시 끝난다 (F-11)
            pickupShare = if (to.isTerminal) null else errand.pickupShare,
            note = note ?: errand.note,
            history = errand.history + StatusEvent(to, at, note),
        )
    }
}
