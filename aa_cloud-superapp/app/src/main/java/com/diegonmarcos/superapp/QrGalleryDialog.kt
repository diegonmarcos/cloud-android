package com.diegonmarcos.superapp

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject
import java.io.BufferedReader

/**
 * Self-contained native QR gallery — equivalent of the linktree web
 * page `qrcode.html`, but rendered entirely from a bundled asset
 * (assets/qrcodes/qrcodes.json) so the app never needs the linktree
 * site at runtime.
 *
 * The JSON is the same single source of truth the linktree project
 * uses; refresh it via `build.sh sync-qrcodes` after changes on the
 * front side.
 *
 * Layout: 2-column grid of cards (title + per-category-tinted QR).
 * Tapping a card opens a tap-to-dismiss full-screen view.
 */
object QrGalleryDialog {

    /** Entry point: build + show the gallery dialog. */
    fun show(ctx: Context) {
        val manifest = loadManifest(ctx) ?: return showError(ctx, "qrcodes.json missing in assets")
        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = buildGalleryRoot(ctx, manifest, dialog::dismiss)
        dialog.setContentView(root)
        dialog.show()
    }

    // ── manifest loading ───────────────────────────────────────────

    /** Read assets/qrcodes/qrcodes.json into a single JSONObject. */
    private fun loadManifest(ctx: Context): JSONObject? = runCatching {
        ctx.assets.open("qrcodes/qrcodes.json").bufferedReader().use(BufferedReader::readText)
            .let(::JSONObject)
    }.getOrNull()

    // ── UI tree ────────────────────────────────────────────────────

    private fun buildGalleryRoot(ctx: Context, m: JSONObject, onClose: () -> Unit): View {
        val frame = FrameLayout(ctx).apply { setBackgroundColor(0xFF1A0033.toInt()) }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 16); setPadding(p, dp(ctx, 24), p, dp(ctx, 24))
        }

        // Page chrome from manifest.page.
        val page = m.optJSONObject("page") ?: JSONObject()
        col.addView(TextView(ctx).apply {
            text = page.optString("title", "QR Codes")
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setTextColor(0xFFE9D5FF.toInt())
            gravity = Gravity.CENTER
        })
        col.addView(TextView(ctx).apply {
            text = page.optString("subtitle", "")
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            alpha = 0.75f
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 18))
        })

        // 2-column grid of cards.
        val grid = GridLayout(ctx).apply {
            columnCount = 2
            useDefaultMargins = true
        }
        val qrcodes = m.optJSONArray("qrcodes") ?: org.json.JSONArray()
        for (i in 0 until qrcodes.length()) {
            val spec = qrcodes.getJSONObject(i)
            grid.addView(buildQrCard(ctx, spec, m))
        }
        col.addView(grid)

        // Close button.
        col.addView(TextView(ctx).apply {
            text = "Close"
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 24), 0, 0)
            alpha = 0.7f
            isClickable = true; isFocusable = true
            setOnClickListener { onClose() }
        })

        scroll.addView(col)
        frame.addView(scroll)
        return frame
    }

    private fun buildQrCard(ctx: Context, spec: JSONObject, manifest: JSONObject): View {
        val category = spec.optString("category", "web")
        val title = spec.optString("title", spec.optString("id"))
        val palette = categoryPalette(category)
        val payload = resolvePayload(spec, manifest)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0x33000000.toInt())
            val p = dp(ctx, 10); setPadding(p, p, p, p)
            val cellSize = (ctx.resources.displayMetrics.widthPixels / 2) - dp(ctx, 32)
            layoutParams = GridLayout.LayoutParams().apply {
                width = cellSize
                height = cellSize + dp(ctx, 56)
                setMargins(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
            }
            isClickable = true; isFocusable = true
        }
        card.addView(TextView(ctx).apply {
            text = title
            setTextColor(palette.eyeFinder)
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(ctx, 6))
        })
        val qrSize = ((ctx.resources.displayMetrics.widthPixels / 2) - dp(ctx, 60))
            .coerceAtLeast(dp(ctx, 140))
        val qr = qrBitmap(payload, qrSize, palette)
        if (qr != null) {
            card.addView(ImageView(ctx).apply {
                setImageBitmap(qr)
                layoutParams = LinearLayout.LayoutParams(qrSize, qrSize)
            })
        }
        card.setOnClickListener { showFullScreen(ctx, payload, title, palette) }
        return card
    }

    /** Full-screen QR — tap anywhere to dismiss. */
    private fun showFullScreen(ctx: Context, payload: String, title: String, palette: Palette) {
        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(0xEE000000.toInt())
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        val side = (ctx.resources.displayMetrics.let { Math.min(it.widthPixels, it.heightPixels) } * 0.85f).toInt()
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { gravity = Gravity.CENTER }
        }
        column.addView(TextView(ctx).apply {
            text = title
            setTextColor(palette.eyeFinder)
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(ctx, 16))
        })
        val bmp = qrBitmap(payload, side, palette)
        if (bmp != null) column.addView(ImageView(ctx).apply {
            setImageBitmap(bmp)
            layoutParams = LinearLayout.LayoutParams(side, side)
        })
        root.addView(column)
        dialog.setContentView(root)
        dialog.show()
    }

    // ── payload resolution ────────────────────────────────────────

    /** Resolve a qrcodes[i].source into the actual QR string payload. */
    private fun resolvePayload(spec: JSONObject, manifest: JSONObject): String {
        val source = spec.optJSONObject("source") ?: return spec.optString("id")
        return when (source.optString("type")) {
            "url" -> {
                val ref = source.optString("ref", "")
                if (ref.isNotEmpty()) {
                    manifest.optJSONObject("links")?.optJSONObject(ref)?.optString("value", "") ?: ""
                } else source.optString("value", "")
            }
            "vcard" -> {
                val contact = manifest.optJSONObject("contact") ?: return ""
                val links = manifest.optJSONObject("links")
                renderVcard(contact, links)
            }
            else -> ""
        }
    }

    /** Port of qr-code-generator.ts:renderVcard. Photo never embedded
     *  (the only photo'd vCard is URL-based — its QR points at a
     *  landing page, not the inlined photo blob). */
    private fun renderVcard(contact: JSONObject, links: JSONObject?): String {
        val lines = mutableListOf("BEGIN:VCARD", "VERSION:3.0")
        val family = contact.optString("family", "")
        val given = contact.optString("given", "")
        lines += "N:$family;$given;"
        lines += "FN:${contact.optString("displayName", "$given $family")}"
        contact.optString("email").takeIf { it.isNotEmpty() }?.let {
            val t = contact.optString("emailType", "personal")
            lines += "EMAIL;TYPE=$t:$it"
        }
        contact.optString("tel").takeIf { it.isNotEmpty() }?.let {
            val t = contact.optString("telType", "mobile")
            lines += "TEL;TYPE=$t:$it"
        }
        contact.optJSONObject("address")?.let { a ->
            val t = a.optString("type", "home")
            val adrValue = ";;${a.optString("street")};${a.optString("city")};" +
                "${a.optString("region")};${a.optString("postalCode")};${a.optString("country")}"
            // RFC 2426 §5.7 — non-ASCII values need CHARSET=UTF-8 or
            // Apple's QR vCard parser silently drops the field. (The
            // file-import path sniffs the encoding and doesn't care.)
            val charsetParam = if (adrValue.any { it.code > 0x7F }) ";CHARSET=UTF-8" else ""
            lines += "ADR;TYPE=$t$charsetParam:$adrValue"
        }
        val urls = contact.optJSONArray("urls")
        if (urls != null) for (i in 0 until urls.length()) {
            val u = urls.getJSONObject(i)
            val (type, value) = if (u.has("ref")) {
                val link = links?.optJSONObject(u.getString("ref")) ?: continue
                link.optString("type") to link.optString("value")
            } else {
                u.optString("type") to u.optString("value")
            }
            lines += "URL;TYPE=$type:$value"
        }
        contact.optString("birthday").takeIf { it.isNotEmpty() }?.let { lines += "BDAY:$it" }
        lines += "END:VCARD"
        // vCard 3.0 (RFC 2426 §3.1) requires CRLF line endings — Apple's
        // QR vCard parser is strict about it and drops fields when the
        // line break isn't CRLF.
        return lines.joinToString("\r\n")
    }

    // ── QR rendering ───────────────────────────────────────────────

    /** Per-category palette — kept in Kotlin (small, stable surface)
     *  but conceptually matches qrcodes.json defaults.styleByCategory.
     *  Hue gradient drives a per-pixel HSV cycle. */
    private data class Palette(val gradStart: Int, val gradEnd: Int, val eyeFinder: Int, val eyeDot: Int)

    private fun categoryPalette(category: String): Palette = when (category) {
        "vcard"  -> Palette(0xFFF5D0FE.toInt(), 0xFF86198F.toInt(), 0xFF86198F.toInt(), 0xFFE879F9.toInt())
        "social" -> Palette(0xFFC7D2FE.toInt(), 0xFF1E1B4B.toInt(), 0xFF1E1B4B.toInt(), 0xFF818CF8.toInt())
        else     -> Palette(0xFFA78BFA.toInt(), 0xFF34208C.toInt(), 0xFF34208C.toInt(), 0xFF7F6DF2.toInt())
    }

    private fun qrBitmap(text: String, size: Int, palette: Palette): Bitmap? = runCatching {
        if (text.isBlank()) return@runCatching null
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN to 2,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (matrix.get(x, y)) {
                    // Linear interpolation between gradStart / gradEnd
                    // across the diagonal (matches the web's linear
                    // gradient rotation 0.785 ≈ 45°).
                    val t = ((x + y).toFloat() / (size * 2)).coerceIn(0f, 1f)
                    bmp.setPixel(x, y, lerpColor(palette.gradStart, palette.gradEnd, t))
                } else {
                    bmp.setPixel(x, y, Color.WHITE)
                }
            }
        }
        bmp
    }.getOrNull()

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val ar = (a shr 16) and 0xff; val ag = (a shr 8) and 0xff; val ab = a and 0xff
        val br = (b shr 16) and 0xff; val bg = (b shr 8) and 0xff; val bb = b and 0xff
        val r = (ar + (br - ar) * t).toInt().coerceIn(0, 255)
        val g = (ag + (bg - ag) * t).toInt().coerceIn(0, 255)
        val bC = (ab + (bb - ab) * t).toInt().coerceIn(0, 255)
        return (0xff shl 24) or (r shl 16) or (g shl 8) or bC
    }

    // ── helpers ────────────────────────────────────────────────────

    private fun showError(ctx: Context, message: String) {
        Dialog(ctx, android.R.style.Theme_Black_NoTitleBar).apply {
            setContentView(TextView(ctx).apply {
                text = message
                setTextColor(0xFFFCA5A5.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 24), dp(ctx, 48), dp(ctx, 24), dp(ctx, 48))
            })
            show()
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
