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
import kotlin.math.abs
import kotlin.math.max

private const val POINTS = 96 // oscilloscope sample count per frame

/**
 * Continuous offline recognizer. A single [AudioRecord] stream is fed manually
 * into a Vosk [Recognizer], so one mic capture yields both live amplitude (for
 * the waveform) and live partial/final transcripts. Runs until [stop].
 *
 * [stop] releases the microphone IMMEDIATELY and DIRECTLY (not only via the
 * worker's finally) so the OS mic indicator goes off the instant the box closes
 * and the next session can re-acquire the mic. Idempotent + safe to call twice.
 * All callbacks are delivered on the main thread.
 */
class VoiceRecognizer(
    private val model: Model,
    private val onWaveform: (FloatArray) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var worker: Thread? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile private var recognizer: Recognizer? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "vosk-rec", isDaemon = true) { loop() }
    }

    /** Stop listening and release the mic NOW. Safe to call from any thread. */
    fun stop() {
        running = false
        // Release the recorder directly so the mic frees instantly even if the
        // worker is blocked inside read(). The worker's next read() then fails
        // and the loop exits.
        releaseRecord()
        worker?.let { if (it !== Thread.currentThread()) runCatching { it.join(500) } }
        worker = null
        releaseRecognizer()
    }

    private fun loop() {
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = if (minBuf > 0) minBuf * 2 else sampleRate
        try {
            recognizer = Recognizer(model, sampleRate.toFloat())
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
            record = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                post { onError("microphone unavailable") }
                return
            }
            val buffer = ByteArray(bufSize)
            rec.startRecording()
            while (running) {
                val r = record ?: break
                val n = try { r.read(buffer, 0, buffer.size) } catch (_: Exception) { break }
                if (n <= 0) continue
                val rg = recognizer ?: break
                // Compute on THIS thread (buffer is reused by the next read) and
                // post the immutable result for the oscilloscope view.
                val wf = waveform(buffer, n)
                post { onWaveform(wf) }
                if (rg.acceptWaveForm(buffer, n)) {
                    val text = JSONObject(rg.result).optString("text").trim()
                    if (text.isNotEmpty()) post { onFinal(text) }
                } else {
                    val partial = JSONObject(rg.partialResult).optString("partial").trim()
                    post { onPartial(partial) }
                }
            }
            recognizer?.let {
                val tail = JSONObject(it.finalResult).optString("text").trim()
                if (tail.isNotEmpty()) post { onFinal(tail) }
            }
        } catch (e: Exception) {
            post { onError(e.message ?: "recognition error") }
        } finally {
            releaseRecord()
            releaseRecognizer()
        }
    }

    private fun releaseRecord() {
        val r = record ?: return
        record = null
        runCatching { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() }
        runCatching { r.release() }
    }

    private fun releaseRecognizer() {
        val rg = recognizer ?: return
        recognizer = null
        runCatching { rg.close() }
    }

    /**
     * Downsample a PCM16LE buffer to [POINTS] signed, normalised (-1..1) samples
     * by taking the peak (most-extreme) sample in each slice — preserves the live
     * wave shape for the oscilloscope display.
     */
    private fun waveform(buf: ByteArray, len: Int): FloatArray {
        val total = len / 2
        val out = FloatArray(POINTS)
        if (total <= 0) return out
        val step = max(1, total / POINTS)
        for (p in 0 until POINTS) {
            var peak = 0
            val start = p * step
            var j = 0
            while (j < step && start + j < total) {
                val idx = (start + j) * 2
                val s = ((buf[idx].toInt() and 0xff) or (buf[idx + 1].toInt() shl 8)).toShort().toInt()
                if (abs(s) > abs(peak)) peak = s
                j++
            }
            out[p] = (peak / 32768f).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun post(block: () -> Unit) = main.post(block)
}
