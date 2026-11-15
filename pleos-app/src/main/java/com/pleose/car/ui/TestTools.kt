package com.pleose.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleose.car.CarAppState
import com.pleose.car.CarRuntime
import com.pleose.car.agent.CarUi
import com.pleose.car.overlay.NaviForegroundWatcher
import com.pleose.shared.model.DriveState
import com.pleose.shared.text.formatMinutes
import androidx.compose.ui.platform.LocalContext

/**
 * 테스트 조작 (심사·시연용으로 release에도 표시, 패널 목록 머리의 '…').
 * 에뮬레이터는 실제로 달리지 않으므로 주행 상태·경유지 도착/출발·문 열림을 대신 만든다.
 * 경로와 경유지는 언제나 Pleos 내비의 것을 쓴다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TestTools(rt: CarRuntime, state: CarAppState, ui: CarUi) {
    val agent = state.agent ?: return
    val d = ui.drive
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)) {
        Section("주행 상태 · 지금 ${d.state.label}")
        Chips {
            Chip("차량 신호 따름", d.override == null) { rt.drive.setOverride(null) }
            Chip("주행 중", d.override == DriveState.DRIVING) { rt.drive.setOverride(DriveState.DRIVING) }
            Chip("정차", d.override == DriveState.STOPPED) { rt.drive.setOverride(DriveState.STOPPED) }
            Chip("주차", d.override == DriveState.PARKED) { rt.drive.setOverride(DriveState.PARKED) }
            Chip(if (d.caution) "주의 구간 끄기" else "주의 구간", d.caution) { rt.drive.setCaution(!d.caution) }
        }
        Note("기어 ${d.gear?.label ?: "-"} · ${d.speed.toInt()}km/h · UX 제한 ${if (d.uxRestricted) "있음" else "없음"} · ${d.momentLabel}")

        Section("경유지 (Pleos 내비 이벤트 대신)")
        Chips {
            Chip("경유지 도착") { agent.testArrive() }
            Chip(if (d.doorOpen) "문 닫기" else "문 열기") { rt.drive.onDoor(!d.doorOpen) }
            Chip("경유지 출발") { agent.testDepart() }
            Chip("목적지 도착") { agent.testArriveHome() }
        }

        if (rt.canInject) {
            Section("가족이 보내는 부탁 (단독 데모)")
            Chips {
                Chip("수진: 아이 해열제 (급해요)") { rt.injectMedicine() }
                Chip("수진: 우유도") { rt.injectMilk() }
                Chip("수진: 택배 (상세 주소)") { rt.injectAddressed() }
                Chip("태권도 픽업 + 김밥 포장") { rt.injectPickupPair() }
                Chip("어머님: 반찬 들러줘") { rt.injectSideDish() }
                Chip("수진: 방금 부탁 취소") { rt.cancelLatestFromSujin() }
            }
        } else {
            Section("가족 연결")
            Note("${state.session?.familyName} · 초대 코드 ${state.session?.inviteCode} · ${state.connection.label}")
        }

        val dest by rt.navi.destination.collectAsState()
        val wps by rt.navi.waypoints.collectAsState()
        val home by rt.navi.home.collectAsState()
        Section("Pleos 내비")
        Note(
            listOf(
                dest?.let { "목적지 ${it.name} · ${formatMinutes(it.remainTimeSec)}" } ?: "목적지 없음",
                if (wps.isEmpty()) "경유지 없음" else "경유지 " + wps.joinToString { it.name },
                "집 ${home?.name ?: "없음"}",
            ).joinToString("\n"),
        )

        val context = LocalContext.current
        Section("연동 상태")
        Note(
            listOf(
                "차량 프로퍼티 ${if (state.carApi) "연결" else "없음"}",
                "Vehicle SDK ${if (state.vehicleSdk) "연결" else "없음"}",
                "Pleos 내비 ${if (state.naviSdk) "연결" else "없음"}",
                "음성 인식 ${if (state.canListen) "사용" else "없음"}",
                "내비 표시 감지 ${if (NaviForegroundWatcher.hasAccess(context)) "사용" else "없음"}",
            ).joinToString(" · "),
        )
        val demo by rt.demo.collectAsState()
        Section("시연 · ${demo?.let { "${it.title} · ${it.phase}" } ?: "멈춤"}")
        Chips {
            rt.demoStore.all().take(6).forEach { s -> Chip(s.title, demo?.scenarioId == s.id && demo?.running == true) { rt.startDemo(s) } }
            if (demo?.running == true) Chip("시연 멈추기") { rt.stopDemo() }
            Chip("시나리오 편집") { context.startActivity(com.pleose.car.MainActivity.demoIntent(context)) }
        }
        Chips {
            Chip("처음부터") { rt.restart() }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = pleos.text, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, color = pleos.sub, fontSize = 18.sp, lineHeight = 25.sp, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun Chip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        text, color = if (selected) pleos.onPrimary else pleos.text, fontSize = 20.sp,
        modifier = Modifier.background(if (selected) pleos.primary else pleos.surface).border(1.dp, pleos.line)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
    )
}
