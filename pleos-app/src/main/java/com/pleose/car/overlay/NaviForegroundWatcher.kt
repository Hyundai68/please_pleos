package com.pleose.car.overlay

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.pleose.car.platform.PleosNavi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Pleos 내비가 화면에 떠 있는지 살핀다 — 내비 위에서만 Please 버튼을 보여 주기 위해서다.
 * NaviHelper SDK에는 이 정보가 없어 사용 기록 접근(UsageStats)을 쓴다.
 * 접근 권한이 없으면 내비가 보이는지 알 수 없으므로 버튼을 항상 보여 준다.
 */
class NaviForegroundWatcher(context: Context) {
    private val appContext = context.applicationContext
    private val usage = appContext.getSystemService(UsageStatsManager::class.java)

    private val _visible = MutableStateFlow(!hasAccess(appContext))
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    suspend fun run() {
        var since = System.currentTimeMillis() - LOOKBACK_MS
        while (coroutineContext.isActive) {
            if (hasAccess(appContext)) {
                val now = System.currentTimeMillis()
                latestNaviState(since, now)?.let { _visible.value = it }
                since = now - OVERLAP_MS
            } else {
                _visible.value = true
            }
            delay(POLL_MS)
        }
    }

    /** 내비 화면의 마지막 상태: 다른 앱과 나란히 있으면 PAUSED여도 보이고, STOPPED면 가려진 것 */
    private fun latestNaviState(from: Long, to: Long): Boolean? {
        val events = runCatching { usage.queryEvents(from, to) }.getOrNull() ?: return null
        val e = UsageEvents.Event()
        var result: Boolean? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.packageName != PleosNavi.NAVI_APP_PACKAGE) continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_PAUSED -> result = true
                UsageEvents.Event.ACTIVITY_STOPPED -> result = false
            }
        }
        return result
    }

    companion object {
        private const val POLL_MS = 1_000L
        private const val OVERLAP_MS = 2_000L
        private const val LOOKBACK_MS = 24 * 60 * 60_000L

        fun hasAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
