package com.diegonmarcos.cloudkeyboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.diegonmarcos.cloudkeyboardlibs.ITranslateEngine
import com.diegonmarcos.superapp.translate.TranslateEngineClient

/**
 * Translate engine client that binds the companion cloud-keyboard-libs
 * service over AIDL. Registered in App.onCreate so libs:translate's
 * Translator / TranslateBarView work without bundling ML Kit.
 *
 * If the service is not installed or not yet bound, translate() returns
 * an empty result (Translator treats that as a no-op).
 */
class AidlTranslateEngineClient(private val context: Context) : TranslateEngineClient {

    private val LIBS_PKG    = "com.diegonmarcos.cloudkeyboardlibs"
    private val LIBS_ACTION = "com.diegonmarcos.cloudkeyboardlibs.ITranslateEngine"

    @Volatile private var engine: ITranslateEngine? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            engine = ITranslateEngine.Stub.asInterface(binder)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            engine = null
            // Re-bind so the client reconnects if the service restarts.
            bindService()
        }
    }

    init { bindService() }

    private fun bindService() {
        val result = runCatching {
            val intent = Intent(LIBS_ACTION).apply { setPackage(LIBS_PKG) }
            // bindService() returns false (no exception!) when the target service
            // can't be found/bound — e.g. the cloud-keyboard-libs companion app
            // isn't installed. That return value used to be silently discarded,
            // which meant a failed bind and a successful-but-pending bind were
            // indistinguishable from the outside. Log it so it's diagnosable.
            context.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }
        val bound = result.getOrDefault(false)
        if (!bound) {
            Log.w(TAG, "bindService to $LIBS_PKG/$LIBS_ACTION failed " +
                "(exception=${result.exceptionOrNull()}) — is cloud-keyboard-libs installed?")
        }
    }

    override fun translate(text: String, targetTag: String): Array<String> =
        engine?.translate(text, targetTag) ?: arrayOf("und", "")

    override fun supportedLanguages(): List<String> =
        engine?.supportedLanguages() ?: emptyList()

    override fun isConnected(): Boolean = engine != null

    private companion object {
        const val TAG = "AidlTranslateEngine"
    }
}
