package com.pleose.car

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pleose.car.overlay.NaviForegroundWatcher
import com.pleose.car.overlay.PleaseOverlayService
import com.pleose.car.ui.DemoActions
import com.pleose.car.ui.DemoScreen
import com.pleose.car.ui.PleosTheme
import com.pleose.car.ui.SetupActions
import com.pleose.car.ui.SetupScreen
import com.pleose.car.ui.SetupStatus
import com.pleose.shared.demo.DemoScenario
import kotlinx.coroutines.launch

/**
 * 처음 설정·권한·가족 연결만 맡는다. 준비가 끝나 있으면 화면을 보여 주지 않고
 * Please 버튼을 띄운 뒤 Pleos 내비로 넘어간다 (pleose는 별도 화면이 아니라 내비 위에서 쓴다).
 */
class MainActivity : ComponentActivity() {
    private val runtime by lazy { (application as PleoseApplication).runtime }
    private var status by mutableStateOf<SetupStatus?>(null)
    /** 패널의 톱니 버튼으로 일부러 연 설정: 준비가 끝나 있어도 내비로 넘어가지 않는다 */
    private var settingsMode = false
    /** 시연 시나리오 화면을 보는 중 */
    private var demoMode by mutableStateOf(false)

    private val demoActions = object : DemoActions {
        override fun start(s: DemoScenario): String? {
            if (!Settings.canDrawOverlays(this@MainActivity)) return "먼저 '내비 위에 표시'를 허용해 주세요 (설정 2번)"
            runtime.startDemo(s)?.let { return it }
            enterNavi()
            return null
        }
        override fun stop() = runtime.stopDemo()
        override fun back() {
            demoMode = false
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshStatus()
            runtime.startPlatform()
        }

    private val actions = object : SetupActions {
        override fun requestPermissions() = permissionLauncher.launch(PERMISSIONS.toTypedArray())
        override fun openOverlaySettings() {
            startActivity(overlaySettingsIntent())
        }
        override fun openUsageSettings() {
            runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        override fun connect(server: String, code: String, name: String) = runtime.connectRemote(server, code, name)
        override fun startDemo() = runtime.startLocalDemo()
        override fun disconnect() = runtime.disconnect()
        override fun goToNavi() = enterNavi()
        override fun close() = finish()
        override fun openDemo() {
            demoMode = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsMode = intent.getBooleanExtra(EXTRA_SETTINGS, false) || intent.getBooleanExtra(EXTRA_DEMO, false)
        demoMode = intent.getBooleanExtra(EXTRA_DEMO, false)
        refreshStatus()
        val missing = PERMISSIONS.filter { !granted(it) }
        // 권한 결과와 관계없이 시작: SDK를 못 쓰면 해당 기능만 빠진다
        if (missing.isEmpty()) runtime.startPlatform() else permissionLauncher.launch(missing.toTypedArray())
        setContent {
            val state by runtime.state.collectAsState()
            val demo by runtime.demo.collectAsState()
            PleosTheme {
                if (demoMode) {
                    BackHandler { demoMode = false }
                    DemoScreen(runtime.demoStore, demo, demoActions)
                } else {
                    status?.let { SetupScreen(state, it, settingsMode, actions) }
                }
            }
        }
        // adb로 시연 시작 (화면 녹화와 함께 쓰기 좋다):
        // adb shell am start --user 10 -n com.pleose.car/.MainActivity --es demo_start preset-a
        intent.getStringExtra(EXTRA_DEMO_START)?.let { id ->
            val s = runtime.demoStore.find(id)
            val problem = if (s == null) "시나리오를 찾을 수 없어요: $id" else demoActions.start(s)
            if (problem == null) return
            settingsMode = true
            demoMode = true
            android.widget.Toast.makeText(this, problem, android.widget.Toast.LENGTH_LONG).show()
        }
        if (intent.getBooleanExtra(EXTRA_DEMO_STOP, false)) {
            runtime.stopDemo()
            finish()
            return
        }
        // 설정을 마치는 순간(가족 연결) 바로 내비로 넘어간다
        lifecycleScope.launch {
            runtime.state.collect { if (!settingsMode && runtime.isReady(this@MainActivity)) enterNavi() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (!settingsMode && runtime.isReady(this)) enterNavi()
    }

    private fun enterNavi() {
        if (isFinishing) return
        runtime.startPlatform()
        PleaseOverlayService.start(this)
        runtime.navi.showNavigation()
        finish()
    }

    private fun refreshStatus() {
        status = SetupStatus(
            runtimePermissions = PERMISSIONS.all(::granted),
            overlay = Settings.canDrawOverlays(this),
            overlaySettingsAvailable = overlaySettingsIntent().resolveActivity(packageManager) != null,
            usageAccess = NaviForegroundWatcher.hasAccess(this),
            packageName = packageName,
        )
    }

    private fun overlaySettingsIntent() = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val EXTRA_SETTINGS = "settings"
        private const val EXTRA_DEMO = "demo"
        /** 이 id의 시연 시나리오를 바로 시작 */
        const val EXTRA_DEMO_START = "demo_start"
        /** 진행 중인 시연을 멈춘다 */
        const val EXTRA_DEMO_STOP = "demo_stop"

        fun settingsIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_SETTINGS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** 시연 시나리오 화면을 바로 연다 */
        fun demoIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_DEMO, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        private val PERMISSIONS = listOf(
            "android.car.permission.CAR_POWERTRAIN",  // 기어 (GEAR_SELECTION)
            "android.car.permission.CAR_SPEED",       // 차속 (PERF_VEHICLE_SPEED)
            "pleos.car.permission.CAR_DOORS",         // 도어 열림·닫힘 (데려와줘 탑승 감지)
            "pleos.car.permission.NAVI_ROUTE",        // 내비 경로·경유지·도착 이벤트
            "pleos.car.permission.NAVI_ROUTE_SEARCH", // 경로·경유지 요청
            "pleos.car.permission.NAVI_CUSTOM_MAP",   // 내비 화면 요청 (Pleos 지도 장소 검색)
            "pleos.car.permission.TTS_SERVICE",       // Pleos 음성 안내
            "pleos.car.permission.STT_SERVICE",       // Pleos 음성 인식
            Manifest.permission.RECORD_AUDIO,         // 음성 응답 인식
        )
    }
}
