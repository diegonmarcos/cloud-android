package com.diegonmarcos.superapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File

/**
 * Virtual Business Card — LinkedIn-style hero strip (banner photo +
 * round profile picture overlapping the banner edge), then the
 * profile text (Name / Titles / Company / Location / Website), then
 * a QR code that encodes the website URL.
 *
 * All data comes from [ProfilePrefs]. The two photos are picked by
 * the user in Configs → Profile (gallery picker); falls back to a
 * solid brand colour + an initials avatar when neither is set.
 */
class BusinessCardFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val profile = ProfilePrefs(ctx)

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        scroll.addView(col)

        // ── Hero strip: banner photo + overlapping circular avatar ──
        val hero = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(ctx, 200),
            )
        }
        val banner = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(ctx, 140),
            )
            val bmp = loadBitmap(profile.bannerUri)
            if (bmp != null) setImageBitmap(bmp)
            else             setBackgroundColor(0xFF1A0033.toInt())
        }
        hero.addView(banner)

        val avatarSize = dp(ctx, 110)
        val avatar = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                avatarSize, avatarSize,
                android.view.Gravity.BOTTOM or android.view.Gravity.START,
            ).apply { leftMargin = dp(ctx, 18); bottomMargin = 0 }
        }
        val avatarIv = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            val bmp = loadBitmap(profile.pictureUri)
            setImageBitmap(circularBitmap(bmp ?: initialsBitmap(profile.initials, avatarSize), avatarSize))
        }
        avatar.addView(avatarIv)
        hero.addView(avatar)
        col.addView(hero)

        // ── Identity block ─────────────────────────────────────────
        val pad = dp(ctx, 18)
        val identity = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(ctx, 8), pad, dp(ctx, 8))
        }
        identity.addView(textRow(ctx, profile.name,
            android.R.style.TextAppearance_Material_Headline))
        // Titles — single line, the ASCII "| " separators are part of
        // the design. wraps on overflow so all four segments stay
        // visible without being split across stacked rows.
        identity.addView(TextView(ctx).apply {
            text = profile.titles
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            alpha = 0.85f
            setPadding(0, dp(ctx, 4), 0, 0)
        })
        identity.addView(spacer(ctx, 6))
        // Plain value rows (no "Company:" / "Location:" / "Website:"
        // labels — the visual context tells the user what's what).
        identity.addView(textRow(ctx, profile.company,
            android.R.style.TextAppearance_Material_Body1))
        identity.addView(textRow(ctx, profile.location,
            android.R.style.TextAppearance_Material_Body2, alpha = 0.75f))
        identity.addView(TextView(ctx).apply {
            text = profile.website
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            alpha = 0.85f
            setPadding(0, dp(ctx, 4), 0, 0)
            isClickable = true; isFocusable = true
            setOnClickListener {
                val url = "https://${profile.website.removePrefix("https://").removePrefix("http://")}"
                runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)))
                }
            }
        })
        col.addView(identity)

        // ── QR code linking to the website ─────────────────────────
        val qrUrl = "https://${profile.website.removePrefix("https://").removePrefix("http://")}"
        val qrCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(pad, dp(ctx, 12), pad, dp(ctx, 24))
        }
        qrCard.addView(TextView(ctx).apply {
            text = "Scan to visit"
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            alpha = 0.7f
        })
        // 75% smaller thumbnail (was 220dp → 70dp). Tap opens a
        // full-screen view of the same QR for easier scanning.
        val qrBmp = qrBitmap(qrUrl, dp(ctx, 220))
        if (qrBmp != null) {
            val frame = FrameLayout(ctx).apply {
                background = androidx.core.content.ContextCompat.getDrawable(
                    ctx, R.drawable.bg_qr_frame)
                layoutParams = LinearLayout.LayoutParams(
                    dp(ctx, 70), dp(ctx, 70)
                ).apply { topMargin = dp(ctx, 6) }
                val p = dp(ctx, 4); setPadding(p, p, p, p)
                isClickable = true; isFocusable = true
            }
            frame.addView(ImageView(ctx).apply {
                setImageBitmap(qrBmp)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            })
            frame.setOnClickListener {
                Haptics.tap(it)
                showQrFullScreen(qrUrl)
            }
            qrCard.addView(frame)
        }
        col.addView(qrCard)

        return scroll
    }

    // ── helpers ─────────────────────────────────────────────────────

    private fun loadBitmap(path: String): Bitmap? {
        if (path.isBlank()) return null
        return runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }

    private fun circularBitmap(src: Bitmap, size: Int): Bitmap {
        // Square crop + circular mask + white outer ring so the avatar
        // reads against any banner backdrop.
        val scaled = if (src.width != size || src.height != size) {
            Bitmap.createScaledBitmap(src, size, size, true)
        } else src
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path().apply { addCircle(size / 2f, size / 2f, size / 2f - dp(requireContext(), 3), Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        canvas.restore()
        // White outer ring.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(requireContext(), 3).toFloat()
        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - dp(requireContext(), 1.5f.toInt()), paint)
        return out
    }

    private fun initialsBitmap(initials: String, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF7C3AED.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.42f
            isFakeBoldText = true
        }
        val baselineY = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initials.ifBlank { "•" }, size / 2f, baselineY, paint)
        return bmp
    }

    /** Show the QR code full-screen in a tap-to-dismiss dialog. Same
     *  rainbow frame; the QR itself is regenerated at a larger pixel
     *  size so reader cameras pick it up cleanly. */
    private fun showQrFullScreen(qrUrl: String) {
        val ctx = requireContext()
        val dialog = android.app.Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(0xEE000000.toInt())
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        // Compute display side ~80% of screen min dimension.
        val side = (ctx.resources.displayMetrics.let { Math.min(it.widthPixels, it.heightPixels) } * 0.8f).toInt()
        val frame = FrameLayout(ctx).apply {
            background = androidx.core.content.ContextCompat.getDrawable(
                ctx, R.drawable.bg_qr_frame)
            layoutParams = FrameLayout.LayoutParams(side, side, android.view.Gravity.CENTER)
            val p = dp(ctx, 18); setPadding(p, p, p, p)
        }
        val bigQr = qrBitmap(qrUrl, side)
        frame.addView(ImageView(ctx).apply {
            if (bigQr != null) setImageBitmap(bigQr)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        })
        root.addView(frame)
        dialog.setContentView(root)
        dialog.show()
    }

    /** Encode `text` as a colourful QR code Bitmap. Uses ZXing for the
     *  matrix; each "on" module gets a hue from a diagonal rainbow
     *  gradient (HSV cycle across the diagonal), so the QR reads like
     *  the "geeky rainbow QR" reference images — still high-contrast
     *  enough to scan since saturation/value stay high.
     *
     *  Background stays white. Modules tinted only — never overlap so
     *  the data integrity is unchanged. */
    private fun qrBitmap(text: String, size: Int): Bitmap? = runCatching {
        if (text.isBlank()) return@runCatching null
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val hsv = FloatArray(3).apply { this[1] = 0.85f; this[2] = 0.85f }
        for (x in 0 until size) {
            for (y in 0 until size) {
                if (matrix.get(x, y)) {
                    // Hue from 0..360 across the diagonal — same colour
                    // pattern as a rainbow QR overlay.
                    hsv[0] = ((x + y).toFloat() / (size * 2) * 360f) % 360f
                    bmp.setPixel(x, y, Color.HSVToColor(hsv))
                } else {
                    bmp.setPixel(x, y, Color.WHITE)
                }
            }
        }
        bmp
    }.getOrNull()

    private fun textRow(ctx: android.content.Context, text: String, styleRes: Int, alpha: Float = 1f): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(styleRes)
            this.alpha = alpha
            setPadding(0, dp(ctx, 2), 0, 0)
        }

    private fun keyValueRow(ctx: android.content.Context, key: String, value: String, clickUrl: String? = null): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
        }
        row.addView(TextView(ctx).apply {
            text = key
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            alpha = 0.6f
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 96), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        row.addView(TextView(ctx).apply {
            text = value
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (clickUrl != null) {
                isClickable = true; isFocusable = true
                setOnClickListener {
                    val i = android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(clickUrl))
                    runCatching { startActivity(i) }
                }
            }
        })
        return row
    }

    private fun spacer(ctx: android.content.Context, heightDp: Int) =
        View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, heightDp))
        }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    companion object {
        fun newInstance(): BusinessCardFragment = BusinessCardFragment()
    }
}
