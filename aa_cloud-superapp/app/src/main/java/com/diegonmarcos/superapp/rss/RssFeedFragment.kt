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
        val scopes = NtfyScopes.load()
        val byScope = LinkedHashMap<NtfyScopes.Scope, MutableList<String>>()
        for (sc in scopes) byScope[sc] = mutableListOf()
        for (t in topics) byScope.getValue(NtfyScopes.scopeOf(t, scopes)).add(t)

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
                row.findViewById<TextView>(R.id.r_name).text = topic
                val url = "https://rss.diegonmarcos.com/$topic"
                row.findViewById<TextView>(R.id.r_url).text  = "rss.diegonmarcos.com/$topic"
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

    companion object {
        fun newInstance() = RssFeedFragment()
    }
}
