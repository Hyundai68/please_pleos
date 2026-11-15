package com.pleose.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleose.car.demo.DemoProgress
import com.pleose.car.demo.DemoStore
import com.pleose.shared.demo.DemoErrand
import com.pleose.shared.demo.DemoOutcome
import com.pleose.shared.demo.DemoPlace
import com.pleose.shared.demo.DemoPresets
import com.pleose.shared.demo.DemoScenario
import com.pleose.shared.demo.DemoStep
import com.pleose.shared.demo.DriverReply
import com.pleose.shared.demo.RequesterReply
import com.pleose.shared.model.ErrandType

interface DemoActions {
    /** 시연을 시작하고 Pleos 내비로 넘어간다. 시작하지 못하면 이유를 돌려준다 */
    fun start(s: DemoScenario): String?
    fun stop()
    fun back()
}

/**
 * 시연 시나리오 화면 (설정의 '시연 시나리오').
 * 왼쪽에서 시나리오를 고르고, 오른쪽에서 어디로 가는지·무슨 부탁을 보내는지·몇 초 뒤 답하고 도착·완료하는지 모두 정한다.
 */
@Composable
fun DemoScreen(store: DemoStore, progress: DemoProgress?, a: DemoActions) {
    val custom by store.custom.collectAsState()
    var selectedId by remember { mutableStateOf(store.selectedId) }
    val selected = store.find(selectedId) ?: DemoPresets.scenarioA
    var draft by remember(selectedId, custom) { mutableStateOf(selected) }
    var message by remember { mutableStateOf<String?>(null) }
    var showJson by remember { mutableStateOf(false) }
    val dirty = draft != selected
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxSize().background(pleos.surface)) {
        Row(Modifier.fillMaxWidth().height(PleosSize.headerCell).background(PleosStatic.header), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(PleosSize.headerCell).clickable(onClick = a::back), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.BACK, PleosStatic.onHeader, 30.dp) }
            Text("시연 시나리오", color = PleosStatic.onHeader, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(24.dp))
            Text(
                progress?.let { p -> "${if (p.running) "시연 중" else "시연 끝"} · ${p.title} · ${if (p.step >= 0) "${p.step + 1}/${p.stepCount}단계 · " else ""}${p.phase}${p.error?.let { " · $it" } ?: ""}" } ?: "",
                color = if (progress?.error != null) PleosStatic.red else PleosStatic.headerSub, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 16.dp),
            )
            progress?.let { p ->
                if (p.running) {
                    Box(Modifier.height(PleosSize.headerCell).background(PleosStatic.red).clickable(onClick = a::stop).padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
                        Text("시연 멈추기", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // 시나리오 목록
            Column(Modifier.width(520.dp).fillMaxHeight()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    if (custom.isNotEmpty()) ListLabel("내 시나리오")
                    custom.forEach { s -> ScenarioRow(s, s.id == selectedId) { selectedId = s.id; store.selectedId = s.id; message = null } }
                    ListLabel("기본 시나리오 (기획안)")
                    store.presets.forEach { s -> ScenarioRow(s, s.id == selectedId) { selectedId = s.id; store.selectedId = s.id; message = null } }
                }
                Line()
                Row(Modifier.fillMaxWidth().height(PleosSize.cell)) {
                    Cell("새 시나리오", Modifier.weight(1f)) { val s = store.newBlank(); selectedId = s.id; store.selectedId = s.id }
                    VLineD()
                    Cell("파일에서 불러오기", Modifier.weight(1f)) {
                        message = store.importFiles().fold(
                            { n -> if (n == 0) "가져올 JSON이 없어요: ${store.importDir?.absolutePath}" else "${n}개를 가져왔어요" },
                            { "가져오지 못했어요: ${it.message}" },
                        )
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(pleos.line))
            // 편집
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp)) {
                    if (store.isPreset(draft.id)) Note("기본 시나리오예요. 고쳐서 저장하면 '내 시나리오'에 사본으로 저장돼요.")
                    message?.let { Text(it, color = PleosStatic.blue, fontSize = 20.sp, modifier = Modifier.padding(bottom = 10.dp)) }
                    ScenarioEditor(draft) { draft = it }
                    val problems = draft.problems()
                    if (problems.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        problems.forEach { Text("· $it", color = PleosStatic.red, fontSize = 20.sp) }
                    }
                    if (showJson) {
                        Spacer(Modifier.height(20.dp))
                        JsonBox(store.exportJson(draft), onApply = { text ->
                            message = runCatching { DemoScenario.fromJson(text).first() }.fold(
                                { s -> draft = s.copy(id = draft.id); "JSON을 적용했어요 (저장을 눌러야 남아요)" },
                                { "JSON을 읽지 못했어요: ${it.message}" },
                            )
                        }, onCopy = { clipboard.setText(AnnotatedString(it)); message = "JSON을 복사했어요" })
                    }
                    Spacer(Modifier.height(40.dp))
                }
                Line()
                Row(Modifier.fillMaxWidth().height(PleosSize.cell)) {
                    Cell(if (dirty) "저장" else "저장됨", Modifier.weight(1f), enabled = dirty) {
                        val saved = store.save(draft)
                        selectedId = saved.id
                        store.selectedId = saved.id
                        message = "저장했어요"
                    }
                    VLineD()
                    Cell("복제", Modifier.weight(1f)) { val c = store.duplicate(draft); selectedId = c.id; store.selectedId = c.id }
                    VLineD()
                    Cell("삭제", Modifier.weight(1f), enabled = !store.isPreset(draft.id)) { store.delete(draft.id); selectedId = store.selectedId }
                    VLineD()
                    Cell(if (showJson) "JSON 닫기" else "JSON", Modifier.weight(1f)) { showJson = !showJson }
                    VLineD()
                    Box(
                        Modifier.weight(1.6f).fillMaxHeight().background(if (draft.problems().isEmpty()) pleos.primary else pleos.surface2)
                            .clickable(enabled = draft.problems().isEmpty()) {
                                if (!store.isPreset(draft.id) && dirty) store.save(draft)
                                message = a.start(draft)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlyphIcon(Glyph.PLAY, if (draft.problems().isEmpty()) pleos.onPrimary else pleos.dim, 26.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("시연 시작 · 약 ${draft.estimatedSec / 60}분 ${draft.estimatedSec % 60}초", color = if (draft.problems().isEmpty()) pleos.onPrimary else pleos.dim, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListLabel(text: String) {
    Text(text, color = pleos.sub, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 22.dp, top = 18.dp, bottom = 8.dp))
}

@Composable
private fun ScenarioRow(s: DemoScenario, selected: Boolean, onClick: () -> Unit) {
    Line()
    Row(
        Modifier.fillMaxWidth().background(if (selected) pleos.surface2 else pleos.surface).clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).height(56.dp).background(if (selected) pleos.primary else Color.Transparent))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, color = pleos.text, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (s.description.isNotBlank()) Text(s.description, color = pleos.sub, fontSize = 17.sp, lineHeight = 23.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "운전자 ${s.driverName} · 요청자 ${s.requesters.joinToString()} · ${s.steps.size}단계 · 약 ${s.estimatedSec / 60}분",
                color = pleos.dim, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// region 편집

@Composable
private fun ScenarioEditor(s: DemoScenario, onChange: (DemoScenario) -> Unit) {
    Section("시나리오")
    TextRow("이름", s.title) { onChange(s.copy(title = it)) }
    TextRow("설명", s.description) { onChange(s.copy(description = it)) }
    Fields(
        Field { m -> TextRow("운전자 (이 차)", s.driverName, m) { onChange(s.copy(driverName = it)) } },
        Field { m -> NumRow("시작 전 대기 (녹화 준비)", s.startDelaySec, "초", m) { onChange(s.copy(startDelaySec = it)) } },
        Field { m -> NumRow("평소 속도", s.cruiseKmh, "km/h", m, max = 140) { onChange(s.copy(cruiseKmh = it)) } },
    )
    Toggles {
        Toggle("진행 화면 띄우기", s.showPanel) { onChange(s.copy(showPanel = it)) }
        Toggle("시작할 때 집 경로 잡기", s.routeHomeAtStart) { onChange(s.copy(routeHomeAtStart = it)) }
        Toggle("끝나면 처음부터 반복", s.loop) { onChange(s.copy(loop = it)) }
        Toggle("연결된 가족으로 시연 (요청자 앱 함께)", s.useConnectedFamily) { onChange(s.copy(useConnectedFamily = it)) }
    }
    if (s.useConnectedFamily) Note("요청자 이름을 요청자 앱 가족 이름과 똑같이 적어 주세요. 연결이 없으면 차 안 시연용 가족으로 진행해요.")
    SubLabel("최종 목적지 (집)")
    PlaceEditor("집", s.home, allowDetour = false, onChange = { onChange(s.copy(home = it)) })
    OptNumRow("마지막 단계 뒤 집 도착", s.arriveHomeAfterSec, "초 뒤") { onChange(s.copy(arriveHomeAfterSec = it)) }

    s.steps.forEachIndexed { i, step ->
        StepEditor(
            i, step, s.steps.size,
            onChange = { n -> onChange(s.copy(steps = s.steps.toMutableList().also { it[i] = n })) },
            onMove = { d ->
                val j = i + d
                if (j in s.steps.indices) onChange(s.copy(steps = s.steps.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }))
            },
            onDelete = { onChange(s.copy(steps = s.steps.filterIndexed { k, _ -> k != i })) },
        )
    }
    Spacer(Modifier.height(16.dp))
    OutlineButton("+ 단계 추가") {
        val from = s.requesters.firstOrNull() ?: "지은"
        onChange(s.copy(steps = s.steps + DemoStep(title = "${s.steps.size + 1}단계", errands = listOf(DemoPresets.newErrand(from)))))
    }
}

@Composable
private fun StepEditor(i: Int, step: DemoStep, count: Int, onChange: (DemoStep) -> Unit, onMove: (Int) -> Unit, onDelete: () -> Unit) {
    Spacer(Modifier.height(26.dp))
    Column(Modifier.fillMaxWidth().border(2.dp, pleos.text)) {
        Row(Modifier.fillMaxWidth().background(pleos.primary).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}단계", color = pleos.onPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            Text(step.title, color = pleos.onPrimary, fontSize = 22.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (i > 0) SmallButton("위로", pleos.onPrimary) { onMove(-1) }
            if (i < count - 1) SmallButton("아래로", pleos.onPrimary) { onMove(1) }
            SmallButton("삭제", pleos.onPrimary) { onDelete() }
        }
        Column(Modifier.padding(18.dp)) {
            TextRow("단계 이름", step.title) { onChange(step.copy(title = it)) }
            SubLabel("부탁 보내기")
            Fields(
                Field { m -> NumRow("앞 단계 끝나고 보내기까지", step.delaySec, "초", m) { onChange(step.copy(delaySec = it)) } },
                Field { m -> NumRow("고속 주행 보류", step.holdSec, "초", m) { onChange(step.copy(holdSec = it)) } },
            )
            Fields(
                Field { m -> NumRow("보류 중 속도", step.holdKmh, "km/h", m, max = 140) { onChange(step.copy(holdKmh = it)) } },
                Field { m -> NumRow("안내 순간 속도 (0 = 신호 대기)", step.safeKmh, "km/h", m, max = 59) { onChange(step.copy(safeKmh = it)) } },
            )
            Note("고속 주행 보류가 0이 아니면 그동안 시속 ${step.holdKmh}km로 달려 안내를 미루고, 안내 순간 속도(0 = 신호 대기)가 되면 음성으로 안내해요.")
            SubLabel("운전자 답")
            Choices(DriverReply.entries, step.driverReply, { it.label }) { onChange(step.copy(driverReply = it)) }
            if (step.driverReply != DriverReply.MANUAL) NumRow("음성 안내 끝나고 답하기까지", step.replyAfterSec, "초", Modifier.width(FIELD_MIN)) { onChange(step.copy(replyAfterSec = it)) }
            if (step.driverReply == DriverReply.CONDITIONAL) {
                SubLabel("요청자 답 (조건부 수락)")
                Choices(RequesterReply.entries, step.requesterReply, { it.label }) { onChange(step.copy(requesterReply = it)) }
                if (step.requesterReply != RequesterReply.MANUAL) NumRow("제안 보내고 답하기까지", step.requesterReplyAfterSec, "초", Modifier.width(FIELD_MIN)) { onChange(step.copy(requesterReplyAfterSec = it)) }
                Note("조건부 수락은 데려와줘처럼 약속 시각이 있고, 도착 예정이 약속보다 늦을 때만 쓸 수 있어요.")
            }
            step.errands.forEachIndexed { j, e ->
                ErrandEditor(
                    j, e,
                    onChange = { n -> onChange(step.copy(errands = step.errands.toMutableList().also { it[j] = n })) },
                    onDelete = { onChange(step.copy(errands = step.errands.filterIndexed { k, _ -> k != j })) },
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlineButton("+ 함께 보낼 부탁 추가") {
                onChange(step.copy(errands = step.errands + DemoPresets.newErrand(step.errands.firstOrNull()?.from ?: "지은")))
            }
        }
    }
}

@Composable
private fun ErrandEditor(j: Int, e: DemoErrand, onChange: (DemoErrand) -> Unit, onDelete: () -> Unit) {
    Spacer(Modifier.height(18.dp))
    Column(Modifier.fillMaxWidth().background(pleos.surface2).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("부탁 ${j + 1} · ${e.title.ifBlank { e.text }.ifBlank { "내용 없음" }}", color = pleos.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            SmallButton("삭제", pleos.text, onClick = onDelete)
        }
        Spacer(Modifier.height(8.dp))
        Choices(ErrandType.entries, e.type, { it.label }) { onChange(e.copy(type = it)) }
        Fields(
            Field { m -> TextRow("요청자", e.from, m) { onChange(e.copy(from = it)) } },
            Field(2f) { m -> TextRow("제목 (화면·음성)", e.title, m) { onChange(e.copy(title = it)) } },
            *(if (e.type == ErrandType.PICKUP_PERSON) arrayOf(Field { m -> TextRow("데려올 사람", e.person, m) { onChange(e.copy(person = it)) } }) else emptyArray()),
        )
        TextRow("요청자가 쓴 문장", e.text) { onChange(e.copy(text = it)) }
        Toggles { Toggle("급해요", e.urgent) { onChange(e.copy(urgent = it)) } }

        SubLabel(if (e.type == ErrandType.BUY) "들를 곳 (비우면 아래 경로상 후보 중 첫 곳)" else "들를 곳")
        PlaceEditor("들를 곳", e.place, allowDetour = true, optional = e.type == ErrandType.BUY, onChange = { onChange(e.copy(place = it)) })
        if (e.type == ErrandType.BUY) {
            SubLabel("장소 미정 · 경로상 후보 (추가 시간 순)")
            e.candidates.forEachIndexed { k, c ->
                PlaceEditor("후보 ${k + 1}", c, allowDetour = true, onChange = { n -> onChange(e.copy(candidates = e.candidates.toMutableList().also { l -> if (n == null) l.removeAt(k) else l[k] = n })) })
                Spacer(Modifier.height(10.dp))
            }
            OutlineButton("+ 후보 추가") {
                onChange(e.copy(candidates = e.candidates + DemoPlace("새 매장", 37.3762, 127.1118, detourMin = 3 + e.candidates.size * 3)))
            }
        }

        SubLabel("시간")
        Fields(
            Field { m -> OptNumRow("약속 시각 (보낸 때부터)", e.appointmentInMin, "분 뒤", m) { onChange(e.copy(appointmentInMin = it)) } },
            Field { m -> OptNumRow("지금 경로로 도착", e.etaMin, "분 뒤", m) { onChange(e.copy(etaMin = it)) } },
        )
        e.appointmentInMin?.let { appt ->
            e.etaMin?.let { eta -> Note(if (eta > appt) "약속보다 ${eta - appt}분 늦어요 → 조건부 수락을 쓸 수 있어요" else "약속보다 ${appt - eta}분 일찍 도착해요") }
        }
        Fields(
            Field { m -> NumRow("수락 뒤 도착까지", e.driveSec, "초", m) { onChange(e.copy(driveSec = it)) } },
            Field { m -> NumRow("도착 뒤 출발까지 (출발하면 자동 완료)", e.stopSec, "초", m) { onChange(e.copy(stopSec = it)) } },
        )
        SubLabel("결과")
        Choices(DemoOutcome.entries, e.outcome, { it.label }) { onChange(e.copy(outcome = it)) }
    }
}

/**
 * 장소 하나를 테두리 카드로: 머리줄(이름·빼기 버튼) → 이름 → 위도·경도·추가 시간 → 주소.
 * 버튼은 머리줄에만 두고, 입력칸은 줄마다 나눠 좁아지면 위아래로 쌓는다. [optional]이면 빼서 비울 수 있다
 */
@Composable
private fun PlaceEditor(title: String, p: DemoPlace?, allowDetour: Boolean, optional: Boolean = true, onChange: (DemoPlace?) -> Unit) {
    if (p == null) {
        OutlineButton("+ $title 정하기") { onChange(DemoPlace("새 장소", 37.3851, 127.1123)) }
        return
    }
    Column(Modifier.fillMaxWidth().background(pleos.surface).border(1.dp, pleos.line)) {
        Row(Modifier.fillMaxWidth().background(pleos.surface2).padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$title · ${p.name}", color = pleos.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (optional) SmallButton("빼기", pleos.text) { onChange(null) }
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            TextRow("이름 (내비 경유지 이름)", p.name) { onChange(p.copy(name = it)) }
            Fields(
                Field { m -> DecimalRow("위도", p.lat, m) { onChange(p.copy(lat = it)) } },
                Field { m -> DecimalRow("경도", p.lon, m) { onChange(p.copy(lon = it)) } },
                *(if (allowDetour) arrayOf(Field { m -> OptNumRow("들르면 추가", p.detourMin, "분", m) { onChange(p.copy(detourMin = it)) } }) else emptyArray()),
            )
            TextRow("주소", p.address) { onChange(p.copy(address = it)) }
        }
    }
}

// endregion

// region 입력 조각

@Composable
private fun Section(text: String) {
    Text(text, color = pleos.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
}

@Composable
private fun SubLabel(text: String) {
    Text(text, color = pleos.text, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, color = pleos.sub, fontSize = 18.sp, lineHeight = 25.sp, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun Line() = Box(Modifier.fillMaxWidth().height(1.dp).background(pleos.line))

@Composable
private fun VLineD() = Box(Modifier.width(1.dp).fillMaxHeight().background(pleos.line))

@Composable
private fun Cell(text: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight().clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) pleos.text else pleos.dim, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun OutlineButton(text: String, onClick: () -> Unit) {
    Box(Modifier.border(1.dp, pleos.line).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(text, color = pleos.text, fontSize = 20.sp)
    }
}

@Composable
private fun SmallButton(text: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.padding(start = 8.dp).border(1.dp, color).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, color = color, fontSize = 18.sp)
    }
}

/** 한 줄에 놓을 입력칸 하나 ([weight]만큼 너비를 나눈다) */
private class Field(val weight: Float = 1f, val content: @Composable (Modifier) -> Unit)

/** 입력칸 하나가 이보다 좁아지면 한 줄에 두지 않고 위아래로 쌓는다 */
private val FIELD_MIN = 260.dp

/**
 * 입력칸 한 줄: 칸마다 [FIELD_MIN] 이상 들어가면 가로로 나란히, 아니면 세로로 쌓는다.
 * 칸이 좁아져 −/+ 버튼과 글자가 옆 칸을 덮는 일이 없게 한다.
 */
@Composable
private fun Fields(vararg items: Field) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 14.dp
        val total = items.sumOf { it.weight.toDouble() }.toFloat()
        val fits = items.all { maxWidth * (it.weight / total) - gap >= FIELD_MIN }
        if (fits) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top) {
                items.forEach { f -> f.content(Modifier.weight(f.weight)) }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                items.forEach { f -> f.content(Modifier.fillMaxWidth()) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Toggles(content: @Composable () -> Unit) {
    FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@Composable
private fun Toggle(text: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.border(1.dp, if (on) pleos.primary else pleos.line).background(if (on) pleos.primary else pleos.surface)
            .clickable { onChange(!on) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (on) {
            GlyphIcon(Glyph.CHECK, pleos.onPrimary, 22.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = if (on) pleos.onPrimary else pleos.text, fontSize = 19.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val on = o == selected
            Text(
                label(o), color = if (on) pleos.onPrimary else pleos.text, fontSize = 20.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.background(if (on) pleos.primary else pleos.surface).border(1.dp, pleos.line).clickable { onPick(o) }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = pleos.sub, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun TextRow(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    Column(modifier.padding(vertical = 6.dp)) {
        FieldLabel(label)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = pleos.text, fontSize = 21.sp),
            cursorBrush = SolidColor(pleos.text),
            modifier = Modifier.fillMaxWidth().background(pleos.surface).border(1.dp, pleos.line).padding(horizontal = 14.dp, vertical = 13.dp),
        )
    }
}

/** 정수: −/+ 버튼과 직접 입력 */
@Composable
private fun NumRow(
    label: String,
    value: Int,
    suffix: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    min: Int = 0,
    max: Int = 3600,
    clear: (() -> Unit)? = null,
    onChange: (Int) -> Unit,
) {
    Column(modifier.padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { FieldLabel(label) }
            clear?.let { Text("없음으로", color = pleos.sub, fontSize = 17.sp, modifier = Modifier.padding(bottom = 4.dp).clickable(onClick = it).padding(horizontal = 6.dp)) }
        }
        Row(Modifier.fillMaxWidth().height(56.dp).border(1.dp, pleos.line), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(52.dp).fillMaxHeight().background(pleos.surface2).clickable { onChange((value - 1).coerceIn(min, max)) }, contentAlignment = Alignment.Center) {
                Text("−", color = pleos.text, fontSize = 26.sp)
            }
            var text by remember(value) { mutableStateOf(value.toString()) }
            BasicTextField(
                text, { t -> text = t.filter(Char::isDigit).take(5); text.toIntOrNull()?.let { onChange(it.coerceIn(min, max)) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = TextStyle(color = pleos.text, fontSize = 22.sp, fontWeight = FontWeight.Bold),
                cursorBrush = SolidColor(pleos.text),
                modifier = Modifier.weight(1f).padding(start = 14.dp),
            )
            Text(suffix, color = pleos.sub, fontSize = 18.sp, modifier = Modifier.padding(end = 10.dp), maxLines = 1)
            Box(Modifier.width(52.dp).fillMaxHeight().background(pleos.surface2).clickable { onChange((value + 1).coerceIn(min, max)) }, contentAlignment = Alignment.Center) {
                Text("+", color = pleos.text, fontSize = 26.sp)
            }
        }
    }
}

/** 없어도 되는 정수 (켜고 끄기) */
@Composable
private fun OptNumRow(label: String, value: Int?, suffix: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (Int?) -> Unit) {
    if (value == null) {
        Column(modifier.padding(vertical = 6.dp)) {
            FieldLabel(label)
            Box(Modifier.height(56.dp).border(1.dp, pleos.line).clickable { onChange(10) }.padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                Text("없음 · 눌러서 정하기", color = pleos.dim, fontSize = 19.sp)
            }
        }
    } else {
        // '없음으로'는 이름 줄 오른쪽에 두어 입력칸 너비를 줄이지 않는다
        NumRow(label, value, suffix, modifier, clear = { onChange(null) }) { onChange(it) }
    }
}

@Composable
private fun DecimalRow(label: String, value: Double, modifier: Modifier, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Column(modifier.padding(vertical = 6.dp)) {
        FieldLabel(label)
        BasicTextField(
            text, { t -> text = t.filter { it.isDigit() || it == '.' || it == '-' }.take(14); text.toDoubleOrNull()?.let(onChange) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = TextStyle(color = pleos.text, fontSize = 21.sp),
            cursorBrush = SolidColor(pleos.text),
            modifier = Modifier.fillMaxWidth().background(pleos.surface).border(1.dp, pleos.line).padding(horizontal = 14.dp, vertical = 13.dp),
        )
    }
}

@Composable
private fun ColumnScope.JsonBox(json: String, onApply: (String) -> Unit, onCopy: (String) -> Unit) {
    var text by remember(json) { mutableStateOf(json) }
    LaunchedEffect(json) { text = json }
    SubLabel("JSON (PC에서 고쳐 붙여 넣거나 파일로 넣을 수 있어요)")
    BasicTextField(
        text, { text = it },
        textStyle = TextStyle(color = pleos.text, fontSize = 16.sp, fontFamily = FontFamily.Monospace, lineHeight = 22.sp),
        cursorBrush = SolidColor(pleos.text),
        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 560.dp).verticalScroll(rememberScrollState()).border(1.dp, pleos.line).padding(14.dp),
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlineButton("이 JSON 적용") { onApply(text) }
        OutlineButton("복사") { onCopy(text) }
    }
}

// endregion
