package com.pleose.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleose.car.CarAppState

/** 설정에 필요한 권한 상태 */
data class SetupStatus(
    val runtimePermissions: Boolean,
    val overlay: Boolean,
    /** 이 차량의 설정 앱에 '다른 앱 위에 표시' 화면이 있는지 */
    val overlaySettingsAvailable: Boolean,
    val usageAccess: Boolean,
    val packageName: String,
)

interface SetupActions {
    fun requestPermissions()
    fun openOverlaySettings()
    fun openUsageSettings()
    fun connect(server: String, code: String, name: String)
    fun startDemo()
    fun disconnect()
    fun goToNavi()
    fun close()
    /** 시연 시나리오 화면 */
    fun openDemo()
}

/**
 * 처음 한 번만 보는 설정 화면. 평소에는 이 화면 없이 Pleos 내비 위 Please 버튼으로 쓴다.
 * 패널의 설정(톱니) 버튼으로 다시 열 수 있다.
 */
@Composable
fun SetupScreen(state: CarAppState, status: SetupStatus, settingsMode: Boolean, a: SetupActions) {
    Column(Modifier.fillMaxSize().background(pleos.surface)) {
        Row(Modifier.fillMaxWidth().height(PleosSize.headerCell).background(PleosStatic.header), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(24.dp))
            PleaseMark(PleosStatic.onHeader, PleosStatic.header, 36.dp)
            Spacer(Modifier.width(12.dp))
            Text(if (settingsMode) "오는 길에 부탁해 설정" else "오는 길에 부탁해 시작하기", color = PleosStatic.onHeader, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box(Modifier.size(PleosSize.headerCell).clickable(onClick = a::close), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.CLOSE, PleosStatic.onHeader, 30.dp) }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(32.dp)) {
                Text("Pleos 내비 위에서 가족 부탁을 처리해요", color = pleos.text, fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp)
                Spacer(Modifier.height(8.dp))
                Text("내비 오른쪽 위 오는 길에 부탁해 버튼에 부탁이 숫자로 뜨고, 눌러서 고르면 Pleos 내비가 안내해요.", color = pleos.sub, fontSize = 22.sp, lineHeight = 30.sp)
                Spacer(Modifier.height(28.dp))
                StepRow(1, "차량 기능 사용 허용", "내비 경로, 음성 안내와 인식, 주행 상태를 써요", status.runtimePermissions, "허용하기", a::requestPermissions)
                StepRow(
                    2, "내비 위에 표시", overlayHelp(status), status.overlay,
                    if (status.overlaySettingsAvailable) "설정 열기" else null, a::openOverlaySettings,
                )
                StepRow(
                    3, "내비가 켜져 있을 때만 표시 (선택)",
                    if (status.usageAccess) "내비가 보일 때만 오는 길에 부탁해 버튼이 떠요" else "허용하지 않으면 다른 앱 위에도 버튼이 떠요",
                    status.usageAccess, "설정 열기", a::openUsageSettings,
                )
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(pleos.line))
            Column(Modifier.width(620.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(32.dp)) {
                if (state.session != null) ConnectedInfo(state, a) else ConnectForm(state, a)
                Spacer(Modifier.height(28.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(pleos.line))
                Spacer(Modifier.height(20.dp))
                Text("시연", color = pleos.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "어디로 가는지, 어떤 부탁을 보내는지, 몇 초 뒤 답하고 도착·완료하는지 정해 두면 그대로 자동 진행해요. 영상 촬영용이에요.",
                    color = pleos.sub, fontSize = 20.sp, lineHeight = 28.sp,
                )
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(PleosSize.cell).border(1.dp, pleos.text).clickable(onClick = a::openDemo), contentAlignment = Alignment.Center) {
                    Text("시연 시나리오 열기", color = pleos.text, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (state.session != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(pleos.line))
            Box(
                Modifier.fillMaxWidth().height(PleosSize.cell).background(if (status.overlay) pleos.primary else pleos.surface2)
                    .clickable(enabled = status.overlay, onClick = a::goToNavi),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (status.overlay) "Pleos 내비로 가기" else "2번을 마치면 Pleos 내비에서 쓸 수 있어요",
                    color = if (status.overlay) pleos.onPrimary else pleos.dim, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun overlayHelp(s: SetupStatus): String = when {
    s.overlay -> "내비 오른쪽 위에 오는 길에 부탁해 버튼을 띄울 수 있어요"
    s.overlaySettingsAvailable -> "설정에서 '다른 앱 위에 표시'를 허용해 주세요"
    else -> "이 차량에는 설정 화면이 없어요. 개발 환경에서는 PC에서 다음을 실행해 주세요\nadb shell appops set --user 10 ${s.packageName} SYSTEM_ALERT_WINDOW allow"
}

@Composable
private fun StepRow(n: Int, title: String, body: String, done: Boolean, action: String?, onAction: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(pleos.line))
    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).background(if (done) PleosStatic.blue else pleos.surface2), contentAlignment = Alignment.Center) {
            if (done) GlyphIcon(Glyph.CHECK, androidx.compose.ui.graphics.Color.White, 30.dp) else Text("$n", color = pleos.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = pleos.text, fontSize = 25.sp, fontWeight = FontWeight.Medium)
            Text(body, color = pleos.sub, fontSize = 20.sp, lineHeight = 28.sp, fontFamily = if (body.contains("adb ")) FontFamily.Monospace else null)
        }
        if (!done && action != null) {
            Spacer(Modifier.width(16.dp))
            Box(Modifier.border(1.dp, pleos.line).clickable(onClick = onAction).padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(action, color = pleos.text, fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun ConnectedInfo(state: CarAppState, a: SetupActions) {
    val s = state.session ?: return
    Text("가족 연결", color = pleos.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(16.dp))
    Text(s.familyName, color = pleos.text, fontSize = 25.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    Text(
        if (s.remote) "${s.memberName} · 초대 코드 ${s.inviteCode} · ${state.connection.label}" else "단독 데모 · 부탁은 패널의 테스트 도구에서 보내요",
        color = pleos.sub, fontSize = 21.sp, lineHeight = 29.sp,
    )
    Spacer(Modifier.height(24.dp))
    Box(Modifier.fillMaxWidth().height(PleosSize.cell).border(1.dp, pleos.line).clickable(onClick = a::disconnect), contentAlignment = Alignment.Center) {
        Text("연결 해제", color = pleos.text, fontSize = 25.sp)
    }
}

@Composable
private fun ConnectForm(state: CarAppState, a: SetupActions) {
    var server by rememberSaveable { mutableStateOf(state.lastServerUrl) }
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("지훈") }
    Text("가족과 연결", color = pleos.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text("요청자 앱의 초대 코드와 이 차를 쓰는 사람 이름을 넣어 주세요.", color = pleos.sub, fontSize = 20.sp, lineHeight = 28.sp)
    Spacer(Modifier.height(18.dp))
    Field("중계 서버", server) { server = it }
    Field("초대 코드", code, caps = true) { code = it.uppercase().take(6) }
    Field("내 이름 (요청자 앱과 같은 이름)", name) { name = it }
    state.error?.let { Text(it, color = PleosStatic.red, fontSize = 20.sp, modifier = Modifier.padding(bottom = 10.dp)) }
    val canConnect = !state.connecting && code.length == 6 && name.isNotBlank()
    Box(
        Modifier.fillMaxWidth().height(PleosSize.cell).background(if (canConnect) pleos.primary else pleos.surface2)
            .clickable(enabled = canConnect) { a.connect(server, code, name.trim()) },
        contentAlignment = Alignment.Center,
    ) { Text(if (state.connecting) "연결 중…" else "가족과 연결", color = if (canConnect) pleos.onPrimary else pleos.dim, fontSize = 25.sp, fontWeight = FontWeight.Bold) }
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth().height(PleosSize.cell).border(1.dp, pleos.line).clickable(onClick = a::startDemo), contentAlignment = Alignment.Center) {
        Text("단독 데모로 시작 (가족: 수진 · 어머님)", color = pleos.text, fontSize = 23.sp)
    }
}

@Composable
private fun Field(label: String, value: String, caps: Boolean = false, onChange: (String) -> Unit) {
    Text(label, color = pleos.sub, fontSize = 20.sp)
    Spacer(Modifier.height(6.dp))
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = pleos.text, fontSize = 24.sp),
        cursorBrush = SolidColor(pleos.text),
        keyboardOptions = KeyboardOptions(capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.None),
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).border(1.dp, pleos.line).padding(horizontal = 16.dp, vertical = 18.dp),
    )
}
