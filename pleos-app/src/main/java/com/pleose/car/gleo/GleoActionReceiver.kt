package com.pleose.car.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Gleo Action 처리 결과 → Gleo Callback (status: success / asking / fail, message는 영어 한 문장) */
data class GleoResult(
    val status: String,
    val message: String,
    val data: List<Map<String, Any?>> = emptyList(),
) {
    companion object {
        fun success(message: String, data: List<Map<String, Any?>> = emptyList()) = GleoResult("success", message, data)
        fun asking(message: String, data: List<Map<String, Any?>> = emptyList()) = GleoResult("asking", message, data)
        fun fail(message: String) = GleoResult("fail", message)
    }
}

/**
 * Gleo Interface: Gleo AI가 운전자 음성 명령을 pleose 함수로 바꿔 부른다 (assets/schema.json).
 * pleose가 떠 있으면 진행자([com.pleose.car.agent.CarAgent])가 [handler]를 등록한다.
 */
object GleoBridge {
    const val ANSWER_ERRAND_REQUEST = "ANSWER_ERRAND_REQUEST"
    const val CONFIRM_ERRAND_DONE = "CONFIRM_ERRAND_DONE"
    const val READ_FAMILY_ERRANDS = "READ_FAMILY_ERRANDS"
    val FUNCTIONS = setOf(ANSWER_ERRAND_REQUEST, CONFIRM_ERRAND_DONE, READ_FAMILY_ERRANDS)

    /** (함수 이름, 파라미터) → 결과. 메인 스레드에서 7초 안에 끝나야 한다 */
    @Volatile
    var handler: ((String, JSONObject) -> GleoResult)? = null
}

/**
 * Gleo Action 수신 (component = "service" → Gleo가 sendBroadcast).
 * 파라미터는 "ACTION_PARAM" JSON 문자열로 오고, 키별 extra로도 올 수 있어 둘 다 읽는다.
 * 처리 결과는 ai.pleos.playground.intent.action.APP_ACTION_RESPONSE 브로드캐스트로 돌려준다.
 */
class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.action ?: return
        if (name !in GleoBridge.FUNCTIONS) return
        val params = runCatching { JSONObject(intent.getStringExtra(ACTION_PARAM) ?: "{}") }.getOrDefault(JSONObject())
        intent.extras?.keySet()?.filter { it != ACTION_PARAM && !params.has(it) }?.forEach { key ->
            intent.extras?.get(key)?.let { params.put(key, it) }
        }
        Log.i(TAG, "Gleo action $name $params")
        val result = runCatching { GleoBridge.handler?.invoke(name, params) }.getOrNull()
            ?: GleoResult.fail("오는 길에 부탁해 is not running. Open 오는 길에 부탁해 to handle family errands.")
        respond(context, name, result)
    }

    private fun respond(context: Context, functionName: String, r: GleoResult) {
        val json = JSONObject()
            .put("functionName", functionName)
            .put("status", r.status)
            .put("message", r.message)
        if (r.data.isNotEmpty()) json.put("data", JSONArray(r.data.map { JSONObject(it) }).toString())
        runCatching {
            context.sendBroadcast(
                Intent(RESPONSE_ACTION)
                    .putExtra("packageName", context.packageName)
                    .putExtra("response", json.toString()),
            )
        }.onFailure { Log.w(TAG, "respondAction onFailure >> $it") }
    }

    private companion object {
        const val TAG = "pleose-Gleo"
        const val ACTION_PARAM = "ACTION_PARAM"
        const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    }
}
