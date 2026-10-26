package com.visualagent.hyperos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.visualagent.hyperos.agent.ChatUiMessage
import com.visualagent.hyperos.agent.GeminiLiveVoiceManager
import com.visualagent.hyperos.ui.theme.McButtonVariant
import com.visualagent.hyperos.ui.theme.McColors
import com.visualagent.hyperos.ui.theme.McPanelContainer
import com.visualagent.hyperos.ui.theme.McPixelButton
import com.visualagent.hyperos.ui.theme.mcBevelPanel

@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val messages by AgentRunner.messages.collectAsState()
    val isRunning by AgentRunner.isRunning.collectAsState()
    val isLiveVoice by GeminiLiveVoiceManager.isLiveActive.collectAsState()
    val apiKey by AgentRunner.apiKey.collectAsState()
    var inputPrompt by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(McColors.BgDarkStone)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 顶部状态与语音通话快捷栏
        McPanelContainer(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isLiveVoice) "🎙 Live API 实时语音通话中 (已暂停高频截图)"
                        else if (isRunning) "⚡ Agent 视觉闭环规划执行中..."
                        else "🟢 VisualAgent 待命中 (支持输入“跟我通话”)",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
                McPixelButton(
                    text = if (isLiveVoice) "挂断语音" else "语音通话",
                    variant = if (isLiveVoice) McButtonVariant.RED else McButtonVariant.GREEN
                ) {
                    GeminiLiveVoiceManager.toggleVoiceSession(apiKey)
                }
            }
        }

        // 消息流列表（对话流 + 工具调用卡片 + 0-999 坐标映射日志）
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .mcBevelPanel(baseColor = Color(0xFF141415))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageCard(msg)
            }
        }

        // 底部指令输入与发送/停止按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = inputPrompt,
                onValueChange = { inputPrompt = it },
                cursorBrush = SolidColor(Color.White),
                textStyle = TextStyle(
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                modifier = Modifier
                    .weight(1f)
                    .mcBevelPanel(baseColor = Color(0xFF222224))
                    .padding(12.dp),
                decorationBox = { inner ->
                    if (inputPrompt.isEmpty()) {
                        Text(
                            text = "输入指令 (如: 打开设置并搜索蓝牙 / 跟我通话)",
                            color = Color(0xFF777777),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                    inner()
                }
            )

            if (isRunning) {
                McPixelButton(
                    text = "停止",
                    variant = McButtonVariant.RED
                ) {
                    AgentRunner.cancelCurrentTask("用户在聊天页点击停止按钮")
                }
            } else {
                McPixelButton(
                    text = "执行",
                    variant = McButtonVariant.GREEN
                ) {
                    val prompt = inputPrompt.trim()
                    if (prompt.isNotEmpty()) {
                        inputPrompt = ""
                        AgentRunner.startObjective(
                            context = context,
                            userPrompt = prompt,
                            onTriggerVoiceCall = {
                                GeminiLiveVoiceManager.startVoiceSession(apiKey)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageCard(msg: ChatUiMessage) {
    val bgColor = when (msg.role) {
        ChatUiMessage.Role.USER -> Color(0xFF2A4B22)
        ChatUiMessage.Role.AGENT_THOUGHT -> Color(0xFF2B2D30)
        ChatUiMessage.Role.TOOL_CALL -> Color(0xFF1F3347)
        ChatUiMessage.Role.SYSTEM_LOG -> if (msg.isSuccess) Color(0xFF263238) else Color(0xFF4A1F1F)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .mcBevelPanel(baseColor = bgColor)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "[${msg.role.name}] ${msg.toolName ?: ""}",
            color = Color(0xFFAED581),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
        Text(
            text = msg.content,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
        if (!msg.coordinatesInfo.isNullOrBlank()) {
            Text(
                text = "🎯 坐标映射: ${msg.coordinatesInfo}",
                color = Color(0xFFFFD54F),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }
    }
}
