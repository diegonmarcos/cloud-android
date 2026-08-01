package com.diegonmarcos.superapp.translate

import android.content.Context

/**
 * In-process ML Kit engine client. Registered by the superapp and by
 * cloud-keyboard-libs; cloud-keyboard (standalone APK) uses
 * AidlTranslateEngineClient instead so ML Kit is not bundled there.
 */
class LocalTranslateEngineClient(private val context: Context) : TranslateEngineClient {

    override fun translate(text: String, targetTag: String): Array<String> =
        try {
            TranslateEngine.translateBlocking(context, text, targetTag)
        } catch (_: Exception) {
            arrayOf("und", "")
        }

    override fun supportedLanguages(): List<String> =
        TranslateEngine.supportedLanguageTags()

    // In-process — always ready, no bind step to fail.
    override fun isConnected(): Boolean = true
}
