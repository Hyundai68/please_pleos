package com.pleose.car.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.pleose.car.MainActivity
import com.pleose.car.PanelPage
import com.pleose.car.PleoseApplication
import com.pleose.car.R
import com.pleose.car.agent.CarUi
import com.pleose.car.ui.BadgeOverhang
import com.pleose.car.ui.PanelActions
import com.pleose.car.ui.PanelWidth
import com.pleose.car.ui.PleaseButton
import com.pleose.car.ui.PleaseCallout
import com.pleose.car.ui.PleosTheme
import com.pleose.car.ui.QuestPanel
import com.pleose.car.ui.TestTools
import com.pleose.shared.voice.Reply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Pleos 내비 위에 뜨는 Please 버튼과 패널.
 *
 * NaviHelper SDK에는 내비 화면에 UI를 넣는 공개 API가 없어(updateOccupiedRegion은 시스템 앱 전용)
 * 다른 앱 위에 그리는 창(TYPE_APPLICATION_OVERLAY)으로 내비의 오른쪽 위에 올린다.
 * 내비가 보일 때만 창을 띄우고, 창은 필요한 크기로만 만들어 그 밖의 터치는 내비로 그대로 간다.
 * 음성·가족 연결이 끊기지 않도록 포그라운드 서비스로 돈다.
 */
class PleaseOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owner = OverlayOwner()
    private val runtime by lazy { (application as PleoseApplication).runtime }
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }

    private lateinit var buttonWindow: OverlayWindow
    private lateinit var panelWindow: OverlayWindow
    private lateinit var calloutWindow: OverlayWindow

    /** 지금 진행자의 화면 상태 (가족 연결을 바꾸면 진행자도 바뀐다) */
    private val uiFlow = MutableStateFlow<CarUi?>(null)

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        startInForeground()
        runtime.startPlatform()
        owner.onCreate()

        val d = resources.displayMetrics.density
        val screenH = (resources.displayMetrics.heightPixels / d)
        val panelMax = (screenH - PANEL_TOP_DP - BOTTOM_RESERVED_DP).coerceAtLeast(320f)

        buttonWindow = OverlayWindow(
            params(BUTTON_RIGHT_DP - BadgeOverhang.value.toInt(), BUTTON_TOP_DP, (BUTTON_DP + BadgeOverhang.value).toInt(), (BUTTON_DP + BadgeOverhang.value).toInt()),
        ) { ButtonContent() }
        panelWindow = OverlayWindow(
            params(PANEL_RIGHT_DP, PANEL_TOP_DP, PanelWidth.value.toInt(), null, watchOutside = true),
            onOutsideTouch = ::onTouchOutsidePanel,
        ) { PanelContent(panelMax) }
        calloutWindow = OverlayWindow(params(PANEL_RIGHT_DP, BUTTON_TOP_DP, null, BUTTON_DP.toInt())) { CalloutContent() }

        val ui: Flow<CarUi?> = runtime.state.map { it.agent }.distinctUntilChanged().flatMapLatest { it?.ui ?: flowOf(null) }
        scope.launch { ui.collect { uiFlow.value = it } }

        val watcher = NaviForegroundWatcher(this)
        scope.launch { watcher.run() }
        scope.launch {
            combine(watcher.visible, uiFlow, runtime.panel) { naviVisible, u, page ->
                val canShow = naviVisible && u != null && Settings.canDrawOverlays(this@PleaseOverlayService)
                val panel = canShow && (page != null || u?.prompt != null)
                Triple(canShow, panel, canShow && !panel && u?.callout != null)
            }.distinctUntilChanged().collect { (button, panel, callout) ->
                buttonWindow.show(button)
                panelWindow.show(panel)
                calloutWindow.show(callout)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        buttonWindow.show(false)
        panelWindow.show(false)
        calloutWindow.show(false)
        owner.onDestroy()
        scope.cancel()
        super.onDestroy()
    }

    // region 창 내용

    @Composable
    private fun ButtonContent() {
        val u by uiFlow.collectAsState()
        val page by runtime.panel.collectAsState()
        val ui = u ?: return
        PleosTheme {
            PleaseButton(ui.badge, ui.current != null, ui.attention, ui.driving, page != null, runtime::onPleaseTapped)
        }
    }

    @Composable
    private fun CalloutContent() {
        val u by uiFlow.collectAsState()
        val callout = u?.callout ?: return
        PleosTheme { PleaseCallout(callout, runtime::onPleaseTapped) }
    }

    @Composable
    private fun PanelContent(maxHeightDp: Float) {
        val u by uiFlow.collectAsState()
        val page by runtime.panel.collectAsState()
        val state by runtime.state.collectAsState()
        val ui = u ?: return
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        PleosTheme {
            QuestPanel(
                ui, page, state.canListen, debuggable, androidx.compose.ui.unit.Dp(maxHeightDp), panelActions,
            ) { TestTools(runtime, state, ui) }
        }
    }

    private val panelActions = object : PanelActions {
        override fun close() = runtime.closePanel()
        override fun show(page: PanelPage) = runtime.showPanel(page)
        override fun start(errandId: String) = runtime.startQuest(errandId)
        override fun cancel(errandId: String) = runtime.cancelQuest(errandId)
        override fun done(errandId: String) = runtime.confirmDone(errandId)
        override fun research(errandId: String) = runtime.researchRoute(errandId)
        override fun routeHome() = runtime.routeHome()
        override fun openSettings() {
            runtime.closePanel()
            startActivity(MainActivity.settingsIntent(this@PleaseOverlayService))
        }
        override fun reply(r: Reply) {
            runtime.state.value.agent?.onChip(r)
        }
        override fun readRemaining() = runtime.readRemaining()
    }

    /** 패널 밖 지도를 누르면 닫는다. 음성 질문 중에는 음성으로 답할 수 있게 그대로 둔다 */
    private fun onTouchOutsidePanel() {
        if (uiFlow.value?.prompt == null) runtime.closePanel()
    }

    // endregion

    // region 창

    /** 화면 오른쪽 끝에서 [rightDp], 위에서 [topDp] 떨어진 창. 크기가 null이면 내용에 맞춘다 */
    private fun params(rightDp: Int, topDp: Int, widthDp: Int?, heightDp: Int?, watchOutside: Boolean = false): WindowManager.LayoutParams {
        val d = resources.displayMetrics.density
        fun px(dp: Int?) = dp?.let { (it * d).toInt() } ?: ViewGroup.LayoutParams.WRAP_CONTENT
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (watchOutside) flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        return WindowManager.LayoutParams(px(widthDp), px(heightDp), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (rightDp * d).toInt()
            y = (topDp * d).toInt()
            title = "오는 길에 부탁해"
            // 상태 바를 뺀 영역이 아니라 화면 전체 기준 좌표로 내비 버튼에 맞춘다
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
        }
    }

    @SuppressLint("ViewConstructor")
    private inner class OverlayWindow(
        private val layoutParams: WindowManager.LayoutParams,
        onOutsideTouch: (() -> Unit)? = null,
        content: @Composable () -> Unit,
    ) {
        private val root = OverlayFrame(this@PleaseOverlayService, onOutsideTouch).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            addView(
                ComposeView(context).apply {
                    setContent {
                        // 내용 크기에 맞는 창: 창을 다시 띄우는 사이에 내용이 커지면 창이 따라오지 않아 잘리므로 다시 재게 한다
                        Box(Modifier.onSizeChanged { post { this@OverlayWindow.relayout() } }) { content() }
                    }
                },
            )
        }
        private var shown = false

        private fun relayout() {
            if (shown) root.requestLayout()
        }

        fun show(visible: Boolean) {
            if (visible == shown) return
            runCatching {
                if (visible) windowManager.addView(root, layoutParams) else windowManager.removeView(root)
                shown = visible
            }.onFailure { Log.w(TAG, "overlay ${if (visible) "add" else "remove"} failed: ${it.message}") }
        }
    }

    private class OverlayFrame(context: Context, private val onOutsideTouch: (() -> Unit)?) : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                onOutsideTouch?.invoke()
                return true
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    /** 액티비티 없이 Compose를 돌리기 위한 수명 주기 */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun onCreate() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun onDestroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    // endregion

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "오는 길에 부탁해", NotificationManager.IMPORTANCE_MIN))
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("오는 길에 부탁해")
            .setContentText("Pleos 내비에서 가족 부탁을 알려 드려요")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val TAG = "pleose-Overlay"
        private const val CHANNEL = "please_overlay"
        private const val NOTIFICATION_ID = 1

        // Pleos 내비의 2D 버튼(오른쪽 끝에서 49dp, 위에서 114dp, 99dp 칸) 바로 아래 맞닿게 놓는다
        private const val BUTTON_DP = 99f
        private const val BUTTON_RIGHT_DP = 49
        private const val BUTTON_TOP_DP = 212
        /** 패널은 버튼 왼쪽, 2D 버튼 윗줄부터 */
        private const val PANEL_RIGHT_DP = 160
        private const val PANEL_TOP_DP = 114
        /** 아래쪽 내비 바(140dp) + 여백 */
        private const val BOTTOM_RESERVED_DP = 165

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PleaseOverlayService::class.java))
        }
    }
}

/** 차량이 켜지면 Please 버튼을 다시 띄운다 (가족 연결과 표시 권한이 있을 때만) */
class PleaseBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val runtime = (context.applicationContext as PleoseApplication).runtime
        if (runtime.isReady(context)) PleaseOverlayService.start(context)
    }
}
