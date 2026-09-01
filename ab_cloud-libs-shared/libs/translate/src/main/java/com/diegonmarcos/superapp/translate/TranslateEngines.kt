package com.diegonmarcos.superapp.translate

/**
 * App-level client holder. Each consuming app registers its own
 * implementation in Application.onCreate before any translate call fires:
 *
 *   Superapp / cloud-keyboard-libs:
 *       TranslateEngines.client = LocalTranslateEngineClient(this)
 *
 *   cloud-keyboard (standalone IME):
 *       TranslateEngines.client = AidlTranslateEngineClient(this)
 */
object TranslateEngines {
    @JvmField
    @Volatile
    var client: TranslateEngineClient? = null
}
