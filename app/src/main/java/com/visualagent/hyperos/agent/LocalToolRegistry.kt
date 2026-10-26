package com.visualagent.hyperos.agent

import android.content.Context
import com.visualagent.hyperos.core.TouchInjector
import com.visualagent.hyperos.core.VisualAgentAccessibilityService
import kotlinx.coroutines.delay

/**
 * 第二部分 2：工具层 (Function Calling)
 * 除了视觉坐标外，提供本地系统级工具供 Gemini 调用
 */
object LocalToolRegistry {

    // 安全受限命令白名单前缀（允许桌面文件整理、查看目录、截图清理等安全操作，拦截高危破坏指令）
    private val FORBIDDEN_TOKENS = listOf("rm -rf /", "mkfs", "dd if=", "reboot", "su -c")

    /**
     * 1. open_app(packageName)：通过 Shizuku shell 执行 am start 或 monkey，若 Shizuku 未就绪则回退 PackageManager
     */
    suspend fun openApp(context: Context, packageName: String): String {
        val cleanPkg = packageName.trim()
        if (cleanPkg.isEmpty()) return "ERROR: packageName is empty"

        // 优先使用 Shizuku shell monkey / am start
        val shizukuCmd = "monkey -p $cleanPkg -c android.intent.category.LAUNCHER 1"
        val shizukuRes = TouchInjector.execShizukuShell(shizukuCmd)
        if (shizukuRes.isSuccess) {
            return "SUCCESS (via Shizuku UID 2000): Launched $cleanPkg"
        }

        // 降级使用 Context startActivity
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(cleanPkg)
            if (launchIntent != null) {
                launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                "SUCCESS (via PackageManager Fallback): Launched $cleanPkg"
            } else {
                "ERROR: Package not found: $cleanPkg"
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    /**
     * 2. shell_exec(command)：受限执行必要命令（如桌面整理相关的文件操作、窗口查询）
     */
    suspend fun shellExec(command: String): String {
        val trimmed = command.trim()
        if (FORBIDDEN_TOKENS.any { trimmed.contains(it, ignoreCase = true) }) {
            return "REJECTED: Command blocked by safety policy: $trimmed"
        }
        val res = TouchInjector.execShizukuShell(trimmed)
        return res.fold(
            onSuccess = { "STDOUT:\n$it" },
            onFailure = { "ERROR: ${it.message}" }
        )
    }

    /**
     * 3. get_foreground_app()：返回当前前台包名（优先 Shizuku dumpsys window，辅以无障碍监听缓存）
     */
    suspend fun getForegroundApp(): String {
        val shizukuRes = TouchInjector.execShizukuShell(
            "dumpsys window displays | grep -E 'mCurrentFocus|mFocusedApp'"
        )
        if (shizukuRes.isSuccess) {
            val out = shizukuRes.getOrNull().orEmpty()
            val regex = Regex("([a-zA-Z0-9_]+\\.[a-zA-Z0-9_.]+)/")
            val match = regex.find(out)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        val cached = VisualAgentAccessibilityService.lastForegroundPackage
        return if (cached.isNotBlank()) cached else "unknown.foreground.package"
    }

    /**
     * 4. wait(ms)：显式等待
     */
    suspend fun waitMs(ms: Long): String {
        val clamped = ms.coerceIn(100L, 15_000L)
        delay(clamped)
        return "Waited ${clamped}ms"
    }
}
