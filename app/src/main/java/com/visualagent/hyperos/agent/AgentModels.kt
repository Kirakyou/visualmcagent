package com.visualagent.hyperos.agent

import kotlinx.serialization.Serializable

/**
 * Agent 规划与执行数据模型
 */
@Serializable
data class AgentStepPlan(
    val thought: String,
    val objective_fulfilled: Boolean = false,
    val action: String, // "click", "type", "scroll", "wait", "open_app", "shell_exec", "get_foreground_app", "none"
    val normalized_x: Int = 500, // 0..999
    val normalized_y: Int = 500, // 0..999
    val end_normalized_x: Int = 500, // 0..999 (scroll 终点)
    val end_normalized_y: Int = 200, // 0..999 (scroll 终点)
    val text: String = "",
    val package_name: String = "",
    val shell_command: String = "",
    val wait_ms: Long = 1000L
)

data class ChatUiMessage(
    val id: Long = System.currentTimeMillis(),
    val role: Role,
    val content: String,
    val toolName: String? = null,
    val coordinatesInfo: String? = null,
    val isSuccess: Boolean = true
) {
    enum class Role { USER, AGENT_THOUGHT, TOOL_CALL, SYSTEM_LOG }
}
