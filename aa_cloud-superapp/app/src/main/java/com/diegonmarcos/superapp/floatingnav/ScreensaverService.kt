package com.diegonmarcos.superapp.floatingnav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screensaver — a full-screen OPAQUE BLACK overlay (SYSTEM_ALERT_WINDOW), NOT a
 * lock and NOT an Activity: the app underneath stays resumed, so a playing
 * video/audio keeps going, just hidden behind the black. Shows the time + date
 * at the top and a single "Unlock" button at the bottom (the only actionable).
 *
 * Behaviour matches the "Black Screen" app, renamed Screensaver. Triggered by
 * the floating-nav `screensaver` action. Formats are data-driven
 * (build.json::ui.screensaver → BuildConfig.SCREENSAVER_*).
 */
class ScreensaverService : Service() {

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var clock: TextView? = null
    private var dateLabel: TextView? = null

    private val timeFmt by lazy { SimpleDateFormat(BuildConfig.SCREENSAVER_TIME_FORMAT, Locale.getDefault()) }
    private val dateFmt by lazy { SimpleDateFormat(BuildConfig.SCREENSAVER_DATE_FORMAT, Locale.getDefault()) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
        showOverlay()
        main.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        isRunning = false
        main.removeCallbacksAndMessages(null)
        overlay?.let { runCatching { wm.removeView(it) } }; overlay = null
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clock?.text = runCatching { timeFmt.format(now) }.getOrDefault("")
            dateLabel?.text = runCatching { dateFmt.format(now) }.getOrDefault("")
            main.postDelayed(this, 1000L)
        }
    }

    private fun showOverlay() {
        if (overlay != null) return
        // Configs → Launcher → Screensaver picker. 'neon_lights' swaps the
        // black backdrop for the iOS-18 Siri edge-lighting glow + an inner
        // Solar-System orrery; anything else = the classic black clock.
        val neon = runCatching {
            com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(this).screensaver == "neon_lights"
        }.getOrDefault(false)
        val root = FrameLayout(this).apply {
            setBackgroundColor(if (neon) 0xFF0A0014.toInt() else Color.BLACK)
            isClickable = true; isFocusable = true   // swallow taps to the app underneath
        }
        // Neon scene sits behind everything (added first).
        if (neon) root.addView(NeonBackdropView(this), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // Time + date, near the top.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clock = TextView(this@ScreensaverService).apply {
                setTextColor(0xFFEDEDED.toInt()); textSize = 56f
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            }
            dateLabel = TextView(this@ScreensaverService).apply {
                setTextColor(0xCCFFFFFF.toInt()); textSize = 18f
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            addView(clock); addView(dateLabel)
            layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = dp(72) }
        })
        // Unlock button, near the bottom.
        root.addView(TextView(this).apply {
            text = BuildConfig.SCREENSAVER_UNLOCK_LABEL
            setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
            gravity = Gravity.CENTER
            isAllCaps = false
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(0x33, 0xFF, 0xFF, 0xFF))
                setStroke(dp(1), Color.argb(0x66, 0xFF, 0xFF, 0xFF))
            }
            setPadding(dp(40), dp(12), dp(40), dp(12))
            isClickable = true
            setOnClickListener { stopSelf() }
            layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(96) } // clear the system nav bar
        })

        if (neon) clock?.apply {
            setTextColor(0xFFEAFCFF.toInt())
            setShadowLayer(28f, 0f, 0f, 0xFF18E0FF.toInt())
        }

        runCatching { wm.addView(root, overlayParams()) }
        overlay = root
    }

    /** Animated neon "synthwave" grid — a scrolling horizontal floor below a
     *  horizon plus converging verticals, cyan + magenta with a glow. Software
     *  layer so the shadow-layer glow renders. Stops itself on detach. */
    /** Apple-retail "demo mode" vibe: a vibrant neon EDGE-LIGHTING border whose
     *  colours sweep continuously around the screen perimeter, plus a soft inner
     *  light that drifts. Software layer so the shadow-layer glow renders. */
    // A natural satellite — orbits its planet. r/size in dp, speed in rad/s.
    private data class Moon(val r: Int, val size: Int, val speed: Float, val color: Int)
    // A planet — orbits the sun. `orbit` is a 1..8 rank mapped to a screen
    // radius; size in dp; speed in rad/s (inner planets faster, Kepler-ish).
    private data class Planet(
        val orbit: Int, val size: Int, val color: Int, val speed: Float,
        val ring: Boolean, val moons: List<Moon>,
    )

    /**
     * Screensaver backdrop = iOS-18 "Siri" edge lighting (exact Apple
     * Intelligence palette + a sharp core stroke under a blurred bloom,
     * gently breathing) wrapped around an inner Solar-System orrery — the
     * Sun, all eight planets on their orbits, each with its major moons,
     * Saturn ringed. Time-driven (continuous, no loop reset).
     */
    private inner class NeonBackdropView(ctx: Context) : View(ctx) {
        private val startNanos = System.nanoTime()

        // Apple Intelligence / Siri glow palette (looped back to the first).
        private val siri = intArrayOf(
            0xFFBC82F3.toInt(), 0xFFF5B9EA.toInt(), 0xFF8D9FFF.toInt(),
            0xFFAA6EEE.toInt(), 0xFFFF6778.toInt(), 0xFFFFBA71.toInt(),
            0xFFC686FF.toInt(), 0xFFBC82F3.toInt())
        private val bloom = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND
            maskFilter = android.graphics.BlurMaskFilter(dp(16).toFloat(), android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        private val core = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private val rect = android.graphics.RectF()
        private val mtx = android.graphics.Matrix()

        // ── Solar System (compressed orbits so all eight fit a phone) ────────
        private val sunColor = 0xFFFFCC33.toInt()
        private val planets = listOf(
            Planet(1, 4,  0xFF9E9E9E.toInt(), 1.30f, false, emptyList()),                                  // Mercury
            Planet(2, 6,  0xFFE8C16B.toInt(), 0.95f, false, emptyList()),                                  // Venus
            Planet(3, 6,  0xFF4B8FE3.toInt(), 0.80f, false, listOf(Moon(11, 2, 2.4f, 0xFFCFCFCF.toInt()))), // Earth + Moon
            Planet(4, 5,  0xFFD9603B.toInt(), 0.65f, false, listOf(                                         // Mars + Phobos/Deimos
                Moon(9, 1, 3.0f, 0xFFB0A090.toInt()), Moon(13, 1, 2.1f, 0xFFA09080.toInt()))),
            Planet(5, 13, 0xFFD8A87B.toInt(), 0.42f, false, listOf(                                         // Jupiter + 4 Galilean
                Moon(20, 2, 2.6f, 0xFFE8E0C0.toInt()), Moon(26, 2, 2.0f, 0xFFC8D8E0.toInt()),
                Moon(32, 3, 1.6f, 0xFFB0A088.toInt()), Moon(39, 3, 1.2f, 0xFF888078.toInt()))),
            Planet(6, 11, 0xFFE3D9A6.toInt(), 0.32f, true,  listOf(                                         // Saturn (ring) + Titan, Rhea
                Moon(26, 3, 1.5f, 0xFFD9A86B.toInt()), Moon(33, 2, 1.1f, 0xFFB8B0A0.toInt()))),
            Planet(7, 8,  0xFF8FE3E0.toInt(), 0.24f, false, listOf(Moon(15, 2, 1.4f, 0xFFBfC8C8.toInt()))), // Uranus + Titania
            Planet(8, 8,  0xFF3F54D1.toInt(), 0.19f, false, listOf(Moon(15, 2, 1.6f, 0xFFC8C0B0.toInt()))), // Neptune + Triton
        )
        private val body = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val orbitPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = dp(1).toFloat()
            color = 0x18FFFFFF
        }
        private val ringPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = dp(2).toFloat()
            color = 0xAAE3D9A6.toInt()
        }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); postInvalidateOnAnimation() }
        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val cx = w / 2f; val cy = h / 2f
            val sec = (System.nanoTime() - startNanos) / 1_000_000_000f
            val minDim = Math.min(w, h)

            // ── Solar System orrery ──────────────────────────────────────────
            // Sun — radial-glow core at centre.
            body.shader = android.graphics.RadialGradient(
                cx, cy, dp(16).toFloat(), sunColor, 0x00FFCC33,
                android.graphics.Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, dp(16).toFloat(), body)
            body.shader = null
            body.color = sunColor
            canvas.drawCircle(cx, cy, dp(9).toFloat(), body)

            for (p in planets) {
                val orbitR = minDim * (0.085f + 0.046f * p.orbit)
                canvas.drawCircle(cx, cy, orbitR, orbitPaint)
                val a = sec * p.speed
                val px = cx + orbitR * Math.cos(a.toDouble()).toFloat()
                val py = cy + orbitR * Math.sin(a.toDouble()).toFloat()
                if (p.ring) {
                    rect.set(px - dp(p.size + 8), py - dp(p.size + 2),
                             px + dp(p.size + 8), py + dp(p.size + 2))
                    canvas.save(); canvas.rotate(-20f, px, py)
                    canvas.drawOval(rect, ringPaint); canvas.restore()
                }
                body.color = p.color
                canvas.drawCircle(px, py, dp(p.size).toFloat(), body)
                for (m in p.moons) {
                    val ma = sec * m.speed
                    val mx = px + dp(m.r) * Math.cos(ma.toDouble()).toFloat()
                    val my = py + dp(m.r) * Math.sin(ma.toDouble()).toFloat()
                    body.color = m.color
                    canvas.drawCircle(mx, my, dp(m.size).toFloat(), body)
                }
            }

            // ── iOS-18 Siri edge lighting ────────────────────────────────────
            // Sweep of the Apple palette around a rounded rect, drifting slowly
            // (≈40 s/rev) with a breathing bloom — not a harsh spin.
            val inset = dp(7).toFloat()
            rect.set(inset, inset, w - inset, h - inset)
            val sweep = android.graphics.SweepGradient(cx, cy, siri, null)
            mtx.setRotate((sec * 9f) % 360f, cx, cy)   // ~0.025 rev/s
            sweep.setLocalMatrix(mtx)
            val rad = dp(44).toFloat()
            val breathe = 0.5f + 0.5f * Math.sin(sec * 1.1).toFloat()  // 0..1
            // Bloom (wide, blurred, breathing alpha + width) under a sharp core.
            bloom.shader = sweep
            bloom.strokeWidth = dp(9).toFloat() + dp(5) * breathe
            bloom.alpha = (120 + 110 * breathe).toInt()
            canvas.drawRoundRect(rect, rad, rad, bloom)
            core.shader = sweep
            core.strokeWidth = dp(4).toFloat()
            core.alpha = 255
            canvas.drawRoundRect(rect, rad, rad, core)

            postInvalidateOnAnimation()
        }
    }

    private fun overlayParams(): WindowManager.LayoutParams {
        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        // Use the REAL screen size (incl. the system-bar regions) so the black
        // covers edge-to-edge — MATCH_PARENT gets clipped to the content frame,
        // leaving the app's purple gradient + nav bar area showing.
        val (w, h) = realScreenSize()
        return WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags, android.graphics.PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    /** Full physical screen size, including the status- and nav-bar regions. */
    private fun realScreenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, "Screensaver", NotificationManager.IMPORTANCE_MIN,
                ).apply { description = "Active while the black-screen screensaver is up."; setShowBadge(false) })
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("Cloud SuperApp — Screensaver")
            .setContentText("Tap Unlock on screen to dismiss.")
            .setOngoing(true).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "screensaver"
        private const val NOTIF_ID = 0xF2

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Start the screensaver iff enabled and "display over other apps"
         *  is granted. Returns false otherwise (caller can prompt). */
        fun start(ctx: Context): Boolean {
            if (!BuildConfig.SCREENSAVER_ENABLED) return false
            if (!Settings.canDrawOverlays(ctx)) return false
            val i = Intent(ctx, ScreensaverService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
            return true
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ScreensaverService::class.java)) }
    }
}
