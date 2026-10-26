package com.visualagent.hyperos.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.visualagent.hyperos.MainActivity
import com.visualagent.hyperos.agent.AgentRunner
import com.visualagent.hyperos.agent.GeminiLiveVoiceManager
import com.visualagent.hyperos.ui.sound.McSoundManager
import kotlin.math.abs
import kotlin.math.sin

/**
 * 第二部分 3：悬浮窗（Floating Bubble）
 * - 类型：优先 TYPE_ACCESSIBILITY_OVERLAY，备选 TYPE_APPLICATION_OVERLAY
 * - 动画驱动：Choreographer.postFrameCallback 原生高刷驱动，自绘单层 View Canvas
 * - 交互：点击停止任务 (Cancel AgentRunner) / 展开聊天窗；长按拖拽（拖拽时关闭非必要动画）
 */
class FloatingBubbleView(
    context: Context,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams
) : View(context), Choreographer.FrameCallback {

    private val density = resources.displayMetrics.density
    private val bubbleSizePx = (58 * density).toInt()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private var phaseRad = 0f
    private var isAttached = false
    private var isDragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var initialWinX = 0
    private var initialWinY = 0
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isAttached = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        isAttached = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    /**
     * Choreographer 高刷帧回调（跟随 60Hz / 120Hz / 144Hz VSYNC 信号）
     */
    override fun doFrame(frameTimeNanos: Long) {
        if (!isAttached) return

        // 硬性要求：长按拖拽时关闭非必要动画计算，节省渲染开销
        if (!isDragging) {
            val running = AgentRunner.isRunning.value
            val liveVoice = GeminiLiveVoiceManager.isLiveActive.value
            val speed = if (running || liveVoice) 0.14f else 0.04f
            phaseRad = (phaseRad + speed) % (Math.PI.toFloat() * 2f)
            invalidate()
        }

        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(bubbleSizePx, bubbleSizePx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val border = 3f * density

        val isRunning = AgentRunner.isRunning.value
        val isVoice = GeminiLiveVoiceManager.isLiveActive.value

        // 1. 绘制 Minecraft 像素方块外黑框
        paint.color = Color.parseColor("#111111")
        canvas.drawRect(0f, 0f, w, h, paint)

        // 2. 根据状态选择绿/红/蓝主色调（拖拽时使用静态石板灰，关闭呼吸脉冲）
        val baseColor = when {
            isDragging -> Color.parseColor("#7D7D7D")
            isRunning -> Color.parseColor("#B3312C") // 运行中显示红石色，点击即可立即停止
            isVoice -> Color.parseColor("#2C7IB3".replace('I', '8')) // 语音通话中显示钻石蓝
            else -> Color.parseColor("#3C8527") // 待机显示绿宝石色
        }
        paint.color = baseColor
        canvas.drawRect(border, border, w - border, h - border, paint)

        // 3. 非拖拽状态下绘制 Choreographer 驱动的像素能量脉冲内框
        if (!isDragging) {
            val pulseAlpha = ((sin(phaseRad.toDouble()) * 0.5 + 0.5) * 140 + 40).toInt()
            paint.color = Color.argb(pulseAlpha, 255, 255, 255)
            val inset = border * 2.2f
            canvas.drawRect(inset, inset, w - inset, inset + border, paint)
            canvas.drawRect(inset, inset, inset + border, h - inset, paint)
        }

        // 4. 绘制中心像素状态文字
        val label = when {
            isDragging -> "MOVE"
            isRunning -> "STOP"
            isVoice -> "LIVE"
            else -> "AI"
        }
        val textY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, w / 2f, textY, textPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                initialWinX = layoutParams.x
                initialWinY = layoutParams.y
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    isDragging = true
                    invalidate() // 立即切换为静态无动画绘制态
                }
                if (isDragging) {
                    layoutParams.x = (initialWinX + dx).toInt()
                    layoutParams.y = (initialWinY + dy).toInt()
                    try {
                        windowManager.updateViewLayout(this, layoutParams)
                    } catch (_: Exception) {}
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    invalidate()
                } else {
                    handleBubbleClick()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleBubbleClick() {
        McSoundManager.playClick()
        // 硬性要求：若 Agent 正在执行，点击悬浮球立即发送 Cancel 信号停止任务
        if (AgentRunner.isRunning.value) {
            AgentRunner.cancelCurrentTask("用户点击 Minecraft 悬浮球紧急停止")
            return
        }
        // 否则展开/拉起 VisualAgent 聊天窗口
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_CHAT)
        }
        context.startActivity(intent)
    }
}

/**
 * 悬浮球挂载管理器（支持 TYPE_ACCESSIBILITY_OVERLAY 与 TYPE_APPLICATION_OVERLAY 双通道）
 */
object FloatingOverlayController {
    private var bubbleView: FloatingBubbleView? = null
    private var currentWm: WindowManager? = null

    fun attachToAccessibilityOverlay(serviceContext: Context) {
        if (bubbleView != null) return
        val wm = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 420
        }
        val view = FloatingBubbleView(serviceContext, wm, params)
        try {
            wm.addView(view, params)
            bubbleView = view
            currentWm = wm
        } catch (_: Exception) {}
    }

    fun attachToApplicationOverlayIfPermitted(context: Context) {
        if (bubbleView != null) return
        if (!Settings.canDrawOverlays(context)) return
        val appCtx = context.applicationContext
        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 420
        }
        val view = FloatingBubbleView(appCtx, wm, params)
        try {
            wm.addView(view, params)
            bubbleView = view
            currentWm = wm
        } catch (_: Exception) {}
    }

    fun detachOverlay() {
        val view = bubbleView ?: return
        try {
            currentWm?.removeView(view)
        } catch (_: Exception) {}
        bubbleView = null
        currentWm = null
    }
}
