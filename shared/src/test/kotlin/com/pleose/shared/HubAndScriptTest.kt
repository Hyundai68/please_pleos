package com.pleose.shared

import com.pleose.shared.api.ApiException
import com.pleose.shared.api.CreateFamilyRequest
import com.pleose.shared.api.JoinRequest
import com.pleose.shared.api.NewErrandRequest
import com.pleose.shared.api.StatusUpdate
import com.pleose.shared.api.VehicleUpdate
import com.pleose.shared.hub.FamilyHub
import com.pleose.shared.model.Delivery
import com.pleose.shared.model.DriveState
import com.pleose.shared.model.ErrandStatus
import com.pleose.shared.model.ErrandType
import com.pleose.shared.model.PlaceCategory
import com.pleose.shared.voice.VoiceScripts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyHubTest {
    private var now = EVENING
    private val hub = FamilyHub(clock = { now })
    private val sujin = hub.createFamily(CreateFamilyRequest("수진"))
    private val jihun = hub.join(JoinRequest(sujin.family.inviteCode, "지훈"))
    private val familyId = sujin.family.id

    private fun send(text: String, type: ErrandType? = null, placeName: String? = null, appt: Long? = null) =
        hub.createErrand(NewErrandRequest(familyId, sujin.member.id, jihun.member.id, type, text, placeName, appt))

    @Test
    fun `같은 이름으로 참여하면 같은 구성원(휴대폰과 차량 연결)`() {
        val again = hub.join(JoinRequest(sujin.family.inviteCode.lowercase(), "지훈"))
        assertEquals(jihun.member.id, again.member.id)
        assertThrows(ApiException::class.java) { hub.join(JoinRequest("ZZZZZZ", "누구")) }
    }

    @Test
    fun `F-03 운전 중이면 차량 음성, 차량 미연결이면 앱 푸시`() {
        assertEquals(Delivery.APP_PUSH, send("우유 사 와").delivery)
        hub.updateVehicle(jihun.member.id, VehicleUpdate(true, DriveState.DRIVING))
        val e = send("해열제 사 와줘")
        assertEquals(Delivery.VEHICLE_VOICE, e.delivery)
        assertEquals(ErrandStatus.DELIVERED, e.status)
        assertEquals(PlaceCategory.PHARMACY, e.category)
    }

    @Test
    fun `F-02 필수값 검증`() {
        assertThrows(ApiException::class.java) { send("포장 받아 와줘", ErrandType.PICKUP_ITEM) } // 장소 필수
        assertThrows(ApiException::class.java) { send("아이 데려와줘", ErrandType.PICKUP_PERSON, "한빛태권도") } // 시간 필수
        assertThrows(ApiException::class.java) { send("그거 사와") } // 품목 해석 실패 → 요청자 확인
        assertThrows(ApiException::class.java) {
            hub.createErrand(NewErrandRequest(familyId, sujin.member.id, sujin.member.id, null, "우유"))
        }
    }

    @Test
    fun `F-11 데려와줘 도착 예정은 요청자에게만, 탑승 완료 즉시 종료`() {
        val e = send("아이 태권도 끝나면 데려와줘", null, "한빛태권도", at(19, 0))
        assertEquals(ErrandType.PICKUP_PERSON, e.type)
        assertEquals("한빛태권도", e.placeName)
        hub.updateStatus(e.id, StatusUpdate(jihun.member.id, ErrandStatus.ACCEPTED, targetPlaceName = "한빛태권도 판교점", pickupEtaAt = at(19, 2)))
        val forSujin = hub.snapshot(familyId, sujin.member.id).errand(e.id)!!
        val forJihun = hub.snapshot(familyId, jihun.member.id).errand(e.id)!!
        assertEquals("한빛태권도 판교점 앞", forSujin.pickupShare?.stopPoint)
        assertNull(forJihun.pickupShare)

        hub.updateStatus(e.id, StatusUpdate(jihun.member.id, ErrandStatus.IN_PROGRESS))
        hub.updateStatus(e.id, StatusUpdate(jihun.member.id, ErrandStatus.COMPLETED))
        assertNull(hub.snapshot(familyId, sujin.member.id).errand(e.id)!!.pickupShare)
    }

    @Test
    fun `거절하면 요청자에게 오늘은 어렵대요, 요청자만 취소 가능`() {
        val e = send("우유 사 와")
        val declined = hub.updateStatus(e.id, StatusUpdate(jihun.member.id, ErrandStatus.DECLINED))
        assertEquals("오늘은 어렵대요", declined.note)
        val e2 = send("해열제 사 와")
        assertThrows(ApiException::class.java) { hub.cancel(e2.id, jihun.member.id) }
        assertEquals(ErrandStatus.CANCELLED, hub.cancel(e2.id, sujin.member.id).status)
        assertThrows(ApiException::class.java) { hub.updateStatus(e2.id, StatusUpdate(jihun.member.id, ErrandStatus.ACCEPTED)) }
    }

    @Test
    fun `롱 폴링은 변경이 생기면 새 버전을 돌려준다`() {
        val v = hub.snapshot(familyId, sujin.member.id).version
        val t = Thread { Thread.sleep(100); send("우유 사 와") }
        t.start()
        val snap = hub.awaitSnapshot(familyId, sujin.member.id, v, 5_000)
        t.join()
        assertTrue(snap.version > v)
        assertEquals(1, snap.errands.size)
    }

    @Test
    fun `완료 후 30일이 지난 기록은 삭제`() {
        val e = send("우유 사 와")
        hub.updateStatus(e.id, StatusUpdate(jihun.member.id, ErrandStatus.DECLINED))
        now += FamilyHub.RETENTION_MS + 1
        send("해열제 사 와")
        assertNull(hub.snapshot(familyId, sujin.member.id).errand(e.id))
    }
}

class VoiceScriptTest {
    private var now = EVENING
    private val hub = FamilyHub(clock = { now })
    private val sujin = hub.createFamily(CreateFamilyRequest("수진"))
    private val jihun = hub.join(JoinRequest(sujin.family.inviteCode, "지훈"))

    private fun errand(text: String, from: String = sujin.member.id, placeName: String? = null, appt: Long? = null) =
        hub.createErrand(NewErrandRequest(sujin.family.id, from, jihun.member.id, null, text, placeName, appt))

    @Test
    fun `사 와줘 - 경로 근처 약국을 Pleos 지도에서`() {
        val e = errand("아이가 열이 나서 해열제 좀 사 와줘. 시럽형으로!")
        assertEquals("약국", e.searchKeyword)
        assertEquals("수진님이 아이 해열제를 부탁했어요. 경로 근처 약국을 Pleos 지도에서 찾아 드릴까요?", VoiceScripts.offer(e).spoken)
        assertEquals("Pleos 지도에 경로 근처 약국을 띄웠어요. 고른 곳을 경유지로 넣으면 수진님께 알려 드릴게요.", VoiceScripts.searchOpened(e).spoken)
        assertEquals("판교풀잎약국을 경유지로 넣었어요. 7시 3분에 도착해요.", VoiceScripts.waypointAdded("판교풀잎약국", at(19, 3)).spoken)
        assertEquals("해열제 챙겼어요?", VoiceScripts.completionQuestion(e).spoken)
    }

    @Test
    fun `두 번째 부탁 - 우유 2팩도`() {
        val e = errand("우유 2팩도 사 와줘")
        assertEquals("우유 2팩도 부탁했어요. 경로 근처 편의점을 Pleos 지도에서 찾아 드릴까요?", VoiceScripts.offer(e, followUp = true).spoken)
    }

    @Test
    fun `여러 건 - 태권도 먼저 김밥집은 그다음`() {
        val tkd = errand("아이 태권도 5시에 끝나, 데려와줘", placeName = "한빛태권도", appt = at(17, 0))
        val gimbap = errand("저녁 포장 주문해뒀어, 받아 와줘", placeName = "할매김밥")
        assertEquals(
            "부탁이 2개 있어요. 태권도 먼저, 김밥집은 그다음이에요. 차례로 Pleos 지도에서 찾아 경유지로 넣을까요?",
            VoiceScripts.batch(listOf(tkd, gimbap)).spoken,
        )
        assertEquals("수진님이 5시에 태권도에서 아이를 데려와 달래요. Pleos 지도에서 찾아 경유지로 넣을까요?", VoiceScripts.offer(tkd).spoken)
        assertEquals("수진님이 할매김밥 포장 주문을 받아 와 달래요. Pleos 지도에서 찾아 경유지로 넣을까요?", VoiceScripts.offer(gimbap).spoken)
        assertEquals("한빛태권도", tkd.searchKeyword)
        assertEquals("한빛태권도를 경유지로 넣었어요. 5시 2분에 도착해요. 약속보다 2분 늦어요.", VoiceScripts.waypointAdded("한빛태권도", at(17, 2), 2).spoken)
    }

    @Test
    fun `들러줘 - 반찬`() {
        val mom = hub.join(JoinRequest(sujin.family.inviteCode, "어머님"))
        val e = errand("반찬 챙겨뒀으니 들러서 가져가", from = mom.member.id, placeName = "부모님 댁")
        assertEquals("어머님이 반찬을 챙겨두셨대요. Pleos 지도에서 찾아 경유지로 넣을까요?", VoiceScripts.offer(e).spoken)
    }

    @Test
    fun `상세 주소가 있으면 주소로 안내할지 묻는다`() {
        val e = hub.createErrand(
            NewErrandRequest(sujin.family.id, sujin.member.id, jihun.member.id, null, "해열제 사 와줘", address = "경기도 성남시 수정구 창업로 17"),
        )
        assertEquals("수진님이 해열제를 부탁했어요. 요청한 주소로 안내할까요?", VoiceScripts.offer(e).spoken)
    }

    @Test
    fun `다음 부탁 질문`() {
        val e = errand("우유 2팩 사 와줘")
        assertEquals("남은 부탁이 2개 있어요. 다음은 우유 2팩이에요. 이어서 안내할까요?", VoiceScripts.nextQuest(e, 2).spoken)
        assertEquals("남은 부탁은 우유 2팩이에요. 이어서 안내할까요?", VoiceScripts.nextQuest(e, 1).spoken)
    }

    @Test
    fun `상세 주소와 좌표는 요청자와 수행자에게만 보인다`() {
        val mom = hub.join(JoinRequest(sujin.family.inviteCode, "어머님"))
        val loc = com.pleose.shared.model.GeoPoint(37.41, 127.09)
        val e = hub.createErrand(
            NewErrandRequest(sujin.family.id, sujin.member.id, jihun.member.id, null, "해열제 사 와줘", address = " 창업로 17 ", addressLocation = loc),
        )
        assertEquals("창업로 17", hub.snapshot(sujin.family.id, sujin.member.id).errand(e.id)?.address)
        assertEquals(loc, hub.snapshot(sujin.family.id, jihun.member.id).errand(e.id)?.addressLocation)
        val other = hub.snapshot(sujin.family.id, mom.member.id).errand(e.id)
        assertEquals(null, other?.address)
        assertEquals(null, other?.addressLocation)
    }

    @Test
    fun `주소 없이 좌표만 오면 저장하지 않는다`() {
        val e = hub.createErrand(
            NewErrandRequest(sujin.family.id, sujin.member.id, jihun.member.id, null, "해열제 사 와줘", addressLocation = com.pleose.shared.model.GeoPoint(37.41, 127.09)),
        )
        assertEquals(null, e.addressLocation)
    }
}
