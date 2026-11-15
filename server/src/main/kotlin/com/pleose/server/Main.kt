package com.pleose.server

import com.pleose.shared.api.ApiError
import com.pleose.shared.api.ApiException
import com.pleose.shared.api.CancelRequest
import com.pleose.shared.api.CreateFamilyRequest
import com.pleose.shared.api.DeleteFavoriteRequest
import com.pleose.shared.api.JoinRequest
import com.pleose.shared.api.JoinResult
import com.pleose.shared.api.NewErrandRequest
import com.pleose.shared.api.NewFavoriteRequest
import com.pleose.shared.api.ProposalAnswer
import com.pleose.shared.api.PleoseJson
import com.pleose.shared.api.Snapshot
import com.pleose.shared.api.StatusUpdate
import com.pleose.shared.api.VehicleUpdate
import com.pleose.shared.hub.FamilyHub
import com.pleose.shared.model.Errand
import com.pleose.shared.model.Favorite
import com.pleose.shared.model.Member
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * pleose 가족 중계 서버.
 *
 * 요청자 앱(Android)과 차량 앱(Pleos)이 같은 가족 데이터를 보도록 부탁·상태를 중계한다.
 * 의존성 없이 JDK 내장 HttpServer로 동작하며, 변경 알림은 롱 폴링(/api/sync)으로 전한다.
 * 데이터는 메모리에만 둔다 (PoC). 운전자 위치 좌표는 받지도 저장하지도 않는다.
 *
 * 실행: ./gradlew :server:run  (기본 포트 8787, PORT 환경 변수로 변경)
 * 에뮬레이터에서 접속: http://10.0.2.2:8787
 */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: System.getenv("PORT")?.toIntOrNull() ?: 8787
    val server = startServer(port)
    println("pleose 중계 서버 실행 중: http://localhost:${server.address.port}  (Android 에뮬레이터: http://10.0.2.2:${server.address.port})")
}

/** 서버를 띄운다. [port]가 0이면 빈 포트를 쓴다 (테스트용) */
fun startServer(port: Int, hub: FamilyHub = FamilyHub(), quiet: Boolean = false): HttpServer {
    val server = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)
    server.executor = Executors.newCachedThreadPool() // 롱 폴링 연결마다 스레드 하나
    server.createContext("/", Router(hub, quiet))
    server.start()
    return server
}

private class Router(private val hub: FamilyHub, private val quiet: Boolean) : com.sun.net.httpserver.HttpHandler {
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")

    override fun handle(ex: HttpExchange) {
        val method = ex.requestMethod
        val path = ex.requestURI.path.trimEnd('/').ifEmpty { "/" }
        val query = parseQuery(ex.requestURI.rawQuery)
        try {
            val seg = path.split('/').filter { it.isNotEmpty() }
            when {
                method == "GET" && path == "/" -> text(ex, 200, "pleose relay server OK\n")
                method == "GET" && path == "/api/health" -> raw(ex, 200, """{"ok":true}""")

                method == "POST" && path == "/api/families" ->
                    reply(ex, JoinResult.serializer(), hub.createFamily(body(ex, CreateFamilyRequest.serializer())))
                        .also { log("가족 생성: ${it.family.name} (${it.family.inviteCode})") }

                method == "POST" && path == "/api/join" ->
                    reply(ex, JoinResult.serializer(), hub.join(body(ex, JoinRequest.serializer())))
                        .also { log("참여: ${it.member.name} → ${it.family.name}") }

                method == "GET" && path == "/api/sync" -> {
                    val familyId = query["familyId"] ?: throw ApiException(400, "familyId가 필요해요")
                    val memberId = query["memberId"] ?: throw ApiException(400, "memberId가 필요해요")
                    val since = query["since"]?.toLongOrNull() ?: 0
                    val wait = (query["waitMs"]?.toLongOrNull() ?: 25_000).coerceIn(0, 30_000)
                    reply(ex, Snapshot.serializer(), hub.awaitSnapshot(familyId, memberId, since, wait))
                }

                method == "POST" && path == "/api/errands" ->
                    reply(ex, Errand.serializer(), hub.createErrand(body(ex, NewErrandRequest.serializer())))
                        .also { log("부탁: ${it.fromName} → ${it.toName} [${it.type.label}] ${it.title} · ${it.delivery?.label}") }

                method == "POST" && seg.size == 4 && seg[1] == "errands" && seg[3] == "status" ->
                    reply(ex, Errand.serializer(), hub.updateStatus(seg[2], body(ex, StatusUpdate.serializer())))
                        .also { log("상태: ${it.title} → ${it.status} (${it.status.requesterLabel})") }

                method == "POST" && seg.size == 4 && seg[1] == "errands" && seg[3] == "cancel" ->
                    reply(ex, Errand.serializer(), hub.cancel(seg[2], body(ex, CancelRequest.serializer()).actorId))
                        .also { log("취소: ${it.title}") }

                method == "POST" && seg.size == 4 && seg[1] == "errands" && seg[3] == "proposal" -> {
                    val answer = body(ex, ProposalAnswer.serializer())
                    reply(ex, Errand.serializer(), hub.answerProposal(seg[2], answer.actorId, answer.accept))
                        .also { log("조건부 수락 답: ${it.title} → ${if (answer.accept) "좋아요" else "다른 방법 찾을게요"}") }
                }

                method == "POST" && path == "/api/favorites" ->
                    reply(ex, Favorite.serializer(), hub.saveFavorite(body(ex, NewFavoriteRequest.serializer())))
                        .also { log("즐겨찾기: ${it.ownerName} → ${it.toName} ${it.title}") }

                method == "POST" && seg.size == 4 && seg[1] == "favorites" && seg[3] == "delete" -> {
                    hub.deleteFavorite(seg[2], body(ex, DeleteFavoriteRequest.serializer()).actorId)
                    raw(ex, 200, """{"ok":true}""")
                }

                method == "POST" && seg.size == 4 && seg[1] == "members" && seg[3] == "vehicle" ->
                    reply(ex, Member.serializer(), hub.updateVehicle(seg[2], body(ex, VehicleUpdate.serializer())))
                        .also { log("차량: ${it.name} · ${it.vehicleLabel}") }

                else -> throw ApiException(404, "없는 경로예요: $method $path")
            }
        } catch (e: ApiException) {
            error(ex, e.code, e.message ?: "오류")
        } catch (e: SerializationException) {
            error(ex, 400, "요청 형식이 올바르지 않아요")
        } catch (e: IllegalArgumentException) {
            error(ex, 400, e.message ?: "요청 형식이 올바르지 않아요")
        } catch (e: Exception) {
            e.printStackTrace()
            error(ex, 500, "서버 오류")
        } finally {
            ex.close()
        }
    }

    private fun <T> body(ex: HttpExchange, serializer: KSerializer<T>): T =
        PleoseJson.decodeFromString(serializer, ex.requestBody.readBytes().toString(Charsets.UTF_8))

    private fun <T> reply(ex: HttpExchange, serializer: KSerializer<T>, value: T): T {
        raw(ex, 200, PleoseJson.encodeToString(serializer, value))
        return value
    }

    private fun error(ex: HttpExchange, code: Int, message: String) {
        if (code >= 500) log("오류 $code: $message")
        runCatching { raw(ex, code, PleoseJson.encodeToString(ApiError.serializer(), ApiError(message))) }
    }

    private fun raw(ex: HttpExchange, code: Int, json: String) = send(ex, code, json, "application/json; charset=utf-8")

    private fun text(ex: HttpExchange, code: Int, body: String) = send(ex, code, body, "text/plain; charset=utf-8")

    private fun send(ex: HttpExchange, code: Int, body: String, type: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.set("Content-Type", type)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun parseQuery(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { it.contains('=') }.associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    private fun log(msg: String) {
        if (!quiet) println("[${LocalTime.now().format(time)}] $msg")
    }
}
