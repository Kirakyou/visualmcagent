package com.visualagent.hyperos.core

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * 硬性约束 1：Gemini 3.5 Flash Computer Use 坐标映射器
 *
 * Gemini Computer Use 返回的所有坐标均基于 0–999 的归一化网格，与截图缩放后的分辨率（如 <=768px）无关。
 * 绝对禁止直接将模型返回的数字作为像素坐标进行点击！
 */
object CoordinateMapper {

    data class PixelPoint(
        val x: Int,
        val y: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        val rawNormalizedX: Int,
        val rawNormalizedY: Int
    )

    /**
     * 将模型返回的归一化 X 坐标 (0..999) 转换为物理屏幕像素 X 坐标
     */
    fun mapNormalizedX(normalizedX: Int, screenWidth: Int): Int {
        require(screenWidth > 0) { "screenWidth must be positive, got $screenWidth" }
        val pixelX = (normalizedX / 999.0 * screenWidth).roundToInt().coerceIn(0, screenWidth)
        return pixelX
    }

    /**
     * 将模型返回的归一化 Y 坐标 (0..999) 转换为物理屏幕像素 Y 坐标
     */
    fun mapNormalizedY(normalizedY: Int, screenHeight: Int): Int {
        require(screenHeight > 0) { "screenHeight must be positive, got $screenHeight" }
        val pixelY = (normalizedY / 999.0 * screenHeight).roundToInt().coerceIn(0, screenHeight)
        return pixelY
    }

    /**
     * 实时读取 Redmi K80 至尊版当前物理屏幕真实宽高（自动感知横竖屏旋转），并完成 0-999 -> 真实像素映射
     */
    fun toPhysicalPoint(context: Context, normalizedX: Int, normalizedY: Int): PixelPoint {
        val (screenWidth, screenHeight) = getPhysicalScreenSize(context)
        val pixelX = mapNormalizedX(normalizedX, screenWidth)
        val pixelY = mapNormalizedY(normalizedY, screenHeight)
        return PixelPoint(
            x = pixelX,
            y = pixelY,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            rawNormalizedX = normalizedX,
            rawNormalizedY = normalizedY
        )
    }

    /**
     * 获取包含系统状态栏/导航栏在内的整块屏幕真实物理像素尺寸（与 `input tap` 和 `MediaProjection` 坐标系一致）
     */
    fun getPhysicalScreenSize(context: Context): Pair<Int, Int> {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        defaultDisplay?.getRealMetrics(metrics)
        if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            return metrics.widthPixels to metrics.heightPixels
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.maximumWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }
}
