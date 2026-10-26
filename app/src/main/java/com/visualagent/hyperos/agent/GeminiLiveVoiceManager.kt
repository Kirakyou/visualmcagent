package com.visualagent.hyperos.agent

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.visualagent.hyperos.core.ScreenCaptureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * 第二部分 4：语音通话模式（Gemini Live API / Firebase AI Logic BidiGenerateContent）
 * - 触发条件：输入“跟我通话”或点击悬浮窗/聊天窗语音按钮
 * - 双向音频流：上行 16kHz 16-bit Mono PCM，下行 24kHz 16-bit Mono PCM
 * - 视觉节流：通话期间自动调用 ScreenCaptureManager.setVoicePauseMode(true) 暂停高频视觉轮询，实现超低延迟对话
 */
object GeminiLiveVoiceManager {
    private const val TAG = "GeminiLiveVoiceManager"
    private const val INPUT_SAMPLE_RATE = 16000
    private const val OUTPUT_SAMPLE_RATE = 24000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private var webSocket: WebSocket? = null
    private var audioRecordJob: Job? = null
    private var audioTrack: AudioTrack? = null

    private val _isLiveActive = MutableStateFlow(false)
    val isLiveActive: StateFlow<Boolean> = _isLiveActive.asStateFlow()

    fun toggleVoiceSession(apiKey: String) {
        if (_isLiveActive.value) {
            stopVoiceSession()
        } else {
            startVoiceSession(apiKey)
        }
    }

    fun startVoiceSession(apiKey: String) {
        if (_isLiveActive.value || apiKey.isBlank()) return

        // 通话期间暂停高频视觉轮询，降低带宽与发热
        ScreenCaptureManager.setVoicePauseMode(true)
        _isLiveActive.value = true

        val wsUrl =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(wsUrl).build()

        initAudioTrack()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Gemini Live API WebSocket Connected")
                // 发送初始 Setup 握手帧（指定原生音频模型与 AUDIO 响应模态）
                val setupFrame = buildJsonObject {
                    put("setup", buildJsonObject {
                        put("model", "models/gemini-2.0-flash-exp")
                        put("generationConfig", buildJsonObject {
                            put("responseModalities", buildJsonArray {
                                add(kotlinx.serialization.json.JsonPrimitive("AUDIO"))
                            })
                        })
                    })
                }
                webSocket.send(setupFrame.toString())
                startMicrophoneStreaming(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Gemini Live WebSocket failure", t)
                stopVoiceSession()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopVoiceSession()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startMicrophoneStreaming(ws: WebSocket) {
        audioRecordJob?.cancel()
        audioRecordJob = scope.launch {
            val minBufSize = AudioRecord.getMinBufferSize(
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufSize * 2
            )

            val buffer = ByteArray(3200) // 100ms per chunk at 16kHz 16-bit
            try {
                recorder.startRecording()
                while (isActive && _isLiveActive.value) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val b64Pcm = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                        val realtimeMsg = buildJsonObject {
                            put("realtimeInput", buildJsonObject {
                                put("mediaChunks", buildJsonArray {
                                    add(buildJsonObject {
                                        put("mimeType", "audio/pcm;rate=$INPUT_SAMPLE_RATE")
                                        put("data", b64Pcm)
                                    })
                                })
                            })
                        }
                        ws.send(realtimeMsg.toString())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord streaming error", e)
            } finally {
                try {
                    recorder.stop()
                    recorder.release()
                } catch (_: Exception) {}
            }
        }
    }

    private fun initAudioTrack() {
        val minBuf = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4800)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }
    }

    private fun handleServerMessage(jsonText: String) {
        try {
            val root = json.parseToJsonElement(jsonText).jsonObject
            val parts = root["serverContent"]
                ?.jsonObject?.get("modelTurn")
                ?.jsonObject?.get("parts")
                ?.jsonArray ?: return

            for (part in parts) {
                val inlineData = part.jsonObject["inlineData"]?.jsonObject ?: continue
                val mime = inlineData["mimeType"]?.jsonPrimitive?.content.orEmpty()
                if (mime.startsWith("audio/pcm")) {
                    val b64 = inlineData["data"]?.jsonPrimitive?.content ?: continue
                    val pcmBytes = Base64.decode(b64, Base64.DEFAULT)
                    audioTrack?.write(pcmBytes, 0, pcmBytes.size)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Live API chunk", e)
        }
    }

    fun stopVoiceSession() {
        _isLiveActive.value = false
        ScreenCaptureManager.setVoicePauseMode(false)
        audioRecordJob?.cancel()
        audioRecordJob = null
        try {
            webSocket?.close(1000, "User ended voice call")
        } catch (_: Exception) {}
        webSocket = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }
}
