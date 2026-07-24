package com.diegonmarcos.cloudkeyboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.diegonmarcos.cloudkeyboardlibs.IVoiceCallback
import com.diegonmarcos.cloudkeyboardlibs.IVoiceEngine
import com.diegonmarcos.superapp.voice.LanguageTagAware
import com.diegonmarcos.superapp.voice.VoiceEngineClient

/**
 * Voice engine client that binds the companion cloud-keyboard-libs service
 * over AIDL. Registered in App.onCreate so libs:voice's VoiceBarView works
 * without bundling Vosk in the keyboard APK.
 *
 * Audio capture stays in this process (cloud-keyboard holds RECORD_AUDIO);
 * PCM frames are forwarded to the companion via [feed], and recognition
 * results arrive on the companion's binder thread and are relayed to the
 * callbacks supplied in [start].
 *
 * If the service is not installed or not yet bound, all calls are no-ops.
 */
class AidlVoiceEngineClient(private val context: Context) : VoiceEngineClient, LanguageTagAware {

    private val LIBS_PKG    = "com.diegonmarcos.cloudkeyboardlibs"
    private val LIBS_ACTION = "com.diegonmarcos.cloudkeyboardlibs.IVoiceEngine"

    @Volatile private var engine: IVoiceEngine? = null
    @Volatile override var languageTag: String? = null
    private val main = Handler(Looper.getMainLooper())

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            engine = IVoiceEngine.Stub.asInterface(binder)
            // Push the current language tag if it was set before binding completed.
            languageTag?.let { runCatching { engine?.setLanguageTag(it) } }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            engine = null
            bindService()
        }
    }

    init { bindService() }

    private fun bindService() {
        runCatching {
            val intent = Intent(LIBS_ACTION).apply { setPackage(LIBS_PKG) }
            context.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }
    }

    override fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val e = engine ?: return
        val cbPartial = onPartial   // capture lambdas: the Stub's own onPartial/etc. would shadow them (infinite recursion)
        val cbFinal = onFinal
        val cbError = onError
        runCatching {
            e.start(object : IVoiceCallback.Stub() {
                override fun onPartial(text: String) { main.post { cbPartial(text) } }
                override fun onFinal(text: String)   { main.post { cbFinal(text) } }
                override fun onError(message: String) { main.post { cbError(message) } }
            })
        }
    }

    override fun feed(pcm: ByteArray, len: Int) {
        runCatching { engine?.feed(pcm, len) }
    }

    override fun stop() {
        runCatching { engine?.stop() }
    }
}
