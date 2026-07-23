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
}
