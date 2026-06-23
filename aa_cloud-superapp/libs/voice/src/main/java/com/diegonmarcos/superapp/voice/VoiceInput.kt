package com.diegonmarcos.superapp.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.vosk.Model

/**
 * Offline voice input (Vosk) for the keyboard's VOICE toolbar key, with a
 * Gboard-style UI: tapping the mic opens a popup over the keyboard showing a
 * live waveform + a Cancel button, and keeps listening CONTINUOUSLY until Cancel
 * is pressed. Recognised text is written LIVE into the field — the in-progress
 * utterance shows as composing text, each finished utterance is committed.
 * Fully offline after the one-time per-language model download.
 *
 * Called from LatinIME's KeyCode.VOICE_INPUT handler (patches/0005-voice-input).
 */
object VoiceInput {

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var model: Model? = null
    @Volatile private var loadedDir: String? = null
    @Volatile private var starting = false

    private var popup: PopupWindow? = null
    private var overlay: VoiceOverlayView? = null
    private var recognizer: VoiceRecognizer? = null
    private var ime: InputMethodService? = null

    @JvmStatic
    val isActive: Boolean get() = popup != null

    /**
     * Open the listening popup and start dictation. [anchor] is the IME root view
     * (mInputView) — used for the popup window token and to size it over the
     * keyboard. [languageTag] is the active subtype's BCP-47 tag.
     */
    @JvmStatic
    fun start(ime: InputMethodService, anchor: View?, languageTag: String?) {
        if (isActive || starting || anchor == null) return
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
            onReady = { dir -> main.post { startSession(ime, anchor, dir); starting = false } },
            onError = { msg -> main.post { starting = false; toast(ctx, "Voice model error: $msg") } }
        )
    }

    private fun startSession(service: InputMethodService, anchor: View, dir: String) {
        try {
            if (loadedDir != dir || model == null) {
                model?.close()
                model = Model(dir)
                loadedDir = dir
            }
            val activeModel = model ?: return
            ime = service

            val ov = VoiceOverlayView(service, onCancel = { stop() })
            overlay = ov
            val pw = PopupWindow(
                ov,
                if (anchor.width > 0) anchor.width else ViewGroup.LayoutParams.MATCH_PARENT,
                if (anchor.height > 0) anchor.height else dpFallback(service)
            )
            pw.isClippingEnabled = false
            // Non-focusable so the IME keeps its InputConnection (text still goes to
            // the field); touchable so the Cancel button works.
            pw.isFocusable = false
            pw.isTouchable = true
            popup = pw
            pw.showAtLocation(anchor, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 0)

            val rec = VoiceRecognizer(
                activeModel,
                onAmplitude = { lvl -> overlay?.setLevel(lvl) },
                onPartial = { t ->
                    overlay?.setPartial(t)
                    if (t.isNotEmpty()) ime?.currentInputConnection?.setComposingText(t, 1)
                },
                onFinal = { t ->
                    overlay?.setPartial("")
                    ime?.currentInputConnection?.commitText("$t ", 1)
                },
                onError = { msg ->
                    toast(service.applicationContext, "Voice error: $msg")
                    stop()
                }
            )
            recognizer = rec
            rec.start()
        } catch (e: Exception) {
            toast(service.applicationContext, "Voice init failed: ${e.message}")
            stop()
        }
    }

    /** Stop listening, finish any composing text, and close the popup. Idempotent. */
    @JvmStatic
    fun stop() {
        val rec = recognizer; recognizer = null
        val pw = popup; popup = null
        val ov = overlay; overlay = null
        val service = ime; ime = null
        main.post {
            service?.currentInputConnection?.finishComposingText()
            if (pw?.isShowing == true) runCatching { pw.dismiss() }
            ov?.let { /* detached with the popup */ }
        }
        // Releasing the recorder joins its thread briefly — keep it off the main thread.
        if (rec != null) Thread { rec.stop() }.start()
    }

    private fun dpFallback(ctx: Context) = (240 * ctx.resources.displayMetrics.density).toInt()

    private fun toast(ctx: Context, msg: String) {
        main.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }
}
