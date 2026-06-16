package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.system.CrashLogger
import com.diegonmarcos.superapp.notificationcenter.NotificationCenterFragment
import com.diegonmarcos.superapp.notificationcenter.PhoneNotificationListenerService
import com.diegonmarcos.superapp.rss.RssFeedFragment
import com.diegonmarcos.superapp.cloud.CalendarMonthFragment
import com.diegonmarcos.superapp.cloud.CalendarAgendaFragment
import com.diegonmarcos.superapp.cloud.TasksFragment
import com.diegonmarcos.superapp.cloud.NewsFeedFragment
import com.diegonmarcos.superapp.cloud.GitHubFeed
import com.diegonmarcos.superapp.cloud.DriveConnectionsFragment
import com.diegonmarcos.superapp.cloud.C3MeshFragment
import com.diegonmarcos.superapp.cloud.C3HealthFragment

import com.diegonmarcos.superapp.core.Collapsible
import com.diegonmarcos.superapp.notificationcenter.PhoneNotificationStore

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
import androidx.lifecycle.lifecycleScope
import com.diegonmarcos.superapp.core.NotificationStore
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

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
        for (panel in panels) column.addView(
            if (panel.kind == "section_title") sectionTitleView(ctx, panel.title)
            else buildPanel(ctx, inflater, panel)
        )
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

    /** kind=section_title — a plain heading between cards (NOT a card),
     *  used to separate the "Containers" cards from the "Stack" list card. */
    private fun sectionTitleView(ctx: android.content.Context, text: String): View =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setTextColor(0xFFE9D8FD.toInt())
            typeface = Typeface.DEFAULT_BOLD
            textSize = 20f
            setPadding(dp(4), dp(12), dp(4), dp(6))
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
        "calendar_agenda"    -> embedChild(body, CalendarAgendaFragment.newInstance())
        "tasks"              -> embedChild(body, TasksFragment.newInstance())
        "drive_connections"  -> embedChild(body, DriveConnectionsFragment.newInstance())
        "linktree_slide"     -> renderLinktreeSlide(ctx, body, panel.slideId)
        "link_grid"          -> renderLinkGrid(ctx, body, panel.columns, panel.links)
        "tile_row"           -> renderTileRow(body, panel.tiles)
        "mail_accounts"      -> renderMailAccounts(ctx, body)
        "chat_matrix"        -> renderChatPlaceholder(ctx, body, "Matrix", "page:chat/matrix")
        "chat_mattermost"    -> renderChatPlaceholder(ctx, body, "Mattermost", "page:chat/mattermost")
        "open_link"          -> renderOpenLink(ctx, body, panel)
        "notifications"      -> renderNotifications(ctx, body)
        "phone_notifications" -> renderPhoneNotifications(ctx, body)
        "repos"              -> renderRepos(ctx, body, panel)
        "gha_runs"           -> renderGhaRuns(ctx, body, panel)
        "stats"              -> renderStats(ctx, body, panel)
        "cloud_dashboard"    -> renderCloudDashboard(ctx, body, panel)
        else                 -> renderPlaceholder(ctx, body, panel)
    }

    /** Recent commits across the panel's declared repos. Fetches in
     *  parallel from the GitHub REST API (unauthed, 60-req/h limit
     *  shared with gha_runs; 60s cache via GitHubFeed). */
    private fun renderRepos(ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel) {
        if (panel.repos.isEmpty()) {
            body.addView(android.widget.TextView(ctx).apply {
                text = "No repos declared. Add a `repos: [{owner, repo, label}]` array to the panel."
                setTextColor(0x99FFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        val loading = android.widget.TextView(ctx).apply {
            text = "Loading commits…"
            setTextColor(0x88FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, dp(8), 0, dp(8))
        }
        body.addView(loading)
        viewLifecycleOwner.lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val results = panel.repos.map { ref ->
                async { ref to GitHubFeed.commits(ctx, ref.owner, ref.repo, 3) }
            }.awaitAll()
            body.removeView(loading)
            for ((ref, commits) in results) {
                body.addView(repoHeader(ctx, "${ref.label} (commits)", "${ref.owner}/${ref.repo}"))
                if (commits.isEmpty()) {
                    body.addView(emptyRow(ctx, "(no recent commits — API rate-limited or repo unreachable)"))
                    continue
                }
                for (c in commits) {
                    body.addView(githubRow(
                        ctx,
                        title    = c.message,
                        meta     = "${c.sha} · ${c.author} · ${GitHubFeed.ago(now - c.tsMillis)}",
                        url      = c.htmlUrl,
                        severity = "info",
                    ))
                }
            }
        }
    }

    /** Recent workflow runs across the panel's declared repos. */
    private fun renderGhaRuns(ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel) {
        if (panel.repos.isEmpty()) {
            body.addView(android.widget.TextView(ctx).apply {
                text = "No repos declared. Add a `repos` array to the panel."
                setTextColor(0x99FFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        val loading = android.widget.TextView(ctx).apply {
            text = "Loading workflow runs…"
            setTextColor(0x88FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, dp(8), 0, dp(8))
        }
        body.addView(loading)
        viewLifecycleOwner.lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val results = panel.repos.map { ref ->
                async { ref to GitHubFeed.runs(ctx, ref.owner, ref.repo, 3) }
            }.awaitAll()
            body.removeView(loading)
            for ((ref, runs) in results) {
                body.addView(repoHeader(ctx, "${ref.label} (workflow runs)", "${ref.owner}/${ref.repo}"))
                if (runs.isEmpty()) {
                    body.addView(emptyRow(ctx, "(no workflow runs — repo may have no Actions enabled)"))
                    continue
                }
                for (r in runs) {
                    val sev = when (r.conclusion) {
                        "success" -> "info"
                        "failure", "timed_out", "cancelled" -> "error"
                        else      -> "warn"
                    }
                    val statusLabel = if (r.conclusion.isNotBlank()) r.conclusion else r.status
                    body.addView(githubRow(
                        ctx,
                        title    = r.displayTitle.ifBlank { r.name },
                        meta     = "${r.name} · $statusLabel · ${GitHubFeed.ago(now - r.tsMillis)}",
                        url      = r.htmlUrl,
                        severity = sev,
                    ))
                }
            }
        }
    }

    private fun repoHeader(ctx: android.content.Context, title: String, sub: String): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }
        box.addView(android.widget.TextView(ctx).apply {
            text = title
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        })
        box.addView(android.widget.TextView(ctx).apply {
            text = sub
            setTextColor(0x77FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
        })
        return box
    }

    private fun emptyRow(ctx: android.content.Context, label: String): View =
        android.widget.TextView(ctx).apply {
            text = label
            setTextColor(0x77FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(dp(4), dp(2), 0, dp(2))
        }

    private fun githubRow(ctx: android.content.Context, title: String, meta: String,
                          url: String, severity: String): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(8); setPadding(pad, pad, pad, pad)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
            layoutParams = lp
            setBackgroundColor(when (severity) {
                "error" -> 0x55B91C1C.toInt()
                "warn"  -> 0x55D97706.toInt()
                else    -> 0x331A0033
            })
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (url.isNotBlank()) {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }
            }
        }
        row.addView(android.widget.TextView(ctx).apply {
            text = title
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(android.widget.TextView(ctx).apply {
            text = meta
            setTextColor(0x88FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, dp(2), 0, 0)
        })
        return row
    }

    /** Embed the in-app NotificationStore feed inline as a stack-panel
     *  body. Same row shape as NotificationCenterFragment but without
     *  the backdrop / dropdown chrome — this is the version that lives
     *  inside the Infos aggregator. Wired producers: Updater +
     *  CrashLogger; future producers push via NotificationStore.push(). */
    private fun renderNotifications(ctx: android.content.Context, body: LinearLayout) {
        // Viewing this panel also dismisses any matching framework
        // notification so the launcher icon badge clears at the same
        // time the in-app feed becomes visible.
        runCatching {
            (ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as? android.app.NotificationManager)?.cancelAll()
        }
        val entries = NotificationStore.all(ctx)
        if (entries.isEmpty()) {
            body.addView(android.widget.TextView(ctx).apply {
                text = "No notifications yet.\n\nProducers wired: Updater (version-bump on launch), Crash (uncaught exceptions)."
                setTextColor(0x99FFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        val now = System.currentTimeMillis()
        for (e in entries.take(20)) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = dp(10); setPadding(pad, pad, pad, pad)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) }
                layoutParams = lp
                setBackgroundColor(when (e.severity) {
                    NotificationStore.Sev.ERROR -> 0x55B91C1C.toInt()
                    NotificationStore.Sev.WARN  -> 0x55D97706.toInt()
                    else                        -> 0x331A0033
                })
            }
            val meta = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            meta.addView(android.widget.TextView(ctx).apply {
                text = e.title
                setTextColor(0xFFE9D8FD.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val ago = (now - e.ts).let { ms ->
                when {
                    ms < 60_000     -> "just now"
                    ms < 3_600_000  -> "${ms / 60_000}m ago"
                    ms < 86_400_000 -> "${ms / 3_600_000}h ago"
                    else            -> "${ms / 86_400_000}d ago"
                }
            }
            meta.addView(android.widget.TextView(ctx).apply {
                text = "${e.source} · $ago"
                setTextColor(0x88FFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            })
            row.addView(meta)
            row.addView(android.widget.TextView(ctx).apply {
                text = e.body
                setTextColor(0xCCE9D8FD.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setPadding(0, dp(2), 0, 0)
            })
            body.addView(row)
        }
    }

    /** Phone-system notifications captured by PhoneNotificationListenerService.
     *  When access is denied the panel collapses to a single CTA button
     *  that fires Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS. When
     *  granted, rows render like the in-app notifications panel above
     *  but tapping launches the source app. */
    private fun renderPhoneNotifications(ctx: android.content.Context, body: LinearLayout) {
        if (!isNotificationAccessGranted(ctx)) {
            body.addView(android.widget.TextView(ctx).apply {
                text = "Notification Access not granted. As a launcher we can read the phone's system notifications — grant once, then they stream here."
                setTextColor(0x99FFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setPadding(0, dp(8), 0, dp(8))
            })
            body.addView(android.widget.Button(ctx).apply {
                text = "Grant Notification Access"
                setOnClickListener {
                    runCatching {
                        ctx.startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            })
            return
        }
        val entries = PhoneNotificationStore.all(ctx)
        if (entries.isEmpty()) {
            body.addView(android.widget.TextView(ctx).apply {
                text = "Notification Access granted — no phone notifications captured yet. They will land here as apps post them."
                setTextColor(0x99FFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        val now = System.currentTimeMillis()
        for (e in entries.take(50)) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = dp(10); setPadding(pad, pad, pad, pad)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) }
                layoutParams = lp
                setBackgroundColor(0x331A0033)
                isClickable = true
                setOnClickListener {
                    runCatching {
                        val intent = ctx.packageManager.getLaunchIntentForPackage(e.packageName)
                        if (intent != null) ctx.startActivity(intent)
                    }
                }
            }
            val meta = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            meta.addView(android.widget.TextView(ctx).apply {
                text = e.title.ifBlank { e.appLabel }
                setTextColor(0xFFE9D8FD.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val ago = (now - e.ts).let { ms -> when {
                ms < 60_000     -> "just now"
                ms < 3_600_000  -> "${ms / 60_000}m ago"
                ms < 86_400_000 -> "${ms / 3_600_000}h ago"
                else            -> "${ms / 86_400_000}d ago"
            }}
            meta.addView(android.widget.TextView(ctx).apply {
                text = "${e.appLabel} · $ago"
                setTextColor(0x88FFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            })
            row.addView(meta)
            if (e.text.isNotBlank()) {
                row.addView(android.widget.TextView(ctx).apply {
                    text = e.text
                    setTextColor(0xCCE9D8FD.toInt())
                    setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                    setPadding(0, dp(2), 0, 0)
                })
            }
            body.addView(row)
        }
    }

    /** Settings.Secure.enabled_notification_listeners is a colon-
     *  separated flat string of ComponentName.flattenToString()s. We
     *  match on packageName alone — sufficient since only ONE listener
     *  per package can be enabled at a time. */
    private fun isNotificationAccessGranted(ctx: android.content.Context): Boolean {
        val flat = android.provider.Settings.Secure.getString(
            ctx.contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        return flat.contains(ctx.packageName)
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

    /** kind=stats — a mock dashboard surface: one label/value line per
     *  declared row, with a "mock data" footer so it's clear the numbers
     *  are placeholders until the card's live fetch is plumbed in. */
    private fun renderStats(
        ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel,
    ) {
        if (panel.subtitle.isNotBlank()) body.addView(caption(ctx, panel.subtitle))
        for (r in panel.rows) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                val p = dp(5); setPadding(0, p, 0, p)
            }
            row.addView(TextView(ctx).apply {
                text = r.label
                setTextColor(0x99FFFFFF.toInt())
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(ctx).apply {
                text = r.value
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            })
            body.addView(row)
        }
        body.addView(caption(ctx, "Mock data — live fetch pending"))
    }

    /** kind=cloud_dashboard — live container map. Decodes the baked service
     *  inventory (data/services_{private,public}.json → BuildConfig.SERVICES_*_B64),
     *  buckets every container by category into the card's [dashGroups], draws a
     *  TCP-ping status dot per container ({name}.app — green up / red down / grey
     *  checking, meaningful only over WireGuard) and opens that .app in the in-app
     *  browser on tap. Provider groups render external consoles (no ping). */
    private fun renderCloudDashboard(
        ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel,
    ) {
        val dash = Sections.cloudServices()
        val cols = com.diegonmarcos.superapp.BuildConfig.UI_TILE_COLUMNS.coerceAtLeast(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        val wanted = panel.dashGroupIds.ifEmpty { dash.groups.map { it.id } }
        val groups = dash.groups.filter { it.id in wanted }
        // Only label the group inside the card when one card shows >1 group
        // (the Others card = Providers + MCP & API). Single-group cards rely
        // on the card title.
        val showGroupHeader = groups.size > 1
        for (group in groups) {
            if (showGroupHeader) body.addView(groupHeader(ctx, group.label))
            if (group.providers.isNotEmpty()) {
                addCloudGrid(ctx, body, cols, executor, group.providers.map {
                    CloudTile(it.label, group.icon, it.url, showLight = false, ping = null)
                })
            }
            for (sub in group.subgroups) {
                if (sub.containers.isEmpty()) continue
                body.addView(subHeader(ctx, sub.label))
                addCloudGrid(ctx, body, cols, executor, sub.containers.map {
                    when {
                        // Explicit open-URL (e.g. VM dashboard) — open it verbatim
                        // but still light up from the url:port ping.
                        it.link.isNotBlank() -> CloudTile(it.label, sub.icon, it.link, showLight = true,
                            ping = if (it.port in 1..65535) it.url to it.port else null)
                        it.external -> CloudTile(it.label, sub.icon, it.url, showLight = false, ping = null)
                        else -> CloudTile(it.label, sub.icon, "https://${it.url}", showLight = true,
                            ping = if (it.port in 1..65535) it.url to it.port else null)
                    }
                })
            }
        }
        executor.shutdown()
    }

    private data class CloudTile(
        val label: String, val icon: String, val openUrl: String,
        val showLight: Boolean, val ping: Pair<String, Int>?,
    )

    /** Wrap-flowing `cols`-column grid of cloud tiles (same grid as the
     *  link icon grid: each row a horizontal LinearLayout, padding cells
     *  fill the last row). */
    private fun addCloudGrid(
        ctx: android.content.Context, body: LinearLayout, cols: Int,
        executor: java.util.concurrent.ExecutorService, tiles: List<CloudTile>,
    ) {
        var i = 0
        while (i < tiles.size) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            for (c in 0 until cols) {
                if (i < tiles.size) row.addView(cloudTile(ctx, tiles[i++], executor))
                else                row.addView(spacerTile(ctx))
            }
            body.addView(row)
        }
    }

    /** Icon + STATUS LIGHT (under the icon) + label tile. weight=1 → 1/Nth
     *  row width. Tap opens the in-app browser; the light TCP-pings the
     *  container's {name}.app (green up / red down / grey checking). */
    private fun cloudTile(
        ctx: android.content.Context, t: CloudTile,
        executor: java.util.concurrent.ExecutorService,
    ): View {
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            val padH = dp(4); val padV = dp(8); setPadding(padH, padV, padH, padV)
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { openUrlOrTarget(t.openUrl) }
        }
        cell.addView(ImageView(ctx).apply {
            val resId = Sections.iconResFor(ctx, t.icon)
            if (resId != 0) setImageResource(resId)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFE9D8FD.toInt())
            val sz = dp(28)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
        })
        if (t.showLight) {
            val dot = View(ctx).apply {
                val sz = dp(8)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { topMargin = dp(3) }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0x66FFFFFF)
                }
            }
            cell.addView(dot)
            t.ping?.let { (host, port) ->
                runCatching {
                    executor.execute {
                        val up = try {
                            java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 1200); true }
                        } catch (_: Throwable) { false }
                        dot.post {
                            (dot.background as? android.graphics.drawable.GradientDrawable)
                                ?.setColor(if (up) 0xFF34C759.toInt() else 0xFFFF3B30.toInt())
                        }
                    }
                }
            }
        }
        cell.addView(TextView(ctx).apply {
            text = t.label
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            gravity = android.view.Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        })
        return cell
    }

    private fun groupHeader(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(resources.getColor(R.color.cloud_primary, ctx.theme))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 16f
            setPadding(0, dp(14), 0, dp(4))
        }

    private fun subHeader(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(0xCCFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
            setPadding(dp(4), dp(8), 0, dp(2))
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
