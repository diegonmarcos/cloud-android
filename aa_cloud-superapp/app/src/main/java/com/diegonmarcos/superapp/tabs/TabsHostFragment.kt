package com.diegonmarcos.superapp.tabs

import android.content.Context
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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.R

/**
 * Internal browser host — the renderer for the Tabs section.
 *
 * Layout:
 *   ┌────────────────────────────────────────────────┐
 *   │ [tab chip] [tab chip] [+ new]                  │  ← strip
 *   │────────────────────────────────────────────────│
 *   │                                                │
 *   │              WebView                           │
 *   │                                                │
 *   └────────────────────────────────────────────────┘
 *
 * Tab persistence lives in [TabPrefs]. URLs sent via the activity's
 * launchUri (http://) end up here automatically — see MainActivity's
 * dispatch logic.
 */
class TabsHostFragment : Fragment() {

    private lateinit var prefs: TabPrefs
    private lateinit var stripRow: LinearLayout
    private lateinit var webView: WebView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        prefs = TabPrefs(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
        }

        val strip = HorizontalScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(ctx, 44),
            )
            isHorizontalScrollBarEnabled = false
        }
        stripRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dp(ctx, 6); setPadding(pad, pad, pad, pad)
        }
        strip.addView(stripRow)
        root.addView(strip)

        webView = WebView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString + " CloudSuperApp/1.0"
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null) prefs.setActive(url)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    val u = view?.url ?: return
                    val t = title ?: u
                    prefs.updateTitle(u, t)
                    renderStrip()
                }
            }
        }
        root.addView(webView)

        renderStrip()
        loadActiveTab()
        return root
    }

    private fun loadActiveTab() {
        val all = prefs.all()
        if (all.isEmpty()) {
            webView.loadDataWithBaseURL(
                null,
                "<html><body style='background:#000;color:#E9D8FD;font-family:sans-serif;padding:24px'>" +
                "<h2>No open tabs</h2><p>Tap a web link anywhere in the app and it'll open here.</p>" +
                "</body></html>",
                "text/html", "utf-8", null,
            )
            return
        }
        val active = prefs.activeUrl()?.takeIf { url -> all.any { it.url == url } }
            ?: all.first().url
        prefs.setActive(active)
        webView.loadUrl(active)
    }

    private fun renderStrip() {
        val ctx = stripRow.context
        stripRow.removeAllViews()
        val active = prefs.activeUrl()
        for (tab in prefs.all()) {
            stripRow.addView(chip(ctx, tab.title, isActive = tab.url == active, onTap = {
                prefs.setActive(tab.url)
                webView.loadUrl(tab.url)
                renderStrip()
            }, onClose = {
                prefs.remove(tab.url)
                renderStrip()
                loadActiveTab()
            }))
        }
        stripRow.addView(addButton(ctx))
    }

    private fun chip(ctx: Context, label: String, isActive: Boolean, onTap: () -> Unit, onClose: () -> Unit): View {
        val pillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, 14).toFloat()
            setColor(if (isActive) 0xFF4C1D95.toInt() else 0xFF1A0033.toInt())
            setStroke(1, 0x55B794F4)
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = pillBg
            val p = dp(ctx, 10); setPadding(p, dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
            val m = dp(ctx, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = m }
            isClickable = true; isFocusable = true
            setOnClickListener { onTap() }
        }
        row.addView(TextView(ctx).apply {
            text = label
            maxWidth = dp(ctx, 140)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (isActive) Color.WHITE else 0xFFB794F4.toInt())
            typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        })
        row.addView(TextView(ctx).apply {
            text = " × "
            setTextColor(0xCCFFFFFF.toInt())
            setOnClickListener { onClose() }
            val m = dp(ctx, 6); setPadding(m, 0, m, 0)
        })
        return row
    }

    private fun addButton(ctx: Context): View {
        return TextView(ctx).apply {
            text = " + "
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF7C3AED.toInt())
            }
            val sz = dp(ctx, 32)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            gravity = android.view.Gravity.CENTER
            setOnClickListener { promptForUrl() }
        }
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
                renderStrip()
                webView.loadUrl(url)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        webView.destroy()
        super.onDestroyView()
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = TabsHostFragment() }
}
