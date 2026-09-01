package com.diegonmarcos.superapp.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.translate.Translator
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

private const val SAMPLE_RATE = 16000
private const val POINTS = 96 // oscilloscope sample count per frame

/**
 * Offline voice dictation bar, hosted INSIDE the keyboard frame (added as the
 * first child of the vertical LinearLayout that owns strip_container — the exact
 * pattern the translate bar uses). NOT a PopupWindow: that avoids all the
 * focus / touch / mic-lifecycle bugs. It sits directly above the keyboard, the
 * keys stay usable below, and the mic key TOGGLES it (tap = open, tap = close).
 *
 * The mic lifecycle is tied to visibility: [onShown] starts capture + the engine,
 * [onHidden] stops them and RELEASES the mic — so closing always frees the mic.
 *
 * Audio capture runs in VoiceBarView (the foreground IME holds RECORD_AUDIO).
 * Recognition is delegated to [VoiceEngines.client]:
 *   - cloud-keyboard-libs (companion APK): LocalVoiceEngineClient — Vosk in-process
 *   - cloud-keyboard (standalone APK):     AidlVoiceEngineClient  — cross-process AIDL
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class VoiceBarView(context: Context) : LinearLayout(context) {

    private val main = Handler(Looper.getMainLooper())
    private val wave = WaveformView(context)
    private val hintView: TextView
    private val editor: EditText

    /** SAM interface so LatinIME can pass `this::getCurrentInputConnection`
     *  (a Java method reference). A Kotlin `() -> InputConnection?` would NOT
     *  accept the Java method ref — same pattern as TranslateBarView.IcProvider. */
    fun interface IcProvider { fun get(): InputConnection? }

    private var icSupplier: IcProvider? = null
    private var langTag: String = "en-us"
    private var onCloseRequest: Runnable? = null

    // Capture state — owned by VoiceBarView so the mic lives here (foreground IME).
    @Volatile private var record: AudioRecord? = null
    @Volatile private var capturing = false
    private var captureThread: Thread? = null

    // Data-driven translate targets (build.json::voice.translate_targets). The
    // chip cycles through them; Translate uses the selected one. Lets you pick a
    // target different from the dictated language (same-language = no-op).
    private val targets: List<String> =
        BuildConfig.VOICE_TRANSLATE_TARGETS.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("en") }
    private var targetIndex = 0
    private lateinit var targetChip: Button

    private companion object {
        const val ACCENT = 0xFF8A6CFF.toInt()
        const val PANEL = 0xFF1B1922.toInt()
        const val FIELD = 0xFF2C2842.toInt()
        const val NEUTRAL = 0xFF3A3552.toInt()
        const val DANGER = 0xFF5A2E45.toInt()
        const val TEXT = 0xFFF2EEFF.toInt()
        const val MUTED = 0xFFB9A7FF.toInt()
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(PANEL)
        val p = dp(12)
        setPadding(p, dp(10), p, dp(10))

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "🎤"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) })
        header.addView(TextView(context).apply {
            text = "Voice"; setTextColor(TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(12) })
        header.addView(wave, LayoutParams(0, dp(40), 1f))
        targetChip = pill("🌐 ${targets[0]}", NEUTRAL, TEXT) { cycleTarget() }
        header.addView(targetChip, LayoutParams(LayoutParams.WRAP_CONTENT, dp(36)).apply { leftMargin = dp(8) })
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        hintView = TextView(context).apply {
            setTextColor(MUTED); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); text = "Listening…"
        }
        addView(hintView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        editor = EditText(context).apply {
            setTextColor(TEXT); setHintTextColor(0x80FFFFFF.toInt())
            hint = "Speak — your words appear here…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(FIELD) }
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setLines(2); maxLines = 5; gravity = Gravity.TOP or Gravity.START
            setHorizontallyScrolling(false)
            val pad = dp(12); setPadding(pad, pad, pad, pad)
        }
        addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        val actions = LinearLayout(context).apply { orientation = HORIZONTAL }
        actions.addView(pill("Copy", NEUTRAL, TEXT) { copy() }, pillLp())
        actions.addView(pill("Translate", NEUTRAL, TEXT) { translate() }, pillLp())
        actions.addView(pill("Insert", ACCENT, Color.WHITE) { insert() }, pillLp())
        actions.addView(pill("✕", DANGER, TEXT) { onCloseRequest?.run() }, pillLp(0.6f))
        addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    /** Wire the bar to the IME. [ic] supplies the live InputConnection, [langTag]
     *  is the active subtype tag, [onClose] hides the bar (→ onHidden stops mic). */
    fun bind(ic: IcProvider, langTag: String, onClose: Runnable) {
        this.icSupplier = ic
        this.langTag = langTag
        this.onCloseRequest = onClose
    }

    /** Called by LatinIME when the bar becomes visible — start dictation. */
    fun onShown() {
        val ctx = context.applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            hintView.text = "Grant microphone, then tap the mic key again"
            ctx.startActivity(
                Intent(ctx, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            onCloseRequest?.run() // close; user re-taps after granting
            return
        }
        val engine = VoiceEngines.client
        if (engine == null) {
            hintView.text = "Voice unavailable — install cloud-keyboard-libs companion"
            return
        }
        editor.setText("")
        hintView.text = "Preparing voice engine…"
        // Propagate the current language tag to the engine if it supports it.
        if (engine is LanguageTagAware) engine.languageTag = langTag
        engine.start(
            onPartial = { t -> main.post { hintView.text = if (t.isEmpty()) "Listening…" else "… $t" } },
            onFinal   = { t -> main.post { hintView.text = "Listening…"; appendFinal(t) } },
            onError   = { msg -> main.post { hintView.text = "Voice error: $msg"; stopCapture() } }
        )
        startCapture(engine)
        hintView.text = "Listening…"
    }

    /** Called by LatinIME when the bar is hidden — STOP and release the mic. */
    fun onHidden() {
        stopCapture()
        VoiceEngines.client?.stop()
    }

    private fun startCapture(engine: VoiceEngineClient) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = if (minBuf > 0) minBuf * 2 else SAMPLE_RATE
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            main.post { hintView.text = "Microphone unavailable" }
            rec.release()
            return
        }
        record = rec
        capturing = true
        captureThread = thread(name = "vosk-capture", isDaemon = true) {
            val buffer = ByteArray(bufSize)
            rec.startRecording()
            while (capturing) {
                val r = record ?: break
                val n = try { r.read(buffer, 0, buffer.size) } catch (_: Exception) { break }
                if (n <= 0) continue
                val wf = waveform(buffer, n)
                main.post { wave.setWaveform(wf) }
                engine.feed(buffer.copyOf(n), n)
            }
        }
    }

    private fun stopCapture() {
        capturing = false
        val r = record ?: return
        record = null
        runCatching { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() }
        runCatching { r.release() }
        captureThread?.let { if (it !== Thread.currentThread()) runCatching { it.join(500) } }
        captureThread = null
    }

    private fun appendFinal(text: String) {
        if (text.isEmpty()) return
        val cur = editor.text?.toString().orEmpty()
        editor.setText(if (cur.isBlank()) text else "${cur.trimEnd()} $text")
        editor.setSelection(editor.text.length)
    }

    private fun text(): String = editor.text?.toString().orEmpty().trim()

    private fun copy() {
        val t = text(); if (t.isEmpty()) return
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("dictation", t))
        hintView.text = "Copied"
    }

    private fun translate() {
        val t = text(); if (t.isEmpty()) { toast("Nothing to translate"); return }
        val target = targets[targetIndex]
        hintView.text = "Translating → $target…"
        Translator.liveTranslate(t, target) { result ->
            when {
                result == null -> {
                    hintView.text = "Listening…"
                    toast("No translation — text may already be '$target'. Tap 🌐 to pick another target.")
                }
                result.isBlank() -> hintView.text = "Listening…"
                else -> { editor.setText(result); editor.setSelection(editor.text.length); hintView.text = "Listening…" }
            }
        }
    }

    private fun cycleTarget() {
        targetIndex = (targetIndex + 1) % targets.size
        targetChip.text = "🌐 ${targets[targetIndex]}"
        toast("Translate to: ${targets[targetIndex]}")
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun insert() {
        val t = text()
        if (t.isNotEmpty()) icSupplier?.get()?.commitText("$t ", 1)
        onCloseRequest?.run()
    }

    private fun pill(label: String, bg: Int, fg: Int, onClick: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        stateListAnimator = null
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(bg) }
        minWidth = 0; minimumWidth = 0
        val ph = dp(6); setPadding(dp(4), ph, dp(4), ph)
        setOnClickListener { onClick() }
    }

    private fun pillLp(grow: Float = 1f) = LayoutParams(0, dp(44), grow).apply {
        leftMargin = dp(3); rightMargin = dp(3)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * Downsample a PCM16LE buffer to [POINTS] signed, normalised (-1..1) samples
     * by taking the peak (most-extreme) sample in each slice.
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

    /**
     * Oscilloscope-style waveform: draws the actual captured audio wave as a
     * smooth gradient line (with a soft glow + faint mirror) across the strip,
     * scrolling/wiggling with the voice — a synthesizer/scope look, not bars.
     */
    private class WaveformView(context: Context) : View(context) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(2.2f)
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(7f)
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; alpha = 60
        }
        private val mirror = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(1.6f)
            strokeCap = Paint.Cap.ROUND; alpha = 38
        }
        private val baseline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF; strokeWidth = dp(1f) }
        private val path = Path()
        private val mirrorPath = Path()
        private var smooth = FloatArray(0)
        private val gain = 2.8f

        fun setWaveform(s: FloatArray) {
            if (smooth.size != s.size) smooth = FloatArray(s.size)
            for (i in s.indices) smooth[i] = smooth[i] * 0.4f + s[i] * 0.6f // temporal smoothing
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            val grad = LinearGradient(0f, 0f, w.toFloat(), 0f,
                intArrayOf(0xFF5CC8FF.toInt(), 0xFF8A6CFF.toInt(), 0xFFB66CFF.toInt()),
                null, Shader.TileMode.CLAMP)
            line.shader = grad; glow.shader = grad; mirror.shader = grad
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val cy = h / 2f
            canvas.drawLine(0f, cy, w, cy, baseline)
            val n = smooth.size
            if (n < 2) { canvas.drawLine(0f, cy, w, cy, line); return }
            path.reset(); mirrorPath.reset()
            val dx = w / (n - 1); val amp = h * 0.46f
            for (i in 0 until n) {
                val x = i * dx
                val v = (smooth[i] * gain).coerceIn(-1f, 1f)
                val y = cy - v * amp
                if (i == 0) { path.moveTo(x, y); mirrorPath.moveTo(x, cy + v * amp) }
                else { path.lineTo(x, y); mirrorPath.lineTo(x, cy + v * amp) }
            }
            canvas.drawPath(mirrorPath, mirror)
            canvas.drawPath(path, glow)
            canvas.drawPath(path, line)
        }

        private fun dp(v: Float) = v * resources.displayMetrics.density
    }
}

/** Implemented by engines that can be told which language to transcribe. */
interface LanguageTagAware {
    var languageTag: String?
}
