package com.diegonmarcos.superapp.translate

/**
 * Swappable translate-engine interface.
 *
 * Implementations:
 *  - LocalTranslateEngineClient (libs:translate-mlkit) — in-process ML Kit.
 *    Registered by the superapp and cloud-keyboard-libs.
 *  - AidlTranslateEngineClient (cloud-keyboard app) — binds the companion
 *    cloud-keyboard-libs service. Registered by the standalone keyboard APK
 *    so ML Kit is not bundled there.
 */
interface TranslateEngineClient {
    /** Returns [detectedTag, translatedText]. */
    fun translate(text: String, targetTag: String): Array<String>
    fun supportedLanguages(): List<String>

    /**
     * True when the engine is actually able to translate right now — for
     * AidlTranslateEngineClient this reflects the real AIDL bind state, NOT
     * just whether the client object exists (a client can be constructed and
     * registered successfully while its underlying service connection is
     * still pending, failed, or was silently refused). Settings/diagnostic
     * screens must check this, not `client != null`, to report honest status.
     */
    fun isConnected(): Boolean
}
