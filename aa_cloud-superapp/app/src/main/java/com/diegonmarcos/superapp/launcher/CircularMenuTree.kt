package com.diegonmarcos.superapp.launcher

import android.content.Context
import com.diegonmarcos.superapp.onehand.CircularMenu

/**
 * Nested child provider for the Sirius circular-menu.
 *
 * [childrenOf] maps a key to the next ring of [CircularMenu.Child] items. Keys
 * are either a section id (e.g. "tools", "config") or a slash-delimited path
 * that encodes the expansion trail (e.g. "suite/cloud"). The library calls
 * [childrenOf] recursively as the user drags deeper, so nesting is arbitrary.
 *
 * Expandable child  → non-null [CircularMenu.Child.childKey]; dragging into it
 *                     triggers another [childrenOf] call with that key.
 * Terminal child    → null [CircularMenu.Child.childKey]; releasing navigates
 *                     its [CircularMenu.Child.target].
 */
object CircularMenuTree {

    fun childrenOf(ctx: Context, key: String): List<CircularMenu.Child> =
        runCatching { build(key) }.getOrDefault(emptyList())

    // ── key dispatch ─────────────────────────────────────────────────────────

    private fun build(key: String): List<CircularMenu.Child> = when (key) {

        "suite" -> listOf(
            CircularMenu.Child("Cloud", sectionIcon("suite"), "tab:cloud:suite", "suite/cloud"),
            CircularMenu.Child("Phone", sectionIcon("suite"), "tab:phone:suite", "suite/phone"),
        )

        "suite/phone" -> listOf(
            CircularMenu.Child("Home", sectionIcon("suite"), "tab:phone:suite",          null),
            CircularMenu.Child("More", "ic_p_more",          "action:open_home_apps_phone", null),
        )

        "suite/cloud" -> {
            // One terminal child per tile-group title on the Cloud tab.
            val groups = Sections.byId("suite")?.tileGroups.orEmpty()
                .filter { it.title.isNotBlank() }
            groups.map { g ->
                CircularMenu.Child(g.title, sectionIcon("suite"), "tab:cloud:suite", null)
            }
        }

        "communication", "infos" -> {
            // Apps / Admin facets for aggregator sections.
            val s = Sections.byId(key)
            listOf(
                CircularMenu.Child("Apps",  s?.iconApps  ?: s?.iconName ?: "", "mode:apps:$key",  null),
                CircularMenu.Child("Admin", s?.iconAdmin ?: s?.iconName ?: "", "mode:admin:$key", null),
            )
        }

        else -> {
            // Generic: one terminal child per declared page (e.g. "tools", "config").
            SectionPages.pagesFor(key).map { p ->
                val target = p.action.ifBlank { "page:$key/${p.id}" }
                CircularMenu.Child(p.label, p.id, target, null, isAction(target))
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Inner-ring test. An entry whose target fires and returns isn't really a
     *  page, so it belongs on the actions ring. Derived from the target grammar
     *  rather than a per-page flag — build.json needs no edit to opt in. */
    private fun isAction(target: String): Boolean =
        target.startsWith("action:") || target.startsWith("extapp:")

    private fun sectionIcon(id: String): String = Sections.byId(id)?.iconName ?: ""
}
