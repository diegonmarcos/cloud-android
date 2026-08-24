package com.diegonmarcos.superapp.voice

/**
 * Singleton holder for the active [VoiceEngineClient].
 *
 * Set once at Application.onCreate before any recognition request:
 * - cloud-keyboard-libs (companion): registers LocalVoiceEngineClient (Vosk in-process)
 * - cloud-keyboard (standalone):     registers AidlVoiceEngineClient (cross-process)
 * - cloud-superapp:                  leaves null — no voice lib (no VoiceBarView usage)
 *
 * [VoiceBarView] reads [client] on each [VoiceBarView.onShown] call. Null means
 * voice is unavailable (companion not installed); the bar shows an error message.
 */
object VoiceEngines {
    @JvmField
    @Volatile
    var client: VoiceEngineClient? = null
}
