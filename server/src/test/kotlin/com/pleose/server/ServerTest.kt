package com.pleose.server

import com.pleose.shared.api.ApiException
import com.pleose.shared.api.CreateFamilyRequest
import com.pleose.shared.api.JoinRequest
import com.pleose.shared.api.NewErrandRequest
import com.pleose.shared.api.PleoseApi
import com.pleose.shared.api.StatusUpdate
import com.pleose.shared.api.VehicleUpdate
import com.pleose.shared.model.Delivery
import com.pleose.shared.model.DriveState
import com.pleose.shared.model.ErrandStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 요청자 앱 ↔ 서버 ↔ 차량 앱 흐름을 실제 HTTP로 확인한다 */
class ServerTest {
    private val server = startServer(0, quiet = true)
    private val api = PleoseApi("http://127.0.0.1:${server.address.port}")

    @After
    fun tearDown() = server.stop(0)

    @Test
    fun `부탁 전송 → 차량 롱 폴링 수신 → 수락 → 요청자에게 들르는 중`() = runBlocking {
        assertTrue(api.health())
        val sujin = api.createFamily(CreateFamilyRequest("수진"))
        val jihun = api.join(JoinRequest(sujin.family.inviteCode, "지훈"))
        api.updateVehicle(jihun.member.id, VehicleUpdate(true, DriveState.DRIVING))

        val base = api.sync(sujin.family.id, jihun.member.id, 0, 0)
        // 차량 앱: 새 버전이 생길 때까지 기다림
        val waiting = async { api.sync(sujin.family.id, jihun.member.id, base.version, 10_000) }
        delay(200)
        val sent = api.createErrand(
            NewErrandRequest(sujin.family.id, sujin.member.id, jihun.member.id, null, "아이가 열이 나서 해열제 좀 사 와줘. 시럽형으로!", urgent = true),
        )
        assertEquals(Delivery.VEHICLE_VOICE, sent.delivery)
        val got = waiting.await()
        assertEquals("아이 해열제 (시럽형)", got.errands.single().title)

        api.updateStatus(sent.id, StatusUpdate(jihun.member.id, ErrandStatus.ACCEPTED, targetPlaceName = "새봄약국"))
        api.updateStatus(sent.id, StatusUpdate(jihun.member.id, ErrandStatus.IN_PROGRESS, homeEtaAt = 123L))
        val mine = api.sync(sujin.family.id, sujin.member.id, 0, 0).errands.single()
        assertEquals("들르는 중", mine.status.requesterLabel)
        assertEquals("새봄약국", mine.targetPlaceName)
        assertEquals(123L, mine.homeEtaAt)
    }

    @Test
    fun `검증 오류는 한국어 메시지로 돌려준다`() = runBlocking {
        val sujin = api.createFamily(CreateFamilyRequest("수진"))
        val jihun = api.join(JoinRequest(sujin.family.inviteCode, "지훈"))
        try {
            api.createErrand(NewErrandRequest(sujin.family.id, sujin.member.id, jihun.member.id, null, "그거 사와"))
            fail("품목 해석 실패여야 함")
        } catch (e: ApiException) {
            assertEquals(422, e.code)
            assertTrue(e.message!!.contains("품목"))
        }
    }
}
