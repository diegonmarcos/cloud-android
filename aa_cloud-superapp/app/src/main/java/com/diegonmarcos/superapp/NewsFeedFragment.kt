package com.diegonmarcos.superapp

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.rss.RssClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * News & RSS — each channel is a collapsable card. Header tap toggles
 * the body; on first expand the feed XML is fetched in the background,
 * parsed, and rendered as a tappable item list. Each item tap pushes
 * the URL into the internal browser (Tabs section) via
 * MainActivity.onTileClicked.
 *
 * Tap targets follow the same target-grammar the rest of the app uses,
 * so http(s) URLs automatically route through launchUri → TabPrefs →
 * TabsHostFragment.
 */
class NewsFeedFragment : Fragment() {

    private val executor = Executors.newFixedThreadPool(3)
    /** url → cached items so re-expand doesn't re-fetch. */
    private val cache = ConcurrentHashMap<String, List<RssClient.Item>>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(4); setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val feeds = Sections.newsFeeds()
        if (feeds.isEmpty()) {
            root.addView(emptyText(ctx, getString(R.string.news_feeds_empty)))
            return root
        }

        val byCat = feeds.groupBy { it.category.ifBlank { "Other" } }
        for ((cat, list) in byCat) {
            root.addView(catHeader(ctx, cat))
            for (feed in list) root.addView(channelCard(ctx, feed))
        }
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        executor.shutdownNow()
    }

    private fun catHeader(ctx: android.content.Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        setTextColor(resources.getColor(R.color.cloud_primary, ctx.theme))
        typeface = Typeface.DEFAULT_BOLD
        val pad = dp(10); setPadding(pad, pad, pad, pad / 2)
    }

    private fun channelCard(ctx: android.content.Context, feed: Sections.NewsFeed): View {
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_tile_glass)
            val m = dp(4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(m, m, m, m) }
        }
        // Header row — channel name + host + chevron.
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dp(12); setPadding(pad, dp(10), pad, dp(10))
            isClickable = true; isFocusable = true
        }
        val nameView = TextView(ctx).apply {
            text = feed.name
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            setTextColor(0xFFE9D8FD.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val hostView = TextView(ctx).apply {
            text = hostOf(feed.url)
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setTextColor(0xCCFFFFFF.toInt())
            alpha = 0.65f
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        val chevron = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_chevron_right)
            val sz = dp(20); layoutParams = LinearLayout.LayoutParams(sz, sz)
        }
        header.addView(nameView); header.addView(hostView); header.addView(chevron)
        outer.addView(header)

        // Body — initially gone; populated on first expand.
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(8); setPadding(pad * 2, 0, pad * 2, pad * 2)
            isVisible = false
        }
        outer.addView(body)

        var loaded = false
        header.setOnClickListener {
            body.isVisible = !body.isVisible
            chevron.animate().rotation(if (body.isVisible) 90f else 0f).setDuration(180).start()
            if (body.isVisible && !loaded) {
                loaded = true
                renderItems(ctx, body, feed)
            }
        }
        return outer
    }

    private fun renderItems(ctx: android.content.Context, body: LinearLayout, feed: Sections.NewsFeed) {
        body.removeAllViews()
        cache[feed.url]?.let {
            for (item in it) body.addView(itemRow(ctx, item))
            if (it.isEmpty()) body.addView(emptyText(ctx, "No items"))
            return
        }
        // Loading spinner + status text while we fetch.
        val spinner = ProgressBar(ctx).apply {
            isIndeterminate = true
            val sz = dp(24)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { gravity = android.view.Gravity.CENTER }
        }
        val statusView = TextView(ctx).apply {
            text = "Fetching ${hostOf(feed.url)}…"
            setTextColor(0xCCFFFFFF.toInt())
            alpha = 0.7f
            val pad = dp(12); setPadding(pad, dp(8), pad, dp(8))
        }
        body.addView(spinner); body.addView(statusView)

        executor.execute {
            val result = runCatching { RssClient.fetch(feed.url) }
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                body.removeAllViews()
                result.onSuccess { items ->
                    cache[feed.url] = items
                    if (items.isEmpty()) {
                        body.addView(emptyText(ctx, "No items"))
                    } else {
                        for (item in items) body.addView(itemRow(ctx, item))
                    }
                }.onFailure { t ->
                    body.addView(emptyText(ctx, "Fetch failed: ${t.message ?: t.toString()}"))
                }
            }
        }
    }

    private fun itemRow(ctx: android.content.Context, item: RssClient.Item): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(8); setPadding(pad, dp(8), pad, dp(8))
            isClickable = true; isFocusable = true
        }
        row.addView(TextView(ctx).apply {
            text = item.title
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
        })
        if (item.date.isNotBlank()) {
            row.addView(TextView(ctx).apply {
                text = item.date
                setTextColor(0x99FFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            })
        }
        row.setOnClickListener {
            // http(s) routes through MainActivity.launchUri →
            // TabPrefs.add → TabsHostFragment (internal browser).
            (activity as? TileGridFragment.TileClickListener)?.onTileClicked(item.link)
        }
        return row
    }

    private fun emptyText(ctx: android.content.Context, msg: String) = TextView(ctx).apply {
        text = msg
        setTextColor(0xCCFFFFFF.toInt())
        alpha = 0.6f
        val pad = dp(12); setPadding(pad, pad, pad, pad)
    }

    private fun hostOf(url: String): String =
        runCatching { android.net.Uri.parse(url).host ?: url }.getOrDefault(url)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = NewsFeedFragment() }
}
