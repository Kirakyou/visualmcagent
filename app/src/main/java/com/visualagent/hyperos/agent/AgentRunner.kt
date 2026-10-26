package com.visualagent.hyperos.agent

import android.content.Context
import com.visualagent.hyperos.core.CoordinateMapper
import com.visualagent.hyperos.core.ScreenCaptureManager
import com.visualagent.hyperos.core.TouchInjector
import com.visualagent.hyperos.core.VisualAgentAccessibilityService
import com.visualagent.hyperos.ui.sound.McSoundManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 第二部分 1：Agent 层架构（规划与执行闭环）
 * - Planner：接收用户指令与当前屏幕截图/UI 树，生成动作序列
 * - Executor：解析动作（click, type, scroll, wait, open_app, shell_exec, get_foreground_app），调用执行器
 * - 感知循环：执行后严格等待 1 秒 (1000ms)，重新截图，判断 UI 变化，推送给模型判断是否 objective_fulfilled
 */
object AgentRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val geminiClient = GeminiComputerUseClient()
    private var currentTaskJob: Job? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatUiMessage>>(emptyList())
    val messages: StateFlow<List<ChatUiMessage>> = _messages.asStateFlow()

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    fun setApiKey(key: String) {
        _apiKey.value = key.trim()
    }

    fun appendMessage(msg: ChatUiMessage) {
        _messages.value = (_messages.value + msg).takeLast(120)
    }

    /**
     * 悬浮球点击停止时调用：立即向 AgentRunner 发送 Cancel 信号
     */
    fun cancelCurrentTask(reason: String = "用户点击悬浮球主动终止任务") {
        if (currentTaskJob?.isActive == true) {
            currentTaskJob?.cancel(CancellationException(reason))
            _isRunning.value = false
            McSoundManager.playError()
            appendMessage(
                ChatUiMessage(
                    role = ChatUiMessage.Role.SYSTEM_LOG,
                    content = "⏹ 已停止执行：$reason",
                    isSuccess = false
                )
            )
        }
    }

    fun startObjective(context: Context, userPrompt: String, onTriggerVoiceCall: () -> Unit) {
        val appContext = context.applicationContext
        appendMessage(ChatUiMessage(role = ChatUiMessage.Role.USER, content = userPrompt))

        // 第二部分 4：触发语音通话模式检测（输入特定提示词如“跟我通话”）
        if (userPrompt.contains("跟我通话") || userPrompt.contains("语音通话") || userPrompt.contains("Live通话")) {
            McSoundManager.playSuccess()
            appendMessage(
                ChatUiMessage(
                    role = ChatUiMessage.Role.SYSTEM_LOG,
                    content = "🎙 检测到语音通话指令，正在启动 Gemini Live API 双向音频流..."
                )
            )
            onTriggerVoiceCall()
            return
        }

        cancelCurrentTask("启动新任务")
        _isRunning.value = true
        McSoundManager.playClick()

        currentTaskJob = scope.launch {
            val historyLog = mutableListOf<String>()
            var stepCount = 0
            val maxSteps = 15

            try {
                while (isActive && stepCount < maxSteps) {
                    stepCount++
                    // 1. 获取最新感知帧（MediaProjection 主通道 + Accessibility UI 树辅通道）
                    val frame = ScreenCaptureManager.getLatestFrame()
                    val uiTree = VisualAgentAccessibilityService.instance?.dumpCompactUiTreeSummary()
                        ?: "[AccessibilityService not enabled]"
                    val fgPkg = LocalToolRegistry.getForegroundApp()

                    if (frame == null) {
                        appendMessage(
                            ChatUiMessage(
                                role = ChatUiMessage.Role.SYSTEM_LOG,
                                content = "⚠ 当前无可用录屏帧，正在请求 MediaProjection 授权或使用辅助通道...",
                                isSuccess = false
                            )
                        )
                    }

                    // 2. Planner：调用 Gemini Computer Use 规划下一步或判断是否 objective_fulfilled
                    val planResult = geminiClient.planNextStep(
                        apiKey = _apiKey.value,
                        userObjective = userPrompt,
                        base64JpegScreenshot = frame?.base64Jpeg,
                        uiTreeSummary = uiTree,
                        foregroundApp = fgPkg,
                        historyLog = historyLog
                    )

                    val plan = planResult.getOrElse { err ->
                        McSoundManager.playError()
                        appendMessage(
                            ChatUiMessage(
                                role = ChatUiMessage.Role.SYSTEM_LOG,
                                content = "❌ 规划请求异常: ${err.message}",
                                isSuccess = false
                            )
                        )
                        break
                    }

                    appendMessage(
                        ChatUiMessage(
                            role = ChatUiMessage.Role.AGENT_THOUGHT,
                            content = "Step $stepCount 思考: ${plan.thought}"
                        )
                    )

                    // 3. 终止判定：模型确认 objective_fulfilled == true
                    if (plan.objective_fulfilled || plan.action.equals("none", ignoreCase = true)) {
                        McSoundManager.playSuccess()
                        appendMessage(
                            ChatUiMessage(
                                role = ChatUiMessage.Role.SYSTEM_LOG,
                                content = "✅ 目标达成 (objective_fulfilled = true)！共执行 ${stepCount - 1} 步。"
                            )
                        )
                        break
                    }

                    // 4. Executor：执行具体动作或本地 Function Calling 工具
                    val execSummary = executeStep(appContext, plan)
                    historyLog.add("Step $stepCount [${plan.action}]: $execSummary")

                    // 5. 感知循环硬性要求：执行后等待 1 秒 (1000ms)，让 UI 动画与页面渲染稳定后再重新取帧验证
                    delay(1000L)
                }

                if (stepCount >= maxSteps) {
                    appendMessage(
                        ChatUiMessage(
                            role = ChatUiMessage.Role.SYSTEM_LOG,
                            content = "⚠ 已达单次任务最大步数限制 ($maxSteps 步)，自动停止以防死循环。",
                            isSuccess = false
                        )
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } finally {
                _isRunning.value = false
            }
        }
    }

    private suspend fun executeStep(context: Context, plan: AgentStepPlan): String {
        return when (plan.action.lowercase()) {
            "click" -> {
                val pt = CoordinateMapper.toPhysicalPoint(context, plan.normalized_x, plan.normalized_y)
                val ok = TouchInjector.tapNormalized(context, plan.normalized_x, plan.normalized_y)
                val coordDesc = "Norm(${plan.normalized_x}, ${plan.normalized_y}) -> Pixel(${pt.x}, ${pt.y})"
                appendMessage(
                    ChatUiMessage(
                        role = ChatUiMessage.Role.TOOL_CALL,
                        content = "执行点击 [${TouchInjector.activeChannel.value}]",
                        toolName = "click",
                        coordinatesInfo = coordDesc,
                        isSuccess = ok
                    )
                )
                "click $coordDesc success=$ok"
            }

            "type" -> {
                val pt = CoordinateMapper.toPhysicalPoint(context, plan.normalized_x, plan.normalized_y)
                val ok = TouchInjector.inputTextAtNormalized(
                    context,
                    plan.normalized_x,
                    plan.normalized_y,
                    plan.text
                )
                val coordDesc = "Norm(${plan.normalized_x}, ${plan.normalized_y}) -> Pixel(${pt.x}, ${pt.y})"
                appendMessage(
                    ChatUiMessage(
                        role = ChatUiMessage.Role.TOOL_CALL,
                        content = "输入文本: \"${plan.text}\"",
                        toolName = "type",
                        coordinatesInfo = coordDesc,
                        isSuccess = ok
                    )
                )
                "type '${plan.text}' at $coordDesc success=$ok"
            }

            "scroll" -> {
                val startPt = CoordinateMapper.toPhysicalPoint(context, plan.normalized_x, plan.normalized_y)
                val endPt = CoordinateMapper.toPhysicalPoint(context, plan.end_normalized_x, plan.end_normalized_y)
                val ok = TouchInjector.swipeNormalized(
                    context,
                    plan.normalized_x,
                    plan.normalized_y,
                    plan.end_normalized_x,
                    plan.end_normalized_y
                )
                val coordDesc = "Norm(${plan.normalized_x},${plan.normalized_y})->(${plan.end_normalized_x},${plan.end_normalized_y}) | Pixel(${startPt.x},${startPt.y})->(${endPt.x},${endPt.y})"
                appendMessage(
                    ChatUiMessage(
                        role = ChatUiMessage.Role.TOOL_CALL,
                        content = "滑动屏幕",
                        toolName = "scroll",
                        coordinatesInfo = coordDesc,
                        isSuccess = ok
                    )
                )
                "scroll $coordDesc success=$ok"
            }

            "open_app" -> {
                val res = LocalToolRegistry.openApp(context, plan.package_name)
                appendMessage(
                    ChatUiMessage(
                        role = ChatUiMessage.Role.TOOL_CALL,
                        content = res,
                        toolName = "open_app(${plan.package_name})",
                        isSuccess = !res.startsWith("ERROR")
                    )
                )
                res
            }

            "shell_exec" -> {
                val res = LocalToolRegistry.shellExec(plan.shell_command)
                appendMessage(
                    ChatUiMessage(
                        role = ChatUiMessage.Role.TOOL_CALL,
                        content = res,
                        toolName = "shell_exec(${plan.shell_command})",
                        isSuccess = !res.startsWith("ERROR") && !res.startsWith("REJECTED")
                    )
                )
                res
            }

            "get_foreground_app" -> {
                val pkg = LocalToolRegistry.getForegroundApp()
                appendMessage(
                    ChatUiMessage(
                        role = ChatUiMessage.Role.TOOL_CALL,
                        content = "当前前台包名: $pkg",
                        toolName = "get_foreground_app()",
                        isSuccess = true
                    )
                )
                "foreground_app=$pkg"
            }

            "wait" -> {
                val res = LocalToolRegistry.waitMs(plan.wait_ms)
                appendMessage(
                    ChatUiMessage(
                        role = ChatUiMessage.Role.TOOL_CALL,
                        content = res,
                        toolName = "wait(${plan.wait_ms}ms)",
                        isSuccess = true
                    )
                )
                res
            }

            else -> "Unknown action: ${plan.action}"
        }
    }
}
