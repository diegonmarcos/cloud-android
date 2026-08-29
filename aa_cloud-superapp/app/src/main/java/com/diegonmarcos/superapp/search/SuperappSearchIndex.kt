package com.diegonmarcos.superapp.search

import android.content.Context
import com.diegonmarcos.superapp.apps.PhoneAppsFragment
import com.diegonmarcos.superapp.cloud.CloudData
import com.diegonmarcos.superapp.launcher.Sections

/**
 * The superapp half of the search: everything [SearchSheet] cannot know.
 *
 * The sheet, the matching, the chips and the `:`-command line moved to
 * libs:search; what stayed is this — the four index builders that are made of
 * THIS app's data (sections, tiles, pages, the consolidated cloud config) plus
 * the launcher-profile-filtered app list. A second app implements its own of
 * these and gets the same search bar for free.
 */
object SuperappSearchIndex {

    /** Hits for one declared scope. Unknown kinds yield nothing rather than
     *  throwing, so adding a scope to build.json before its builder exists
     *  degrades to an empty chip instead of a crash. */
    fun hitsFor(ctx: Context, scope: SearchScope): List<SearchHit> = when (scope.kind) {
        "cloud_apps"    -> cloudApps(scope.id)
        "phone_apps"    -> phoneApps(scope.id, ctx)
        "cloud_configs" -> cloudConfigs(scope.id, ctx)
        "phone_configs" -> PhoneConfigs.hits(ctx, scope.id)
        else -> emptyList()
    }

    /**
     * The `:`-command table, derived from the home actions the app already
     * declares — no second list to keep in step. `update_all` becomes
     * `:update-all`, because underscores are two taps on a phone keyboard and
     * kebab-case is what a command line looks like everywhere else.
     */
    fun commands(): List<SearchCommand> =
        Sections.homeActions().map { act ->
            SearchCommand(
                alias = act.actionType.replace('_', '-'),
                label = act.label,
                target = "action:${act.actionType}",
                crumb = act.label,
            )
        }.distinctBy { it.alias }.sortedBy { it.alias }

    /** Cloud-Apps: navigable cloud destinations — sections, their tiles, and
     *  home actions. (Sub-pages live in Cloud-Configs.) */
    private fun cloudApps(scopeId: String): List<SearchHit> {
        val out = mutableListOf<SearchHit>()
        for (sec in Sections.all().filter { !it.isMasterIndex }) {
            out += SearchHit(sec.label, "Section", scopeId, target = "section:${sec.id}")
            for (tile in (sec.tilesShared + sec.tilesApps + sec.tilesAdmin)) {
                out += SearchHit(tile.label, "${sec.label} · Tile", scopeId, target = tile.target)
            }
        }
        for (act in Sections.homeActions()) {
            out += SearchHit(act.label, "Action", scopeId, target = "action:${act.actionType}")
        }
        return out
    }

    /** Phone-Apps: installed launchable apps. Goes through
     *  [PhoneAppsFragment.snapshot] so the launcher-profile filter (Guest
     *  whitelist) applies here exactly as it does on the Phone tab — the
     *  reason this scope is NOT in the library. */
    private fun phoneApps(scopeId: String, ctx: Context): List<SearchHit> =
        PhoneAppsFragment.snapshot(ctx).map { app ->
            SearchHit(app.label, "Phone app · ${app.packageName}", scopeId,
                phoneApp = PhoneAppRef(app.activityComponent, app.user))
        }

    /** Cloud-Configs: every section sub-page PLUS cached consolidated.json
     *  service entries (tap copies the value). Consolidated entries appear
     *  only once some screen has fetched the config this install — this stays
     *  network-free. */
    private fun cloudConfigs(scopeId: String, ctx: Context): List<SearchHit> {
        val out = mutableListOf<SearchHit>()
        for (sec in Sections.all().filter { !it.isMasterIndex }) {
            for (page in sec.pages) {
                out += SearchHit(page.label, "${sec.label} · Page", scopeId,
                    target = "page:${sec.id}/${page.id}")
            }
        }
        val root = CloudData.cachedOrNull(ctx) ?: return out
        val services = CloudData.services(root)
        val names = services.keys()
        while (names.hasNext()) {
            val name = names.next()
            val svc = services.optJSONObject(name) ?: continue
            val domain = svc.optString("domain", svc.optString("private_dns", ""))
            val vm = svc.optString("vm", "")
            val crumb = listOf("Cloud config", vm, domain).filter { it.isNotBlank() }.joinToString(" · ")
            out += SearchHit(name, crumb, scopeId, copyValue = domain.ifBlank { name })
        }
        return out
    }
}
