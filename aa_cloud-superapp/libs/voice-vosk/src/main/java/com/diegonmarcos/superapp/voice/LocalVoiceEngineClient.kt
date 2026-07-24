package com.diegonmarcos.superapp.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * In-process [VoiceEngineClient] backed by Vosk. Used by cloud-keyboard-libs
 * (the companion APK) which includes libs:voice-vosk and therefore libvosk.so.
 *
 * Audio capture is NOT performed here — PCM frames arrive via [feed] from the
 * IME process (cloud-keyboard), which holds the RECORD_AUDIO foreground session.
 *
 * [start] downloads/caches the Vosk model if needed (VoiceModelManager), then
 * opens a Recognizer ready to accept frames. [feed] is thread-safe. [stop]
 * flushes the final hypothesis and closes the recognizer.
 */
class LocalVoiceEngineClient(private val context: Context) : VoiceEngineClient, LanguageTagAware {

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var recognizer: Recognizer? = null
    @Volatile private var onPartialCb: ((String) -> Unit)? = null
    @Volatile private var onFinalCb: ((String) -> Unit)? = null
    @Volatile private var onErrorCb: ((String) -> Unit)? = null

    /** language tag resolved from the current IME subtype — set before [start]. */
    @Volatile override var languageTag: String? = null

    override fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        onPartialCb = onPartial
        onFinalCb   = onFinal
        onErrorCb   = onError

        val key = VoiceModelManager.resolveModelKey(languageTag)
        VoiceModelManager.ensureModel(context, key,
            onReady = { dir ->
                try {
                    val model = loadOrCacheModel(dir)
                    recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
                } catch (e: Exception) {
                    main.post { onError(e.message ?: "recognizer init failed") }
                }
            },
            onError = { msg -> main.post { onError(msg) } }
        )
    }

    override fun feed(pcm: ByteArray, len: Int) {
        val rg = recognizer ?: return
        try {
            if (rg.acceptWaveForm(pcm, len)) {
                val text = JSONObject(rg.result).optString("text").trim()
                if (text.isNotEmpty()) main.post { onFinalCb?.invoke(text) }
            } else {
                val partial = JSONObject(rg.partialResult).optString("partial").trim()
                main.post { onPartialCb?.invoke(partial) }
            }
        } catch (_: Exception) { /* recognizer closed mid-feed — ignored */ }
    }

    override fun stop() {
        val rg = recognizer ?: return
        recognizer = null
        try {
            val tail = JSONObject(rg.finalResult).optString("text").trim()
            if (tail.isNotEmpty()) main.post { onFinalCb?.invoke(tail) }
        } catch (_: Exception) { }
        runCatching { rg.close() }
        onPartialCb = null
        onFinalCb   = null
        onErrorCb   = null
    }

    companion object {
        const val SAMPLE_RATE = 16000

        // Cache the heavy Vosk model in memory so consecutive open/close cycles
        // don't reload the ~40 MB acoustic model from disk each time.
        @Volatile private var cachedDir: String? = null
        @Volatile private var cachedModel: Model? = null

        @Synchronized
        fun loadOrCacheModel(dir: String): Model {
            if (cachedDir == dir && cachedModel != null) return cachedModel!!
            cachedModel?.close()
            cachedModel = null
            cachedModel = Model(dir)
            cachedDir   = dir
            return cachedModel!!
        }
    }
}
