package com.diegonmarcos.superapp.tabs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.Collapsible
import com.diegonmarcos.superapp.R
import java.io.File
import java.security.MessageDigest

/**
 * Tabs host — two modes:
 *
 *   GRID    initial view; large square cards of every open tab with
 *           preview screenshot + title + close button + a "+" card.
 *           Modeled on the phone browser tab switcher.
 *   DETAIL  WebView fullscreen of the selected tab; back arrow
 *           returns to GRID. Captures a preview screenshot to
 *           cacheDir/tabs/<urlhash>.png on every onPageFinished.
 *
 * Implements [Collapsible] so re-tapping the Tabs bottom-nav slot
 * snaps back to GRID — quick gesture to "see all tabs".
 */
class TabsHostFragment : Fragment(), Collapsible, com.diegonmarcos.superapp.SuppressHorizontalSwipe {

    /** Activity-level horizontal-fling cycler skips this fragment while
     *  a WebView is visible, so URL bar gestures / desktop-view-mode
     *  horizontal scrolls inside the page don't get hijacked into a
     *  bottom-nav cycle. */
    override fun suppressHorizontalSwipe(): Boolean = mode is Mode.DETAIL

    /** Desktop-mode toggle — flips WebView UA + width override + initial
     *  scale. Persists for the lifetime of this fragment only. */
    private var desktopMode: Boolean = false

    private lateinit var prefs: TabPrefs
    private lateinit var rootContainer: FrameLayout
    private var webView: WebView? = null
    private var mode: Mode = Mode.GRID

    private sealed class Mode {
        object GRID : Mode()
        data class DETAIL(val url: String) : Mode()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        prefs = TabPrefs(ctx)
        rootContainer = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            background = androidx.core.content.ContextCompat.getDrawable(
                ctx, R.drawable.bg_gradient_black_purple)
        }
        // If launched with an open_url argument (set by
        // MainActivity.launchUri when an http URL was tapped), go
        // straight into DETAIL mode for that URL. Otherwise start in
        // GRID (the user opened the Tabs section directly and wants
        // to see all open tabs).
        val openUrl = arguments?.getString(ARG_OPEN_URL)
        if (!openUrl.isNullOrBlank()) {
            prefs.add(openUrl, openUrl); prefs.setActive(openUrl)
            showDetail(openUrl)
        } else {
            showGrid()
        }
        return rootContainer
    }

    override fun toggleAllCollapsed(): Boolean {
        // Re-tap on Tabs bottom-nav → always snap back to grid.
        if (mode !is Mode.GRID) { showGrid(); return true }
        return false
    }

    // ── GRID mode ────────────────────────────────────────────────────

    private fun showGrid() {
        mode = Mode.GRID
        teardownWebView()
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12); setPadding(pad, pad, pad, pad)
        }

        // Header: title + new-tab button
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val pad = dp(8); setPadding(pad, dp(4), pad, dp(12))
        }
        header.addView(TextView(ctx).apply {
            text = "Tabs"
            setTextColor(0xFFE9D8FD.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setTextAppearance(android.R.style.TextAppearance_Material_Title)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(addPill(ctx, "+ New tab") { promptForUrl() })
        column.addView(header)

        // 2-col grid
        val grid = GridLayout(ctx).apply {
            columnCount = 2
            useDefaultMargins = false
        }
        val tabs = prefs.all()
        if (tabs.isEmpty()) {
            column.addView(TextView(ctx).apply {
                text = "No tabs yet. Tap + to open a URL — or tap any external link in the app and it'll open here."
                setTextColor(0xCCFFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                alpha = 0.7f
                val pad = dp(20); setPadding(pad, pad, pad, pad)
            })
        } else {
            for (tab in tabs) grid.addView(tabCard(ctx, tab))
        }
        column.addView(grid)
        scroll.addView(column)

        rootContainer.removeAllViews()
        rootContainer.addView(scroll)
    }

    private fun tabCard(ctx: Context, tab: TabPrefs.Tab): View {
        val cardW = (resources.displayMetrics.widthPixels / 2) - dp(20)
        val cardH = dp(220)
        val card = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(0xFF1A0033.toInt())
                setStroke(1, 0x55B794F4)
            }
            layoutParams = GridLayout.LayoutParams().apply {
                width = cardW; height = cardH
                val m = dp(6); setMargins(m, m, m, m)
            }
            clipToOutline = true
        }

        // Preview image
        val preview = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val previewFile = if (tab.previewPath.isNotBlank()) File(tab.previewPath) else null
        if (previewFile != null && previewFile.exists()) {
            runCatching {
                preview.setImageBitmap(BitmapFactory.decodeFile(previewFile.absolutePath))
            }
        } else {
            preview.setImageDrawable(GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF2D1B69.toInt(), 0xFF1A0033.toInt()),
            ))
        }
        card.addView(preview)

        // Title overlay at bottom
        val titleBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC000000.toInt())
            val pad = dp(8); setPadding(pad, dp(6), pad, dp(6))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM,
            )
        }
        titleBar.addView(TextView(ctx).apply {
            text = tab.title.ifBlank { tab.url }
            setTextColor(Color.WHITE)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleBar.addView(TextView(ctx).apply {
            text = " ✕ "
            setTextColor(0xCCFFFFFF.toInt())
            setOnClickListener {
                // Delete preview file too.
                if (tab.previewPath.isNotBlank()) runCatching { File(tab.previewPath).delete() }
                prefs.remove(tab.url)
                showGrid()
            }
        })
        card.addView(titleBar)

        card.setOnClickListener {
            prefs.setActive(tab.url)
            showDetail(tab.url)
        }
        return card
    }

    private fun addPill(ctx: Context, text: String, onTap: () -> Unit): View =
        TextView(ctx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(0xFF7C3AED.toInt())
            }
            val px = dp(14); val py = dp(8)
            setPadding(px, py, px, py)
            setOnClickListener { onTap() }
        }

    private fun promptForUrl() {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            hint = "https://"
            setTextColor(Color.WHITE)
            setHintTextColor(0x80FFFFFF.toInt())
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("New tab")
            .setView(input)
            .setPositiveButton("Open") { _, _ ->
                var url = input.text.toString().trim()
                if (url.isBlank()) return@setPositiveButton
                if (!url.contains("://")) url = "https://$url"
                prefs.add(url, url)
                prefs.setActive(url)
                showDetail(url)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── DETAIL mode (WebView) ────────────────────────────────────────

    private fun showDetail(url: String) {
        mode = Mode.DETAIL(url)
        val ctx = requireContext()
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        // Top bar: ← Back to grid + URL text
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC1A0033.toInt())
            val pad = dp(8); setPadding(pad, pad, pad, pad)
        }
        bar.addView(TextView(ctx).apply {
            text = " ← Tabs "
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { showGrid() }
        })
        bar.addView(TextView(ctx).apply {
            text = url
            setTextColor(0xCCFFFFFF.toInt())
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            val m = dp(8); setPadding(m, 0, m, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // Overflow ⋮ — standard browser menu (open external / reload /
        // view mode toggle / copy URL / share). Placed at the right end
        // of the bar where every Chromium-derived browser puts it.
        bar.addView(TextView(ctx).apply {
            text = "⋮"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setTextAppearance(android.R.style.TextAppearance_Material_Title)
            val pad = dp(8); setPadding(pad, 0, pad, 0)
            setOnClickListener { v -> showBrowserMenu(v, url) }
        })
        column.addView(bar)

        webView = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            // UA + viewport mode applied here from the persisted-per-
            // fragment desktopMode flag. Re-applied on every toggle.
            applyViewMode(this)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)
                    val u = finishedUrl ?: return
                    // Capture preview after a short delay so the layout settles.
                    postDelayed({ capturePreview(this@apply, u) }, 600)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    val u = view?.url ?: return
                    prefs.updateTitle(u, title ?: u)
                }
            }
            loadUrl(url)
        }
        column.addView(webView)

        rootContainer.removeAllViews()
        rootContainer.addView(column)
    }

    private fun capturePreview(wv: WebView, url: String) {
        runCatching {
            val w = wv.width; val h = wv.height
            if (w <= 0 || h <= 0) return
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            wv.draw(Canvas(bmp))
            val dir = File(requireContext().cacheDir, "tabs").apply { mkdirs() }
            val file = File(dir, "${sha256(url)}.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 70, it) }
            bmp.recycle()
            prefs.updatePreview(url, file.absolutePath)
        }
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(40)
    }

    // ── lifecycle ────────────────────────────────────────────────────

    /** Apply the current desktop/mobile flag to the WebView — UA string,
     *  viewport, and initial zoom. Called once at WebView creation and
     *  again every time the user toggles via the overflow menu. */
    private fun applyViewMode(wv: WebView) {
        val s = wv.settings
        if (desktopMode) {
            // Chrome desktop UA — what most servers gate "desktop view" on.
            s.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 CloudSuperApp/1.0"
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
            wv.setInitialScale(1)
        } else {
            // Default Android UA — keep the system one so servers serve
            // their mobile layout. Suffix our app tag for the bot detectors.
            s.userAgentString = android.webkit.WebSettings.getDefaultUserAgent(requireContext()) +
                " CloudSuperApp/1.0"
            s.useWideViewPort = false
            s.loadWithOverviewMode = false
            wv.setInitialScale(0)
        }
    }

    /** Browser-style overflow menu — Open in external browser / Reload /
     *  View Desktop ↔ Mobile / Copy URL / Share. Mirrors what Chrome,
     *  Brave, Samsung Internet, etc. surface from their ⋮ button. */
    private fun showBrowserMenu(anchor: android.view.View, url: String) {
        val ctx = anchor.context
        val pop = android.widget.PopupMenu(ctx, anchor)
        val mi0 = pop.menu.add(0, 0, 0, "Open in browser…")
        val mi1 = pop.menu.add(0, 1, 1, "Reload")
        val mi2 = pop.menu.add(0, 2, 2,
            if (desktopMode) "View: Mobile" else "View: Desktop")
        val mi3 = pop.menu.add(0, 3, 3, "Copy URL")
        val mi4 = pop.menu.add(0, 4, 4, "Share…")
        pop.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                mi0.itemId -> openInExternalBrowser(url)
                mi1.itemId -> webView?.reload()
                mi2.itemId -> {
                    desktopMode = !desktopMode
                    webView?.let { applyViewMode(it); it.reload() }
                }
                mi3.itemId -> {
                    val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    clip?.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                    android.widget.Toast.makeText(ctx, "URL copied", android.widget.Toast.LENGTH_SHORT).show()
                }
                mi4.itemId -> {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, url)
                    }
                    startActivity(android.content.Intent.createChooser(send, "Share URL"))
                }
            }
            true
        }
        pop.show()
    }

    private fun openInExternalBrowser(url: String) {
        // ACTION_VIEW + chooser → system picker shows Chrome / Brave /
        // Firefox / Samsung Internet / whatever else is installed.
        val view = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(url),
        )
        startActivity(android.content.Intent.createChooser(view, "Open with"))
    }

    private fun teardownWebView() {
        webView?.let {
            runCatching { it.stopLoading() }
            runCatching { it.destroy() }
        }
        webView = null
    }

    override fun onDestroyView() {
        teardownWebView()
        super.onDestroyView()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val ARG_OPEN_URL = "open_url"
        fun newInstance(openUrl: String? = null) = TabsHostFragment().apply {
            arguments = Bundle().apply {
                if (!openUrl.isNullOrBlank()) putString(ARG_OPEN_URL, openUrl)
            }
        }
    }
}
