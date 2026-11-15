package com.pleose.car.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleose.car.agent.Badge
import com.pleose.car.agent.BadgeKind
import com.pleose.car.agent.Callout

/** 배지 때문에 창이 버튼보다 오른쪽·아래로 이만큼 더 크다 */
val BadgeOverhang = 16.dp

private fun BadgeKind.color(): Color = when (this) {
    BadgeKind.NEW -> PleosStatic.red
    BadgeKind.CONFIRM -> PleosStatic.orange
    BadgeKind.ACTIVE, BadgeKind.NONE -> PleosStatic.blue
}

/**
 * Pleos 내비 위의 Please 버튼. 내비의 2D 버튼과 같은 크기·모양의 정사각 칸이다.
 * - 새 부탁: 오른쪽 아래 빨강 숫자 배지, 확인할 때까지 깜빡임 (주행 중에는 세 번만)
 * - 진행 중: 파랑 표시 (내비 하단 바의 선택 표시처럼 아래쪽 띠)
 * - 도착: 주황 체크 배지 → 눌러서 완료
 */
@Composable
fun PleaseButton(badge: Badge, inProgress: Boolean, attention: Boolean, driving: Boolean, panelOpen: Boolean, onClick: () -> Unit) {
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(attention, driving, badge.kind, badge.count) {
        pulse.snapTo(0f)
        if (!attention) return@LaunchedEffect
        var cycles = 0
        // Pleos 주행 안전 가이드: 주행 중 반복되는 움직임은 최소화한다
        while (!driving || cycles < 3) {
            pulse.animateTo(1f, tween(620))
            pulse.animateTo(0f, tween(620))
            cycles++
        }
    }
    val accent = badge.kind.color()
    Box(Modifier.size(PleosSize.cell + BadgeOverhang)) {
        Box(
            Modifier.size(PleosSize.cell)
                .background(if (panelOpen) pleos.surface2 else pleos.surface)
                .border(1.dp, pleos.line)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(PleosSize.cell).background(accent.copy(alpha = pulse.value * 0.16f)))
            if (pulse.value > 0f) Box(Modifier.size(PleosSize.cell).border((1f + pulse.value * 4f).dp, accent))
            // 칸이 작아 서비스 이름(오는 길에 부탁해) 대신 대표 아이콘만 보여 준다
            PleaseMark(pleos.text, pleos.surface, 60.dp)
            if (inProgress) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(5.dp).background(PleosStatic.blue))
        }
        if (badge.kind == BadgeKind.NEW || badge.kind == BadgeKind.CONFIRM) {
            val scale = 1f + pulse.value * 0.22f
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = 1.dp, bottom = 1.dp).size(40.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale)
                    .background(accent, CircleShape)
                    .border(2.dp, pleos.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (badge.kind == BadgeKind.CONFIRM) {
                    GlyphIcon(Glyph.CHECK, Color.White, 24.dp)
                } else {
                    Text("${badge.count}", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** 버튼 왼쪽에 잠깐 뜨는 안내 ("수진님의 새 부탁 · 아이 해열제") */
@Composable
fun PleaseCallout(c: Callout, onClick: () -> Unit) {
    Row(
        Modifier.widthIn(min = 320.dp, max = 560.dp).height(PleosSize.cell)
            .background(pleos.surface).border(1.dp, pleos.line).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(8.dp).fillMaxHeight().background(c.kind.color()))
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(c.title, color = pleos.sub, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(c.body, color = pleos.text, fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
