package com.diegonmarcos.cloudme

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.updater.Updater
import org.json.JSONArray
import org.json.JSONObject

/**
 * Renders one page's `stack_<id>` list. Seven block kinds cover every page in
 * the app that is not a library surface:
 *
 *   section_title  a heading between blocks
 *   note           a paragraph — used for honest empty states
 *   stats          label/value rows in a card
 *   cards          a card per item, with optional meta rows and progress bar
 *   link_grid      a four-column icon grid of navigation targets
 *   about          version, commit, build time and the update control
 *   permissions    the runtime permissions from build.json, requestable inline
 *
 * Adding a page means adding JSON, not Kotlin. An unrecognised kind renders
 * its `title` and nothing else, so a typo shows up as a stub on the phone
 * rather than as a missing block nobody notices.
 */
class StackFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 28))
        }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.me_bg))
            addView(col)
        }

        val section = Sections.byId(arguments?.getString(ARG_SECTION))
        val stack = section?.stackFor(arguments?.getString(ARG_PAGE).orEmpty()) ?: JSONArray()
        for (i in 0 until stack.length()) {
            val block = stack.optJSONObject(i) ?: continue
            renderBlock(ctx, col, block)
        }
        if (col.childCount == 0) col.addView(note(ctx, "Nothing here yet", ""))
        return scroll
    }

    private fun renderBlock(ctx: Context, col: LinearLayout, o: JSONObject) {
        when (o.optString("kind")) {
            "section_title" -> col.addView(heading(ctx, o.optString("title")))
            "note" -> col.addView(note(ctx, o.optString("title"), o.optString("body")))
            "stats" -> col.addView(statsCard(ctx, o))
            "cards" -> {
                o.optString("title").takeIf { it.isNotBlank() }?.let { col.addView(heading(ctx, it)) }
                o.optString("subtitle").takeIf { it.isNotBlank() }?.let { col.addView(dim(ctx, it)) }
                val items = o.optJSONArray("items") ?: JSONArray()
                for (i in 0 until items.length()) {
                    items.optJSONObject(i)?.let { col.addView(itemCard(ctx, it)) }
                }
            }
            "link_grid" -> {
                o.optString("title").takeIf { it.isNotBlank() }?.let { col.addView(heading(ctx, it)) }
                o.optString("subtitle").takeIf { it.isNotBlank() }?.let { col.addView(dim(ctx, it)) }
                col.addView(linkGrid(ctx, o.optJSONArray("links") ?: JSONArray()))
            }
            "about" -> col.addView(aboutCard(ctx, o))
            "permissions" -> col.addView(permissionsCard(ctx, o))
            // `fragment` is consumed by SectionFragment when it is a page's
            // only block; reaching here means it was mixed into a longer
            // stack, which this renderer has no container for.
            "fragment" -> Unit
            else -> o.optString("title").takeIf { it.isNotBlank() }?.let { col.addView(heading(ctx, it)) }
        }
    }

    // ── blocks ───────────────────────────────────────────────────────

    private fun statsCard(ctx: Context, o: JSONObject): View {
        val card = card(ctx)
        o.optString("title").takeIf { it.isNotBlank() }?.let { card.addView(cardTitle(ctx, it)) }
        o.optString("subtitle").takeIf { it.isNotBlank() }?.let { card.addView(dim(ctx, it)) }
        val rows = o.optJSONArray("rows") ?: JSONArray()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            card.addView(keyValueRow(ctx, r.optString("label"), r.optString("value")))
        }
        return card
    }

    private fun itemCard(ctx: Context, o: JSONObject): View {
        val card = card(ctx)
        val accent = parseColor(o.optString("accent"), ContextCompat.getColor(ctx, R.color.me_primary))

        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 10), dp(ctx, 10)).apply { rightMargin = dp(ctx, 10) }
        })
        header.addView(cardTitle(ctx, o.optString("title")))
        card.addView(header)

        o.optString("subtitle").takeIf { it.isNotBlank() }?.let { card.addView(dim(ctx, it)) }

        val meta = o.optJSONArray("meta") ?: JSONArray()
        for (i in 0 until meta.length()) {
            val m = meta.optJSONObject(i) ?: continue
            card.addView(keyValueRow(ctx, m.optString("label"), m.optString("value")))
        }

        // Progress is optional and only drawn when declared, so a course
        // without a percentage does not render a permanently empty bar.
        if (o.has("progress")) {
            card.addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = o.optInt("progress", 0).coerceIn(0, 100)
                progressTintList = android.content.res.ColorStateList.valueOf(accent)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(ctx, 10) }
            })
        }

        o.optString("target").takeIf { it.isNotBlank() }?.let { target ->
            card.isClickable = true
            card.setOnClickListener { (activity as? MainActivity)?.onTarget(target) }
        }
        return card
    }

    private fun linkGrid(ctx: Context, links: JSONArray): View {
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        for (i in 0 until links.length()) {
            val l = links.optJSONObject(i) ?: continue
            if (i % COLUMNS == 0) {
                row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(ctx, 8) }
                }
                grid.addView(row)
            }
            row?.addView(linkTile(ctx, l), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        // Pad the last row so three tiles do not stretch to fill four slots.
        val remainder = links.length() % COLUMNS
        if (remainder != 0) repeat(COLUMNS - remainder) {
            row?.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        }
        return grid
    }

    private fun linkTile(ctx: Context, o: JSONObject): View {
        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(ctx, 6), dp(ctx, 12), dp(ctx, 6), dp(ctx, 12))
            background = roundedBackground(ctx, R.color.me_surface)
            isClickable = true
        }
        iconRes(ctx, o.optString("icon"))?.let { res ->
            tile.addView(ImageView(ctx).apply {
                setImageResource(res)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.me_primary))
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 26), dp(ctx, 26))
            })
        }
        tile.addView(TextView(ctx).apply {
            text = o.optString("label")
            setTextColor(ContextCompat.getColor(ctx, R.color.me_text))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 6), 0, 0)
        })
        val target = o.optString("target")
        if (target.isNotBlank()) tile.setOnClickListener { (activity as? MainActivity)?.onTarget(target) }
        return LinearLayout(ctx).apply {
            setPadding(dp(ctx, 3), 0, dp(ctx, 3), 0)
            addView(tile, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun aboutCard(ctx: Context, o: JSONObject): View {
        val card = card(ctx)
        card.addView(cardTitle(ctx, o.optString("title").ifBlank { getString(R.string.app_name) }))
        card.addView(keyValueRow(ctx, "Version", versionName(ctx)))
        card.addView(keyValueRow(ctx, "Commit", BuildConfig.GIT_SHORT_SHA))
        card.addView(keyValueRow(ctx, "Built", BuildConfig.BUILD_TIMESTAMP))
        card.addView(keyValueRow(ctx, "Package", ctx.packageName))
        card.addView(actionRow(ctx, "Check for updates now") {
            Updater.checkNow(requireContext())
        })
        return card
    }

    private fun permissionsCard(ctx: Context, o: JSONObject): View {
        val card = card(ctx)
        o.optString("title").takeIf { it.isNotBlank() }?.let { card.addView(cardTitle(ctx, it)) }
        o.optString("subtitle").takeIf { it.isNotBlank() }?.let { card.addView(dim(ctx, it)) }
        val perms = runCatching {
            JSONArray(String(Base64.decode(BuildConfig.UI_PERMISSIONS_RUNTIME_B64, Base64.DEFAULT)))
        }.getOrDefault(JSONArray())
        if (perms.length() == 0) card.addView(dim(ctx, "None declared."))
        for (i in 0 until perms.length()) {
            val p = perms.optJSONObject(i) ?: continue
            val perm = p.optString("perm")
            val granted = ContextCompat.checkSelfPermission(ctx, perm) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            card.addView(actionRow(ctx, p.optString("label"), if (granted) "granted" else "grant") {
                if (!granted) requestPermissions(arrayOf(perm), 0)
            })
        }
        return card
    }

    // ── small view helpers ───────────────────────────────────────────

    private fun card(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(ctx, R.color.me_surface)
        setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 10) }
    }

    private fun heading(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(ctx, R.color.me_text))
        textSize = 17f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(ctx, 16), 0, dp(ctx, 2))
    }

    private fun cardTitle(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(ctx, R.color.me_text))
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dim(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(ctx, R.color.me_text_dim))
        textSize = 13f
        setPadding(0, dp(ctx, 3), 0, 0)
    }

    private fun note(ctx: Context, title: String, body: String): View = card(ctx).apply {
        if (title.isNotBlank()) addView(cardTitle(ctx, title))
        if (body.isNotBlank()) addView(TextView(ctx).apply {
            text = body
            setTextColor(ContextCompat.getColor(ctx, R.color.me_text_dim))
            textSize = 13f
            setLineSpacing(dp(ctx, 3).toFloat(), 1f)
            setPadding(0, dp(ctx, 6), 0, 0)
        })
    }

    private fun keyValueRow(ctx: Context, label: String, value: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(ctx, 7), 0, 0)
        addView(TextView(ctx).apply {
            text = label
            setTextColor(ContextCompat.getColor(ctx, R.color.me_text_dim))
            textSize = 13f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(ctx).apply {
            text = value
            setTextColor(ContextCompat.getColor(ctx, R.color.me_text))
            textSize = 13f
            gravity = Gravity.END
        })
    }

    private fun actionRow(ctx: Context, label: String, action: String = "open", onTap: () -> Unit) =
        keyValueRow(ctx, label, action).apply {
            isClickable = true
            setOnClickListener { onTap() }
        }

    private fun roundedBackground(ctx: Context, colorRes: Int) = GradientDrawable().apply {
        cornerRadius = dp(ctx, 14).toFloat()
        setColor(ContextCompat.getColor(ctx, colorRes))
        setStroke(dp(ctx, 1), ContextCompat.getColor(ctx, R.color.me_outline))
    }

    private fun versionName(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "—"
    }.getOrDefault("—")

    private fun iconRes(ctx: Context, name: String): Int? {
        if (name.isBlank()) return null
        @Suppress("DiscouragedApi")
        val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
        return if (id == 0) null else id
    }

    /** `#RRGGBB` / `#AARRGGBB`, falling back to the theme accent — a bad
     *  colour string in JSON must not take a whole page down. */
    private fun parseColor(value: String, fallback: Int): Int =
        if (value.isBlank()) fallback else runCatching { Color.parseColor(value) }.getOrDefault(fallback)

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    companion object {
        private const val ARG_SECTION = "section"
        private const val ARG_PAGE = "page"
        private const val COLUMNS = 4

        fun newInstance(sectionId: String, pageId: String): StackFragment =
            StackFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SECTION, sectionId)
                    putString(ARG_PAGE, pageId)
                }
            }
    }
}
