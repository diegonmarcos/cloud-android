package com.diegonmarcos.superapp.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Continuous offline recognizer. A single [AudioRecord] stream is fed manually
 * into a Vosk [Recognizer] (acceptWaveForm), so from ONE mic capture we get both
 * the live mic amplitude (for the waveform visualiser) and the live partial /
 * final transcripts. Keeps running until [stop]. All callbacks are delivered on
 * the main thread.
 */
class VoiceRecognizer(
    private val model: Model,
    private val onAmplitude: (Float) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "vosk-rec", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        worker?.join(800)
        worker = null
    }

    private fun loop() {
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = if (minBuf > 0) minBuf * 2 else sampleRate
        val recognizer = Recognizer(model, sampleRate.toFloat())
        var record: AudioRecord? = null
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                post { onError("microphone unavailable") }
                return
            }
            val buffer = ByteArray(bufSize)
            record.startRecording()
            while (running) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                val level = rms(buffer, n)
                post { onAmplitude(level) }
                if (recognizer.acceptWaveForm(buffer, n)) {
                    val text = JSONObject(recognizer.result).optString("text").trim()
                    if (text.isNotEmpty()) post { onFinal(text) }
                } else {
                    val partial = JSONObject(recognizer.partialResult).optString("partial").trim()
                    post { onPartial(partial) }
                }
            }
            val tail = JSONObject(recognizer.finalResult).optString("text").trim()
            if (tail.isNotEmpty()) post { onFinal(tail) }
        } catch (e: Exception) {
            post { onError(e.message ?: "recognition error") }
        } finally {
            try { record?.stop() } catch (_: Exception) {}
            record?.release()
            recognizer.close()
        }
    }

    /** Root-mean-square of a PCM16LE buffer, normalised to 0..1. */
    private fun rms(buf: ByteArray, len: Int): Float {
        var sum = 0.0
        var i = 0
        val count = len / 2
        if (count == 0) return 0f
        while (i + 1 < len) {
            val s = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
            sum += (s.toShort().toDouble() * s.toShort().toDouble())
            i += 2
        }
        return (sqrt(sum / count) / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    private fun post(block: () -> Unit) = main.post(block)
}
