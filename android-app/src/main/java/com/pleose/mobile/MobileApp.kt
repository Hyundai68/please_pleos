package com.pleose.mobile

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pleose.shared.api.ConnectionState
import com.pleose.shared.api.PleoseApi
import com.pleose.shared.api.RemoteErrandRepository
import com.pleose.shared.api.Snapshot
import com.pleose.shared.model.Delivery
import com.pleose.shared.model.Errand
import com.pleose.shared.model.ErrandStatus
import com.pleose.shared.text.Korean
import com.pleose.shared.text.Kst
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 요청자 앱 연결 정보 */
data class MobileSession(
    val serverUrl: String,
    val familyId: String,
    val familyName: String,
    val inviteCode: String,
    val memberId: String,
    val memberName: String,
)

/**
 * 앱 전역: 가족 동기화(롱 폴링)와 상태 알림.
 * 화면이 꺼져 있어도 프로세스가 살아 있는 동안 받은 부탁(F-03 앱 푸시)과
 * 보낸 부탁의 상태 변화(F-10)를 알림으로 보여 준다. (PoC: FCM 대신 로컬 알림)
 */
class MobileApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { getSharedPreferences("pleose_mobile", Context.MODE_PRIVATE) }

    private val _session = MutableStateFlow<MobileSession?>(null)
    val session: StateFlow<MobileSession?> = _session.asStateFlow()
    var repo: RemoteErrandRepository? = null
        private set
    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()
    private val _connection = MutableStateFlow(ConnectionState.CONNECTING)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    private var jobs = listOf<Job>()

    private var started = 0
    val inForeground: Boolean get() = started > 0
    /** 부탁마다 마지막으로 본 상태 ("IN_PROGRESS", 도착했으면 "IN_PROGRESS+arrived") */
    private val seen = mutableMapOf<String, String>()
    private var firstSnapshot = true

    var lastServerUrl: String
        get() = prefs.getString("serverUrl", null) ?: DEFAULT_SERVER
        set(v) = prefs.edit().putString("serverUrl", v).apply()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started++ }
            override fun onActivityStopped(activity: Activity) { started-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        loadSession()?.let(::connect)
    }

    private fun loadSession(): MobileSession? {
        val familyId = prefs.getString("familyId", null) ?: return null
        val memberId = prefs.getString("memberId", null) ?: return null
        return MobileSession(
            lastServerUrl, familyId,
            prefs.getString("familyName", "") ?: "", prefs.getString("inviteCode", "") ?: "",
            memberId, prefs.getString("memberName", "") ?: "",
        )
    }

    fun connect(s: MobileSession) {
        prefs.edit()
            .putString("serverUrl", s.serverUrl).putString("familyId", s.familyId).putString("familyName", s.familyName)
            .putString("inviteCode", s.inviteCode).putString("memberId", s.memberId).putString("memberName", s.memberName)
            .apply()
        disconnectInternal()
        val r = RemoteErrandRepository(PleoseApi(s.serverUrl), s.familyId, s.memberId)
        repo = r
        firstSnapshot = true
        seen.clear()
        r.start(scope)
        jobs = listOf(
            scope.launch { r.snapshot.collect { snap -> snap?.let { onSnapshot(s, it) } } },
            scope.launch { r.connection.collect { _connection.value = it } },
        )
        _session.value = s
    }

    fun logout() {
        disconnectInternal()
        val url = lastServerUrl
        prefs.edit().clear().apply()
        lastServerUrl = url
        _session.value = null
        _snapshot.value = null
    }

    private fun disconnectInternal() {
        jobs.forEach { it.cancel() }
        repo?.stop()
        repo = null
    }

    private fun onSnapshot(s: MobileSession, snap: Snapshot) {
        _snapshot.value = snap
        val notify = !firstSnapshot
        firstSnapshot = false
        snap.errands.forEach { e ->
            val before = seen[e.id]
            val now = e.status.name + if (e.arrivedAt != null) "+arrived" else ""
            seen[e.id] = now
            if (!notify || before == now) return@forEach
            when {
                // 받은 부탁: 차에 없을 때(앱 푸시) 전달
                e.toId == s.memberId && before == null && e.delivery == Delivery.APP_PUSH -> post(
                    e, "${Korean.nim(e.fromName)}의 부탁 · ${e.type.label}", "${e.title} · 운전을 시작하면 차에서 음성으로도 알려 드려요",
                )
                // 보낸 부탁: 조건부 수락은 답이 필요하다
                e.fromId == s.memberId && e.status == ErrandStatus.PROPOSED -> post(
                    e, "${Korean.nim(e.toName)}이 조건을 붙여 답했어요", "${e.title} · ${e.proposal?.let { "${Kst.hhmm(it.etaAt)} 도착 가능" } ?: "확인해 주세요"}",
                )
                // 보낸 부탁: 상태 공유
                e.fromId == s.memberId && before != null && e.status in NOTIFY -> post(e, e.title, statusSentence(e))
            }
        }
    }

    private fun statusSentence(e: Errand): String = when (e.status) {
        ErrandStatus.IN_PROGRESS, ErrandStatus.ACCEPTED -> if (e.arrivedAt != null) "${e.targetPlaceName ?: "부탁한 곳"}에 도착했어요" else acceptedSentence(e)
        else -> otherSentence(e)
    }

    private fun acceptedSentence(e: Errand): String = when (e.status) {
        ErrandStatus.ACCEPTED -> "${Korean.nim(e.toName)}이 수락했어요${e.targetPlaceName?.let { " · $it 경유 예정" } ?: ""}"
        ErrandStatus.IN_PROGRESS -> "${e.targetPlaceName ?: "부탁한 곳"}에 들르고 있어요"
        ErrandStatus.COMPLETED -> "완료했어요"
        ErrandStatus.DECLINED, ErrandStatus.EXPIRED -> "오늘은 어렵대요"
        ErrandStatus.FAILED -> e.note ?: "못 했대요"
        else -> e.status.requesterLabel
    }

    private fun otherSentence(e: Errand): String = when (e.status) {
        ErrandStatus.COMPLETED -> "${e.doneLabel} · ${Korean.nim(e.toName)}이 마쳤어요"
        ErrandStatus.DECLINED -> "${Korean.nim(e.toName)}이 오늘은 어렵대요"
        else -> acceptedSentence(e)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "가족 부탁", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun post(e: Errand, title: String, text: String) {
        if (inForeground) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pleose)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(e.id.hashCode(), n) }
    }

    companion object {
        const val DEFAULT_SERVER = "http://10.0.2.2:8787"
        private const val CHANNEL = "errands"
        private val NOTIFY = setOf(
            ErrandStatus.ACCEPTED, ErrandStatus.IN_PROGRESS, ErrandStatus.COMPLETED,
            ErrandStatus.DECLINED, ErrandStatus.FAILED, ErrandStatus.EXPIRED,
        )
    }
}
