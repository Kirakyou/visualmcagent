package com.visualagent.hyperos.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.visualagent.hyperos.ui.overlay.FloatingOverlayController
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 硬性约束 2 & 3：
 * 1. Android 16 上作为辅助通道提取精简 UI 树摘要（当 rootInActiveWindow 不为 null 时提供补充语义）
 * 2. 提供 Foreground Package 实时监听
 * 3. 在 Shizuku 未激活时提供 dispatchGesture 降级触控
 * 4. 承载 TYPE_ACCESSIBILITY_OVERLAY 级别的 Minecraft 悬浮球
 */
class VisualAgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: VisualAgentAccessibilityService? = null
            private set

        @Volatile
        var lastForegroundPackage: String = ""
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        TouchInjector.refreshChannelState()
        // 挂载 TYPE_ACCESSIBILITY_OVERLAY 悬浮窗
        FloatingOverlayController.attachToAccessibilityOverlay(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.isNotBlank() && pkg != packageName) {
                lastForegroundPackage = pkg
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        FloatingOverlayController.detachOverlay()
        instance = null
        TouchInjector.refreshChannelState()
        super.onDestroy()
    }

    /**
     * 辅助通道：提取当前活跃窗口的精简 UI 树文本摘要（上限 40 个可交互节点，节省 Token）
     * 注意：在 Android 16 某些系统级保护层或切换瞬间可能返回 null，此时完全依赖 MediaProjection 截图通道
     */
    fun dumpCompactUiTreeSummary(): String {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return "[UI_TREE_NULL_ON_ANDROID_16: Relying on primary MediaProjection screenshot channel]"

        val lines = mutableListOf<String>()
        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || lines.size >= 40) return
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val viewId = node.viewIdResourceName?.substringAfterLast("/") ?: ""
            val clickable = node.isClickable || node.isEditable
            if (clickable || text.isNotEmpty() || desc.isNotEmpty()) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                lines.add(
                    "id=$viewId text=\"${text.take(25)}\" desc=\"${desc.take(25)}\" click=$clickable bounds=[${rect.left},${rect.top},${rect.right},${rect.bottom}]"
                )
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i), depth + 1)
            }
        }
        traverse(root, 0)
        return if (lines.isEmpty()) "[UI_TREE_EMPTY]" else lines.joinToString("\n")
    }

    /**
     * 降级路径：使用 dispatchGesture 执行点击
     */
    suspend fun performFallbackTap(x: Float, y: Float): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 65L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!dispatched && cont.isActive) cont.resume(false)
        }

    /**
     * 降级路径：使用 dispatchGesture 执行滑动
     */
    suspend fun performFallbackSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    /**
     * 向当前焦点输入框设置文本（支持中文）
     */
    fun inputTextToFocusedNode(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
