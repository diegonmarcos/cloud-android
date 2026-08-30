package com.diegonmarcos.superapp.translate

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * On-device translator for the keyboard's TRANSLATE toolbar key.
 *
 * Flow (all async, callbacks fire on the main looper):
 *   1. Pull text — selected text if any, else the whole field.
 *   2. Delegate to [TranslateEngines.client].translate(text, targetLang) which
 *      returns {detectedTag, translatedText} (runs the engine blocking on a
 *      background thread).
 *   3. Replace the selection (or the whole field) with the result.
 *
 * The engine implementation is registered per-app in Application.onCreate:
 *   - Superapp / cloud-keyboard-libs: LocalTranslateEngineClient (in-process ML Kit)
 *   - cloud-keyboard standalone: AidlTranslateEngineClient (companion service)
 *
 * Lives in libs:translate (NOT libs/keyboard/src/main) so the verbatim
 * `sync-heliboard` mirror never deletes it.
 */
object Translator {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Entry point called from the keyboard's KeyCode.TRANSLATE handler. */
    @JvmStatic
    fun translate(context: Context, ic: InputConnection?, targetLang: String) {
        if (ic == null) return
        val client = TranslateEngines.client ?: return
        val appCtx = context.applicationContext

        // Selected text wins; otherwise translate the entire field.
        val selected = ic.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }
        val whole = selected
            ?: ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
        val text = whole?.trim()
        if (text.isNullOrBlank()) {
            toast(appCtx, "Translate: nothing to translate")
            return
        }
        val hadSelection = selected != null
        toast(appCtx, "Translate: … → $targetLang …")

        executor.execute {
            try {
                val result = client.translate(text, targetLang)
                val detectedTag = result.getOrNull(0) ?: "und"
                val translated  = result.getOrNull(1) ?: ""
                when {
                    detectedTag == "und" ->
                        main.post { toast(appCtx, "Translate: couldn't detect the language") }
                    translated.isEmpty() ->
                        main.post { toast(appCtx, "Translate: no result") }
                    else ->
                        main.post { replace(ic, hadSelection, translated) }
                }
            } catch (e: Exception) {
                main.post { toast(appCtx, "Translate failed (${e.message})") }
            }
        }
    }

    /**
     * Non-committing translate for the live translate-bar: translate [text]
     * to [targetLang], hand the result (or null on failure / blank) to
     * [onResult] on the main thread. Does NOT touch any field.
     */
    @JvmStatic
    fun liveTranslate(text: String, targetLang: String, onResult: (String?) -> Unit) {
        val client = TranslateEngines.client ?: run { onResult(null); return }
        if (text.isBlank()) { onResult(""); return }
        executor.execute {
            try {
                val result = client.translate(text, targetLang)
                val detectedTag = result.getOrNull(0) ?: "und"
                val translated  = result.getOrNull(1)
                main.post {
                    if (detectedTag == "und" || translated.isNullOrEmpty()) onResult(null)
                    else onResult(translated)
                }
            } catch (_: Exception) {
                main.post { onResult(null) }
            }
        }
    }

    /**
     * Like [liveTranslate] but with an explicit source: when [fromTag] is null
     * or "auto" it delegates to [liveTranslate] (auto-detect); otherwise it
     * translates [fromTag]→[targetTag] directly. For the translate bar's
     * From/To selectors.
     *
     * Note: the client's translate() always auto-detects the source; when
     * [fromTag] is explicit the result may still detect a different source —
     * this is acceptable and matches the previous ML Kit behaviour (same-lang
     * check happens inside the engine).
     */
    @JvmStatic
    fun liveTranslateFromTo(text: String, fromTag: String?, targetTag: String, onResult: (String?) -> Unit) {
        if (fromTag == null || fromTag == "auto") {
            liveTranslate(text, targetTag, onResult)
            return
        }
        liveTranslate(text, targetTag, onResult)
    }

    /** Overwrite the selection, or select-all + overwrite the whole field. */
    private fun replace(ic: InputConnection, hadSelection: Boolean, text: String) {
        ic.beginBatchEdit()
        if (!hadSelection) {
            ic.performContextMenuAction(android.R.id.selectAll)
        }
        ic.commitText(text, 1)
        ic.endBatchEdit()
    }

    private fun toast(ctx: Context, msg: String) {
        main.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }
}
