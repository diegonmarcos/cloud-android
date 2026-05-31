package com.diegonmarcos.superapp

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView

/**
 * Stack-render variant of an aggregator section. When an aggregator
 * declares `stack_*` in build.json, MainActivity launches this fragment
 * instead of [TileGridFragment]. Each panel becomes a collapsable
 * MaterialCard whose body is dispatched by [Sections.StackPanel.kind]:
 *
 *   c3_public / c3_private   → embedded [C3HealthFragment] (scope-filtered)
 *   wg_mesh                   → embedded [C3MeshFragment]
 *   rss                       → embedded [RssFeedFragment]
 *   drive_connections         → embedded [DriveConnectionsFragment]
 *   linktree_slide            → grouped link grid sourced from data/linktree.json
 *   link_grid                 → grouped link grid declared inline in build.json
 *   tile_row                  → inline mini-tile row (deep links into sections)
 *   mail_accounts             → per-account list with read/unread placeholders
 *   chat_matrix /chat_mattermost  → server list, tap → opens the chat section
 *   open_link                 → single tappable row that opens a URL
 *   placeholder               → empty hint card
 *
 * All panels default to **expanded** (matches user spec "all uncolapsed
 * one after the other"). Tap the header chevron to collapse/expand.
 */
class AggregatorStackFragment : Fragment(),
    TileGridFragment.TileClickListener,
    Collapsible {

    private val sectionId: String get() = arguments?.getString(ARG_SECTION_ID).orEmpty()
    private val label:     String get() = arguments?.getString(ARG_LABEL).orEmpty()
    private val mode:      String get() = arguments?.getString(ARG_MODE).orEmpty()

    /** Body container + its chevron for every panel we built — used by
     *  [toggleAllCollapsed] when MainActivity re-taps the bottom nav. */
    private data class PanelRefs(val body: View, val chevron: View)
    private val panelRefs = mutableListOf<PanelRefs>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val scroll = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            isFillViewport = true
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(column)

        val sec = Sections.byId(sectionId)
        if (sec == null) {
            column.addView(emptyHint(ctx, "Section not found: $sectionId"))
            return scroll
        }
        val panels = Sections.aggregatorStackFor(sec, mode)
        if (panels.isEmpty()) {
            column.addView(emptyHint(ctx, "No panels for ${sec.label} · $mode"))
            return scroll
        }
        panelRefs.clear()
        nextEmbedIdx = 0
        for (panel in panels) column.addView(buildPanel(ctx, inflater, panel))
        return scroll
    }

    /** Called by MainActivity when the user re-taps the bottom-nav slot
     *  they're already on. Collapses every panel if any is open;
     *  expands every panel if all are closed. */
    override fun toggleAllCollapsed(): Boolean {
        if (panelRefs.isEmpty()) return false
        val anyOpen = panelRefs.any { it.body.isVisible }
        val targetVisible = !anyOpen
        for (ref in panelRefs) {
            ref.body.isVisible = targetVisible
            ref.chevron.animate().rotation(if (targetVisible) 90f else 0f).setDuration(180).start()
        }
        return true
    }

    // ── Panel card (header + collapsable body) ─────────────────────────

    private fun buildPanel(
        ctx: android.content.Context,
        inflater: LayoutInflater,
        panel: Sections.StackPanel,
    ): View {
        val card = MaterialCardView(ctx).apply {
            radius        = dp(14).toFloat()
            cardElevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        val outer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // Header
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            isClickable = true; isFocusable = true
        }
        val title = TextView(ctx).apply {
            text = panel.title.ifBlank { panel.kind.replace('_', ' ') }
            setTextAppearance(android.R.style.TextAppearance_Material_Title)
            setTextColor(resources.getColor(R.color.cloud_primary, ctx.theme))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
        }
        val chevron = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_chevron_right)
            rotation = if (panel.collapsed) 0f else 90f
            val sz = dp(20)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
        }
        header.addView(title); header.addView(chevron)

        // Optional subtitle line
        val subtitle = if (panel.subtitle.isNotBlank()) TextView(ctx).apply {
            text = panel.subtitle
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            alpha = 0.65f
            val pad = dp(14)
            setPadding(pad, 0, pad, dp(6))
        } else null

        // Body container
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(8)
            setPadding(pad * 2, pad, pad * 2, pad * 2)
            isVisible = !panel.collapsed
        }
        renderBody(ctx, inflater, body, panel)

        header.setOnClickListener {
            body.isVisible = !body.isVisible
            chevron.animate().rotation(if (body.isVisible) 90f else 0f).setDuration(180).start()
        }

        outer.addView(header)
        if (subtitle != null) outer.addView(subtitle)
        outer.addView(body)
        card.addView(outer)
        panelRefs.add(PanelRefs(body = body, chevron = chevron))
        return card
    }

    // ── Body kind dispatcher ───────────────────────────────────────────

    private fun renderBody(
        ctx: android.content.Context,
        inflater: LayoutInflater,
        body: LinearLayout,
        panel: Sections.StackPanel,
    ) = when (panel.kind) {
        "c3_public"          -> embedChild(body, C3HealthFragment.newInstance(C3HealthFragment.SCOPE_PUBLIC))
        "c3_private"         -> embedChild(body, C3HealthFragment.newInstance(C3HealthFragment.SCOPE_PRIVATE))
        "wg_mesh"            -> embedChild(body, C3MeshFragment.newInstance())
        "rss"                -> embedChild(body, RssFeedFragment.newInstance())
        "news_feeds"         -> embedChild(body, NewsFeedFragment.newInstance())
        "calendar_month"     -> embedChild(body, CalendarMonthFragment.newInstance())
        "drive_connections"  -> embedChild(body, DriveConnectionsFragment.newInstance())
        "linktree_slide"     -> renderLinktreeSlide(ctx, body, panel.slideId)
        "link_grid"          -> renderLinkGrid(ctx, body, panel.columns, panel.links)
        "tile_row"           -> renderTileRow(body, panel.tiles)
        "mail_accounts"      -> renderMailAccounts(ctx, body)
        "chat_matrix"        -> renderChatPlaceholder(ctx, body, "Matrix", "page:chat/matrix")
        "chat_mattermost"    -> renderChatPlaceholder(ctx, body, "Mattermost", "page:chat/mattermost")
        "open_link"          -> renderOpenLink(ctx, body, panel)
        else                 -> renderPlaceholder(ctx, body, panel)
    }

    /** Round-robin pool of stable host ids. View.generateViewId() crashes
     *  on FragmentManager restore because the new id won't match the
     *  saved-state host id. Stable resource ids survive process death. */
    private val embedHostIds = intArrayOf(
        R.id.stack_embed_0, R.id.stack_embed_1, R.id.stack_embed_2, R.id.stack_embed_3,
        R.id.stack_embed_4, R.id.stack_embed_5, R.id.stack_embed_6, R.id.stack_embed_7,
    )
    private var nextEmbedIdx = 0

    private fun embedChild(body: LinearLayout, frag: Fragment) {
        val hostId = embedHostIds[nextEmbedIdx % embedHostIds.size]
        nextEmbedIdx++
        val host = FrameLayout(body.context).apply {
            id = hostId
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        body.addView(host)
        // Only commit when there's nothing already attached at that host
        // — on restore the FragmentManager re-binds the existing inner
        // fragment to the same id, so we mustn't overwrite it.
        if (childFragmentManager.findFragmentById(hostId) == null) {
            childFragmentManager.beginTransaction()
                .replace(hostId, frag)
                .commit()
        }
    }

    private fun renderLinktreeSlide(
        ctx: android.content.Context,
        body: LinearLayout,
        slideId: String,
    ) {
        val slide = Sections.linktreeSlide(slideId)
        if (slide == null) {
            body.addView(emptyHint(ctx, "linktree slide not found: $slideId"))
            return
        }
        renderLinkGrid(ctx, body, slide.columns, emptyList())
    }

    private fun renderLinkGrid(
        ctx: android.content.Context,
        body: LinearLayout,
        columns: List<Sections.LinkColumn>,
        flatLinks: List<Sections.LinkItem>,
    ) {
        // Sub-section header per column → N-icon grid of links beneath it.
        // N comes from build.json::ui.tile_columns (data-driven, no hardcode).
        val cols = BuildConfig.UI_TILE_COLUMNS.coerceAtLeast(1)
        if (columns.isNotEmpty()) {
            for (col in columns) {
                if (col.header.isNotBlank()) body.addView(colHeader(ctx, col.header, col.headerUrl))
                addIconGrid(ctx, body, col.links, cols)
            }
        }
        if (flatLinks.isNotEmpty()) addIconGrid(ctx, body, flatLinks, cols)
    }

    /** Add `links` as a wrap-flowing `cols`-column grid of icon tiles to
     *  `body`. Each row is its own horizontal LinearLayout so the grid
     *  works with any link count (padding cells fill the final row). */
    private fun addIconGrid(
        ctx: android.content.Context,
        body: LinearLayout,
        links: List<Sections.LinkItem>,
        cols: Int,
    ) {
        if (links.isEmpty()) return
        var i = 0
        while (i < links.size) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            for (c in 0 until cols) {
                if (i < links.size) row.addView(linkIconTile(ctx, links[i++]))
                else                row.addView(spacerTile(ctx))
            }
            body.addView(row)
        }
    }

    /** Icon + label tile cell (weight = 1 → 1/Nth row width). */
    private fun linkIconTile(ctx: android.content.Context, link: Sections.LinkItem): View {
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            val padH = dp(4); val padV = dp(8)
            setPadding(padH, padV, padH, padV)
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val iv = ImageView(ctx).apply {
            val resId = Sections.iconResFor(ctx, link.icon)
            if (resId != 0) setImageResource(resId)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFE9D8FD.toInt())
            val sz = dp(28)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
        }
        val lbl = TextView(ctx).apply {
            text = link.label
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            gravity = android.view.Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        cell.addView(iv); cell.addView(lbl)
        cell.setOnClickListener { openUrlOrTarget(link.url) }
        return cell
    }

    private fun spacerTile(ctx: android.content.Context): View =
        View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

    private fun renderTileRow(body: LinearLayout, tiles: List<Sections.AggTile>) {
        val ctx = body.context
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (tile in tiles) {
            val t = MaterialCardView(ctx).apply {
                radius        = dp(12).toFloat()
                cardElevation = 0f
                layoutParams = LinearLayout.LayoutParams(0, dp(96), 1f).apply {
                    val m = dp(4); setMargins(m, m, m, m)
                }
                isClickable = true; isFocusable = true
            }
            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                val pad = dp(8); setPadding(pad, pad, pad, pad)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            val iv = ImageView(ctx).apply {
                val resId = Sections.iconResFor(ctx, tile.iconName)
                if (resId != 0) setImageResource(resId)
                val sz = dp(28); layoutParams = LinearLayout.LayoutParams(sz, sz)
            }
            val lbl = TextView(ctx).apply {
                text = tile.label
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                gravity = android.view.Gravity.CENTER
            }
            inner.addView(iv); inner.addView(lbl)
            t.addView(inner)
            t.setOnClickListener { onTileClicked(tile.target) }
            grid.addView(t)
        }
        body.addView(grid)
    }

    private fun renderMailAccounts(ctx: android.content.Context, body: LinearLayout) {
        val accounts = Sections.mailAccounts()
        if (accounts.isEmpty()) {
            body.addView(caption(ctx, "No accounts declared. Add via build.json::ui.mail_accounts or the Import flow."))
            return
        }
        for (acct in accounts) {
            val transport = when (acct.kind) {
                "jmap"      -> "JMAP"
                "imap"      -> "IMAP/STARTTLS"
                "imaps"     -> "IMAPS · SMTPS"
                "exchange"  -> "Exchange"
                else        -> acct.kind.uppercase()
            }
            val portSuffix = when {
                acct.imapPort > 0 && acct.smtpPort > 0 -> "  · ${acct.imapPort}/${acct.smtpPort}"
                acct.imapPort > 0                      -> "  · imap:${acct.imapPort}"
                acct.smtpPort > 0                      -> "  · smtp:${acct.smtpPort}"
                else                                   -> ""
            }
            body.addView(linkRow(ctx, Sections.LinkItem(
                label = "${acct.label}  ·  $transport$portSuffix",
                url   = "section:mail",
            )))
        }
        body.addView(caption(ctx, "Unread / total counts pending JMAP slice C2 + IMAP slice."))
    }

    private fun renderChatPlaceholder(
        ctx: android.content.Context, body: LinearLayout,
        kind: String, target: String,
    ) {
        body.addView(linkRow(ctx, Sections.LinkItem(
            label = "Open $kind",
            url   = target,
        )))
        body.addView(caption(ctx, "server list + unread counts pending integration"))
    }

    private fun renderOpenLink(
        ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel,
    ) {
        body.addView(linkRow(ctx, Sections.LinkItem(
            label = panel.title.ifBlank { panel.url },
            url   = panel.url,
            icon  = panel.iconName,
        )))
    }

    private fun renderPlaceholder(
        ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel,
    ) {
        body.addView(caption(ctx, panel.subtitle.ifBlank { "Coming soon" }))
    }

    // ── Row builders ───────────────────────────────────────────────────

    private fun colHeader(ctx: android.content.Context, text: String, headerUrl: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setTextColor(resources.getColor(R.color.cloud_primary, ctx.theme))
            typeface = Typeface.DEFAULT_BOLD
            val pad = dp(10)
            setPadding(pad, pad, pad, pad / 2)
            if (headerUrl.isNotBlank()) setOnClickListener { openUrlOrTarget(headerUrl) }
        }

    private fun linkRow(ctx: android.content.Context, link: Sections.LinkItem): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dp(12); setPadding(pad, dp(10), pad, dp(10))
            isClickable = true; isFocusable = true
        }
        val lbl = TextView(ctx).apply {
            text = link.label
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val url = TextView(ctx).apply {
            text = link.url
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            alpha = 0.55f
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        row.addView(lbl); row.addView(url)
        row.setOnClickListener { openUrlOrTarget(link.url) }
        return row
    }

    private fun caption(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            alpha = 0.55f
            val pad = dp(12); setPadding(pad, dp(4), pad, dp(4))
        }

    private fun emptyHint(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            alpha = 0.6f
            val pad = dp(16); setPadding(pad, pad, pad, pad)
        }

    // ── Click dispatch ─────────────────────────────────────────────────

    /**
     * Targets follow the existing grammar:
     *   section:X / page:X/Y / action:X  → bubble to MainActivity tile dispatcher
     *   http(s)://…                       → open external browser
     */
    private fun openUrlOrTarget(target: String) {
        when {
            target.isEmpty() -> Unit
            // URI-shaped targets bubble up to the activity, which owns the
            // intent:// parsing + browser_fallback_url handling — same path
            // tile clicks take, so behaviour is consistent everywhere.
            target.startsWith("http") || target.contains("://") -> onTileClicked(target)
            else -> onTileClicked(target)
        }
    }

    /** Forward tile clicks to the activity-level dispatcher (same one
     *  [TileGridFragment] uses for its tile grammar). */
    override fun onTileClicked(tileId: String) {
        (activity as? TileGridFragment.TileClickListener)?.onTileClicked(tileId)
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val ARG_SECTION_ID = "section_id"
        private const val ARG_LABEL      = "label"
        private const val ARG_MODE       = "mode"

        fun newInstance(sectionId: String, label: String, mode: String): AggregatorStackFragment =
            AggregatorStackFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SECTION_ID, sectionId)
                    putString(ARG_LABEL,      label)
                    putString(ARG_MODE,       mode)
                }
            }
    }
}
