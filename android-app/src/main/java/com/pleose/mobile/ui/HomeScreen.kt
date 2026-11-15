package com.pleose.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleose.mobile.MobileUi
import com.pleose.mobile.MobileViewModel
import com.pleose.shared.api.ConnectionState
import com.pleose.shared.model.Delivery
import com.pleose.shared.model.Errand
import com.pleose.shared.model.ErrandStatus
import com.pleose.shared.model.ErrandType
import com.pleose.shared.model.Favorite
import com.pleose.shared.text.Korean
import com.pleose.shared.text.Kst

/** 요청자 앱 · 진행 상태 (화면 시안 ⑥) — F-10 상태 공유, F-11 픽업 도착 예정 */
@Composable
fun HomeScreen(vm: MobileViewModel, ui: MobileUi, onCompose: () -> Unit, onShare: () -> Unit) {
    val session = ui.session ?: return
    val snap = ui.snapshot
    var tab by rememberSaveable { mutableStateOf(0) }
    var menu by rememberSaveable { mutableStateOf(false) }
    var cancelTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val now = snap?.serverTime ?: System.currentTimeMillis()
    val sent = snap?.errands.orEmpty().filter { it.fromId == session.memberId }
    val received = snap?.errands.orEmpty().filter { it.toId == session.memberId }
    val favorites = snap?.favorites.orEmpty().filter { it.ownerId == session.memberId }

    Column(Modifier.fillMaxSize().background(MColors.bg)) {
        Column(Modifier.background(MColors.surface).padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${session.familyName} · ${session.memberName}",
                        color = MColors.sub, fontSize = 12.sp,
                        modifier = Modifier.clickable { menu = true },
                    )
                    Text(if (tab == 0) "보낸 부탁" else "받은 부탁", color = MColors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    Modifier.size(46.dp).background(MColors.navy, RoundedCornerShape(3.dp)).clickable(onClick = onCompose),
                    contentAlignment = Alignment.Center,
                ) { MIcon(MGlyph.PLUS, Color.White, 22.dp) }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                TabText("보낸 부탁 ${sent.count { it.status.isActive }}", tab == 0) { tab = 0 }
                TabText("받은 부탁 ${received.count { it.status.isActive }}", tab == 1) { tab = 1 }
            }
        }
        if (ui.connection == ConnectionState.OFFLINE) {
            Text(
                "서버에 연결하지 못했어요 · ${session.serverUrl}",
                color = Color.White, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().background(MColors.red).padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        val list = if (tab == 0) sent else received
        val members = snap?.members.orEmpty().filter { it.id != session.memberId }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 자주 하는 부탁: 원터치로 보내기 (기획안 3.2)
            if (tab == 0 && favorites.isNotEmpty()) {
                item { FavoritesCard(favorites, now, onSend = vm::sendFavorite, onDelete = vm::deleteFavorite) }
            }
            if (list.isEmpty()) {
                item {
                    EmptyState(
                        if (tab == 0) "아직 보낸 부탁이 없어요" else "받은 부탁이 없어요",
                        if (tab == 0) {
                            if (members.isEmpty()) "가족을 초대하면 운전 중인 가족에게 부탁을 보낼 수 있어요" else "+ 를 눌러 운전 중인 가족에게 부탁해 보세요"
                        } else {
                            "가족이 보낸 부탁은 운전 중이면 차에서, 아니면 여기에서 받아요"
                        },
                        if (members.isEmpty() && tab == 0) "초대 코드 ${session.inviteCode} 공유하기" else null,
                        onShare,
                    )
                }
            }
            val (active, done) = list.partition { it.status.isActive }
            items(active, key = { it.id }) { e ->
                if (tab == 0) {
                    SentCard(e, now, onCancel = { cancelTarget = e.id }, onAnswer = { accept -> vm.answerProposal(e.id, accept) })
                } else {
                    ReceivedCard(e, now, onDecline = { vm.declineReceived(e.id) })
                }
            }
            items(done, key = { it.id }) { e ->
                val saved = favorites.any { it.title == e.title && it.toId == e.toId }
                DoneRow(e, now, tab == 0, canSave = tab == 0 && e.status == ErrandStatus.COMPLETED && !saved, onSave = { vm.saveFavorite(e) })
            }
        }
    }

    if (cancelTarget != null) {
        AlertDialog(
            onDismissRequest = { cancelTarget = null },
            title = { Text("부탁을 취소할까요?") },
            text = { Text("운전 중인 가족에게 취소를 알려 드려요.") },
            confirmButton = { TextButton({ vm.cancel(cancelTarget!!); cancelTarget = null }) { Text("부탁 취소") } },
            dismissButton = { TextButton({ cancelTarget = null }) { Text("닫기") } },
        )
    }
    if (menu) {
        AlertDialog(
            onDismissRequest = { menu = false },
            title = { Text(session.familyName) },
            text = {
                Column {
                    Text("초대 코드 ${session.inviteCode}", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    snap?.members?.forEach { m -> Text("${m.name}${if (m.id == session.memberId) " (나)" else ""} · ${m.vehicleLabel}", fontSize = 13.sp) }
                    Spacer(Modifier.height(6.dp))
                    Text("서버 ${session.serverUrl} · ${ui.connection.label}", fontSize = 12.sp, color = MColors.sub)
                }
            },
            confirmButton = { TextButton({ menu = false; onShare() }) { Text("초대 공유") } },
            dismissButton = { TextButton({ menu = false; vm.logout() }) { Text("나가기") } },
        )
    }
}

@Composable
private fun TabText(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Text(text, color = if (selected) MColors.navy else MColors.sub, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.width(if (selected) 64.dp else 0.dp).height(2.dp).background(MColors.navy))
    }
}

@Composable
private fun EmptyState(title: String, body: String, action: String?, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 60.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = MColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(body, color = MColors.sub, fontSize = 13.sp)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.clickable(onClick = onAction), verticalAlignment = Alignment.CenterVertically) {
                MIcon(MGlyph.SHARE, MColors.navy, 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(action, color = MColors.navy, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MColors.surface, RoundedCornerShape(4.dp)).border(1.dp, MColors.line, RoundedCornerShape(4.dp)).padding(18.dp),
    ) { content() }
}

@Composable
private fun StatusChip(status: ErrandStatus) {
    val filled = status == ErrandStatus.IN_PROGRESS
    val color = when (status) {
        ErrandStatus.IN_PROGRESS, ErrandStatus.ACCEPTED -> MColors.navy
        ErrandStatus.DECLINED, ErrandStatus.FAILED, ErrandStatus.EXPIRED -> MColors.red
        else -> MColors.sub
    }
    Text(
        status.requesterLabel,
        color = if (filled) Color.White else color, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(if (filled) MColors.navy else Color.Transparent, RoundedCornerShape(2.dp))
            .border(1.dp, color, RoundedCornerShape(2.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** 자주 하는 부탁 (디자인 통합 데모: "화·목 하원 픽업 · 원터치로 보내기") */
@Composable
private fun FavoritesCard(list: List<Favorite>, now: Long, onSend: (Favorite) -> Unit, onDelete: (String) -> Unit) = Card {
    Text("자주 하는 부탁", color = MColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    val today = Kst.dayOfWeek(now)
    list.firstOrNull { today in it.days }?.let {
        Text("오늘은 ${Favorite.DAY_NAMES[today - 1]}요일이에요. '${it.title}'을 한 번에 보낼 수 있어요.", color = MColors.blue, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
    list.forEach { f ->
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(listOfNotNull(f.daysLabel.takeIf { it.isNotEmpty() }, f.title).joinToString(" "), color = MColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(listOf("받는 사람 ${f.toName}", f.summary).filter { it.isNotEmpty() }.joinToString(" · "), color = MColors.sub, fontSize = 12.sp)
            }
            LinkText("지우기", MColors.dim, Modifier.padding(end = 12.dp)) { onDelete(f.id) }
            Text(
                "보내기", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(MColors.navy, RoundedCornerShape(3.dp)).clickable { onSend(f) }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** 보낸 부탁 카드 */
@Composable
private fun SentCard(e: Errand, now: Long, onCancel: () -> Unit, onAnswer: (Boolean) -> Unit) = Card {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${e.toName}에게 · ${e.type.label} · ${Kst.hhmm(e.createdAt)}", color = MColors.sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
        StatusChip(e.status)
    }
    Spacer(Modifier.height(12.dp))
    Text(e.title, color = MColors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    if (e.urgent) Text("급해요", color = MColors.amber, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
    Spacer(Modifier.height(14.dp))

    if (e.status == ErrandStatus.PROPOSED) {
        ProposalBlock(e, onAnswer)
        return@Card
    }
    val share = e.pickupShare
    if (e.type == ErrandType.PICKUP_PERSON && e.status.isAccepted) {
        ProgressSteps(e)
        Spacer(Modifier.height(12.dp))
        // F-11: 도착 예정 시각과 정차 지점만. 실시간 위치는 보내지 않는다
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoBox("도착 예정", share?.let { Kst.hhmm(it.etaAt) } ?: "계산 중", big = true, Modifier.weight(1f))
            InfoBox("정차 지점", share?.stopPoint ?: e.targetPlaceName ?: e.placeName ?: "", big = false, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            MIcon(MGlyph.LOCK, MColors.dim, 13.dp, Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(6.dp))
            Text("실시간 위치는 공유하지 않아요. 도착 예정 시각만 탑승 전까지 보여 드려요.", color = MColors.sub, fontSize = 12.sp, lineHeight = 17.sp)
        }
    } else {
        ProgressSteps(e)
        Spacer(Modifier.height(14.dp))
        StatusBox(e)
    }
    Spacer(Modifier.height(10.dp))
    Text("진행 단계만 공유돼요. 위치와 지도는 보이지 않아요.", color = MColors.dim, fontSize = 11.sp)
    Spacer(Modifier.height(10.dp))
    LinkText("부탁 취소", onClick = onCancel)
}

/**
 * 조건부 수락 (기획안 4.3, 디자인): 운전자가 차량 도착 예정을 근거로 "18:50 도착 가능"을 보냈다.
 * 상황을 가장 잘 아는 요청자가 [좋아요] 또는 [다른 방법 찾을게요]로 정한다.
 */
@Composable
private fun ProposalBlock(e: Errand, onAnswer: (Boolean) -> Unit) {
    val p = e.proposal
    Column(Modifier.fillMaxWidth().background(MColors.box, RoundedCornerShape(3.dp)).padding(16.dp)) {
        Text("${Korean.nim(e.toName)}이 조건을 붙여 답했어요", color = MColors.blue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(listOfNotNull(e.title, e.placeName).joinToString(" · "), color = MColors.sub, fontSize = 12.sp)
        Text(p?.let { "${Kst.hhmm(it.etaAt)} 도착 가능" } ?: "조건부 수락", color = MColors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            listOfNotNull(
                e.appointmentAt?.let { appt -> p?.lateMin?.takeIf { it > 0 }?.let { "요청한 ${Kst.hhmm(appt)}보다 약 ${it}분 늦어요." } },
                "${Korean.nim(e.toName)} 차량의 도착 예정 시간을 기준으로 계산했어요.",
            ).joinToString(" "),
            color = MColors.sub, fontSize = 12.sp, lineHeight = 18.sp,
        )
    }
    Spacer(Modifier.height(10.dp))
    Text(
        "상황을 가장 잘 아는 내가 정해요. ${Korean.nim(e.toName)}은 운전 중이라 따로 협상하지 않아도 돼요.",
        color = MColors.sub, fontSize = 12.sp, lineHeight = 18.sp,
    )
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NavyButton("좋아요", modifier = Modifier.weight(1f)) { onAnswer(true) }
        OutlinedNavyButton("다른 방법 찾을게요", modifier = Modifier.weight(1f)) { onAnswer(false) }
    }
    Spacer(Modifier.height(6.dp))
    Text("다른 방법을 고르면 부탁은 자동 취소되고 ${Korean.nim(e.toName)}의 경로는 그대로예요.", color = MColors.dim, fontSize = 11.sp)
}

@Composable
private fun InfoBox(label: String, value: String, big: Boolean, modifier: Modifier) {
    Column(modifier.background(MColors.box, RoundedCornerShape(3.dp)).padding(14.dp)) {
        Text(label, color = MColors.sub, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text(value, color = MColors.text, fontSize = if (big) 26.sp else 15.sp, fontWeight = FontWeight.Bold, lineHeight = if (big) 30.sp else 20.sp)
    }
}

/** 전달됨 · 수락 · 도착 · 완료(픽업 완료·구매 완료) — 위치 대신 단계만 */
@Composable
private fun ProgressSteps(e: Errand) {
    val step = e.progressIndex
    val labels = e.progressLabels
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { i, label ->
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier.fillMaxWidth().height(3.dp).background(
                        when {
                            i < step -> MColors.navy
                            i == step -> if (step == 2) MColors.blue else MColors.navy
                            else -> MColors.line
                        },
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Text(label, color = if (i == step) MColors.text else MColors.sub, fontSize = 11.sp, fontWeight = if (i == step) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun StatusBox(e: Errand) {
    val (title, sub) = when {
        e.status.isAccepted && e.arrivedAt != null -> "${e.targetPlaceName ?: "부탁한 곳"}에 도착했어요" to "출발하면 ${e.doneLabel}로 바로 알려 드려요"
        else -> statusText(e)
    }
    Column(Modifier.fillMaxWidth().background(MColors.box, RoundedCornerShape(3.dp)).padding(14.dp)) {
        Text(title, color = MColors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        if (sub.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(sub, color = MColors.sub, fontSize = 12.sp)
        }
    }
}

private fun statusText(e: Errand): Pair<String, String> {
    val confirmed = e.proposal?.confirmedAt?.let { " · ${Kst.hhmm(e.proposal!!.etaAt)} 확정" } ?: ""
    val detour = e.detourMin?.let { " (+${it}분)" } ?: ""
    return when (e.status) {
        ErrandStatus.IN_PROGRESS -> "${e.targetPlaceName ?: "부탁한 곳"}$detour 들르는 중이에요$confirmed" to etaLine(e)
        ErrandStatus.ACCEPTED -> "${e.targetPlaceName ?: "부탁한 곳"}$detour 경유 예정이에요$confirmed" to etaLine(e)
        ErrandStatus.ON_HOLD -> "${Korean.nim(e.toName)}이 아직 확인하지 못했어요" to "안전한 때 다시 알려 드려요"
        else -> if (e.delivery == Delivery.VEHICLE_VOICE) {
            "${Korean.nim(e.toName)} 차량에서 음성으로 전했어요" to "운전 중에도 화면을 보지 않고 답할 수 있어요"
        } else {
            "${Korean.nim(e.toName)} 휴대폰으로 알렸어요" to "운전을 시작하면 차에서도 음성으로 알려 드려요"
        }
    }
}

/** "18:41 수락 · 집 도착 예정 18:54" */
private fun etaLine(e: Errand): String = listOfNotNull(
    e.acceptedAt?.let { "${Kst.hhmm(it)} 수락" },
    e.homeEtaAt?.let { "집 도착 예정 ${Kst.hhmm(it)}" },
).joinToString(" · ")

/** 받은 부탁 (차가 아닐 때 휴대폰으로 받은 경우) */
@Composable
private fun ReceivedCard(e: Errand, now: Long, onDecline: () -> Unit) = Card {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${Korean.nim(e.fromName)}의 부탁 · ${e.type.label} · ${Kst.hhmm(e.createdAt)}", color = MColors.sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (e.urgent) Text("급해요", color = MColors.amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(10.dp))
    Text(e.title, color = MColors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text("\"${e.text}\"", color = MColors.sub, fontSize = 13.sp)
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().background(MColors.box, RoundedCornerShape(3.dp)).padding(14.dp)) {
        Text(
            when {
                e.status.isAccepted -> "${e.targetPlaceName ?: "경유지"}에 들르기로 했어요"
                else -> "차에 타면 음성으로 알려 드리고 경로 위에서 들를 곳을 찾아 드려요"
            },
            color = MColors.text, fontSize = 13.sp,
        )
        e.appointmentAt?.let { Text("약속 ${Kst.hhmm(it)} · ${e.placeName ?: ""}", color = MColors.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp)) }
    }
    if (e.status.isAwaitingAnswer) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(MColors.blue, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text("운전을 시작하면 차에서 안내해요", color = MColors.sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
            LinkText("오늘은 못 가요", MColors.red, onClick = onDecline)
        }
    }
}

/** 끝난 부탁 한 줄 — 시안: "우유 2팩 / 지훈에게 · 어제 19:12 / 완료" */
@Composable
private fun DoneRow(e: Errand, now: Long, sent: Boolean, canSave: Boolean = false, onSave: () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().background(MColors.surface, RoundedCornerShape(4.dp)).border(1.dp, MColors.line, RoundedCornerShape(4.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        DoneLine(e, now, sent)
        // 끝낸 부탁을 자주 하는 부탁으로 (기획안 4.2 "자주 하는 부탁으로 저장할까요?")
        if (canSave) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().background(MColors.box, RoundedCornerShape(3.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("자주 하는 부탁으로 저장할까요?", color = MColors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("저장하면 다음엔 한 번에 보낼 수 있어요", color = MColors.sub, fontSize = 11.sp)
                }
                Text(
                    "즐겨찾기에 저장", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(MColors.navy, RoundedCornerShape(3.dp)).clickable(onClick = onSave).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun DoneLine(e: Errand, now: Long, sent: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(e.title, color = MColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "${if (sent) "${e.toName}에게" else "${e.fromName}님이"} · ${Kst.relativeDay(e.completedAt ?: e.updatedAt, now)}",
                color = MColors.sub, fontSize = 12.sp,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                e.status.requesterLabel,
                color = when (e.status) {
                    ErrandStatus.COMPLETED -> MColors.text
                    ErrandStatus.CANCELLED -> MColors.dim
                    else -> MColors.red
                },
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
            e.note?.takeIf { e.status != ErrandStatus.COMPLETED }?.let { Text(it, color = MColors.sub, fontSize = 11.sp) }
        }
    }
}
