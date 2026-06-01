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
        // Titles — render each segment (split on " | ") as its own line
        // so a long titles string doesn't crowd a single row.
        for (t in profile.titles.split("|").map { it.trim() }.filter { it.isNotEmpty() }) {
            identity.addView(textRow(ctx, t,
                android.R.style.TextAppearance_Material_Body2, alpha = 0.85f))
        }
        identity.addView(spacer(ctx, 6))
        identity.addView(keyValueRow(ctx, "Company",  profile.company))
        identity.addView(keyValueRow(ctx, "Location", profile.location))
        identity.addView(keyValueRow(ctx, "Website",  profile.website,
            clickUrl = "https://${profile.website.removePrefix("https://").removePrefix("http://")}"))
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
        val qrBmp = qrBitmap(qrUrl, dp(ctx, 220))
        if (qrBmp != null) {
            qrCard.addView(ImageView(ctx).apply {
                setImageBitmap(qrBmp)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 220), dp(ctx, 220)).apply {
                    topMargin = dp(ctx, 6)
                }
            })
        }
        qrCard.addView(TextView(ctx).apply {
            text = qrUrl
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            alpha = 0.7f
            setPadding(0, dp(ctx, 6), 0, 0)
        })
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

    /** Encode `text` as a QR code Bitmap. Uses ZXing — pure-Java, no
     *  Android-specific ZXing artifact needed. Returns null if encoding
     *  fails (empty input, etc.). */
    private fun qrBitmap(text: String, size: Int): Bitmap? = runCatching {
        if (text.isBlank()) return@runCatching null
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
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
