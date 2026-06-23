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
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import org.vosk.Model
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Offline voice dictation bar, hosted INSIDE the keyboard frame (added as the
 * first child of the vertical LinearLayout that owns strip_container — the exact
 * pattern the translate bar uses). NOT a PopupWindow: that avoids all the
 * focus / touch / mic-lifecycle bugs. It sits directly above the keyboard, the
 * keys stay usable below, and the mic key TOGGLES it (tap = open, tap = close).
 *
 * The mic lifecycle is tied to visibility: [onShown] starts the recognizer,
 * [onHidden] stops it and RELEASES the mic — so closing always frees the mic.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class VoiceBarView(context: Context) : LinearLayout(context) {

    private val main = Handler(Looper.getMainLooper())
    private val wave = WaveBarView(context)
    private val hintView: TextView
    private val editor: EditText

    /** SAM interface so LatinIME can pass `this::getCurrentInputConnection`
     *  (a Java method reference). A Kotlin `() -> InputConnection?` would NOT
     *  accept the Java method ref — same pattern as TranslateBarView.IcProvider. */
    fun interface IcProvider { fun get(): InputConnection? }

    private var icSupplier: IcProvider? = null
    private var langTag: String = "en-us"
    private var onCloseRequest: Runnable? = null
    private var recognizer: VoiceRecognizer? = null

    private companion object {
        const val ACCENT = 0xFF8A6CFF.toInt()
        const val PANEL = 0xFF1B1922.toInt()
        const val FIELD = 0xFF2C2842.toInt()
        const val NEUTRAL = 0xFF3A3552.toInt()
        const val DANGER = 0xFF5A2E45.toInt()
        const val TEXT = 0xFFF2EEFF.toInt()
        const val MUTED = 0xFFB9A7FF.toInt()
        // Model cache shared across open/close so we load the ~40 MB model once.
        @Volatile var cachedModel: Model? = null
        @Volatile var cachedDir: String? = null
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
        header.addView(wave, LayoutParams(0, dp(32), 1f))
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
        editor.setText("")
        hintView.text = "Preparing voice model…"
        val key = VoiceModelManager.resolveModelKey(langTag)
        VoiceModelManager.ensureModel(
            ctx, key,
            onReady = { dir -> main.post { startRecognition(dir) } },
            onError = { msg -> main.post { hintView.text = "Model error: $msg" } }
        )
    }

    /** Called by LatinIME when the bar is hidden — STOP and release the mic. */
    fun onHidden() {
        val rec = recognizer; recognizer = null
        if (rec != null) Thread { rec.stop() }.start()
    }

    private fun startRecognition(dir: String) {
        try {
            if (cachedDir != dir || cachedModel == null) {
                cachedModel?.close(); cachedModel = Model(dir); cachedDir = dir
            }
            val model = cachedModel ?: return
            hintView.text = "Listening…"
            val rec = VoiceRecognizer(
                model,
                onAmplitude = { lvl -> wave.setLevel(lvl) },
                onPartial = { t -> hintView.text = if (t.isEmpty()) "Listening…" else "… $t" },
                onFinal = { t -> hintView.text = "Listening…"; appendFinal(t) },
                onError = { msg -> hintView.text = "Voice error: $msg" }
            )
            recognizer = rec
            rec.start()
        } catch (e: Exception) {
            hintView.text = "Voice init failed: ${e.message}"
        }
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
        val t = text(); if (t.isEmpty()) return
        hintView.text = "Translating…"
        Translator.liveTranslate(t, BuildConfig.VOICE_TRANSLATE_TO) { result ->
            if (result != null) { editor.setText(result); editor.setSelection(editor.text.length); hintView.text = "Listening…" }
            else hintView.text = "Translate failed"
        }
    }

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

    private class WaveBarView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bars = 22
        private var level = 0f
        private var phase = 0f
        private val rect = RectF()

        fun setLevel(l: Float) { level = max(level * 0.62f, l); phase += 0.4f; invalidate() }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            paint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
                0xFF6C4CFF.toInt(), 0xFFB66CFF.toInt(), Shader.TileMode.CLAMP)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val gap = dp(2.5f); val barW = (w - gap * (bars - 1)) / bars; val cy = h / 2f
            for (i in 0 until bars) {
                val env = 0.3f + 0.7f * abs(sin((phase + i * 0.5f).toDouble()).toFloat())
                val bh = h * 0.14f + h * 0.82f * level * env
                val x = i * (barW + gap)
                rect.set(x, cy - bh / 2f, x + barW, cy + bh / 2f)
                canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
            }
        }
        private fun dp(v: Float) = v * resources.displayMetrics.density
    }
}
