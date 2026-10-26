package com.visualagent.hyperos.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Gemini 3.5 Flash Computer Use 视觉规划与 Function Calling 客户端
 * 严格约束模型输出 0–999 归一化网格坐标与工具调用 JSON
 */
class GeminiComputerUseClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    companion object {
        // 默认使用支持高速视觉与 Computer Use 坐标归一化的 Gemini Flash 模型端点
        private const val DEFAULT_MODEL = "gemini-2.5-flash"

        private val SYSTEM_PROMPT = """
            You are VisualAgent, an Android 16 (Xiaomi HyperOS 3) system-level visual AI assistant.
            CRITICAL COORDINATE RULE:
            - All screen coordinates (normalized_x, normalized_y, end_normalized_x, end_normalized_y) MUST use a 0 to 999 normalized grid!
            - (0, 0) is top-left, (999, 999) is bottom-right. Never output raw pixel coordinates.
            
            AVAILABLE ACTIONS & LOCAL TOOLS:
            - "click": tap at (normalized_x, normalized_y)
            - "type": tap at (normalized_x, normalized_y) and input `text`
            - "scroll": swipe from (normalized_x, normalized_y) to (end_normalized_x, end_normalized_y)
            - "wait": wait for `wait_ms` milliseconds
            - "open_app": launch app by `package_name` (via Shizuku am start / monkey)
            - "shell_exec": run safe shell command in `shell_command`
            - "get_foreground_app": query current foreground package name
            - "none": use when `objective_fulfilled` is true.
            
            Always respond strictly with a single JSON object matching:
            {
              "thought": "concise reasoning about current screenshot and UI changes",
              "objective_fulfilled": boolean,
              "action": "click|type|scroll|wait|open_app|shell_exec|get_foreground_app|none",
              "normalized_x": 0-999,
              "normalized_y": 0-999,
              "end_normalized_x": 0-999,
              "end_normalized_y": 0-999,
              "text": "",
              "package_name": "",
              "shell_command": "",
              "wait_ms": 1000
            }
        """.trimIndent()
    }

    suspend fun planNextStep(
        apiKey: String,
        userObjective: String,
        base64JpegScreenshot: String?,
        uiTreeSummary: String,
        foregroundApp: String,
        historyLog: List<String>
    ): Result<AgentStepPlan> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("API Key 为空，请在设置页填写 Gemini API Key"))
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$DEFAULT_MODEL:generateContent?key=$apiKey"

        val partsArray = buildJsonArray {
            add(buildJsonObject {
                put("text", buildString {
                    appendLine("User Objective: $userObjective")
                    appendLine("Current Foreground App: $foregroundApp")
                    appendLine("Recent Execution History:\n${historyLog.takeLast(6).joinToString("\n")}")
                    appendLine("Auxiliary UI Tree Summary:\n$uiTreeSummary")
                })
            })
            if (!base64JpegScreenshot.isNullOrBlank()) {
                add(buildJsonObject {
                    put("inline_data", buildJsonObject {
                        put("mime_type", "image/jpeg")
                        put("data", base64JpegScreenshot)
                    })
                })
            }
        }

        val payload = buildJsonObject {
            put("system_instruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", SYSTEM_PROMPT) })
                })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", partsArray)
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", 0.1)
                put("responseMimeType", "application/json")
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Gemini API HTTP ${response.code}: ${bodyStr.take(250)}")
                    )
                }
                val rootObj = json.parseToJsonElement(bodyStr).jsonObject
                val textContent = rootObj["candidates"]
                    ?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("content")
                    ?.jsonObject?.get("parts")
                    ?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("text")
                    ?.jsonPrimitive?.content
                    ?: return@withContext Result.failure(IllegalStateException("Empty model response"))

                val stepPlan = json.decodeFromString(AgentStepPlan.serializer(), textContent)
                Result.success(stepPlan)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
