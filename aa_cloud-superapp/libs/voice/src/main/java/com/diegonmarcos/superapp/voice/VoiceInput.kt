package com.diegonmarcos.superapp.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Offline voice input (Vosk) for the keyboard's VOICE toolbar key.
 *
 * Entry point: [toggle]. First tap starts listening (downloading the model for
 * the active keyboard language on first use), second tap stops. Recognized text
 * is committed to the active input field via the IME's InputConnection. Fully
 * offline after the one-time model download — no Google / system speech IME.
 *
 * Called from LatinIME's KeyCode.VOICE_INPUT handler (patches/0005-voice-input).
 */
object VoiceInput {

    private const val SAMPLE_RATE = 16000.0f
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var speechService: SpeechService? = null
    @Volatile private var model: Model? = null
    @Volatile private var loadedModelDir: String? = null
    @Volatile private var starting = false

    @JvmStatic
    val isListening: Boolean get() = speechService != null

    /** Toggle dictation on/off. [languageTag] is the active subtype BCP-47 tag. */
    @JvmStatic
    fun toggle(ime: InputMethodService, languageTag: String?) {
        if (isListening) { stop(); return }
        if (starting) return
        val ctx = ime.applicationContext

        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toast(ctx, "Grant microphone, then tap the mic key again")
            ctx.startActivity(
                Intent(ctx, VoicePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        starting = true
        val key = VoiceModelManager.resolveModelKey(languageTag)
        toast(ctx, "Preparing voice model…")
        VoiceModelManager.ensureModel(
            ctx, key,
            onReady = { dir ->
                main.post {
                    try {
                        startListening(ime, dir)
                    } catch (e: Exception) {
                        toast(ctx, "Voice init failed: ${e.message}")
                    } finally {
                        starting = false
                    }
                }
            },
            onError = { msg ->
                main.post {
                    starting = false
                    toast(ctx, "Voice model error: $msg")
                }
            }
        )
    }

    private fun startListening(ime: InputMethodService, modelDir: String) {
        if (loadedModelDir != modelDir || model == null) {
            model?.close()
            model = Model(modelDir)
            loadedModelDir = modelDir
        }
        val activeModel = model ?: return
        val recognizer = Recognizer(activeModel, SAMPLE_RATE)
        val service = SpeechService(recognizer, SAMPLE_RATE)
        speechService = service
        toast(ime.applicationContext, "🎤 Listening…")
        service.startListening(object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) { /* live preview not shown */ }
            override fun onResult(hypothesis: String?) = commit(ime, hypothesis)
            override fun onFinalResult(hypothesis: String?) = commit(ime, hypothesis)
            override fun onError(e: Exception?) {
                main.post {
                    toast(ime.applicationContext, "Voice error: ${e?.message}")
                    stop()
                }
            }
            override fun onTimeout() { main.post { stop() } }
        })
    }

    /** Extract the {"text":"..."} field from a Vosk result and commit it. */
    private fun commit(ime: InputMethodService, hypothesis: String?) {
        val text = hypothesis
            ?.let { runCatching { JSONObject(it).optString("text") }.getOrNull() }
            ?.trim()
            .orEmpty()
        if (text.isEmpty()) return
        main.post {
            ime.currentInputConnection?.commitText("$text ", 1)
        }
    }

    /** Stop and release the recognition session (idempotent). */
    @JvmStatic
    fun stop() {
        speechService?.let {
            it.stop()
            it.shutdown()
        }
        speechService = null
    }

    private fun toast(ctx: Context, msg: String) {
        main.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }
}
