package com.pleose.shared.policy

import com.pleose.shared.model.DriveState
import kotlin.math.abs

/** 주행 상황 (F-04 안전 구간 판단 입력) */
data class DrivingContext(
    val state: DriveState,
    val speedKmh: Double,
    /** 다음 회전(교차로)까지 거리 — Pleos 내비 TBT에서 받는다 (모르면 MAX) */
    val distanceToTurnM: Double = Double.MAX_VALUE,
    /** 합류 구간 등 내비가 알려 준 주의 구간 */
    val cautionZone: Boolean = false,
)

/**
 * 주행 중 UX 정책 (PRD 9).
 * - 주행 중: 음성 알림·음성 응답·경유지 자동 추가만 허용, 목록·지도 상세·텍스트 입력 제한
 * - 주의 구간(합류, 교차로 직전, 고속 주행): 모든 알림 보류
 * - 정차·주차: 부탁 목록, 상세, 다른 장소 선택 허용
 */
object DrivingPolicy {
    const val INTERSECTION_HOLD_M = 150.0
    /** 고속 주행: 이 속도 이상이면 알림을 미룬다 (기획안 3.3 — 신호 대기·저속 구간·정차 때 안내) */
    const val HIGH_SPEED_KMH = 60.0
    /** 저속 구간: 이 속도 아래면 안내하기 좋은 때 */
    const val LOW_SPEED_KMH = 30.0

    fun isCautionZone(ctx: DrivingContext): Boolean =
        ctx.state == DriveState.DRIVING &&
            (ctx.cautionZone || ctx.distanceToTurnM < INTERSECTION_HOLD_M || ctx.speedKmh >= HIGH_SPEED_KMH)

    /** 지금 알림을 전해도 되는지 (주의 구간이면 안전 구간까지 보류) */
    fun canAlert(ctx: DrivingContext): Boolean = !isCautionZone(ctx)

    /** 부탁 목록·상세 화면을 보여 줘도 되는지 */
    fun canShowList(state: DriveState): Boolean = state != DriveState.DRIVING

    /** 화면 터치 입력을 받아도 되는지 */
    fun canTouch(state: DriveState): Boolean = state != DriveState.DRIVING
}

/**
 * 데려와줘 도착 예정 공유 (F-11).
 * 대상은 요청자에게만, 수락 시점부터 탑승 완료까지, 도착 예정 시각이 3분 이상 바뀔 때만 갱신한다.
 */
object PickupSharePolicy {
    const val UPDATE_THRESHOLD_MS = 3 * 60_000L

    fun shouldUpdate(lastSharedEtaAt: Long?, newEtaAt: Long): Boolean =
        lastSharedEtaAt == null || abs(newEtaAt - lastSharedEtaAt) >= UPDATE_THRESHOLD_MS
}

/** 요청자 앱의 "집 도착 예정"도 같은 기준(3분)으로만 갱신해 불필요한 갱신을 줄인다 */
object EtaSharePolicy {
    fun shouldUpdate(last: Long?, new: Long): Boolean = PickupSharePolicy.shouldUpdate(last, new)
}
