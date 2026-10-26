package com.visualagent.hyperos.core

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 硬性约束 2 & 4：屏幕感知主通道 (MediaProjection)
 * - 频率控制：1 ~ 3 fps 可调（语音 Live 模式可自动暂停或降频）
 * - 图像压缩：长边强制缩放至 <= 768px，JPEG 压缩质量固定为 70
 * - 生命周期：实现 MediaProjection.Callback.onStop()，锁屏/通话结束被系统强停后立即唤起透明重连 Activity
 */
object ScreenCaptureManager {
    private const val TAG = "ScreenCaptureManager"
    private const val MAX_LONG_EDGE_PX = 768
    private const val JPEG_QUALITY = 70

    data class CapturedFrame(
        val jpegBytes: ByteArray,
        val base64Jpeg: String,
        val scaledWidth: Int,
        val scaledHeight: Int,
        val physicalWidth: Int,
        val physicalHeight: Int,
        val timestampMs: Long
    )

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private val _isProjectionActive = MutableStateFlow(false)
    val isProjectionActive: StateFlow<Boolean> = _isProjectionActive.asStateFlow()

    private val _fps = MutableStateFlow(2) // 默认 2 fps (范围 1..3)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private val _isPausedForVoice = MutableStateFlow(false)
    val isPausedForVoice: StateFlow<Boolean> = _isPausedForVoice.asStateFlow()

    @Volatile
    private var latestFrame: CapturedFrame? = null
    private val lastFrameTimeMs = AtomicLong(0L)

    fun setTargetFps(targetFps: Int) {
        _fps.value = targetFps.coerceIn(1, 3)
    }

    fun setVoicePauseMode(paused: Boolean) {
        _isPausedForVoice.value = paused
    }

    fun getLatestFrame(): CapturedFrame? = latestFrame

    /**
     * 初始化并启动 MediaProjection 虚拟显示流
     */
    fun startProjection(context: Context, resultCode: Int, resultData: Intent) {
        stopProjectionInternal(reRequest = false)

        val appContext = context.applicationContext
        val mpManager = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpManager.getMediaProjection(resultCode, resultData) ?: run {
            Log.e(TAG, "Failed to obtain MediaProjection instance")
            return
        }

        // 硬性约束 4：注册 MediaProjection.Callback.onStop()
        // Android 16 在锁屏、通话结束或单个应用录制会话超时后会强制终止 MediaProjection
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.w(TAG, "MediaProjection stopped by Android 16 system! Triggering re-auth Intent...")
                stopProjectionInternal(reRequest = true, context = appContext)
            }
        }
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        mediaProjection = projection

        val (physW, physH) = CoordinateMapper.getPhysicalScreenSize(appContext)
        val (scaledW, scaledH) = computeScaledDimensions(physW, physH, MAX_LONG_EDGE_PX)

        val thread = HandlerThread("VisualAgent-ScreenCapture").apply { start() }
        val handler = Handler(thread.looper)
        captureThread = thread
        captureHandler = handler

        // 直接按 <=768px 的目标尺寸建立 ImageReader 与 VirtualDisplay，大幅降低 GPU 与内存带宽消耗
        val reader = ImageReader.newInstance(scaledW, scaledH, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ ir ->
            val image = try {
                ir.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener

            try {
                if (_isPausedForVoice.value) {
                    return@setOnImageAvailableListener
                }
                val now = System.currentTimeMillis()
                val minIntervalMs = 1000L / _fps.value.coerceIn(1, 3)
                if (now - lastFrameTimeMs.get() < minIntervalMs) {
                    return@setOnImageAvailableListener
                }
                lastFrameTimeMs.set(now)

                val bitmap = imageToBitmap(image, scaledW, scaledH)
                val finalBitmap = EnsureMaxEdge(bitmap, MAX_LONG_EDGE_PX)
                val bos = ByteArrayOutputStream()
                // 硬性约束 2：JPEG 压缩质量设为 70
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
                if (finalBitmap !== bitmap) finalBitmap.recycle()
                bitmap.recycle()

                val jpegBytes = bos.toByteArray()
                val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

                latestFrame = CapturedFrame(
                    jpegBytes = jpegBytes,
                    base64Jpeg = base64,
                    scaledWidth = scaledW,
                    scaledHeight = scaledH,
                    physicalWidth = physW,
                    physicalHeight = physH,
                    timestampMs = now
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Frame processing error", t)
            } finally {
                image.close()
            }
        }, handler)

        imageReader = reader
        virtualDisplay = projection.createVirtualDisplay(
            "VisualAgent-Display",
            scaledW,
            scaledH,
            appContext.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )

        _isProjectionActive.value = true
        Log.i(TAG, "MediaProjection active: physical=${physW}x${physH}, scaled=${scaledW}x${scaledH}, fps=${_fps.value}")
    }

    /**
     * 计算长边缩放至 <= 768px 的等比分辨率（保持偶数对齐避免图像行跨距异常）
     */
    private fun computeScaledDimensions(width: Int, height: Int, maxLongEdge: Int): Pair<Int, Int> {
        val longEdge = max(width, height)
        if (longEdge <= maxLongEdge) return width to height
        val scale = maxLongEdge.toDouble() / longEdge.toDouble()
        val w = ((width * scale).roundToInt() and 0xFFFE).coerceAtLeast(2)
        val h = ((height * scale).roundToInt() and 0xFFFE).coerceAtLeast(2)
        return w to h
    }

    private fun EnsureMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = max(src.width, src.height)
        if (longEdge <= maxEdge) return src
        val (w, h) = computeScaledDimensions(src.width, src.height, maxEdge)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val paddedBitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        paddedBitmap.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) {
            paddedBitmap
        } else {
            val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
            paddedBitmap.recycle()
            cropped
        }
    }

    fun stopProjection(context: Context? = null) {
        stopProjectionInternal(reRequest = false, context = context)
    }

    private fun stopProjectionInternal(reRequest: Boolean, context: Context? = null) {
        _isProjectionActive.value = false
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {}
        imageReader = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        // 硬性约束 4：停止后主动弹出 Intent 重新申请录屏授权
        if (reRequest && context != null) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    "VisualAgent: Android 16 已中断录屏，请重新确认授权以恢复视觉感知",
                    Toast.LENGTH_LONG
                ).show()
                val reconnectIntent = Intent(context, MediaProjectionRequestActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(reconnectIntent)
            }
        }
    }
}

/**
 * 承载 MediaProjection 的前台服务（Android 14/15/16 强约束）
 */
class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val CHANNEL_ID = "visual_agent_capture_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VisualAgent 视觉感知运行中")
            .setContentText("正在以 1-3fps 低功耗模式捕获屏幕并监听指令")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            ScreenCaptureManager.startProjection(this, resultCode, resultData)
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VisualAgent 屏幕感知服务",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * 硬性约束 4：当 MediaProjection.Callback.onStop() 触发时，主动弹出的透明授权重连 Activity
 */
class MediaProjectionRequestActivity : Activity() {
    companion object {
        private const val REQ_CODE_PROJECTION = 9001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpManager.createScreenCaptureIntent(), REQ_CODE_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CODE_PROJECTION && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(serviceIntent)
        }
        finish()
    }
}
