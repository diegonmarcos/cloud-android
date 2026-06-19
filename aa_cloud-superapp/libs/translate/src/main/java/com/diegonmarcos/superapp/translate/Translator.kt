package com.diegonmarcos.superapp.translate

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * On-device translator for the keyboard's TRANSLATE toolbar key.
 *
 * Flow (all async, callbacks fire on the main looper):
 *   1. Pull text — selected text if any, else the whole field.
 *   2. Auto-detect the source language (ML Kit language-id).
 *   3. Translate source → [targetLang] on-device (ML Kit translate),
 *      downloading the language-pair model once (~30 MB) on first use.
 *   4. Replace the selection (or the whole field) with the result.
 *
 * Target language is the active keyboard subtype's language — passed in
 * by InputLogic as an ISO-639-1 tag ("en"/"de"/"es"/"pt"). Everything is
 * offline after the one-time model fetch; nothing but that download
 * leaves the device.
 *
 * Lives in libs:translate (NOT libs/keyboard/src/main) so the verbatim
 * `sync-heliboard` mirror never deletes it.
 */
object Translator {

    /** Entry point called from the keyboard's KeyCode.TRANSLATE handler. */
    @JvmStatic
    fun translate(context: Context, ic: InputConnection?, targetLang: String) {
        if (ic == null) return
        val appCtx = context.applicationContext

        val target = TranslateLanguage.fromLanguageTag(targetLang)
        if (target == null) {
            toast(appCtx, "Translate: unsupported target '$targetLang'")
            return
        }

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

        LanguageIdentification.getClient().identifyLanguage(text)
            .addOnSuccessListener { detected ->
                if (detected == "und") {
                    toast(appCtx, "Translate: couldn't detect the language")
                    return@addOnSuccessListener
                }
                val source = TranslateLanguage.fromLanguageTag(detected)
                if (source == null) {
                    toast(appCtx, "Translate: '$detected' not supported")
                    return@addOnSuccessListener
                }
                if (source == target) {
                    toast(appCtx, "Translate: text is already $targetLang")
                    return@addOnSuccessListener
                }
                runTranslation(appCtx, ic, hadSelection, text, source, target, detected, targetLang)
            }
            .addOnFailureListener { e ->
                toast(appCtx, "Translate: detect failed (${e.message})")
            }
    }

    private fun runTranslation(
        appCtx: Context,
        ic: InputConnection,
        hadSelection: Boolean,
        text: String,
        source: String,
        target: String,
        detectedTag: String,
        targetTag: String,
    ) {
        val opts = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        val client = Translation.getClient(opts)
        toast(appCtx, "Translate: $detectedTag → $targetTag …")
        // DownloadConditions default = any network (no Wi-Fi requirement),
        // so the first-use model fetch isn't silently blocked on cellular.
        client.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                client.translate(text)
                    .addOnSuccessListener { result ->
                        replace(ic, hadSelection, result)
                        client.close()
                    }
                    .addOnFailureListener { e ->
                        toast(appCtx, "Translate failed (${e.message})")
                        client.close()
                    }
            }
            .addOnFailureListener { e ->
                toast(appCtx, "Translate: model download failed (${e.message})")
                client.close()
            }
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

    /**
     * Non-committing translate for the live translate-bar: detect source,
     * translate [text] → [targetLang], hand the result (or null on
     * same-language / failure / blank) to [onResult] on the main thread.
     * Does NOT touch any field.
     */
    @JvmStatic
    fun liveTranslate(text: String, targetLang: String, onResult: (String?) -> Unit) {
        val target = TranslateLanguage.fromLanguageTag(targetLang)
        if (target == null) { onResult(null); return }
        if (text.isBlank()) { onResult(""); return }
        LanguageIdentification.getClient().identifyLanguage(text)
            .addOnSuccessListener { detected ->
                val source = if (detected == "und") null else TranslateLanguage.fromLanguageTag(detected)
                if (source == null || source == target) { onResult(null); return@addOnSuccessListener }
                val client = Translation.getClient(
                    TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
                client.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener {
                        client.translate(text)
                            .addOnSuccessListener { onResult(it); client.close() }
                            .addOnFailureListener { onResult(null); client.close() }
                    }
                    .addOnFailureListener { onResult(null); client.close() }
            }
            .addOnFailureListener { onResult(null) }
    }

    private val main = Handler(Looper.getMainLooper())
    private fun toast(ctx: Context, msg: String) {
        main.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }
}
