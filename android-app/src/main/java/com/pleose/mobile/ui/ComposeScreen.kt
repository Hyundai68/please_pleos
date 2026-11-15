package com.pleose.mobile.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleose.mobile.Draft
import com.pleose.mobile.MobileUi
import com.pleose.mobile.MobileViewModel
import com.pleose.shared.interpret.ErrandInterpreter
import com.pleose.shared.model.DriveState
import com.pleose.shared.model.ErrandType
import com.pleose.shared.model.Member
import com.pleose.shared.text.Korean
import com.pleose.shared.text.Kst
import java.time.ZonedDateTime

/** 요청자 앱 · 부탁하기 (화면 시안 ⑤) — F-02 부탁 작성·전송 */
@Composable
fun ComposeScreen(vm: MobileViewModel, ui: MobileUi, onBack: () -> Unit) {
    val session = ui.session ?: return
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val members = ui.snapshot?.members.orEmpty().filter { it.id != session.memberId }
    val context = LocalContext.current

    var toId by rememberSaveable { mutableStateOf<String?>(null) }
    var type by rememberSaveable { mutableStateOf<ErrandType?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    var placeName by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var appointmentAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var urgent by rememberSaveable { mutableStateOf(false) }
    var saveFavorite by rememberSaveable { mutableStateOf(false) }
    var pickRecipient by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var noMic by remember { mutableStateOf(false) }

    // 받을 사람 기본값: 운전 중인 가족 → 차량 연결된 가족 → 첫 번째
    LaunchedEffect(members.map { it.id }) {
        if (toId == null || members.none { it.id == toId }) {
            toId = (members.firstOrNull { it.vehicleLinked && it.driveState == DriveState.DRIVING }
                ?: members.firstOrNull { it.vehicleLinked } ?: members.firstOrNull())?.id
        }
    }
    val to = members.firstOrNull { it.id == toId }
    val place = placeName.trim().ifEmpty { null }
    val interp = ErrandInterpreter.interpret(text, type, if (type == ErrandType.BUY) null else place)
    val effectiveType = interp.type
    val missing = when {
        to == null -> "받을 사람이 없어요. 가족을 먼저 초대해 주세요"
        text.isBlank() -> "무엇을 부탁할지 적어 주세요"
        effectiveType == ErrandType.BUY && interp.ambiguous -> "무엇을 사 올지 조금 더 구체적으로 적어 주세요"
        effectiveType.requiresPlace && place == null -> "장소 이름을 적어 주세요"
        effectiveType.requiresTime && appointmentAt == null -> "데려올 시간을 정해 주세요"
        else -> null
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { said ->
                text = if (text.isBlank()) said else "$text $said"
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable(onClick = onBack).padding(8.dp)) { MIcon(MGlyph.BACK, MColors.text, 22.dp) }
            Spacer(Modifier.width(6.dp))
            Text("부탁하기", color = MColors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = MColors.line)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp)) {
            // 받을 사람
            SectionLabel("받을 사람")
            Row(
                Modifier.fillMaxWidth().border(1.dp, MColors.line, RoundedCornerShape(4.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (to != null) {
                    Avatar(to.initial)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(to.name, color = MColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(to.vehicleLabel, color = MColors.sub, fontSize = 12.sp)
                    }
                    if (members.size > 1) LinkText("변경") { pickRecipient = true }
                } else {
                    Text("아직 가족이 없어요 · 초대 코드 ${session.inviteCode}", color = MColors.sub, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(22.dp))

            // 유형
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("어떤 부탁인가요?")
                if (type == null && text.isNotBlank()) Text("  자동 분류", color = MColors.blue, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
            Row(Modifier.fillMaxWidth()) {
                ErrandType.entries.forEach { t ->
                    val selected = t == effectiveType && (type != null || text.isNotBlank())
                    Column(Modifier.weight(1f).clickable { type = if (type == t) null else t }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            t.label, fontSize = 14.sp,
                            color = if (selected) MColors.navy else MColors.sub,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) MColors.navy else MColors.line))
                    }
                }
            }
            Spacer(Modifier.height(22.dp))

            // 내용 (텍스트·음성)
            SectionLabel("무엇을 부탁할까요?")
            Box(Modifier.fillMaxWidth().heightIn(min = 96.dp).border(1.dp, MColors.text, RoundedCornerShape(2.dp)).padding(14.dp)) {
                if (text.isEmpty()) Text(placeholderFor(effectiveType), color = MColors.dim, fontSize = 15.sp, modifier = Modifier.padding(end = 30.dp))
                BasicTextField(
                    text, { text = it.take(200) },
                    textStyle = TextStyle(color = MColors.text, fontSize = 15.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(MColors.navy),
                    modifier = Modifier.fillMaxWidth().padding(end = 30.dp),
                )
                Box(
                    Modifier.align(Alignment.TopEnd).clickable {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "부탁할 내용을 말해 주세요")
                        if (intent.resolveActivity(context.packageManager) != null) speech.launch(intent) else noMic = true
                    },
                ) { MIcon(MGlyph.MIC, MColors.sub, 22.dp) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (noMic) "이 기기에서는 음성 입력을 쓸 수 없어요" else interp.hint,
                color = if (interp.ambiguous && text.isNotBlank()) MColors.red else MColors.sub, fontSize = 13.sp,
            )
            Spacer(Modifier.height(22.dp))

            // 장소: 이름으로 받고, 차량에서 Pleos 지도로 찾아 경유지로 넣는다
            if (effectiveType.requiresPlace) {
                SectionLabel("장소 (필수)")
                InputField(placeName, { placeName = it.take(40) }, placeExample(effectiveType))
                Text(
                    "차에서 Pleos 지도로 이 이름을 찾아 경유지로 넣어요",
                    color = MColors.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                SectionLabel("장소")
                Row(
                    Modifier.fillMaxWidth().background(MColors.box, RoundedCornerShape(3.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("경로 위에서 자동 추천", color = MColors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            interp.category?.let { "경로 근처 ${Korean.eul(it.label)} 차에서 Pleos 지도로 찾아요" } ?: "차에서 Pleos 지도로 찾아요",
                            color = MColors.sub, fontSize = 12.sp,
                        )
                    }
                }
            }

            // 상세 주소 (선택): 있으면 차에서 바로 이 주소로 안내한다
            Spacer(Modifier.height(16.dp))
            SectionLabel("상세 주소 (선택)")
            InputField(address, { address = it.take(120) }, "예) 경기도 성남시 수정구 창업로 17")
            Text(
                when {
                    address.isNotBlank() -> "차에서 바로 이 주소로 안내해요"
                    effectiveType.requiresPlace -> "비워 두면 차에서 장소 이름으로 찾아요"
                    else -> "비워 두면 차에서 근처를 찾아요"
                },
                color = MColors.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
            )

            // 시간 (데려와줘 필수)
            if (effectiveType.requiresTime) {
                Spacer(Modifier.height(22.dp))
                SectionLabel("시간 (필수)")
                Row(
                    Modifier.fillMaxWidth().background(MColors.box, RoundedCornerShape(3.dp)).clickable { pickTime = true }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        appointmentAt?.let { "${Kst.hhmm(it)} · 이때 맞춰 데리러 가요" } ?: "끝나는 시간을 정해 주세요",
                        color = if (appointmentAt == null) MColors.red else MColors.text, fontSize = 15.sp, modifier = Modifier.weight(1f),
                    )
                    LinkText(if (appointmentAt == null) "정하기" else "변경") { pickTime = true }
                }
            }
            Spacer(Modifier.height(22.dp))

            // 급해요
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("급해요", color = MColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("안전 구간이 되면 바로 알려 드려요", color = MColors.sub, fontSize = 12.sp)
                }
                CheckBoxSquare(urgent) { urgent = !urgent }
            }
            Spacer(Modifier.height(18.dp))
            // 자주 하는 부탁으로 저장 (기획안 3.2 반복 부탁 즐겨찾기)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("자주 하는 부탁으로 저장", color = MColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("오늘 요일로 저장해 다음엔 원터치로 보내요", color = MColors.sub, fontSize = 12.sp)
                }
                CheckBoxSquare(saveFavorite) { saveFavorite = !saveFavorite }
            }
            error?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, color = MColors.red, fontSize = 13.sp)
            }
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            NavyButton(if (busy) "보내는 중…" else "부탁 보내기", enabled = missing == null && !busy) {
                vm.send(
                    Draft(toId, type, text, if (effectiveType.requiresPlace) placeName else "", appointmentAt.takeIf { effectiveType.requiresTime }, urgent, address, saveFavorite),
                    onSent = onBack,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                missing ?: deliveryNote(to),
                color = if (missing != null && text.isNotBlank()) MColors.amber else MColors.sub,
                fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }

    if (pickRecipient) {
        AlertDialog(
            onDismissRequest = { pickRecipient = false },
            title = { Text("받을 사람") },
            text = {
                Column {
                    members.forEach { m ->
                        Row(Modifier.fillMaxWidth().clickable { toId = m.id; pickRecipient = false }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(m.initial, 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(m.name, fontWeight = FontWeight.Bold)
                                Text(m.vehicleLabel, color = MColors.sub, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ pickRecipient = false }) { Text("닫기") } },
        )
    }
    if (pickTime) TimePickerDialog(appointmentAt, onPick = { appointmentAt = it; pickTime = false }, onDismiss = { pickTime = false })
}

/** F-03 안내: 차에 있으면 차량 음성, 아니면 휴대폰 알림 */
private fun deliveryNote(to: Member?): String = when {
    to == null -> ""
    to.vehicleLinked && to.driveState.inVehicle -> "${Korean.nim(to.name)} 차량에서 음성으로 전달돼요. 전화하지 않아도 돼요."
    to.vehicleLinked -> "지금은 ${Korean.nim(to.name)} 휴대폰으로 알려요. 운전을 시작하면 차에서도 알려 드려요."
    else -> "${Korean.nim(to.name)} 차량이 연결되지 않아 휴대폰 알림으로만 전달돼요."
}

private fun placeholderFor(t: ErrandType) = when (t) {
    ErrandType.BUY -> "예) 아이가 열이 나서 해열제 좀 사 와줘. 시럽형으로!"
    ErrandType.PICKUP_ITEM -> "예) 저녁 포장 주문해뒀어, 받아 와줘"
    ErrandType.PICKUP_PERSON -> "예) 아이 태권도 5시에 끝나, 데려와줘"
    ErrandType.DROP_BY -> "예) 어머님 댁 들러서 반찬 받아 와"
}

private fun placeExample(t: ErrandType) = when (t) {
    ErrandType.PICKUP_ITEM -> "예) 할매김밥, 크린토피아 판교점"
    ErrandType.PICKUP_PERSON -> "예) 한빛태권도, 판교역 1번 출구"
    ErrandType.DROP_BY -> "예) 부모님 댁, 판교 우체국"
    ErrandType.BUY -> ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(initial: Long?, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val base = initial?.let { Kst.at(it) } ?: ZonedDateTime.now(Kst.zone).plusMinutes(30).let { it.withMinute(it.minute / 10 * 10) }
    val state = rememberTimePickerState(base.hour, base.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("데리러 갈 시간") },
        text = { TimePicker(state) },
        confirmButton = {
            TextButton({
                val now = ZonedDateTime.now(Kst.zone)
                var t = now.withHour(state.hour).withMinute(state.minute).withSecond(0).withNano(0)
                if (t.isBefore(now.minusHours(1))) t = t.plusDays(1) // 이미 지난 시각이면 내일
                onPick(t.toInstant().toEpochMilli())
            }) { Text("확인") }
        },
        dismissButton = { TextButton(onDismiss) { Text("취소") } },
    )
}
