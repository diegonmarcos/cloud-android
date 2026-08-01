package com.diegonmarcos.superapp.voice

/**
 * Streaming voice-recognition engine interface. Audio capture stays in the
 * foreground IME process (cloud-keyboard holds RECORD_AUDIO); only the
 * recognition work is delegated — in-process via [libs:voice-vosk] or
 * cross-process via AIDL to cloud-keyboard-libs.
 *
 * All callbacks are delivered on the calling thread unless the implementation
 * states otherwise. Implementations must be idempotent: calling [stop] twice
 * or [start] while already started must not crash.
 */
interface VoiceEngineClient {

    /**
     * Begin a recognition session. PCM frames arrive via [feed] until [stop].
     *
     * @param onPartial called with the running hypothesis (may be empty string)
     * @param onFinal   called with each committed utterance
     * @param onError   called with a short human-readable message on failure
     */
    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    )

    /**
     * Feed a PCM 16-bit LE mono 16 kHz frame. [len] bytes starting at offset 0
     * in [pcm] are valid. Safe to call from any thread while [start]ed.
     */
    fun feed(pcm: ByteArray, len: Int)

    /** Finish the session and flush the final result. Idempotent. */
    fun stop()

    /** True when the engine can actually recognize speech right now. For
     *  AIDL-backed implementations this reflects the real bind state, not
     *  just whether the client object was constructed. */
    fun isConnected(): Boolean
}
