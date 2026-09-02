package com.diegonmarcos.cloudme

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.json.JSONObject

/**
 * Buro > Wallet: the wallet records as what they actually are — folders and
 * files, browsed in place.
 *
 * The tree under data/files/ ships verbatim as assets, so this needs no
 * manifest: AssetManager.list() enumerates a directory, and a path it returns
 * nothing for is a file. That is the whole reason the source of truth is a
 * directory rather than one blob — Cloud Wallet flattens it into wallet.json
 * to draw its 3D cards, and this shows the same records unflattened.
 *
 * Ordering is alphabetical, which is the only order a directory can promise;
 * the block's optional `order` puts the folders a human names first (Pay, IDs,
 * Vcards, Events) ahead of it, and `labels` gives them their capitalisation.
 */
class FilesFragment : Fragment() {

    private val root: String get() = arguments?.getString(ARG_ROOT).orEmpty()
    private var order: List<String> = emptyList()
    private var labels: Map<String, String> = emptyMap()

    /** Path below [root]; "" is the root itself. */
    private var path: String = ""

    private lateinit var crumb: TextView
    private lateinit var col: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        order = arguments?.getStringArrayList(ARG_ORDER).orEmpty()
        labels = arguments?.getString(ARG_LABELS).orEmpty().let(::parseLabels)
        path = s?.getString(STATE_PATH).orEmpty()

        crumb = TextView(ctx).apply {
            setTextColor(ContextCompat.getColor(ctx, R.color.me_text_dim))
            textSize = 13f
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 2))
            isClickable = true
            setOnClickListener { if (path.isNotEmpty()) go(path.substringBeforeLast('/', "")) }
        }
        col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 4), dp(ctx, 16), dp(ctx, 28))
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.me_bg))
            addView(crumb, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(ScrollView(ctx).apply { isFillViewport = true; addView(col) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        super.onViewCreated(view, s)
        // Registered after the activity's own handler, so it wins while there
        // is somewhere to go up to — Back inside a tree should climb it, not
        // leave the section.
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() = go(path.substringBeforeLast('/', ""))
            }.also { back = it })
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PATH, path)
    }

    private var back: OnBackPressedCallback? = null

    private fun go(next: String) {
        path = next
        render()
    }

    // ── rendering ────────────────────────────────────────────────────

    private fun render() {
        val ctx = context ?: return
        back?.isEnabled = path.isNotEmpty()
        crumb.text = (listOf(label(root.substringAfterLast('/'))) +
            path.split('/').filter { it.isNotEmpty() }.map(::label))
            .joinToString("  ›  ")
            .let { if (path.isEmpty()) it else "↑  $it" }

        col.removeAllViews()
        val full = if (path.isEmpty()) root else "$root/$path"
        val entries = list(ctx, full)
        if (entries == null) {
            col.addView(fileCard(ctx, full))
            return
        }
        if (entries.isEmpty()) {
            col.addView(note(ctx, "Empty folder", "Nothing is filed under this path yet."))
            return
        }
        entries.forEach { name -> col.addView(entryRow(ctx, full, name)) }
    }

    /** Names inside [p], or null when [p] is a file. AssetManager returns an
     *  empty array for both an empty folder and a file, so a file is told
     *  apart by having a suffix — every record in the tree has one. */
    private fun list(ctx: Context, p: String): List<String>? {
        val names = runCatching { ctx.assets.list(p) }.getOrNull()?.toList() ?: emptyList()
        if (names.isEmpty() && p.substringAfterLast('/').contains('.')) return null
        return names.sortedWith(compareBy<String>({ rank(it) }, { it.lowercase() }))
    }

    private fun rank(name: String): Int =
        order.indexOf(name.substringBeforeLast('.')).let { if (it < 0) order.size else it }

    private fun entryRow(ctx: Context, parent: String, name: String): View {
        val child = if (path.isEmpty()) name else "$path/$name"
        val isDir = list(ctx, "$parent/$name") != null
        val count = if (isDir) countLeaves(ctx, "$parent/$name") else 0

        val row = card(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { go(child) }
        }
        row.addView(ImageView(ctx).apply {
            setImageResource(if (isDir) R.drawable.ic_p_folder else R.drawable.ic_code)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.me_primary))
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22))
                .apply { rightMargin = dp(ctx, 12) }
        })
        row.addView(TextView(ctx).apply {
            text = label(name)
            setTextColor(ContextCompat.getColor(ctx, R.color.me_text))
            textSize = 15f
            setTypeface(typeface, if (isDir) Typeface.BOLD else Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(ctx).apply {
            text = if (isDir) "$count" else "file"
            setTextColor(ContextCompat.getColor(ctx, R.color.me_text_dim))
            textSize = 12f
        })
        return row
    }

    /** Records below [p], counted so a folder row says how much is in it
     *  rather than making you open it to find out. */
    private fun countLeaves(ctx: Context, p: String): Int {
        val names = list(ctx, p) ?: return 1
        return names.sumOf { countLeaves(ctx, "$p/$it") }
    }

    private fun fileCard(ctx: Context, p: String): View {
        val body = runCatching { ctx.assets.open(p).bufferedReader().use { it.readText() } }
            .getOrElse { "Could not read this file." }
        return card(ctx).apply {
            addView(TextView(ctx).apply {
                text = p.substringAfterLast('/')
                setTextColor(ContextCompat.getColor(ctx, R.color.me_text))
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(ctx, 8))
            })
            addView(TextView(ctx).apply {
                text = body
                setTextColor(ContextCompat.getColor(ctx, R.color.me_text_dim))
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setHorizontallyScrolling(false)
            })
        }
    }

    private fun note(ctx: Context, title: String, body: String) = card(ctx).apply {
        addView(TextView(ctx).apply {
            text = title
            setTextColor(ContextCompat.getColor(ctx, R.color.me_text))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(ctx).apply {
            text = body
            setTextColor(ContextCompat.getColor(ctx, R.color.me_text_dim))
            textSize = 13f
            setPadding(0, dp(ctx, 6), 0, 0)
        })
    }

    private fun card(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 14).toFloat()
            setColor(ContextCompat.getColor(ctx, R.color.me_surface))
            setStroke(dp(ctx, 1), ContextCompat.getColor(ctx, R.color.me_outline))
        }
        setPadding(dp(ctx, 14), dp(ctx, 13), dp(ctx, 14), dp(ctx, 13))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 8) }
    }

    /** Declared label, else the bare name with its suffix dropped — the tree
     *  is named for the filesystem, the strip is named for a person. */
    private fun label(name: String): String {
        val bare = name.substringBeforeLast('.')
        return labels[bare] ?: bare.replace('_', ' ').replace('-', ' ')
            .replaceFirstChar { it.uppercase() }
    }

    private fun parseLabels(json: String): Map<String, String> = runCatching {
        val o = JSONObject(json)
        o.keys().asSequence().associateWith { o.optString(it) }
    }.getOrDefault(emptyMap())

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    companion object {
        private const val ARG_ROOT = "root"
        private const val ARG_ORDER = "order"
        private const val ARG_LABELS = "labels"
        private const val STATE_PATH = "state_path"

        /** Built straight from the `fragment` block, so the tree's entry
         *  point, its preferred order and its display names are all data. */
        fun newInstance(block: JSONObject): FilesFragment = FilesFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_ROOT, block.optString("root"))
                putStringArrayList(ARG_ORDER, ArrayList(
                    block.optJSONArray("order").let { arr ->
                        (0 until (arr?.length() ?: 0)).map { arr!!.optString(it) }
                    }))
                putString(ARG_LABELS, block.optJSONObject("labels")?.toString().orEmpty())
            }
        }
    }
}
