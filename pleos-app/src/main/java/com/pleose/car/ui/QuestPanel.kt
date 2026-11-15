package com.pleose.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleose.car.PanelPage
import com.pleose.car.agent.CarUi
import com.pleose.car.agent.NoticeKind
import com.pleose.car.agent.Prompt
import com.pleose.car.agent.Quest
import com.pleose.car.agent.QuestStage
import com.pleose.car.platform.VoiceStatus
import com.pleose.shared.model.Errand
import com.pleose.shared.model.ErrandType
import com.pleose.shared.model.Favorite
import com.pleose.shared.route.ErrandPlan
import com.pleose.shared.text.Korean
import com.pleose.shared.text.Kst
import com.pleose.shared.text.formatDistance
import com.pleose.shared.text.formatMinutes
import com.pleose.shared.voice.Reply

interface PanelActions {
    fun close()
    fun show(page: PanelPage)
    fun start(errandId: String)
    fun cancel(errandId: String)
    fun done(errandId: String)
    fun research(errandId: String)
    fun routeHome()
    fun openSettings()
    fun reply(r: Reply)
    /** "남은 부탁 읽어 줘" */
    fun readRemaining()
}

val PanelWidth = 640.dp

/**
 * Please 버튼을 누르면 내비 지도 위에 뜨는 패널.
 * 음성 질문 중이면 질문(수락·조건부 수락·거절), 주행 중이면 지금 상황 카드(보류·답변 대기·경유지·도착·완료)만 보여 준다.
 */
@Composable
fun QuestPanel(
    ui: CarUi,
    page: PanelPage?,
    canListen: Boolean,
    showTestTools: Boolean,
    maxHeight: Dp,
    actions: PanelActions,
    testTools: @Composable () -> Unit,
) {
    Column(Modifier.width(PanelWidth).heightIn(max = maxHeight).background(pleos.surface).border(1.dp, pleos.line)) {
        val prompt = ui.prompt
        when {
            // 테스트 도구는 주행 상태를 바꾸는 곳이라 주행 중·음성 질문 중에도 열려 있어야 한다
            page is PanelPage.Test -> {
                Header("테스트", onBack = { actions.show(PanelPage.Board) }, onClose = actions::close)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { testTools() }
            }
            prompt != null -> PromptPage(ui, prompt, canListen, showTestTools, actions)
            ui.driving || page is PanelPage.Status -> StatusPage(ui, showTestTools, actions, canGoBack = !ui.driving)
            page is PanelPage.Detail -> ui.quest(page.errandId)?.let { DetailPage(ui, it, actions) } ?: BoardPage(ui, showTestTools, actions)
            page is PanelPage.Done -> ui.quest(page.errandId)?.let { DonePage(ui, it, actions) } ?: BoardPage(ui, showTestTools, actions)
            else -> BoardPage(ui, showTestTools, actions)
        }
    }
}

// region 공통 조각

@Composable
private fun Header(
    title: String,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)?,
    trailing: @Composable () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().height(PleosSize.headerCell).background(PleosStatic.header), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            HeaderCell(Glyph.BACK, onBack)
            Spacer(Modifier.width(16.dp))
        } else {
            Spacer(Modifier.width(22.dp))
            PleaseMark(PleosStatic.onHeader, PleosStatic.header, 34.dp)
            Spacer(Modifier.width(12.dp))
        }
        Text(title, color = PleosStatic.onHeader, fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
        onClose?.let { HeaderCell(Glyph.CLOSE, it) }
    }
}

@Composable
private fun HeaderCell(glyph: Glyph, onClick: () -> Unit) {
    Box(Modifier.size(PleosSize.headerCell).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        GlyphIcon(glyph, PleosStatic.onHeader, 30.dp)
    }
}

@Composable
private fun Hairline() = Box(Modifier.fillMaxWidth().height(1.dp).background(pleos.line))

/** 내비 장소 카드의 '목적지로 / 경유지로'와 같은 맞닿은 버튼 */
@Composable
private fun CellButton(text: String, primary: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.height(PleosSize.cell).background(if (primary) pleos.primary else pleos.surface).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = if (primary) pleos.onPrimary else if (enabled) pleos.text else pleos.line, fontSize = 25.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Hairline()
    Row(Modifier.fillMaxWidth().height(PleosSize.cell)) { content() }
}

@Composable
private fun VLine() = Box(Modifier.width(1.dp).fillMaxHeight().background(pleos.line))

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.border(1.dp, color).padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

/** 오늘의 퀘스트 진행 바 */
@Composable
private fun Progress(ui: CarUi) {
    if (ui.totalCount == 0) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("오늘의 퀘스트", color = pleos.sub, fontSize = 21.sp, modifier = Modifier.weight(1f))
            Text("${ui.doneCount} / ${ui.totalCount} 완료", color = pleos.text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(ui.totalCount) { i ->
                Box(Modifier.weight(1f).height(8.dp).background(if (i < ui.doneCount) PleosStatic.blue else pleos.surface2))
            }
        }
    }
}

/** 어디로 안내할지 한 줄: 상세 주소 > 장소 이름 > 근처 장소 종류 */
private fun routeLine(e: Errand): String = when {
    e.hasAddress -> e.address.orEmpty()
    e.type == ErrandType.BUY -> "근처 ${e.searchKeyword} 찾기"
    else -> "${e.searchKeyword} 찾기"
}

// endregion

// region 퀘스트 목록

@Composable
private fun ColumnScope.BoardPage(ui: CarUi, showTestTools: Boolean, a: PanelActions) {
    val open = ui.quests.count { it.stage.startable || it.stage.running || it.stage == QuestStage.PROPOSED }
    var tab by remember { mutableStateOf(0) }
    Header("오는 길에 부탁해", onClose = a::close, trailing = {
        HeaderCell(Glyph.NAV) { a.show(PanelPage.Status) }
        if (showTestTools) HeaderCell(Glyph.MORE) { a.show(PanelPage.Test) }
        HeaderCell(Glyph.GEAR, a::openSettings)
    })
    // 정차 중 부탁 목록 (디자인 12): 가족 부탁 · 자주 받는 부탁
    Row(Modifier.fillMaxWidth().height(64.dp)) {
        TabCell(if (open > 0) "가족 부탁 $open" else "가족 부탁", tab == 0, Modifier.weight(1f)) { tab = 0 }
        VLine()
        TabCell("자주 받는 부탁 ${ui.favorites.size}", tab == 1, Modifier.weight(1f)) { tab = 1 }
    }
    Hairline()
    if (tab == 1) {
        FavoriteList(ui.favorites)
        ButtonRow { CellButton("남은 부탁 읽어 줘", false, Modifier.weight(1f), onClick = a::readRemaining) }
        return
    }
    Progress(ui)
    ui.message?.let {
        Text(it, color = PleosStatic.red, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
    }
    if (ui.quests.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("아직 받은 부탁이 없어요", color = pleos.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("부탁이 오면 이 버튼에 숫자로 알려 드려요", color = pleos.sub, fontSize = 21.sp)
        }
        return
    }
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        var order = 0
        ui.quests.forEach { q ->
            Hairline()
            QuestRow(q, if (q.stage.startable) ++order else null, a)
        }
    }
    ButtonRow {
        CellButton("진행 화면", false, Modifier.weight(1f)) { a.show(PanelPage.Status) }
        VLine()
        CellButton("남은 부탁 읽어 줘", false, Modifier.weight(1f), onClick = a::readRemaining)
    }
}

@Composable
private fun TabCell(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight().background(if (selected) pleos.surface else pleos.surface2).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = if (selected) pleos.text else pleos.sub, fontSize = 22.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        if (selected) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(pleos.primary))
    }
}

/** 자주 받는 부탁 (요청자 앱의 즐겨찾기) */
@Composable
private fun ColumnScope.FavoriteList(list: List<Favorite>) {
    if (list.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("자주 받는 부탁이 없어요", color = pleos.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("가족이 요청자 앱에서 즐겨찾기로 저장하면 여기에 보여요", color = pleos.sub, fontSize = 20.sp)
        }
        return
    }
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        list.forEach { f ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).background(pleos.surface2), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.STAR, pleos.text, 26.dp) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.title, color = pleos.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(f.daysLabel.takeIf { it.isNotEmpty() }, "${f.ownerName} → ${f.toName}", f.summary.takeIf { it.isNotEmpty() })
                            .filterNotNull().joinToString(" · "),
                        color = pleos.sub, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Hairline()
        }
    }
}

/** 퀘스트 한 줄. 왼쪽 번호는 추천 순서이고, 어떤 것부터 할지는 운전자가 고른다 */
@Composable
private fun QuestRow(q: Quest, order: Int?, a: PanelActions) {
    val e = q.errand
    val closed = q.stage == QuestStage.DONE || q.stage == QuestStage.CLOSED
    Row(Modifier.fillMaxWidth().height(if (closed) 86.dp else 126.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).fillMaxHeight().clickable { a.show(PanelPage.Detail(e.id)) }.padding(start = 18.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                q.stage == QuestStage.DONE -> Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.CHECK, PleosStatic.blue, 36.dp) }
                closed -> Spacer(Modifier.width(46.dp))
                q.stage.running -> Box(Modifier.size(46.dp).background(PleosStatic.blue), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.NAV, Color.White, 26.dp) }
                q.stage == QuestStage.PROPOSED -> Box(Modifier.size(46.dp).border(2.dp, PleosStatic.blue), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.CLOCK, PleosStatic.blue, 28.dp) }
                else -> Box(Modifier.size(46.dp).background(pleos.surface2), contentAlignment = Alignment.Center) {
                    Text("${order ?: ""}", color = pleos.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (!closed) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${e.fromName} · ${e.type.label}", color = pleos.sub, fontSize = 19.sp, maxLines = 1)
                        if (e.urgent) Tag("급해요", PleosStatic.red)
                        e.appointmentAt?.let { Tag("${Kst.hhmm(it)} 약속", PleosStatic.orange) }
                    }
                }
                Text(
                    e.title, color = if (closed) pleos.dim else pleos.text, fontSize = if (closed) 22.sp else 26.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(stageLine(q), color = if (q.stage.running || q.stage == QuestStage.PROPOSED) PleosStatic.blue else pleos.sub, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (q.stage.startable) {
            VLine()
            Box(Modifier.width(112.dp).fillMaxHeight().background(pleos.primary).clickable { a.start(e.id) }, contentAlignment = Alignment.Center) {
                Text("진행", color = pleos.onPrimary, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            }
        } else if (q.stage == QuestStage.ARRIVED) {
            VLine()
            Box(Modifier.width(112.dp).fillMaxHeight().background(PleosStatic.orange).clickable { a.show(PanelPage.Detail(e.id)) }, contentAlignment = Alignment.Center) {
                Text("완료", color = Color.Black, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun stageLine(q: Quest): String {
    val e = q.errand
    val s = q.stop
    return when (q.stage) {
        QuestStage.NEW, QuestStage.READY -> routeLine(e)
        QuestStage.PROPOSED -> "${e.proposal?.let { "${Kst.hhmm(it.etaAt)} 도착 가능" } ?: "조건부 수락"} · ${Korean.nim(e.fromName)}의 답을 기다려요"
        QuestStage.PICKING -> "Pleos 지도에서 고르는 중"
        QuestStage.ON_ROUTE -> "${s?.name ?: ""} · ${formatMinutes(s?.remainTimeSec ?: 0)} 뒤 도착"
        QuestStage.ARRIVED -> "${s?.name ?: e.targetPlaceName ?: "도착"} · 출발하면 자동으로 완료돼요"
        QuestStage.DONE -> "완료"
        QuestStage.CLOSED -> e.note ?: e.status.requesterLabel
    }
}

// endregion

// region 퀘스트 상세

@Composable
private fun ColumnScope.DetailPage(ui: CarUi, q: Quest, a: PanelActions) {
    val e = q.errand
    Header("${Korean.nim(e.fromName)}의 부탁", onBack = { a.show(PanelPage.Board) }, onClose = a::close)
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(22.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${e.type.label} · ${Kst.ago(e.createdAt, ui.now)}", color = pleos.sub, fontSize = 21.sp)
            if (e.urgent) Tag("급해요", PleosStatic.red)
            e.appointmentAt?.let { Tag("${Kst.hhmm(it)} 약속", PleosStatic.orange) }
        }
        Spacer(Modifier.height(8.dp))
        Text(e.title, color = pleos.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp)
        Spacer(Modifier.height(14.dp))
        Text(
            "\"${e.text}\"", color = pleos.text, fontSize = 22.sp, lineHeight = 30.sp,
            modifier = Modifier.fillMaxWidth().background(pleos.surface2).padding(16.dp),
        )
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.Top) {
            GlyphIcon(Glyph.PIN, pleos.text, 28.dp, Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(detailPlaceTitle(q), color = pleos.text, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                detailPlaceSub(q)?.let { Text(it, color = pleos.sub, fontSize = 20.sp, lineHeight = 27.sp) }
            }
        }
        if (e.type == ErrandType.PICKUP_PERSON && q.stage.running) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.LOCK, pleos.dim, 18.dp)
                Spacer(Modifier.width(8.dp))
                Text("${Korean.nim(e.fromName)}에게만 도착 예정 시각을 공유하고 있어요", color = pleos.dim, fontSize = 19.sp)
            }
        }
        cancelHint(q)?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = pleos.dim, fontSize = 19.sp, lineHeight = 26.sp)
        }
    }
    QuestActions(q, a)
}

/** 모든 부탁이 같은 자리에 같은 버튼 네 개를 쓴다. 지금 할 수 없는 버튼은 흐리게 두고, 다음 할 일은 검정으로 보여 준다 */
@Composable
private fun QuestActions(q: Quest, a: PanelActions) {
    val id = q.errand.id
    val s = q.stage
    if (s == QuestStage.PROPOSED) {
        ButtonRow { CellButton("제안 거두기", false, Modifier.weight(1f)) { a.cancel(id) } }
        return
    }
    if (!s.startable && !s.running) return
    ButtonRow {
        CellButton("진행", s.startable, Modifier.weight(1f), enabled = s.startable) { a.start(id) }
        VLine()
        CellButton("취소", false, Modifier.weight(1f)) { a.cancel(id) }
        VLine()
        CellButton("완료", s == QuestStage.ARRIVED, Modifier.weight(1f), enabled = s.running) { a.done(id) }
        VLine()
        CellButton("경로 재검색", s == QuestStage.PICKING, Modifier.weight(1f), enabled = s.running) { a.research(id) }
    }
}

/** 취소는 바로 가족에게 알려지므로 누르기 전에 결과를 알려 준다 */
private fun cancelHint(q: Quest): String? = when {
    q.stage == QuestStage.PROPOSED -> "제안을 거두면 ${Korean.nim(q.errand.fromName)}께 지금은 어렵다고 알려요"
    q.stage.startable || q.stage.running -> "취소하면 ${Korean.nim(q.errand.fromName)}께 못 한다고 알려요"
    else -> null
}

private fun detailPlaceTitle(q: Quest): String = when {
    q.stop != null -> q.stop.name
    q.errand.placeName != null -> q.errand.placeName.orEmpty()
    q.errand.hasAddress -> q.errand.address.orEmpty()
    else -> "근처 ${q.errand.searchKeyword}"
}

private fun detailPlaceSub(q: Quest): String? {
    val e = q.errand
    val s = q.stop
    return when (q.stage) {
        QuestStage.ARRIVED -> "도착했어요. 일을 마치면 완료를 눌러 주세요"
        QuestStage.ON_ROUTE -> if (s != null) "${formatDistance(s.remainDistanceM.toDouble())} · ${formatMinutes(s.remainTimeSec)} 뒤 도착" else null
        QuestStage.PICKING -> "Pleos 지도에서 한 곳을 골라 '경유지로' 또는 '목적지로'를 눌러 주세요"
        QuestStage.DONE -> "완료했어요"
        QuestStage.CLOSED -> e.note ?: e.status.requesterLabel
        QuestStage.PROPOSED -> "${e.proposal?.let { "${Kst.hhmm(it.etaAt)} 도착 가능으로 물어봤어요" } ?: "조건부 수락"} · 답이 오면 바로 알려 드려요"
        else -> when {
            e.placeName != null && e.hasAddress -> e.address
            e.hasAddress -> null
            e.type == ErrandType.BUY -> "Pleos 지도에서 근처를 찾아 한 곳을 고를 수 있어요"
            else -> "Pleos 지도에서 찾아 한 곳을 고를 수 있어요"
        }
    }
}

// endregion

// region 완료 → 다음 퀘스트

@Composable
private fun ColumnScope.DonePage(ui: CarUi, q: Quest, a: PanelActions) {
    val next = ui.startable
    val running = ui.current
    Header("퀘스트 완료", onClose = a::close)
    Column(Modifier.fillMaxWidth().padding(vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp).background(PleosStatic.blue, CircleShape), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.CHECK, Color.White, 54.dp) }
        Spacer(Modifier.height(14.dp))
        Text(q.errand.title, color = pleos.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 22.dp))
        Spacer(Modifier.height(4.dp))
        Text("${Korean.nim(q.errand.fromName)}께 완료를 알렸어요", color = pleos.sub, fontSize = 21.sp)
    }
    Hairline()
    Progress(ui)
    if (running != null) {
        Hairline()
        QuestRow(running, null, a)
    }
    if (next.isNotEmpty()) {
        Hairline()
        Text("다음 퀘스트를 할까요?", color = pleos.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            next.forEachIndexed { i, n ->
                Hairline()
                QuestRow(n, i + 1, a)
            }
        }
        ButtonRow { CellButton("오늘은 여기까지", false, Modifier.weight(1f), onClick = a::close) }
    } else if (running == null) {
        Hairline()
        Text("오늘의 퀘스트를 모두 끝냈어요", color = pleos.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp))
        ButtonRow {
            if (ui.destination == null && ui.homeName != null) {
                CellButton("집으로 안내", true, Modifier.weight(1f), onClick = a::routeHome)
                VLine()
            }
            CellButton("닫기", false, Modifier.weight(1f), onClick = a::close)
        }
    } else {
        ButtonRow { CellButton("닫기", false, Modifier.weight(1f), onClick = a::close) }
    }
}

// endregion

// region 음성 질문·지금 상황

@Composable
private fun VoiceState(ui: CarUi) {
    val (label, color) = when {
        ui.voice == VoiceStatus.SPEAKING -> "음성 안내 중" to PleosStatic.blue
        ui.voice == VoiceStatus.LISTENING -> "듣는 중" to PleosStatic.blue
        ui.holding -> "안전 구간에서 알려 드려요" to PleosStatic.orange
        else -> ui.drive.momentLabel to PleosStatic.headerSub
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
        Box(Modifier.size(11.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, color = PleosStatic.onHeader, fontSize = 19.sp, maxLines = 1)
    }
}

/** 카드 머리의 상태 표시 ("안내 보류", "진행 중") */
@Composable
private fun StageTag(text: String, color: Color) {
    Text(
        text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** 음성으로 말한 문장 그대로 (디자인의 따옴표 안내) */
@Composable
private fun Quote(text: String) {
    Text(
        "“$text”", color = pleos.text, fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().background(pleos.surface2).padding(horizontal = 18.dp, vertical = 14.dp),
    )
}

@Composable
private fun Title(text: String) {
    Text(text, color = pleos.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 39.sp)
}

@Composable
private fun Body(text: String, color: Color = pleos.sub) {
    Text(text, color = color, fontSize = 21.sp, lineHeight = 30.sp)
}

/** 부탁 카드 한 장: 제목과 장소·시간 줄 */
@Composable
private fun ErrandCard(title: String, line1: String?, line2: String?, line2Color: Color = pleos.sub, leading: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().border(1.dp, pleos.line).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        leading?.let {
            it()
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = pleos.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            line1?.let { Text(it, color = pleos.sub, fontSize = 19.sp, lineHeight = 26.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            line2?.let { Text(it, color = line2Color, fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** "해님어린이집 · 18:30까지" / "장소 미정 → 경로상 한빛마트" */
private fun placeLine(e: Errand, plan: ErrandPlan?): String {
    val place = when {
        e.type == ErrandType.BUY && !e.hasAddress && plan != null -> "장소 미정 → 경로상 ${plan.place.name}"
        e.type == ErrandType.BUY && !e.hasAddress -> "장소 미정 → 근처 ${e.searchKeyword}"
        else -> e.placeName ?: e.address ?: plan?.place?.name ?: e.searchKeyword
    }
    return listOfNotNull(place, e.appointmentAt?.let { "${Kst.hhmm(it)}까지" }).joinToString(" · ")
}

/** "도착 예정 18:20 · 여유 10분" / "도착 예정 18:50 · 약 20분 늦음" / "추가 시간 +3분" */
private fun timeLine(e: Errand, plan: ErrandPlan?): Pair<String, Boolean>? {
    plan ?: return null
    val eta = plan.etaAt
    val late = plan.lateMin(e)
    return when {
        eta != null && late != null -> "도착 예정 ${Kst.hhmm(eta)} · 약 ${late}분 늦음" to true
        eta != null && e.appointmentAt != null -> "도착 예정 ${Kst.hhmm(eta)} · 여유 ${((e.appointmentAt!! - eta) / 60_000).coerceAtLeast(0)}분" to false
        plan.detourMin != null -> "추가 시간 +${plan.detourMin}분" to false
        eta != null -> "도착 예정 ${Kst.hhmm(eta)}" to false
        else -> null
    }
}

@Composable
private fun ColumnScope.PromptPage(ui: CarUi, p: Prompt, canListen: Boolean, showTestTools: Boolean, a: PanelActions) {
    // 질문은 답하거나 시간이 지나면 저절로 닫힌다
    Header("오는 길에 부탁해", onClose = null, trailing = {
        VoiceState(ui)
        if (showTestTools) HeaderCell(Glyph.MORE) { a.show(PanelPage.Test) }
    })
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(22.dp)) {
        val cards: List<Pair<Errand, ErrandPlan?>> = when (p) {
            is Prompt.Offer -> listOf(p.errand to p.plan)
            is Prompt.Batch -> p.errands.map { it to p.plans[it.id] }
            else -> emptyList()
        }
        val subject = when (p) {
            is Prompt.Offer -> "${Korean.nim(p.errand.fromName)}의 부탁 · ${p.errand.type.label}"
            is Prompt.Batch -> p.errands.map { it.fromName }.distinct().singleOrNull()
                ?.let { "${Korean.nim(it)}의 부탁 ${p.errands.size}건" } ?: "부탁 ${p.errands.size}건"
            is Prompt.Completion -> "${Korean.nim(p.errand.fromName)}의 부탁 · ${p.errand.type.label}"
            is Prompt.NextQuest -> "${Korean.nim(p.errand.fromName)}의 부탁 · ${p.errand.type.label}"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(subject, color = pleos.sub, fontSize = 20.sp, modifier = Modifier.weight(1f))
            if (cards.isNotEmpty()) StageTag(ui.drive.momentLabel.substringBefore(" ·"), if (ui.drive.speed < 1) PleosStatic.green else PleosStatic.blue)
        }
        Spacer(Modifier.height(10.dp))
        if (cards.isEmpty()) {
            Text(p.script.headline, color = pleos.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 39.sp)
            p.script.question.takeIf { it.isNotEmpty() }?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = pleos.sub, fontSize = 23.sp, lineHeight = 30.sp)
            }
        } else {
            Quote(p.script.spoken)
            Spacer(Modifier.height(14.dp))
            cards.forEachIndexed { i, (e, plan) ->
                if (i > 0) Spacer(Modifier.height(8.dp))
                val t = timeLine(e, plan)
                ErrandCard(e.title, placeLine(e, plan), t?.first, if (t?.second == true) PleosStatic.orange else PleosStatic.blue)
            }
            Spacer(Modifier.height(12.dp))
            promptFootnote(p, cards)?.let { Body(it) }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            ui.heard?.takeIf { it.isNotBlank() }?.let { "들은 말: \"$it\"" }
                ?: if (canListen) "음성으로 답해 주세요" else "음성 인식을 쓸 수 없어 아래 버튼으로 답해 주세요",
            color = if (ui.heard.isNullOrBlank()) pleos.dim else PleosStatic.blue, fontSize = 19.sp,
        )
    }
    Hairline()
    Row(Modifier.fillMaxWidth().height(if (p.replies.any { p.hint(it) != null }) 118.dp else PleosSize.cell)) {
        p.replies.forEachIndexed { i, r ->
            if (i > 0) VLine()
            AnswerButton(p.label(r), p.hint(r), primary = r == Reply.YES, enabled = p.enabled(r), Modifier.weight(1f)) { a.reply(r) }
        }
    }
}

private fun promptFootnote(p: Prompt, cards: List<Pair<Errand, ErrandPlan?>>): String? {
    val late = cards.firstOrNull { (e, plan) -> plan?.lateMin(e) != null }
    return when {
        late != null -> {
            val (e, plan) = late
            "차량의 도착 예정 시간으로 지연을 계산했어요. 조건부 수락을 말하면 '${Kst.hhmm(plan!!.etaAt!!)} 도착 가능' 제안이 ${Korean.nim(e.fromName)}께 가요."
        }
        cards.size >= 2 && cards.all { it.second != null } -> "수락하면 ${cards.size}곳을 경유지로 추가하고 방문 순서를 정리해요."
        p is Prompt.Batch -> "수락하면 차례로 Pleos 지도에서 찾아 경유지로 넣어요."
        cards.firstOrNull()?.second != null -> "수락하면 바로 경유지로 넣고 안내해요."
        else -> null
    }
}

/** 두 줄 답 버튼: 버튼 글씨와 말로 하는 법 */
@Composable
private fun AnswerButton(label: String, hint: String?, primary: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg = when {
        !enabled -> pleos.surface2
        primary -> pleos.primary
        else -> pleos.surface
    }
    val fg = when {
        !enabled -> pleos.dim
        primary -> pleos.onPrimary
        else -> pleos.text
    }
    Column(
        modifier.fillMaxHeight().background(bg).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = fg, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        hint?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = if (primary && enabled) pleos.onPrimary.copy(alpha = 0.8f) else pleos.sub, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 지금 상황 카드 (디자인 01~11): 주행 중에는 목록 대신 이것만 보여 준다 (PRD 9 주행 중 UX 정책).
 * 보류 → 답변 대기 → 취소·거절 소식 → 도착 → 경유지 안내 → 완료 → 남은 부탁 → 대기 순으로 지금 가장 중요한 것 하나.
 */
@Composable
private fun ColumnScope.StatusPage(ui: CarUi, showTestTools: Boolean, a: PanelActions, canGoBack: Boolean) {
    Header("오는 길에 부탁해", onBack = if (canGoBack) ({ a.show(PanelPage.Board) }) else null, onClose = a::close, trailing = {
        VoiceState(ui)
        if (showTestTools) HeaderCell(Glyph.MORE) { a.show(PanelPage.Test) }
    })
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val n = ui.notice
        val fresh = n != null && ui.now - n.at < NOTICE_MS
        val arrived = ui.onRoute.firstOrNull { it.stage == QuestStage.ARRIVED }
        val active = ui.quests.any { it.errand.status.isActive && it.stage != QuestStage.NEW }
        when {
            ui.holding && ui.newCount > 0 -> HoldCard(ui)
            ui.proposed.isNotEmpty() -> WaitingCard(ui.proposed.first())
            fresh && (n!!.kind == NoticeKind.CANCELLED || n.kind == NoticeKind.DECLINED) && ui.onRoute.isEmpty() -> {
                StageTag(if (n.kind == NoticeKind.DECLINED) "거절" else "취소됨", PleosStatic.gray)
                Title(n.title)
                Quote(n.quote)
                n.detail?.let { Body(it) }
            }
            arrived != null -> ArrivedCard(ui, arrived)
            ui.onRoute.isNotEmpty() || ui.current != null -> RouteCard(ui)
            ui.doneItems.isNotEmpty() && !active -> DoneCard(ui)
            ui.startable.isNotEmpty() -> {
                StageTag("남은 부탁", PleosStatic.blue)
                Title("남은 부탁 ${ui.startable.size}개")
                ui.startable.forEach { q -> ErrandCard(q.errand.title, "${q.errand.fromName} · ${routeLine(q.errand)}", null) }
                Body(if (ui.driving) "주행 중에는 음성으로 알려 드려요. 목록은 정차하면 볼 수 있어요." else "목록에서 골라 진행할 수 있어요.")
            }
            else -> IdleCard(ui)
        }
        ui.caption?.takeIf { ui.voice == VoiceStatus.SPEAKING && it != n?.quote }?.let { Body(it, pleos.text) }
    }
    Hairline()
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(Glyph.MIC, pleos.sub, 22.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            if (ui.driving) "주행 중엔 음성으로만 응답해요" else "정차 중에는 목록과 상세 화면을 쓸 수 있어요",
            color = pleos.sub, fontSize = 19.sp, modifier = Modifier.weight(1f),
        )
        if (!ui.driving) {
            Text("목록", color = pleos.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { a.show(PanelPage.Board) }.padding(8.dp))
        }
    }
}

private const val NOTICE_MS = 40_000L

@Composable
private fun HoldCard(ui: CarUi) {
    StageTag("안내 보류", PleosStatic.orange)
    Title("새 부탁 ${ui.newCount}건이 도착했어요")
    val why = if (ui.drive.caution) "주의 구간이라" else "시속 ${ui.drive.speed.toInt()}km로 달리는 중이라"
    Body("$why 지금은 읽지 않아요. 신호 대기나 저속 구간에 들어서면 음성으로 안내할게요.")
    Column(Modifier.fillMaxWidth().border(1.dp, pleos.line)) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("안내를 보류하는 상황", color = pleos.sub, fontSize = 19.sp, modifier = Modifier.width(220.dp))
            Text("고속 주행 · 교차로 진입 · 합류 구간", color = pleos.text, fontSize = 19.sp, fontWeight = FontWeight.Medium)
        }
        Hairline()
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("안내하는 순간", color = pleos.sub, fontSize = 19.sp, modifier = Modifier.width(220.dp))
            Text("신호 대기 · 저속 구간 · 정차", color = pleos.text, fontSize = 19.sp, fontWeight = FontWeight.Medium)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(Glyph.LOCK, pleos.dim, 18.dp)
        Spacer(Modifier.width(8.dp))
        Text("부탁 내용은 안전한 순간에만 보여 드려요", color = pleos.dim, fontSize = 19.sp)
    }
}

@Composable
private fun WaitingCard(q: Quest) {
    val e = q.errand
    StageTag("답변 대기", PleosStatic.blue)
    Title("${Korean.nim(e.fromName)}에게 물어봤어요")
    Column(Modifier.fillMaxWidth().border(1.dp, pleos.line).padding(18.dp)) {
        Text("보낸 제안 · ${e.title}", color = pleos.sub, fontSize = 19.sp)
        Spacer(Modifier.height(6.dp))
        Text(e.proposal?.let { "${Kst.hhmm(it.etaAt)} 도착 가능" } ?: "조건부 수락", color = pleos.text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        e.proposal?.lateMin?.takeIf { it > 0 }?.let { Text("요청보다 약 ${it}분 늦음", color = PleosStatic.orange, fontSize = 20.sp, fontWeight = FontWeight.Medium) }
    }
    Body("${Korean.nim(e.fromName)}이 정하면 바로 알려 드릴게요. 그동안 기존 경로로 계속 안내해요.")
}

/** 경유지 안내 중 (디자인 05·09): 방문 순서와 도착 예정 */
@Composable
private fun RouteCard(ui: CarUi) {
    val n = ui.notice
    StageTag(if (n?.kind == NoticeKind.CONFIRMED) "확정" else "진행 중", PleosStatic.blue)
    n?.takeIf { it.kind == NoticeKind.ACCEPTED || it.kind == NoticeKind.CONFIRMED || it.kind == NoticeKind.DONE }?.let { Quote(it.quote) }
    var num = 0
    Column(Modifier.fillMaxWidth().border(1.dp, pleos.line)) {
        ui.stops.forEachIndexed { i, s ->
            if (i > 0) Hairline()
            val q = s.errandId?.let(ui::quest)
            StopRow(
                "${++num}", s.name,
                q?.let { listOfNotNull(it.errand.title, it.errand.appointmentAt?.let { t -> "${Kst.hhmm(t)}까지" }, it.plan?.detourMin?.let { d -> "+${d}분" }).joinToString(" · ") } ?: "직접 넣은 경유지",
                if (s.arrived) "도착" else Kst.hhmm(ui.now + s.remainTimeSec * 1000L),
                highlight = s.arrived,
            )
        }
        ui.destination?.takeIf { d -> ui.stops.none { it.name == d.name } }?.let { d ->
            if (ui.stops.isNotEmpty()) Hairline()
            StopRow(null, d.name, "최종 목적지", Kst.hhmm(ui.now + d.remainTimeSec * 1000L), highlight = false)
        }
        ui.quests.filter { it.stage == QuestStage.PICKING }.forEach { q ->
            Hairline()
            StopRow("·", q.plan?.place?.name ?: q.errand.title, if (q.plan != null) "${q.errand.title} · 경로에 넣는 중" else "Pleos 지도에서 고르는 중", "", highlight = false)
        }
    }
    n?.detail?.takeIf { n.kind == NoticeKind.ACCEPTED || n.kind == NoticeKind.CONFIRMED }?.let { Body(it) }
}

@Composable
private fun StopRow(num: String?, name: String, meta: String, eta: String, highlight: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).background(if (num == null) pleos.surface2 else if (highlight) PleosStatic.orange else PleosStatic.blue),
            contentAlignment = Alignment.Center,
        ) {
            if (num == null) GlyphIcon(Glyph.HOME, pleos.text, 24.dp) else Text(num, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = pleos.text, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, color = pleos.sub, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(eta, color = if (highlight) PleosStatic.orange else pleos.text, fontSize = 23.sp, fontWeight = FontWeight.Bold)
    }
}

/** 경유지 도착 자동 인식 (디자인 06): 부탁마다 진행 단계 */
@Composable
private fun ArrivedCard(ui: CarUi, q: Quest) {
    val n = ui.notice?.takeIf { it.kind == NoticeKind.ARRIVED }
    StageTag("도착", PleosStatic.orange)
    Title("${q.stop?.name ?: q.errand.targetPlaceName ?: "부탁한 곳"}에 도착했어요")
    n?.let { Quote(it.quote) }
    val related = (listOf(q) + ui.onRoute.filter { it.errand.id != q.errand.id }).take(3)
    related.forEach { r ->
        Column(Modifier.fillMaxWidth().border(1.dp, pleos.line).padding(16.dp)) {
            Text(listOfNotNull(r.errand.title, r.stop?.name?.takeIf { r.errand.type != ErrandType.PICKUP_PERSON }).joinToString(" · "), color = pleos.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Steps(r.errand)
        }
    }
    n?.detail?.let { Body(it) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(Glyph.LOCK, pleos.dim, 18.dp)
        Spacer(Modifier.width(8.dp))
        Text("${Korean.nim(q.errand.fromName)}에게는 진행 단계만 전해지고 위치는 공유되지 않아요", color = pleos.dim, fontSize = 19.sp)
    }
}

/** 수락 · 도착 · 픽업 완료 */
@Composable
private fun Steps(e: Errand) {
    val labels = e.progressLabels.drop(1)
    val at = e.progressIndex - 1
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, l ->
            val on = i <= at
            Text(
                l, color = if (on) Color.White else pleos.sub, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(if (on) PleosStatic.blue else pleos.surface2).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** 부탁 완료 (디자인 11) */
@Composable
private fun DoneCard(ui: CarUi) {
    StageTag("완료", PleosStatic.green)
    Title("부탁 ${ui.doneItems.size}건을 마쳤어요")
    ui.notice?.takeIf { it.kind == NoticeKind.DONE }?.let { Quote(it.quote) }
    Column(Modifier.fillMaxWidth().border(1.dp, pleos.line)) {
        ui.doneItems.forEachIndexed { i, d ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.CHECK, PleosStatic.blue, 30.dp)
                Spacer(Modifier.width(14.dp))
                Text(d.title, color = pleos.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(d.label, color = pleos.sub, fontSize = 19.sp)
            }
        }
    }
    ui.destination?.let { Body("${it.name} 도착 예정 ${Kst.hhmm(ui.now + it.remainTimeSec * 1000L)}") }
}

/** 대기 · 새 부탁 없음 (디자인 01) */
@Composable
private fun IdleCard(ui: CarUi) {
    StageTag("대기", PleosStatic.gray)
    Title("새 부탁이 없어요")
    Body("가족이 부탁을 보내면 신호 대기나 정차할 때 음성으로 알려 드려요.")
    if (ui.favorites.isNotEmpty()) {
        Text("자주 받는 부탁", color = pleos.sub, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        ui.favorites.take(3).forEach { f ->
            ErrandCard(f.title, "${f.ownerName} → ${f.toName} · ${f.summary}", f.daysLabel.takeIf { it.isNotEmpty() }?.let { "$it 반복" })
        }
    }
}

// endregion
