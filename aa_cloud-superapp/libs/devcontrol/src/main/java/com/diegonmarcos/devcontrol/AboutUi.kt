package com.diegonmarcos.devcontrol

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * One titled block of the About/diagnostics screen. [render] populates the
 * section body (given the block's container). The app supplies these so the
 * reusable renderer knows nothing about profile/battery/firewall internals —
 * app-specific sections are injected; generic ones can be shared.
 */
data class AboutSection(val title: String, val render: (Context, LinearLayout) -> Unit)

/**
 * App-agnostic About/diagnostics UI toolkit — the section renderer + row/label
 * builders lifted out of the app so every constellation app builds its About
 * screen from injected [AboutSection]s with one consistent look. Pure View
 * construction: no R, no app singletons, no BuildConfig — symlinkable verbatim.
 *
 * Every row/title also appends to [infoBuf] so the app can wire a "Copy all".
 *
 * Usage:
 *   val ui = AboutUi(ctx)
 *   val view = ui.buildAboutView("About Foo", listOf(
 *       AboutSection("Device") { c, host -> ui.row(host, "Model", Build.MODEL) },
 *       AboutSection("Firewall") { c, host -> /* app-specific */ },
 *   ))
 */
class AboutUi(private val ctx: Context) {

    /** Plain-text accumulation of every rendered title/row, for "Copy all". */
    val infoBuf = StringBuilder()

    fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun copy(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("about", text))
    }

    fun title(text: String): TextView = TextView(ctx).apply {
        infoBuf.append("# ").append(text).append("\n")
        this.text = text
        setTextColor(0xFFE9D8FD.toInt())
        typeface = Typeface.DEFAULT_BOLD
        setTextAppearance(android.R.style.TextAppearance_Material_Headline)
        setPadding(0, 0, 0, dp(12))
    }

    fun sectionHeader(text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(0xFF7C3AED.toInt())
        typeface = Typeface.DEFAULT_BOLD
        setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        setPadding(0, dp(14), 0, dp(4))
    }

    fun small(text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(0x99FFFFFF.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Caption)
        setPadding(0, dp(4), 0, dp(4))
    }

    /** Header + body container appended to [host]; [body] fills the block. */
    fun section(host: LinearLayout, head: String, body: (LinearLayout) -> Unit) {
        host.addView(sectionHeader(head))
        val grp = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        host.addView(grp)
        body(grp)
    }

    /** Key/value row (mono value, long-press-to-copy). Returns the value view
     *  so async callers can rebind `.text` later without rebuilding the row. */
    fun row(host: LinearLayout, key: String, value: String): TextView {
        infoBuf.append("  ").append(key).append(": ").append(value).append("\n")
        val rowL = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        rowL.addView(TextView(ctx).apply {
            text = key
            setTextColor(0xCCFFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            layoutParams = LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val valueView = TextView(ctx).apply {
            text = value
            setTextColor(0xFFB794F4.toInt())
            typeface = Typeface.MONOSPACE
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnLongClickListener {
                copy("$key: $value")
                Toast.makeText(ctx, "Copied $key", Toast.LENGTH_SHORT).show(); true
            }
        }
        rowL.addView(valueView)
        host.addView(rowL)
        return valueView
    }

    fun actionButton(label: String, bg: Int = 0xFF7C3AED.toInt(), onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(bg)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }

    /**
     * Build the full scrollable About view: [headerTitle] then each of
     * [sections] in order, then a trailing "Copy all" button that dumps
     * [infoBuf]. This is the whole reusable screen — any app calls it with its
     * own section list.
     */
    fun buildAboutView(headerTitle: String, sections: List<AboutSection>): ScrollView {
        val scroll = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16); setPadding(pad, pad, pad, pad)
        }
        scroll.addView(column)
        column.addView(title(headerTitle))
        for (sec in sections) section(column, sec.title) { sec.render(ctx, it) }
        column.addView(actionButton("Copy all") {
            copy(infoBuf.toString())
            Toast.makeText(ctx, "Copied About", Toast.LENGTH_SHORT).show()
        })
        return scroll
    }
}
