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
import kotlinx.coroutines.withContext

/**
 * Stack-render variant of an aggregator section. When an aggregator
 * declares `stack_*` in build.json, MainActivity launches this fragment
 * instead of [TileGridFragment]. Each panel becomes a collapsable
 * MaterialCard whose body is dispatched by [Sections.StackPanel.kind]:
 *
 *   c3_public / c3_private   → embedded [C3HealthFragment] (scope-filtered)
 *   wg_mesh                   → embedded [C3MeshFragment]
 *   rss                       → embedded [RssFeedFragment]
 *   notification_center       → per-app grouped notification list, inline views
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

    // ── filters_<page> toggle row state ────────────────────────────────
    //
    // Panels are built ONCE. The Source toggle hides and shows whole cards,
    // and the By/Show toggles rebuild only the two notification bodies. The
    // stack is never rebuilt wholesale, because that would re-run
    // [embedChild], whose fixed host ids are still claimed by the already
    // attached child fragments — the rss and news panels would come back
    // blank while looking like they had simply loaded nothing.
    private val originCards   = mutableListOf<Pair<String, View>>()
    private val bodyRefreshers = mutableListOf<() -> Unit>()
    /** The vertical column holding the cards — where the "everything is
     *  filtered out" note is appended. */
    private var cardColumn: LinearLayout? = null
    private var filterPage = ""
    private var sortMode   = "time"
    private var showMode   = "all"
    /** Start of this visit's unread window: the ts of the PREVIOUS visit.
     *  Captured before the watermark is advanced so toggling Show back and
     *  forth within one visit keeps answering the same question. */
    private var visitSeenAt = 0L

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
        cardColumn = column

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
        originCards.clear()
        bodyRefreshers.clear()
        nextEmbedIdx = 0

        // Toggle row, when this page declares one. Reading the watermark
        // BEFORE advancing it is what makes Show=Unread mean "since you were
        // last here" rather than "since a moment ago", which would always be
        // empty.
        val filters = Sections.stackFiltersFor(sec, mode)
        if (filters.isNotEmpty()) {
            filterPage  = mode
            visitSeenAt = StackFilters.lastSeen(ctx, filterPage)
            StackFilters.markSeen(ctx, filterPage, System.currentTimeMillis())
            sortMode = selection(ctx, filters, "sort", sortMode)
            showMode = selection(ctx, filters, "show", showMode)
            column.addView(filterRow(ctx, filters))
        }

        for (panel in panels) {
            val view = if (panel.kind == "section_title") sectionTitleView(ctx, panel.title)
                       else buildPanel(ctx, inflater, panel)
            if (panel.origin.isNotBlank()) originCards += panel.origin to view
            column.addView(view)
        }
        if (filters.isNotEmpty()) applySource(ctx, filters)
        return scroll
    }

    /** Current choice for the filter with [id], or [fallback] if the page
     *  does not declare that filter at all. */
    private fun selection(
        ctx: android.content.Context,
        filters: List<Sections.StackFilter>,
        id: String,
        fallback: String,
    ): String = filters.firstOrNull { it.id == id }
        ?.let { StackFilters.selected(ctx, filterPage, it) } ?: fallback

    /** The declared toggles, one horizontal button row each. Same idiom as
     *  the other button rows in the app (plain Buttons, weight 1f, in a
     *  horizontal LinearLayout); the current option is the opaque one. */
    private fun filterRow(
        ctx: android.content.Context,
        filters: List<Sections.StackFilter>,
    ): View {
        val host = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        for (filter in filters) {
            host.addView(caption(ctx, filter.label))
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val buttons = mutableMapOf<String, android.widget.Button>()
            fun paint() {
                val active = StackFilters.selected(ctx, filterPage, filter)
                for ((id, b) in buttons) b.alpha = if (id == active) 1f else 0.45f
            }
            for (option in filter.options) {
                val b = android.widget.Button(ctx).apply {
                    text = option.label
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                    )
                    setOnClickListener {
                        StackFilters.select(ctx, filterPage, filter.id, option.id)
                        paint()
                        onFilterChanged(ctx, filters)
                    }
                }
                buttons[option.id] = b
                row.addView(b)
            }
            paint()
            host.addView(row)
        }
        return host
    }

    /** Re-apply every toggle after one of them changed. */
    private fun onFilterChanged(
        ctx: android.content.Context,
        filters: List<Sections.StackFilter>,
    ) {
        sortMode = selection(ctx, filters, "sort", sortMode)
        showMode = selection(ctx, filters, "show", showMode)
        for (refresh in bodyRefreshers) refresh()
        applySource(ctx, filters)
    }

    /**
     * Cloud vs Phone. The rule is the PANEL's declared `origin`, i.e. which
     * stream the notification arrived on — not a list of phone package names,
     * which would start going stale the day it was written. Everything the
     * notification listener captures is by construction from an app installed
     * on this device; everything in the ntfy catalog, the in-app SuperApp feed
     * and the news list is by construction from off it.
     *
     * A panel with no declared origin is never hidden.
     */
    private fun applySource(
        ctx: android.content.Context,
        filters: List<Sections.StackFilter>,
    ) {
        val want = selection(ctx, filters, "source", "both")
        for ((origin, card) in originCards) card.isVisible = want == "both" || origin == want
        // Every classified card hidden means the toggle did it, not a dead
        // page — say so, because an empty screen with no reason reads as
        // broken and teaches the user to stop opening it.
        val column = cardColumn ?: return
        val allHidden = originCards.isNotEmpty() && originCards.none { it.second.isVisible }
        val existing = column.findViewWithTag<View>(SOURCE_EMPTY_TAG)
        if (existing != null) existing.isVisible = allHidden
        else if (allHidden) {
            val label = filters.firstOrNull { it.id == "source" }
                ?.options?.firstOrNull { it.id == want }?.label ?: want
            column.addView(emptyHint(
                ctx,
                "No panel on this page carries $label notifications. Switch Source back to Both.",
            ).apply { tag = SOURCE_EMPTY_TAG })
        }
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
        "rss"                -> embedChild(body, RssFeedFragment.newInstance(panel.scopes))
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
        "notification_center" -> refreshable(body) { renderNotificationCenter(ctx, body, panel) }
        "repos"              -> renderRepos(ctx, body, panel)
        "gha_runs"           -> renderGhaRuns(ctx, body, panel)
        "stats"              -> renderStats(ctx, body, panel)
        "signal_health"      -> renderSignalHealth(ctx, body, panel)
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
                        // Only rows whose run identified its workflow file can
                        // be re-dispatched — GitHub addresses the dispatch
                        // endpoint by that file name.
                        action   = r.workflowFile.takeIf { it.isNotBlank() }
                            ?.let { ghaTriggerRow(ctx, ref, it) },
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
                          url: String, severity: String, action: View? = null): View {
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
        if (action != null) row.addView(action)
        return row
    }

    /**
     * "Re-run" control for one GitHub Actions workflow row.
     *
     * Dispatch goes through c3-infra-api (POST /workflows/dispatch) with the
     * Authelia bearer this device already stores, NOT with a GitHub token: a
     * PAT with actions:write shipped inside the APK would be extractable by
     * anyone who unzips it. The server holds the PAT.
     *
     * Every outcome is displayed in place — dispatched, refused by GitHub,
     * proxy not configured, or no token on the device. The control never
     * finishes without saying what happened.
     */
    private fun ghaTriggerRow(ctx: android.content.Context, ref: Sections.RepoRef,
                              workflowFile: String): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val status = android.widget.TextView(ctx).apply {
            setTextColor(0x88FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btn = android.widget.Button(ctx).apply {
            text = "Re-run"
            isAllCaps = false
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
        }
        btn.setOnClickListener {
            btn.isEnabled = false
            status.text = "Dispatching $workflowFile…"
            val bearer = runCatching {
                com.diegonmarcos.superapp.ops.dagu.DaguPrefs(ctx).bearerToken
            }.getOrDefault("")
            viewLifecycleOwner.lifecycleScope.launch {
                val outcome = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.diegonmarcos.superapp.cloud.OpsClient.dispatchWorkflow(
                        repo = "${ref.owner}/${ref.repo}",
                        workflow = workflowFile,
                        ref = "main",
                        bearer = bearer,
                    )
                }
                btn.isEnabled = true
                when (outcome) {
                    is com.diegonmarcos.superapp.cloud.OpsClient.Outcome.Ok -> {
                        status.setTextColor(0xFF7BE38B.toInt())
                        status.text = outcome.message
                    }
                    is com.diegonmarcos.superapp.cloud.OpsClient.Outcome.Failed -> {
                        status.setTextColor(0xFFFF8888.toInt())
                        status.text = "${outcome.kind}: ${outcome.message}"
                    }
                }
            }
        }
        box.addView(btn)
        box.addView(status)
        return box
    }

    // ── kind=notification_center ───────────────────────────────────────
    //
    // The Apps RSS page is now exactly this kind, twice: one card per STREAM,
    // and inside each card one group per APP.
    //
    // Every view built below is a plain inline View. Nothing here goes through
    // [embedChild], and that is load-bearing rather than incidental: embedChild
    // allocates from a FIXED pool of host ids and only commits when nothing is
    // already attached, so any body that re-runs comes back permanently blank
    // while still looking like a card that merely loaded nothing. These bodies
    // re-run on every toggle tap (they are registered through [refreshable]),
    // and a grouped notification list is the most re-rendered surface in the
    // app — so it may not own a child fragment at all. The page also no longer
    // declares any embedChild-routed panel, so [nextEmbedIdx] never advances
    // here and the fixed pool cannot be exhausted by a rebuild.

    /** One app / publisher and everything it has posted. [key] is the stable
     *  identity we grouped on — a package name, an ntfy topic, an in-app
     *  producer — and [label] is what the user reads. */
    private data class NotifGroup(
        val key: String,
        val label: String,
        val sub: String,
        val rows: List<NotifRow>,
        val launchPackage: String = "",
        val url: String = "",
    ) {
        val newest: Long get() = rows.maxOfOrNull { it.ts } ?: 0L
    }

    private data class NotifRow(
        val ts: Long,
        val title: String,
        val text: String,
        val severity: String = "info",
    )

    /** A group's one-line verdict. Same four-state vocabulary the C3 Obsv page
     *  uses, because the two things this page must never confuse are the same
     *  two: an app that has genuinely posted nothing, and a stream we could
     *  not read. */
    private data class GroupState(val text: String, val color: Int)

    private data class NtfyResult(val ok: Boolean, val error: String, val rows: List<NotifRow>)

    /** Per-topic poll results for this visit. Toggling Sort or Show rebuilds
     *  the body; without this every tap would re-poll every channel. */
    private val ntfyCache = mutableMapOf<String, NtfyResult>()

    private fun renderNotificationCenter(
        ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel,
    ) = when (panel.stream) {
        "phone" -> renderPhoneCenter(ctx, body)
        "cloud" -> renderCloudCenter(ctx, body, panel)
        // A card that declares no stream cannot guess one. Saying so beats an
        // empty list, which the user would read as "you have no notifications".
        else -> Unit.also {
            body.addView(emptyHint(ctx, "This card declares no `stream`. " +
                "Add \"stream\": \"phone\" or \"cloud\" to the panel in build.json."))
        }
    }

    /**
     * Phone stream: one group per POSTING APP, keyed on `packageName` and
     * labelled with `appLabel`.
     *
     * There is no package list in this file and there must never be one. The
     * grouping key rides on the notification itself, so an app installed
     * tomorrow groups correctly with no Kotlin and no build.json edit, and an
     * app that goes away simply stops appearing — whereas a hand-written list
     * starts rotting the day it is written.
     */
    private fun renderPhoneCenter(ctx: android.content.Context, body: LinearLayout) {
        if (!isNotificationAccessGranted(ctx)) {
            // The single most likely reason a real user sees nothing here. It is
            // a MISSING CAPABILITY, not an empty inbox, and the card says which
            // — grey, with the way to fix it attached. Rendering an empty list
            // in this state would be a failure reporting success.
            body.addView(stateLine(ctx, "unavailable · permission not granted", SIGNAL_UNKNOWN))
            body.addView(caption(ctx,
                "Notification Access is off, so this phone's notifications are not being captured at all. " +
                "This list is empty because we cannot read them — not because none arrived."))
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
        val stored = PhoneNotificationStore.all(ctx)
        if (stored.isEmpty()) {
            // Granted and empty is a REAL state and a different one: amber, and
            // it names the listener so it cannot be mistaken for a dead page.
            body.addView(stateLine(ctx, "silent · listener granted, nothing captured", SIGNAL_WARN))
            body.addView(caption(ctx,
                "Apps appear here as they post. Nothing has arrived since the store was last cleared."))
            return
        }
        val groups = stored
            .groupBy { it.packageName.ifBlank { it.appLabel } }
            .map { (key, entries) ->
                NotifGroup(
                    key           = key,
                    label         = entries.firstOrNull { it.appLabel.isNotBlank() }?.appLabel ?: key,
                    sub           = key,
                    launchPackage = entries.first().packageName,
                    rows          = entries.map { NotifRow(it.ts, it.title.ifBlank { it.appLabel }, it.text) },
                )
            }
        if (renderGroups(ctx, body, groups) == 0) {
            body.addView(caption(ctx, filteredAwayNote(stored.size)))
        }
    }

    /**
     * Cloud stream, grouped by PUBLISHER: the ntfy TOPIC for a fleet message,
     * the `source` field for an in-app one.
     *
     * Why topic and not scope. A cloud message carries no package name, and the
     * closest thing it does carry is the address it was published to. A topic
     * has exactly one publisher the way a package has exactly one app, so the
     * per-app division survives the crossing from phone to cloud. A scope
     * (advisory / user / cloud) is a ROUTING CATEGORY shared by many
     * publishers; grouping on it would collapse every unrelated channel into
     * one bucket, which is precisely the undivided list this redesign replaces.
     * Scope still decides WHICH topics this card carries — it is just not the
     * grouping key.
     */
    private fun renderCloudCenter(
        ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel,
    ) {
        // Opening the in-app feed also clears the framework badge, as the old
        // panel did: the launcher badge and this list must not disagree. Note
        // this DESTROYS system dismissal state rather than recording it, which
        // is why no "Dismissed" filter is offered anywhere on this page — it
        // would read a field nothing writes.
        runCatching {
            (ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as? android.app.NotificationManager)?.cancelAll()
        }

        body.addView(groupHeader(ctx, "In-app feed"))
        val stored = NotificationStore.all(ctx)
        val local = stored.groupBy { it.source.ifBlank { "SuperApp" } }.map { (source, entries) ->
            NotifGroup(
                key   = source,
                label = source,
                sub   = "in-app producer",
                rows  = entries.map {
                    NotifRow(it.ts, it.title, it.body, when (it.severity) {
                        NotificationStore.Sev.ERROR -> "error"
                        NotificationStore.Sev.WARN  -> "warn"
                        else                        -> "info"
                    })
                },
            )
        }
        if (renderGroups(ctx, body, local) == 0) {
            if (stored.isEmpty()) {
                body.addView(stateLine(ctx, "silent · no in-app events", SIGNAL_WARN))
                body.addView(caption(ctx, "Producers wired: Updater (version bump on launch), " +
                    "Crash (uncaught exceptions)."))
            } else {
                body.addView(caption(ctx, filteredAwayNote(stored.size)))
            }
        }

        body.addView(groupHeader(ctx, "Channels"))
        renderNtfyGroups(ctx, body, panel)
    }

    /**
     * One group per ntfy topic in this card's declared scopes, each with a live
     * verdict. The failure states are what separate this from a plain feed:
     *
     *   N · 5m ago  — messages arrived (green).
     *   silent 7d   — the poll SUCCEEDED and the topic is empty (amber). ntfy
     *                 answers 200 for a topic nobody has ever published to, so a
     *                 dead publisher looks exactly like a quiet one; amber says
     *                 we cannot tell them apart rather than drawing an innocent
     *                 empty group.
     *   unavailable — the poll itself failed. GREY, never red and never absent:
     *                 a failed fetch is a claim about THIS PHONE'S network, not
     *                 about the fleet, and it must never be rendered as "no
     *                 notifications".
     *
     * The topic list comes from the baked `ui.ntfy` catalog, so a channel added
     * upstream lands here by data on the next build. Ordering is alphabetical
     * under both Sort modes: a topic whose poll has not landed yet has no
     * timestamp to sort on, and groups that reshuffle as fetches complete are
     * worse than groups that hold still and carry their age in the chip.
     */
    private fun renderNtfyGroups(
        ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel,
    ) {
        val scopes = com.diegonmarcos.superapp.rss.NtfyScopes.load()
        val catalog = com.diegonmarcos.superapp.rss.NtfyScopes.fallbackChannels()
        val topics = if (panel.scopes.isEmpty()) catalog else catalog.filter {
            com.diegonmarcos.superapp.rss.NtfyScopes.scopeOf(it, scopes).id in panel.scopes
        }
        if (topics.isEmpty()) {
            // Nothing is being polled, which is not the same as everything being
            // quiet. Name the reason and the scopes that produced it.
            body.addView(stateLine(ctx, "unavailable · no channels match", SIGNAL_UNKNOWN))
            body.addView(caption(ctx, "No channel in build.json::ui.ntfy falls in this card's scopes (" +
                panel.scopes.joinToString(", ").ifBlank { "all" } + "). Nothing is being polled."))
            return
        }
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        for (topic in topics.sorted()) {
            val group = NotifGroup(
                key   = topic,
                label = com.diegonmarcos.superapp.rss.NtfyCatalog.labelOf(topic),
                sub   = topic,
                rows  = emptyList(),
                url   = "https://rss.diegonmarcos.com/$topic",
            )
            // "checking…", never OK: an unpolled channel must not spend even its
            // first frame looking healthy.
            val header = groupHeaderRow(ctx, group, GroupState("checking…", SIGNAL_UNKNOWN))
            val state  = header.findViewWithTag<TextView>(GROUP_STATE_TAG) ?: continue
            val rowsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            body.addView(header)
            body.addView(rowsBox)

            val cached = ntfyCache[topic]
            if (cached != null) {
                paintNtfyGroup(ctx, state, rowsBox, cached)
                continue
            }
            runCatching {
                executor.execute {
                    val result = pollTopic(topic)
                    state.post {
                        ntfyCache[topic] = result
                        paintNtfyGroup(ctx, state, rowsBox, result)
                    }
                }
            }.onFailure {
                // Could not even schedule the poll — say so rather than leaving
                // the row reading "checking…" forever, which looks like progress.
                state.text = "unavailable · not polled"
                state.setTextColor(SIGNAL_UNKNOWN)
            }
        }
        executor.shutdown()
    }

    private fun paintNtfyGroup(
        ctx: android.content.Context, state: TextView, rowsBox: LinearLayout, result: NtfyResult,
    ) {
        rowsBox.removeAllViews()
        if (!result.ok) {
            state.text = "unavailable · ${result.error}"
            state.setTextColor(SIGNAL_UNKNOWN)
            return
        }
        val rows = withinGroup(result.rows)
        if (rows.isEmpty()) {
            // Empty channel and hidden-by-filter are different facts, so they get
            // different words and different colours.
            if (result.rows.isEmpty()) {
                state.text = "silent 7d · publisher?"
                state.setTextColor(SIGNAL_WARN)
            } else {
                state.text = "nothing new"
                state.setTextColor(0x88FFFFFF.toInt())
            }
            return
        }
        state.text = "${rows.size} · ${ago(System.currentTimeMillis() - rows.first().ts)}"
        state.setTextColor(SIGNAL_OK)
        for (r in rows) rowsBox.addView(notifRowView(ctx, r, ""))
    }

    /** ntfy's poll API. Any non-200, any exception and any unparseable body is
     *  UNAVAILABLE — the honest answer is that we did not measure, not that the
     *  channel is empty. */
    private fun pollTopic(topic: String): NtfyResult = try {
        val url = java.net.URL("https://rss.diegonmarcos.com/$topic/json?poll=1&since=7d")
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 4000; readTimeout = 4000; requestMethod = "GET"
        }
        try {
            if (conn.responseCode != 200) NtfyResult(false, "HTTP ${conn.responseCode}", emptyList())
            else {
                val rows = mutableListOf<NotifRow>()
                conn.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) runCatching {
                        val o = org.json.JSONObject(line)
                        if (o.optString("event") == "message") rows += NotifRow(
                            ts    = o.optLong("time", 0L) * 1000L,
                            title = o.optString("title").ifBlank { topic },
                            text  = o.optString("message"),
                        )
                    }
                }
                NtfyResult(true, "", rows)
            }
        } finally { conn.disconnect() }
    } catch (_: Throwable) {
        // Includes the mesh being down, which is unknown — not healthy, not empty.
        NtfyResult(false, "no answer", emptyList())
    }

    /**
     * Draw [groups] as a per-app list and return how many had a visible row.
     *
     * The Sort toggle chooses the ORDER OF THE GROUPS — App = alphabetical,
     * Time = most recently active app first — never whether grouping happens,
     * because the division per app IS this page. Ordering INSIDE a group is
     * always newest-first, so flipping Sort can never bury something fresh.
     */
    private fun renderGroups(
        ctx: android.content.Context, body: LinearLayout, groups: List<NotifGroup>,
    ): Int {
        val visible = groups
            .map { it.copy(rows = withinGroup(it.rows)) }
            .filter { it.rows.isNotEmpty() }
        val ordered =
            if (sortMode == "app")
                visible.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { g: NotifGroup -> g.label })
            else visible.sortedByDescending { it.newest }
        val now = System.currentTimeMillis()
        for (g in ordered) {
            body.addView(groupHeaderRow(ctx, g,
                GroupState("${g.rows.size} · ${ago(now - g.newest)}", SIGNAL_OK)))
            for (r in g.rows) body.addView(notifRowView(ctx, r, g.launchPackage))
        }
        return ordered.size
    }

    /** Show=Unread inside one group: `ts > previous visit`, then newest-first.
     *  Derived, not stored: neither store writes a read or dismissed flag, so a
     *  filter over one would show an empty list forever. Both already carry ts. */
    private fun withinGroup(rows: List<NotifRow>): List<NotifRow> {
        val shown = if (showMode == "unread") rows.filter { it.ts > visitSeenAt } else rows
        return shown.sortedByDescending { it.ts }
    }

    /** Group header: who posted, its address, and its state. Tapping opens the
     *  app (phone groups) or the channel (ntfy groups). */
    private fun groupHeaderRow(
        ctx: android.content.Context, g: NotifGroup, state: GroupState,
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(2))
            isClickable = true; isFocusable = true
            setOnClickListener {
                runCatching {
                    when {
                        g.launchPackage.isNotBlank() ->
                            ctx.packageManager.getLaunchIntentForPackage(g.launchPackage)
                                ?.let { ctx.startActivity(it) }
                        g.url.isNotBlank() ->
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(g.url)))
                    }
                }
            }
        }
        val names = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        names.addView(TextView(ctx).apply {
            text = g.label
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            typeface = Typeface.DEFAULT_BOLD
        })
        if (g.sub.isNotBlank() && !g.sub.equals(g.label, ignoreCase = true)) {
            names.addView(TextView(ctx).apply {
                text = g.sub
                setTextColor(0x77FFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            })
        }
        row.addView(names)
        row.addView(TextView(ctx).apply {
            text = state.text
            setTextColor(state.color)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            tag = GROUP_STATE_TAG
        })
        return row
    }

    /** One notification, indented under its app. Unread — newer than the
     *  previous visit — is drawn bold, so Show=All still shows what is new. */
    private fun notifRowView(
        ctx: android.content.Context, r: NotifRow, launchPackage: String,
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(10); setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6); leftMargin = dp(10) }
            setBackgroundColor(when (r.severity) {
                "error" -> 0x55B91C1C.toInt()
                "warn"  -> 0x55D97706.toInt()
                else    -> 0x331A0033
            })
            if (launchPackage.isNotBlank()) {
                isClickable = true
                setOnClickListener {
                    runCatching {
                        ctx.packageManager.getLaunchIntentForPackage(launchPackage)
                            ?.let { ctx.startActivity(it) }
                    }
                }
            }
        }
        val meta = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        meta.addView(TextView(ctx).apply {
            text = r.title
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            if (r.ts > visitSeenAt) typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        meta.addView(TextView(ctx).apply {
            text = ago(System.currentTimeMillis() - r.ts)
            setTextColor(0x88FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
        })
        row.addView(meta)
        if (r.text.isNotBlank()) row.addView(TextView(ctx).apply {
            text = r.text
            setTextColor(0xCCE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, dp(2), 0, 0)
        })
        return row
    }

    /** A card-level verdict line, for the states that apply to a whole stream
     *  rather than to one app. */
    private fun stateLine(ctx: android.content.Context, text: String, color: Int): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, 0)
        }

    private fun ago(ms: Long): String = when {
        ms < 60_000     -> "just now"
        ms < 3_600_000  -> "${ms / 60_000}m ago"
        ms < 86_400_000 -> "${ms / 3_600_000}h ago"
        else            -> "${ms / 86_400_000}d ago"
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

    /** kind=signal_health — the top level of C3 Obsv: "is the cloud telling me
     *  anything is wrong right now".
     *
     *  Every row is one ntfy topic published by a c3-morpheus script probe, and
     *  what is measured is the AGE OF THE NEWEST MESSAGE. That is the only
     *  health fact a phone can establish about a fleet it cannot reach into.
     *
     *  FOUR states, never two, because the two failure modes this page exists to
     *  catch both look like silence:
     *    OK          — a message inside the topic's promised interval.
     *    SILENT      — the fetch SUCCEEDED and the topic is empty. Says so, in
     *                  amber, because a quiet topic is either "nothing wrong" or
     *                  "the publisher died" and this card cannot tell which.
     *                  ntfy answers 200 when publishing to an UNREGISTERED topic,
     *                  so a health script can publish into the void for weeks and
     *                  look perfectly healthy; SILENT is what that looks like here.
     *    STALE       — messages exist but the newest is older than the promise.
     *    UNAVAILABLE — the fetch itself failed. Renders GREY and says the status
     *                  is unknown. It must never render green: a health dashboard
     *                  showing "all clear" because its own fetch broke is the
     *                  worst possible failure-reporting-success, since this is the
     *                  surface trusted to say when something is wrong.
     *
     *  Inline views only, no child fragment — so this card is safe to rebuild,
     *  unlike anything routed through [embedChild]'s fixed host pool. */
    private fun renderSignalHealth(
        ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel,
    ) {
        if (panel.subtitle.isNotBlank()) body.addView(caption(ctx, panel.subtitle))
        if (panel.signals.isEmpty()) {
            // Naming the reason beats an empty card that reads as "all quiet".
            body.addView(caption(ctx, "No signals declared for this card in build.json"))
            return
        }
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        for (sig in panel.signals) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val p = dp(5); setPadding(0, p, 0, p)
                isClickable = true; isFocusable = true
                setOnClickListener {
                    runCatching {
                        startActivity(android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://rss.diegonmarcos.com/${sig.topic}")))
                    }
                }
            }
            val dot = View(ctx).apply {
                val sz = dp(8)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { rightMargin = dp(8) }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0x66FFFFFF)
                }
            }
            row.addView(dot)
            row.addView(TextView(ctx).apply {
                text = sig.label
                setTextColor(0xDDFFFFFF.toInt())
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            // Starts as "checking", NOT as OK — an un-fetched row must never
            // spend even its first frame looking healthy.
            val state = TextView(ctx).apply {
                text = "checking…"
                setTextColor(0x99FFFFFF.toInt())
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            }
            row.addView(state)
            body.addView(row)

            runCatching {
                executor.execute {
                    val verdict = pollSignal(sig)
                    state.post {
                        state.text = verdict.text
                        state.setTextColor(verdict.color)
                        (dot.background as? android.graphics.drawable.GradientDrawable)
                            ?.setColor(verdict.color)
                    }
                }
            }.onFailure {
                // Could not even schedule the probe — say so rather than leaving
                // the row reading "checking…" forever, which looks like progress.
                state.text = "unavailable"
                state.setTextColor(SIGNAL_UNKNOWN)
            }
        }
        executor.shutdown()
    }

    private data class SignalVerdict(val text: String, val color: Int)

    /** Grey = we do not know. Deliberately NOT red: red is a claim about the
     *  fleet, and a failed fetch is a claim about this phone's network. */
    private val SIGNAL_UNKNOWN = 0xFF9E9E9E.toInt()
    private val SIGNAL_OK      = 0xFF34C759.toInt()
    private val SIGNAL_WARN    = 0xFFFFB020.toInt()

    /** ntfy's poll API: newest message wins. Any non-200, any exception, and any
     *  unparseable body is UNAVAILABLE — the honest answer is that we did not
     *  measure, not that everything is fine. */
    private fun pollSignal(sig: Sections.SignalRef): SignalVerdict {
        val newest: Long = try {
            val url = java.net.URL(
                "https://rss.diegonmarcos.com/${sig.topic}/json?poll=1&since=7d")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 4000; readTimeout = 4000; requestMethod = "GET"
            }
            try {
                if (conn.responseCode != 200)
                    return SignalVerdict("unavailable · HTTP ${conn.responseCode}", SIGNAL_UNKNOWN)
                var maxTime = 0L
                conn.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    runCatching {
                        val o = org.json.JSONObject(line)
                        if (o.optString("event") == "message")
                            maxTime = maxOf(maxTime, o.optLong("time", 0L))
                    }
                }
                maxTime
            } finally { conn.disconnect() }
        } catch (_: Throwable) {
            // Includes the mesh being down, which is itself unknown-not-healthy.
            return SignalVerdict("unavailable · no answer", SIGNAL_UNKNOWN)
        }

        if (newest <= 0L) return SignalVerdict("silent 7d · publisher?", SIGNAL_WARN)
        val ageMinutes = ((System.currentTimeMillis() / 1000L) - newest) / 60L
        val ageText = when {
            ageMinutes < 60   -> "${ageMinutes}m ago"
            ageMinutes < 1440 -> "${ageMinutes / 60}h ago"
            else              -> "${ageMinutes / 1440}d ago"
        }
        // No declared cadence means no basis for calling it healthy — report the
        // fact in neutral grey rather than inventing a green verdict.
        if (sig.expectWithinMinutes <= 0) return SignalVerdict(ageText, SIGNAL_UNKNOWN)
        return if (ageMinutes <= sig.expectWithinMinutes) SignalVerdict(ageText, SIGNAL_OK)
               else SignalVerdict("stale · $ageText", SIGNAL_WARN)
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
                            ping = if (it.port in 1..65535) it.url to it.port else null, name = it.name)
                        it.external -> CloudTile(it.label, sub.icon, it.url, showLight = false, ping = null,
                            name = it.name)
                        else -> CloudTile(it.label, sub.icon, "https://${it.url}", showLight = true,
                            ping = if (it.port in 1..65535) it.url to it.port else null, name = it.name)
                    }
                })
            }
        }
        executor.shutdown()
    }

    private data class CloudTile(
        val label: String, val icon: String, val openUrl: String,
        val showLight: Boolean, val ping: Pair<String, Int>?,
        /** Container name, the key every declarative lookup and every ops route
         *  is addressed by. Blank for a provider console, which is a web page
         *  and not a container — those keep the old open-the-URL behaviour. */
        val name: String = "",
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
     *  row width. Tap opens [ContainerSheet] — Infos / Actions for the box and
     *  the app inside it; a provider console has no container behind it and
     *  still opens its URL. The light TCP-pings the container's {name}.app
     *  (green up / red down / grey checking). */
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
            setOnClickListener {
                // Every tile in a container subgroup IS a container, so the tap
                // opens the sheet that can describe and operate it. Opening the
                // URL is still available — it is the first action inside.
                val act = activity
                if (t.name.isNotBlank() && act is androidx.fragment.app.FragmentActivity) {
                    com.diegonmarcos.superapp.cloud.ContainerSheet.show(act, t.name, t.label, t.openUrl)
                } else {
                    openUrlOrTarget(t.openUrl)
                }
            }
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

    // ── By / Show toggles, applied to a notification list ──────────────

    /**
     * Registers [render] so the By/Show toggles can rebuild THIS body in
     * place, and runs it once now. Only bodies made of plain views may be
     * registered: re-running a body that calls [embedChild] would allocate
     * the next fixed host id while the previous child fragment still holds
     * the old one, and the panel would render empty.
     */
    private fun refreshable(body: LinearLayout, render: () -> Unit) {
        bodyRefreshers += { body.removeAllViews(); render() }
        render()
    }

    /** Why a non-empty store still rendered nothing. Only Show=Unread can do
     *  this — sorting never removes a row — so the message can name the cause
     *  instead of shrugging. */
    private fun filteredAwayNote(total: Int): String =
        "Nothing new since your last visit. All $total entries are older, " +
        "and Show is set to Unread — switch it to All to see them."

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
        /** Marks the "Source hid everything" note so it is reused, not
         *  appended once per toggle tap. */
        private const val SOURCE_EMPTY_TAG = "stack_source_empty"
        /** Marks a notification group's verdict chip so an async ntfy poll can
         *  find it again without a field per group. */
        private const val GROUP_STATE_TAG = "notif_group_state"

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
