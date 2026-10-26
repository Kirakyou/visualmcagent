package com.visualagent.hyperos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.visualagent.hyperos.ui.overlay.FloatingOverlayController
import com.visualagent.hyperos.ui.screens.ChatScreen
import com.visualagent.hyperos.ui.screens.SettingsScreen
import com.visualagent.hyperos.ui.theme.McButtonVariant
import com.visualagent.hyperos.ui.theme.McColors
import com.visualagent.hyperos.ui.theme.McPixelButton

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_TAB = "extra_open_tab"
        const val TAB_SETTINGS = 0
        const val TAB_CHAT = 1
    }

    private var selectedTab by mutableIntStateOf(TAB_SETTINGS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNecessaryRuntimePermissions()
        FloatingOverlayController.attachToApplicationOverlayIfPermitted(this)
        handleTabIntent(intent)

        setContent {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(McColors.BgDarkStone)
            ) {
                // Minecraft 风格顶部双窗口导航栏 (设置页 / 聊天页)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    McPixelButton(
                        text = "⚙ 设置与激活引导",
                        variant = if (selectedTab == TAB_SETTINGS) McButtonVariant.GREEN else McButtonVariant.GRAY,
                        modifier = Modifier.weight(1f)
                    ) {
                        selectedTab = TAB_SETTINGS
                    }
                    McPixelButton(
                        text = "💬 视觉 Agent 聊天窗",
                        variant = if (selectedTab == TAB_CHAT) McButtonVariant.GREEN else McButtonVariant.GRAY,
                        modifier = Modifier.weight(1f)
                    ) {
                        selectedTab = TAB_CHAT
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    if (selectedTab == TAB_SETTINGS) {
                        SettingsScreen()
                    } else {
                        ChatScreen()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTabIntent(intent)
    }

    private fun handleTabIntent(intent: Intent?) {
        val tab = intent?.getIntExtra(EXTRA_OPEN_TAB, -1) ?: -1
        if (tab == TAB_CHAT || tab == TAB_SETTINGS) {
            selectedTab = tab
        }
    }

    private fun requestNecessaryRuntimePermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
        }
    }
}
