package com.diegonmarcos.superapp.voice

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
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
import com.diegonmarcos.superapp.translate.Translator
import org.vosk.Model

/**
 * Offline voice input (Vosk) with a COMPACT dictation box (not full-screen):
 * tapping the mic opens a small panel over the keyboard with a live waveform and
 * a live partial hint. Finalised (cleaned) speech accumulates in an editable
 * transcript field across pauses and is KEPT there — nothing is written to the
 * target field until you press Insert. Actions: Copy (to clipboard), Translate
 * (on-device ML Kit), Insert (commit to the field), ✕ (close). Listens
 * continuously until closed. Fully offline after the one-time model download.
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

    /** Open the dictation box and start listening. [anchor] is the IME root view. */
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
                model?.close(); model = Model(dir); loadedDir = dir
            }
            val activeModel = model ?: return
            ime = service

            val ov = VoiceOverlayView(
                service,
                onCopy = { copyToClipboard(service) },
                onTranslate = { translateBox(service) },
                onInsert = { insertIntoField() },
                onClose = { stop() },
            )
            overlay = ov

            // COMPACT: width spans the keyboard, height wraps the content (the bug
            // before was height = anchor.height -> whole-keyboard box). Docked at
            // the bottom over the keyboard. Focusable so the transcript is editable.
            val width = if (anchor.width > 0) anchor.width else ViewGroup.LayoutParams.MATCH_PARENT
            val pw = PopupWindow(ov, width, ViewGroup.LayoutParams.WRAP_CONTENT, true /* focusable */)
            pw.isClippingEnabled = false
            popup = pw
            pw.showAtLocation(anchor, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 0)

            val rec = VoiceRecognizer(
                activeModel,
                onAmplitude = { lvl -> overlay?.setLevel(lvl) },
                onPartial = { t -> overlay?.setPartialHint(t) },          // live hint only
                onFinal = { t -> overlay?.setPartialHint(""); overlay?.appendFinal(t) }, // cleaned -> kept in box
                onError = { msg -> toast(service.applicationContext, "Voice error: $msg"); stop() }
            )
            recognizer = rec
            rec.start()
        } catch (e: Exception) {
            toast(service.applicationContext, "Voice init failed: ${e.message}")
            stop()
        }
    }

    private fun copyToClipboard(ctx: Context) {
        val text = overlay?.currentText().orEmpty()
        if (text.isEmpty()) return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dictation", text))
        toast(ctx, "Copied")
    }

    private fun translateBox(ctx: Context) {
        val text = overlay?.currentText().orEmpty()
        if (text.isEmpty()) return
        Translator.liveTranslate(text, BuildConfig.VOICE_TRANSLATE_TO) { result ->
            if (result != null) overlay?.replaceText(result)
            else toast(ctx, "Translate failed")
        }
    }

    /** Commit the box text to the target field, then close. */
    private fun insertIntoField() {
        val text = overlay?.currentText().orEmpty()
        val service = ime
        stop()
        if (text.isEmpty() || service == null) return
        // Re-fetch the connection after the focusable popup is gone.
        main.postDelayed({ service.currentInputConnection?.commitText("$text ", 1) }, 80)
    }

    /** Stop listening and close the box. Idempotent. */
    @JvmStatic
    fun stop() {
        val rec = recognizer; recognizer = null
        val pw = popup; popup = null
        overlay = null
        ime = null
        main.post { if (pw?.isShowing == true) runCatching { pw.dismiss() } }
        if (rec != null) Thread { rec.stop() }.start()
    }

    private fun toast(ctx: Context, msg: String) {
        main.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }
}
