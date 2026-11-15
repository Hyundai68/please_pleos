package com.pleose.car.platform

import android.content.Context

/** 차량 앱 연결 정보 */
data class CarSession(
    /** true: 중계 서버로 가족과 연결, false: 차량 안에서만 도는 단독 데모 */
    val remote: Boolean,
    val serverUrl: String,
    val familyId: String,
    val familyName: String,
    val inviteCode: String,
    val memberId: String,
    val memberName: String,
)

class SessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pleose_car", Context.MODE_PRIVATE)

    var lastServerUrl: String
        get() = prefs.getString("serverUrl", null) ?: DEFAULT_SERVER
        set(v) = prefs.edit().putString("serverUrl", v).apply()

    /** 단독 데모로 시작했는지 — 다음 실행에도 설정 화면 없이 내비 위 버튼으로 바로 이어진다 (부탁은 새로 시작) */
    var demo: Boolean
        get() = prefs.getBoolean("demo", false)
        set(v) = prefs.edit().putBoolean("demo", v).apply()

    fun load(): CarSession? {
        if (!prefs.getBoolean("remote", false)) return null
        val familyId = prefs.getString("familyId", null) ?: return null
        val memberId = prefs.getString("memberId", null) ?: return null
        return CarSession(
            remote = true,
            serverUrl = lastServerUrl,
            familyId = familyId,
            familyName = prefs.getString("familyName", "") ?: "",
            inviteCode = prefs.getString("inviteCode", "") ?: "",
            memberId = memberId,
            memberName = prefs.getString("memberName", "") ?: "",
        )
    }

    fun save(s: CarSession) {
        prefs.edit()
            .putBoolean("remote", s.remote)
            .putString("serverUrl", s.serverUrl)
            .putString("familyId", s.familyId)
            .putString("familyName", s.familyName)
            .putString("inviteCode", s.inviteCode)
            .putString("memberId", s.memberId)
            .putString("memberName", s.memberName)
            .apply()
    }

    fun clear() {
        val url = lastServerUrl
        prefs.edit().clear().apply()
        lastServerUrl = url
    }

    companion object {
        /** Android 에뮬레이터에서 호스트 PC의 중계 서버 */
        const val DEFAULT_SERVER = "http://10.0.2.2:8787"
    }
}
