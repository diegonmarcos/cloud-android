package com.diegonmarcos.superapp

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * News / open RSS channels — data-driven from
 * [Sections.newsFeeds] (build.json::ui.news_feeds).
 *
 * NOT the same surface as [RssFeedFragment] — that one shows ntfy
 * pub/sub channels (admin/infra signal). This one shows curated
 * external news feeds for the Apps mode of Infos.
 *
 * Tap a row → opens the feed's `site_url` (or `url` if no site set)
 * in the system browser. A future revision can swap this for an
 * inline reader once we pick a parser library.
 */
class NewsFeedFragment : Fragment() {

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

        // Group by category — clean visual hierarchy when there are 10+ feeds.
        val byCat = feeds.groupBy { it.category.ifBlank { "Other" } }
        for ((cat, list) in byCat) {
            root.addView(catHeader(ctx, cat))
            for (feed in list) root.addView(feedRow(ctx, feed))
        }
        return root
    }

    private fun catHeader(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setTextColor(resources.getColor(R.color.cloud_primary, ctx.theme))
            typeface = Typeface.DEFAULT_BOLD
            val pad = dp(10); setPadding(pad, pad, pad, pad / 2)
        }

    private fun feedRow(ctx: android.content.Context, feed: Sections.NewsFeed): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dp(12); setPadding(pad, dp(10), pad, dp(10))
            isClickable = true; isFocusable = true
        }
        val name = TextView(ctx).apply {
            text = feed.name
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val host = TextView(ctx).apply {
            text = hostOf(feed.url.ifBlank { feed.siteUrl })
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            alpha = 0.55f
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        row.addView(name); row.addView(host)
        row.setOnClickListener {
            val target = feed.siteUrl.ifBlank { feed.url }
            if (target.isNotBlank()) runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
            }
        }
        return row
    }

    private fun emptyText(ctx: android.content.Context, msg: String): TextView =
        TextView(ctx).apply {
            text = msg
            alpha = 0.6f
            val pad = dp(16); setPadding(pad, pad, pad, pad)
        }

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host ?: url }.getOrDefault(url)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = NewsFeedFragment() }
}
