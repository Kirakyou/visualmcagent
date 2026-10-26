package com.visualagent.hyperos.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visualagent.hyperos.agent.AgentRunner
import com.visualagent.hyperos.core.MediaProjectionRequestActivity
import com.visualagent.hyperos.core.ScreenCaptureManager
import com.visualagent.hyperos.core.TouchInjector
import com.visualagent.hyperos.core.VisualAgentAccessibilityService
import com.visualagent.hyperos.ui.theme.McButtonVariant
import com.visualagent.hyperos.ui.theme.McColors
import com.visualagent.hyperos.ui.theme.McPanelContainer
import com.visualagent.hyperos.ui.theme.McPixelButton
import com.visualagent.hyperos.ui.theme.mcBevelPanel

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val apiKey by AgentRunner.apiKey.collectAsState()
    val fps by ScreenCaptureManager.fps.collectAsState()
    val isProjectionActive by ScreenCaptureManager.isProjectionActive.collectAsState()
    val injectionChannel by TouchInjector.activeChannel.collectAsState()
    val shizukuUid by TouchInjector.shizukuUid.collectAsState()
    val isA11yActive = VisualAgentAccessibilityService.instance != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(McColors.BgDarkStone)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. 硬件与系统状态卡片
        McPanelContainer(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "■ 系统状态监控 (Redmi K80 至尊版 / HyperOS 3)",
                    color = Color(0xFF52A535),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                StatusRow(
                    label = "1. 主触控通道 (Shizuku UID 2000)",
                    ok = injectionChannel == TouchInjector.InjectionChannel.SHIZUKU_SHELL_UID_2000,
                    detail = if (shizukuUid == 2000) "已激活 (UID=2000)" else "未激活 (已自动降级无障碍)"
                )
                StatusRow(
                    label = "2. 主视觉通道 (MediaProjection)",
                    ok = isProjectionActive,
                    detail = if (isProjectionActive) "运行中 (${fps}fps, <=768px, JPEG 70)" else "未授权或被锁屏中断"
                )
                StatusRow(
                    label = "3. 辅助通道 (AccessibilityService)",
                    ok = isA11yActive,
                    detail = if (isA11yActive) "已连接 (UI树 + 悬浮窗 + 降级手势)" else "未开启"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    McPixelButton(
                        text = "授权 Shizuku",
                        variant = McButtonVariant.GREEN,
                        modifier = Modifier.weight(1f)
                    ) {
                        TouchInjector.requestShizukuPermission()
                    }
                    McPixelButton(
                        text = if (isProjectionActive) "重连录屏" else "开启录屏",
                        variant = if (isProjectionActive) McButtonVariant.GRAY else McButtonVariant.GREEN,
                        modifier = Modifier.weight(1f)
                    ) {
                        context.startActivity(
                            Intent(context, MediaProjectionRequestActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    McPixelButton(
                        text = "无障碍设置",
                        variant = McButtonVariant.GRAY,
                        modifier = Modifier.weight(1f)
                    ) {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        }

        // 2. Gemini API Key 与 1-3 fps 取帧率调节
        McPanelContainer(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "■ Gemini API Key 与取帧率 (1-3 FPS)",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                BasicTextField(
                    value = apiKey,
                    onValueChange = { AgentRunner.setApiKey(it) },
                    cursorBrush = SolidColor(Color.White),
                    textStyle = TextStyle(
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .mcBevelPanel(baseColor = Color(0xFF141415))
                        .padding(12.dp),
                    decorationBox = { innerTextField ->
                        if (apiKey.isEmpty()) {
                            Text(
                                text = "在此粘贴 AIzaSy... Gemini API Key",
                                color = Color(0xFF777777),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        }
                        innerTextField()
                    }
                )

                Text(
                    text = "截图采样频率: 当前 $fps FPS (长边 <= 768px | JPEG 质量 = 70)",
                    color = Color(0xFFCCCCCC),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1, 2, 3).forEach { rate ->
                        McPixelButton(
                            text = "$rate FPS",
                            variant = if (fps == rate) McButtonVariant.GREEN else McButtonVariant.GRAY,
                            modifier = Modifier.weight(1f)
                        ) {
                            ScreenCaptureManager.setTargetFps(rate)
                        }
                    }
                }
            }
        }

        // 3. 硬性约束 3：HyperOS 3 无 Root 重启重激活警告与 Bugjaeger 分屏配对引导
        McPanelContainer(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "⚠ 重要提示：HyperOS 3 无 Root 环境 Shizuku 激活指南",
                    color = Color(0xFFFFB74D),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = """
                        1. 【重启失效风险】在无 Root 的 Redmi K80 至尊版 (HyperOS 3 / Android 16) 上，手机每次重启后 Shizuku 的 UID 2000 无线调试守护进程都会失效，需重新激活（未激活期间 VisualAgent 会自动降级为无障碍手势）。
                        2. 【开启开发者选项】进入 设置 -> 我的设备 -> 全部参数与信息 -> 连续点击 7 次“OS 版本”，然后进入 更多设置 -> 开发者选项，开启“USB 调试”、“USB 调试（安全设置）”与“无线调试”。
                        3. 【HyperOS 3 分屏配对技巧 (Shizuku / Bugjaeger)】
                           • Android 16 无线调试配对码弹窗一旦切出设置就会刷新失效！
                           • 请在最近任务中长按“设置”开启【小窗或上下分屏】，上半屏保持在“设置 -> 开发者选项 -> 无线调试 -> 使用配对码配对设备”，下半屏打开 Shizuku (推荐使用 yangFenTuoZi Android 16 维护版) 或 Bugjaeger 输入 6 位配对码与端口完成激活。
                    """.trimIndent(),
                    color = Color(0xFFDDDDDD),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                McPixelButton(
                    text = "打开开发者选项 (去开启无线调试)",
                    variant = McButtonVariant.GRAY,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    openDeveloperSettings(context)
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
        Text(
            text = if (ok) "[OK] $detail" else "[!] $detail",
            color = if (ok) Color(0xFF66BB6A) else Color(0xFFEF5350),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
    }
}

private fun openDeveloperSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
