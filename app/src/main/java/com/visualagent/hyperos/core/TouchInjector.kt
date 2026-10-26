package com.visualagent.hyperos.core

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.visualagent.hyperos.BuildConfig
import com.visualagent.hyperos.shizuku.IShizukuShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * 硬性约束 3：触控注入器（Shizuku UID 2000 优先，AccessibilityService dispatchGesture 降级）
 * - 主路径：通过 Shizuku 提供的 shell 权限 (UID 2000) 执行 `input tap x y` 或 `input swipe`
 * - 备选降级：当 Shizuku 未激活或断开时（如 HyperOS 3 重启后未重新配对），自动降级至无障碍 dispatchGesture
 */
object TouchInjector {
    private const val TAG = "TouchInjector"
    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 2000

    enum class InjectionChannel {
        SHIZUKU_SHELL_UID_2000,
        ACCESSIBILITY_FALLBACK,
        UNAVAILABLE
    }

    private val _activeChannel = MutableStateFlow(InjectionChannel.UNAVAILABLE)
    val activeChannel: StateFlow<InjectionChannel> = _activeChannel.asStateFlow()

    private val _shizukuUid = MutableStateFlow(-1)
    val shizukuUid: StateFlow<Int> = _shizukuUid.asStateFlow()

    @Volatile
    private var shellService: IShizukuShellService? = null

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShizukuShellUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shizuku_shell")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service != null && service.pingBinder()) {
                shellService = IShizukuShellService.Stub.asInterface(service)
                _shizukuUid.value = try {
                    shellService?.uid ?: -1
                } catch (_: Exception) {
                    -1
                }
                refreshChannelState()
                Log.i(TAG, "ShizukuShellUserService connected! UID=${_shizukuUid.value}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            _shizukuUid.value = -1
            refreshChannelState()
            Log.w(TAG, "ShizukuShellUserService disconnected, falling back to Accessibility")
        }
    }

    fun initShizukuListeners() {
        Shizuku.addBinderReceivedListenerSticky {
            if (isShizukuPermissionGranted()) {
                bindShizukuService()
            }
            refreshChannelState()
        }
        Shizuku.addBinderDeadListener {
            shellService = null
            _shizukuUid.value = -1
            refreshChannelState()
        }
        Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE &&
                grantResult == PackageManager.PERMISSION_GRANTED
            ) {
                bindShizukuService()
            }
            refreshChannelState()
        }
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    fun isShizukuPermissionGranted(): Boolean {
        if (!isShizukuAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun requestShizukuPermission() {
        if (isShizukuAvailable() && !isShizukuPermissionGranted()) {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        } else if (isShizukuPermissionGranted()) {
            bindShizukuService()
        }
    }

    private fun bindShizukuService() {
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind Shizuku UserService", e)
        }
    }

    fun refreshChannelState() {
        val shizukuReady = isShizukuPermissionGranted() && shellService != null
        val a11yReady = VisualAgentAccessibilityService.instance != null
        _activeChannel.value = when {
            shizukuReady -> InjectionChannel.SHIZUKU_SHELL_UID_2000
            a11yReady -> InjectionChannel.ACCESSIBILITY_FALLBACK
            else -> InjectionChannel.UNAVAILABLE
        }
    }

    /**
     * 执行 Shell 命令（仅在 Shizuku UID 2000 可用时生效）
     */
    suspend fun execShizukuShell(command: String): Result<String> = withContext(Dispatchers.IO) {
        val service = shellService
        if (isShizukuPermissionGranted() && service != null) {
            try {
                val output = service.execCommand(command)
                if (output.startsWith("ERROR:")) {
                    Result.failure(IllegalStateException(output))
                } else {
                    Result.success(output)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        } else {
            Result.failure(IllegalStateException("Shizuku UID 2000 service not connected"))
        }
    }

    /**
     * 点击指定归一化坐标 (0–999)
     * 内部强制调用 CoordinateMapper 转换为物理像素，优先走 Shizuku `input tap`，失败降级走 dispatchGesture
     */
    suspend fun tapNormalized(
        context: Context,
        normalizedX: Int,
        normalizedY: Int
    ): Boolean = withContext(Dispatchers.IO) {
        val point = CoordinateMapper.toPhysicalPoint(context, normalizedX, normalizedY)
        Log.i(
            TAG,
            "tapNormalized: norm=(${point.rawNormalizedX},${point.rawNormalizedY}) -> pixel=(${point.x},${point.y})"
        )

        // 1. 主路径：Shizuku UID 2000 Shell
        val shizukuRes = execShizukuShell("input tap ${point.x} ${point.y}")
        if (shizukuRes.isSuccess) {
            return@withContext true
        }

        // 2. 降级路径：AccessibilityService dispatchGesture
        Log.w(TAG, "Shizuku tap unavailable (${shizukuRes.exceptionOrNull()?.message}), falling back to dispatchGesture")
        val a11y = VisualAgentAccessibilityService.instance ?: return@withContext false
        return@withContext a11y.performFallbackTap(point.x.toFloat(), point.y.toFloat())
    }

    /**
     * 滑动/滚动归一化坐标 (0–999)
     */
    suspend fun swipeNormalized(
        context: Context,
        startNormX: Int,
        startNormY: Int,
        endNormX: Int,
        endNormY: Int,
        durationMs: Int = 350
    ): Boolean = withContext(Dispatchers.IO) {
        val start = CoordinateMapper.toPhysicalPoint(context, startNormX, startNormY)
        val end = CoordinateMapper.toPhysicalPoint(context, endNormX, endNormY)
        val safeDuration = durationMs.coerceIn(100, 3000)

        // 1. 主路径：Shizuku UID 2000 `input swipe`
        val shizukuRes = execShizukuShell(
            "input swipe ${start.x} ${start.y} ${end.x} ${end.y} $safeDuration"
        )
        if (shizukuRes.isSuccess) {
            return@withContext true
        }

        // 2. 降级路径：AccessibilityService dispatchGesture
        val a11y = VisualAgentAccessibilityService.instance ?: return@withContext false
        return@withContext a11y.performFallbackSwipe(
            start.x.toFloat(),
            start.y.toFloat(),
            end.x.toFloat(),
            end.y.toFloat(),
            safeDuration.toLong()
        )
    }

    /**
     * 输入文本（先点击输入框归一化坐标，再注入文本）
     */
    suspend fun inputTextAtNormalized(
        context: Context,
        normalizedX: Int,
        normalizedY: Int,
        text: String
    ): Boolean = withContext(Dispatchers.IO) {
        tapNormalized(context, normalizedX, normalizedY)
        kotlinx.coroutines.delay(250)

        // 若为纯 ASCII 文本且 Shizuku 可用，直接用 input text
        val isAsciiPrintable = text.all { it.code in 32..126 } && !text.contains("'")
        if (isAsciiPrintable) {
            val escaped = text.replace(" ", "%s")
            if (execShizukuShell("input text '$escaped'").isSuccess) {
                return@withContext true
            }
        }

        // 中文或复杂字符通过无障碍 ACTION_SET_TEXT 注入当前焦点节点
        val a11y = VisualAgentAccessibilityService.instance ?: return@withContext false
        return@withContext a11y.inputTextToFocusedNode(text)
    }
}
