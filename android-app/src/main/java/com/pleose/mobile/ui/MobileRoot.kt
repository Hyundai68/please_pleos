package com.pleose.mobile.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleose.mobile.MobileViewModel

enum class Screen { HOME, COMPOSE }

@Composable
fun MobileRoot(vm: MobileViewModel, inviteCode: String?) {
    val ui by vm.ui.collectAsState()
    val toast by vm.toast.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(toast) {
        toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.consumeToast() }
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = MColors.navy, background = MColors.bg, surface = MColors.surface)) {
        Column(Modifier.fillMaxSize().background(MColors.surface).systemBarsPadding().imePadding()) {
            val session = ui.session
            if (session == null) {
                Onboarding(vm, inviteCode)
            } else {
                var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                BackHandler(screen == Screen.COMPOSE) { screen = Screen.HOME }
                when (screen) {
                    Screen.HOME -> HomeScreen(vm, ui, onCompose = { vm.clearError(); screen = Screen.COMPOSE }, onShare = {
                        val text = "[pleose 오는 길에 부탁해] ${session.familyName}에 초대해요.\n초대 코드: ${session.inviteCode}\npleose://join?code=${session.inviteCode}"
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "가족 초대"))
                    })
                    Screen.COMPOSE -> ComposeScreen(vm, ui, onBack = { screen = Screen.HOME })
                }
            }
        }
    }
}

/** F-01 가족 그룹 생성·초대 + 공유 범위 동의 (비기능 요구사항: 동의) */
@Composable
private fun Onboarding(vm: MobileViewModel, inviteCode: String?) {
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable(inviteCode) { mutableStateOf(inviteCode ?: "") }
    var server by rememberSaveable { mutableStateOf(vm.lastServerUrl) }
    var consent by rememberSaveable { mutableStateOf(false) }
    var showServer by rememberSaveable { mutableStateOf(false) }
    val ready = name.isNotBlank() && consent && !busy

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("pleose", color = MColors.blue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("오는 길에 부탁해", color = MColors.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            "운전 중인 가족에게 전화하지 않고 부탁하세요.\n차가 음성으로 알려 주고, 경로 위에서 들를 곳을 찾아 완료까지 알려 드려요.",
            color = MColors.sub, fontSize = 14.sp, lineHeight = 21.sp,
        )
        Spacer(Modifier.height(28.dp))
        SectionLabel("내 이름")
        InputField(name, { name = it.take(10) }, "예) 수진")
        Spacer(Modifier.height(18.dp))

        Row(verticalAlignment = Alignment.Top) {
            CheckBoxSquare(consent) { consent = !consent }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("가족과 공유하는 범위에 동의해요", color = MColors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    "부탁의 상태(전달됨 · 수락 · 들르는 중 · 완료)와, 데려와줘 부탁의 도착 예정 시각·정차 지점을 부탁한 가족에게 공유해요. 실시간 위치는 공유하지 않아요.",
                    color = MColors.sub, fontSize = 12.sp, lineHeight = 18.sp,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        NavyButton(if (busy) "연결 중…" else "새 가족 만들기", enabled = ready) { vm.createFamily(server, name.trim()) }
        Spacer(Modifier.height(22.dp))
        SectionLabel("초대받았나요?")
        InputField(code, { code = it.uppercase().filter(Char::isLetterOrDigit).take(6) }, "초대 코드 6자리", caps = true)
        Spacer(Modifier.height(10.dp))
        OutlinedNavyButton("초대 코드로 참여", enabled = ready && code.length == 6) { vm.joinFamily(server, code, name.trim()) }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MColors.red, fontSize = 13.sp)
        }
        Spacer(Modifier.height(26.dp))
        LinkText(if (showServer) "서버 설정 닫기" else "서버 설정", MColors.sub) { showServer = !showServer }
        if (showServer) {
            Spacer(Modifier.height(6.dp))
            InputField(server, { server = it.trim() }, "http://10.0.2.2:8787")
            Text("에뮬레이터는 10.0.2.2, 실제 휴대폰은 같은 와이파이의 PC 주소를 입력하세요.", color = MColors.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
