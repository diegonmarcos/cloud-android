package com.diegonmarcos.superapp.rss
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.cloud.CloudData

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RSS / feed all — lists ONLY ntfy channels declared in
 * `cloud-data/ntfy-api/src/topics.json` (fetched live via
 * [CloudData.loadNtfyTopics]). Each topic is a tappable card; tapping
 * a future revision will open the ntfy SSE stream for that topic.
 *
 * Topics are auto-grouped by their snake_case prefix (deploy_/dev_/
 * health_/ops_/sec_) so the list reads as a categorised inventory of
 * the cloud's pub/sub channels.
 */
class RssFeedFragment : Fragment(R.layout.fragment_rss_feed) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val list    = view.findViewById<LinearLayout>(R.id.rss_list)
        val status  = view.findViewById<TextView>(R.id.rss_status)
        val spinner = view.findViewById<ProgressBar>(R.id.rss_loading)

        spinner.isVisible = true
        status.setText(R.string.rss_loading)

        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    Result.success(CloudData.loadNtfyTopics(ctx))
                } catch (t: Throwable) {
                    Result.failure<List<String>>(t)
                }
            }
            spinner.isVisible = false
            outcome.fold(
                onSuccess = { topics -> render(list, status, topics) },
                onFailure = { t -> status.text = getString(R.string.rss_error, t.message ?: "?") },
            )
        }
    }

    private fun render(container: LinearLayout, status: TextView, topics: List<String>) {
        container.removeAllViews()
        if (topics.isEmpty()) {
            status.setText(R.string.rss_empty)
            return
        }
        status.text = getString(R.string.rss_status, topics.size)

        val inflater = LayoutInflater.from(requireContext())
        // ── USER above INFRA, before anything is grouped by prefix ────────
        //
        // These are two different inboxes that happened to share one list: a
        // notification about THIS PHONE and one about a container restarting
        // read identically when sorted alphabetically together. Scope comes
        // from the topic prefix, declared in build.json::ui.ntfy.scopes, and
        // an unrecognised prefix falls to the LAST scope rather than being
        // dropped — a new channel shows up in the wrong bucket, never in none.
        //
        // A page may show only SOME scopes (My-RSS is the personal inbox, not
        // the whole registry). Classification still runs against the FULL scope
        // list so the catch-all last scope keeps working — filtering happens
        // after, on display, never on the rule. An unknown topic therefore
        // still lands somewhere real; it is merely on the other page.
        val scopes = NtfyScopes.load()
        val wanted = arguments?.getStringArrayList(ARG_SCOPES).orEmpty()
        val byScope = LinkedHashMap<NtfyScopes.Scope, MutableList<String>>()
        for (sc in scopes) byScope[sc] = mutableListOf()
        for (t in topics) byScope.getValue(NtfyScopes.scopeOf(t, scopes)).add(t)
        if (wanted.isNotEmpty()) byScope.keys.retainAll { it.id in wanted }

        // The advisory topic is pinned OPEN at the top with its live messages,
        // ahead of every other channel. This screen is where a user is sent
        // when their app can no longer update itself, and at that moment the
        // difference between "the fix is here" and "the fix is three taps into
        // an alphabetical list of twenty-six addresses" is whether they find
        // it at all. Every other channel stays a link; this one is the page.
        renderAdvisories(container)

        for ((scope, scopeTopics) in byScope) {
            if (scopeTopics.isEmpty()) continue
            container.addView(TextView(requireContext()).apply {
                text = scope.label
                setTextAppearance(android.R.style.TextAppearance_Material_Title)
                setTextColor(resources.getColor(R.color.cloud_primary, requireContext().theme))
                setPadding(
                    (10 * resources.displayMetrics.density).toInt(),
                    (18 * resources.displayMetrics.density).toInt(),
                    0, 0,
                )
            })
            if (scope.subtitle.isNotBlank()) container.addView(TextView(requireContext()).apply {
                text = scope.subtitle
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                alpha = 0.7f
                setPadding((10 * resources.displayMetrics.density).toInt(), 0, 0, 0)
            })
            renderScope(container, inflater, scopeTopics)
        }
    }

    /** One scope's topics, still grouped by snake_case prefix beneath it. */
    private fun renderScope(container: LinearLayout, inflater: LayoutInflater, topics: List<String>) {
        val groups = topics.groupBy { it.substringBefore('_', it).let { p -> if (p == it) "_" else p } }
            .toSortedMap()

        for ((prefix, items) in groups) {
            // Header
            val header = TextView(requireContext()).apply {
                text = if (prefix == "_") getString(R.string.rss_group_other) else prefix.uppercase()
                setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
                setTextColor(resources.getColor(R.color.cloud_primary, requireContext().theme))
                alpha = 0.9f
                setPadding(
                    (10 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt(),
                    0, (4 * resources.displayMetrics.density).toInt(),
                )
            }
            container.addView(header)

            for (topic in items.sorted()) {
                val row = inflater.inflate(R.layout.item_rss_topic, container, false)
                // Title line is what the channel CARRIES; the address moves to
                // the subtitle next to the URL that was already there. The raw
                // topic stays visible because it is what you publish to.
                row.findViewById<TextView>(R.id.r_name).text = NtfyCatalog.labelOf(topic)
                val url = "https://rss.diegonmarcos.com/$topic"
                row.findViewById<TextView>(R.id.r_url).text  = "$topic · rss.diegonmarcos.com/$topic"
                row.setOnClickListener {
                    runCatching {
                        startActivity(android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url),
                        ))
                    }
                }
                container.addView(row)
            }
        }
    }

    /**
     * The advisory channel, expanded in place. Fetched separately from the
     * topic registry and rendered whatever the result: messages if there are
     * any, "nothing published" if the channel is reachable and quiet, the
     * error if it is not. All three are useful; a section that silently
     * vanishes on failure teaches the user the channel is not worth checking.
     */
    private fun renderAdvisories(container: LinearLayout) {
        val ctx = requireContext()
        val topic = NtfyCatalog.advisoryTopic()
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        container.addView(TextView(ctx).apply {
            text = NtfyCatalog.labelOf(topic)
            setTextAppearance(android.R.style.TextAppearance_Material_Title)
            setTextColor(resources.getColor(R.color.cloud_primary, ctx.theme))
            setPadding(dp(10), dp(8), 0, 0)
        })
        val status = TextView(ctx).apply {
            text = getString(R.string.rss_advisory_loading)
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            alpha = 0.7f
            setPadding(dp(10), 0, dp(10), dp(4))
        }
        container.addView(status)

        val slot = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(slot)

        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { AdvisoryChannel.recent(topic) }
            }
            if (!isAdded) return@launch
            outcome.fold(
                onSuccess = { msgs ->
                    status.text = if (msgs.isEmpty()) getString(R.string.rss_advisory_empty)
                                  else getString(R.string.rss_advisory_status, msgs.size)
                    val inflater = LayoutInflater.from(ctx)
                    for (m in msgs) {
                        val row = inflater.inflate(R.layout.item_rss_topic, slot, false)
                        row.findViewById<TextView>(R.id.r_name).text = m.title
                        val hasLink = m.link != null
                        row.findViewById<TextView>(R.id.r_url).text =
                            if (hasLink) getString(R.string.rss_advisory_install) else m.body.take(160)
                        row.setOnClickListener {
                            runCatching {
                                // Straight to the bootstrap installer, which
                                // downloads, verifies the sha256 sidecar and
                                // hands the system installer bytes it can
                                // vouch for. Handing the raw URL to a browser
                                // would skip every one of those steps.
                                startActivity(
                                    if (hasLink)
                                        com.diegonmarcos.superapp.recovery.RecoveryActivity
                                            .intent(ctx, m.appId)
                                    else android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://rss.diegonmarcos.com/$topic"),
                                    )
                                )
                            }
                        }
                        slot.addView(row)
                    }
                },
                onFailure = { status.text = getString(R.string.rss_error, it.message ?: "?") },
            )
        }
    }

    companion object {
        private const val ARG_SCOPES = "scopes"

        /**
         * [scopeIds] restricts the page to those `ui.ntfy.scopes` ids; empty
         * shows every scope. This is the ONLY difference between Cloud-RSS
         * (the full channel browser) and My-RSS (the personal inbox): one
         * catalog, one grouping rule, two filters over it. A subset declared
         * here would have been a second channel list that goes stale the day
         * a topic is added — so it is declared in build.json instead.
         */
        fun newInstance(scopeIds: List<String> = emptyList()) = RssFeedFragment().apply {
            arguments = Bundle().apply { putStringArrayList(ARG_SCOPES, ArrayList(scopeIds)) }
        }
    }
}
