package com.visualagent.hyperos

import android.app.Application
import com.visualagent.hyperos.core.TouchInjector
import com.visualagent.hyperos.ui.sound.McSoundManager

class VisualAgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        McSoundManager.init(this)
        TouchInjector.initShizukuListeners()
    }
}
