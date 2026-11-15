package com.pleose.shared

import com.pleose.shared.interpret.ErrandInterpreter
import com.pleose.shared.model.DriveState
import com.pleose.shared.model.ErrandStateMachine
import com.pleose.shared.model.ErrandStatus
import com.pleose.shared.model.ErrandType
import com.pleose.shared.model.PlaceCategory
import com.pleose.shared.policy.DrivingContext
import com.pleose.shared.policy.DrivingPolicy
import com.pleose.shared.policy.PickupSharePolicy
import com.pleose.shared.text.Korean
import com.pleose.shared.text.Kst
import com.pleose.shared.voice.Reply
import com.pleose.shared.voice.ReplyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** 평일 저녁 18:40 (시나리오 1: 오후 6시 40분 퇴근 운전 중) */
val EVENING: Long = ZonedDateTime.of(2026, 11, 3, 18, 40, 0, 0, Kst.zone).toInstant().toEpochMilli()

fun at(h: Int, m: Int): Long = ZonedDateTime.of(2026, 11, 3, h, m, 0, 0, Kst.zone).toInstant().toEpochMilli()

class InterpreterTest {
    @Test
    fun `해열제 → 약국, 시럽형 수식어 (화면 시안 ⑤)`() {
        val r = ErrandInterpreter.interpret("아이가 열이 나서 해열제 좀 사 와줘. 시럽형으로!")
        assertEquals(ErrandType.BUY, r.type)
        assertTrue(r.autoClassified)
        assertEquals(PlaceCategory.PHARMACY, r.category)
        assertEquals("해열제(시럽형)", r.item)
        assertEquals("아이 해열제 (시럽형)", r.title)
        assertEquals("약국으로 찾아볼게요 · 해열제(시럽형)", r.hint)
        assertFalse(r.ambiguous)
    }

    @Test
    fun `우유 2팩 → 편의점`() {
        val r = ErrandInterpreter.interpret("우유 2팩만 사다 줘")
        assertEquals(PlaceCategory.CONVENIENCE, r.category)
        assertEquals("우유 2팩", r.item)
        assertEquals("편의점으로 찾아볼게요 · 우유 2팩", r.hint)
    }

    @Test
    fun `한 글자 키워드는 다른 낱말 속에서 잡지 않는다`() {
        assertTrue(ErrandInterpreter.interpret("그거 사와").ambiguous)
        assertTrue(ErrandInterpreter.interpret("약속 있어서 그거 사와").ambiguous)
        assertEquals(PlaceCategory.CONVENIENCE, ErrandInterpreter.interpret("물 2병 사와").category)
    }

    @Test
    fun `유형 자동 분류 (부탁 유형별 처리)`() {
        assertEquals(ErrandType.PICKUP_PERSON, ErrandInterpreter.classify("아이 태권도 5시에 끝나, 데려와줘"))
        assertEquals(ErrandType.PICKUP_ITEM, ErrandInterpreter.classify("저녁 포장 주문해뒀어, 김밥집에서 받아 와줘"))
        assertEquals(ErrandType.DROP_BY, ErrandInterpreter.classify("어머님 댁 들러서 반찬 받아 와"))
        assertEquals(ErrandType.DROP_BY, ErrandInterpreter.classify("반품 박스 접수 좀"))
        assertEquals(ErrandType.BUY, ErrandInterpreter.classify("해열제"))
    }

    @Test
    fun `장소 이름 - 태권도 끝난 아이 픽업 (시안 ⑥), 김밥집 포장 주문`() {
        val r = ErrandInterpreter.interpret("아이 태권도 5시에 끝나, 데려와줘", null, "한빛태권도")
        assertEquals("태권도 끝난 아이 픽업", r.title)
        assertEquals("아이", r.person)
        val g = ErrandInterpreter.interpret("저녁 포장 주문해뒀어, 받아 와줘", null, "할매김밥")
        assertEquals("할매김밥 포장 주문", g.title)
        assertEquals("태권도", ErrandInterpreter.shortLabelOf("한빛태권도"))
        assertEquals("김밥집", ErrandInterpreter.shortLabelOf("할매김밥"))
        assertEquals("판교역", ErrandInterpreter.shortLabelOf("판교역"))
    }
}

class StateMachineTest {
    @Test
    fun `완료 전 어느 상태에서든 취소 가능, 완료 후에는 불가`() {
        for (s in listOf(ErrandStatus.CREATED, ErrandStatus.DELIVERED, ErrandStatus.ON_HOLD, ErrandStatus.ACCEPTED, ErrandStatus.IN_PROGRESS)) {
            assertTrue("$s → 취소", ErrandStateMachine.canTransition(s, ErrandStatus.CANCELLED))
        }
        assertFalse(ErrandStateMachine.canTransition(ErrandStatus.COMPLETED, ErrandStatus.CANCELLED))
        assertFalse(ErrandStateMachine.canTransition(ErrandStatus.DECLINED, ErrandStatus.ACCEPTED))
    }

    @Test
    fun `보류는 재알림되어 다시 전달됨, 수락은 진행 중을 거쳐 완료`() {
        assertTrue(ErrandStateMachine.canTransition(ErrandStatus.ON_HOLD, ErrandStatus.DELIVERED))
        assertTrue(ErrandStateMachine.canTransition(ErrandStatus.ACCEPTED, ErrandStatus.IN_PROGRESS))
        assertTrue(ErrandStateMachine.canTransition(ErrandStatus.IN_PROGRESS, ErrandStatus.COMPLETED))
        assertTrue(ErrandStateMachine.canTransition(ErrandStatus.ACCEPTED, ErrandStatus.EXPIRED))
        assertFalse(ErrandStateMachine.canTransition(ErrandStatus.DELIVERED, ErrandStatus.COMPLETED))
    }

    @Test
    fun `요청자 앱 표시 (PRD 8 상태 표)`() {
        assertEquals("전달됨", ErrandStatus.DELIVERED.requesterLabel)
        assertEquals("확인 전", ErrandStatus.ON_HOLD.requesterLabel)
        assertEquals("들르는 중", ErrandStatus.IN_PROGRESS.requesterLabel)
        assertEquals("오늘은 어려워요", ErrandStatus.EXPIRED.requesterLabel)
        assertEquals("오늘은 어려워요", ErrandStatus.FAILED.requesterLabel)
        assertEquals("취소됨", ErrandStatus.CANCELLED.requesterLabel)
    }
}

class PolicyAndTextTest {
    @Test
    fun `주의 구간(교차로 직전·고속·합류)에서는 알림 보류`() {
        assertFalse(DrivingPolicy.canAlert(DrivingContext(DriveState.DRIVING, 40.0, 120.0)))
        assertFalse(DrivingPolicy.canAlert(DrivingContext(DriveState.DRIVING, 95.0, 800.0)))
        assertFalse(DrivingPolicy.canAlert(DrivingContext(DriveState.DRIVING, 40.0, cautionZone = true)))
        assertTrue(DrivingPolicy.canAlert(DrivingContext(DriveState.DRIVING, 48.0)))
        assertTrue(DrivingPolicy.canAlert(DrivingContext(DriveState.STOPPED, 0.0, 50.0)))
        assertFalse(DrivingPolicy.canShowList(DriveState.DRIVING))
        assertTrue(DrivingPolicy.canShowList(DriveState.STOPPED))
    }

    @Test
    fun `도착 예정은 3분 이상 바뀔 때만 갱신`() {
        assertTrue(PickupSharePolicy.shouldUpdate(null, at(17, 2)))
        assertFalse(PickupSharePolicy.shouldUpdate(at(17, 2), at(17, 4)))
        assertTrue(PickupSharePolicy.shouldUpdate(at(17, 2), at(17, 5)))
    }

    @Test
    fun `음성 응답 해석 (F-05)`() {
        assertEquals(Reply.YES, ReplyParser.parse("응"))
        assertEquals(Reply.YES, ReplyParser.parse("응 찾아줘"))
        assertEquals(Reply.OTHER, ReplyParser.parse("다른 곳"))
        assertEquals(Reply.LATER, ReplyParser.parse("나중에"))
        assertEquals(Reply.NO, ReplyParser.parse("못 가"))
        assertEquals(Reply.NO, ReplyParser.parse("오늘은 안 돼"))
        assertEquals(Reply.NO, ReplyParser.parse("어려워"))
        val check = setOf(Reply.YES, Reply.NOT_DONE)
        assertEquals(Reply.NOT_DONE, ReplyParser.parse("못 샀어", check))
        assertEquals(Reply.NOT_DONE, ReplyParser.parse("아니", check))
        assertEquals(Reply.YES, ReplyParser.parse("응 샀어", check))
        assertNull(ReplyParser.parse("음악 틀어줘"))
    }

    @Test
    fun `버튼 글씨 그대로 말해도 알아듣고, 완료 확인은 부탁 종류와 상관없이 해석한다`() {
        val offer = setOf(Reply.YES, Reply.NO, Reply.LATER)
        assertEquals(Reply.YES, ReplyParser.parse("진행", offer))
        assertEquals(Reply.NO, ReplyParser.parse("취소", offer))
        assertEquals(Reply.YES, ReplyParser.parse("경로 재검색", offer))

        val next = setOf(Reply.YES, Reply.OTHER, Reply.LATER)
        assertEquals("다음 부탁을 안 하겠다는 말은 나중에로", Reply.LATER, ReplyParser.parse("취소", next))

        val completion = setOf(Reply.NOT_DONE, Reply.YES, Reply.RESEARCH)
        assertEquals(Reply.YES, ReplyParser.parse("완료", completion))
        assertEquals(Reply.NOT_DONE, ReplyParser.parse("취소", completion))
        assertEquals(Reply.RESEARCH, ReplyParser.parse("경로 재검색", completion))
        assertEquals(Reply.RESEARCH, ReplyParser.parse("다른 곳 찾아줘", completion))
        assertEquals(Reply.RESEARCH, ReplyParser.parse("다시 찾아줘", completion))

        // "했어"가 들어 있어도 못 한 것이다: 가족에게 완료로 잘못 알리면 안 된다
        assertEquals(Reply.NOT_DONE, ReplyParser.parse("못 했어", completion))
        assertEquals(Reply.NOT_DONE, ReplyParser.parse("안 했어", completion))
        assertEquals(Reply.NOT_DONE, ReplyParser.parse("못 태웠어", completion))
        assertEquals(Reply.YES, ReplyParser.parse("태웠어", completion))
        assertEquals(Reply.YES, ReplyParser.parse("들렀어", completion))
        assertEquals(Reply.YES, ReplyParser.parse("다 됐어", completion))
        assertEquals(Reply.NOT_DONE, ReplyParser.parse("됐어", completion))
    }

    @Test
    fun `조사와 시각`() {
        assertEquals("해열제를", Korean.eul("해열제"))
        assertEquals("포장 주문을", Korean.eul("포장 주문"))
        assertEquals("판교풀잎약국을", Korean.eul("판교풀잎약국"))
        assertEquals("약국으로", Korean.euro("약국"))
        assertEquals("김밥집으로", Korean.euro("김밥집"))
        assertEquals("마트로", Korean.euro("마트"))
        assertEquals("김밥집은", Korean.eun("김밥집"))
        assertEquals("9시", Kst.spokenMinuteOfDay(21 * 60))
        assertEquals("5시 2분", Kst.spoken(at(17, 2)))
        assertEquals("18:40", Kst.hhmm(EVENING))
    }
}
