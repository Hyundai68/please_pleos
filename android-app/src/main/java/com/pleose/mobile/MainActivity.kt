package com.pleose.mobile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import com.pleose.mobile.ui.MobileRoot

class MainActivity : ComponentActivity() {
    private val vm: MobileViewModel by viewModels()

    /** 초대 링크 pleose://join?code=ABC123 */
    private val inviteCode = mutableStateOf<String?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        inviteCode.value = codeFrom(intent)
        setContent { MobileRoot(vm, inviteCode.value) }
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        codeFrom(intent)?.let { inviteCode.value = it }
    }

    private fun codeFrom(intent: Intent?): String? =
        intent?.data?.takeIf { it.scheme == "pleose" && it.host == "join" }?.getQueryParameter("code")?.uppercase()
}
