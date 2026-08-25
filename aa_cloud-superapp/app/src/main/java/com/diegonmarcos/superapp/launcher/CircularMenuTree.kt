package com.diegonmarcos.superapp.launcher

import android.content.Context
import com.diegonmarcos.superapp.onehand.CircularMenu

/**
 * Nested child provider for the Sirius circular-menu.
 *
 * [childrenOf] maps a section id to that section's ring of
 * [CircularMenu.Child] items. The library calls [childrenOf] recursively as
 * the user drags deeper, so deeper rings only need a child to hand back a
 * [CircularMenu.Child.childKey] — none do today.
 *
 * Expandable child  → non-null [CircularMenu.Child.childKey]; dragging into it
 *                     triggers another [childrenOf] call with that key.
 * Terminal child    → null [CircularMenu.Child.childKey]; releasing navigates
 *                     its [CircularMenu.Child.target].
 */
object CircularMenuTree {

    fun childrenOf(ctx: Context, key: String): List<CircularMenu.Child> =
        runCatching { build(key) }.getOrDefault(emptyList())

    /** One terminal child per declared page. Every section goes through here
     *  — including the aggregators, whose Cloud|Phone and Apps|Admin children
     *  used to need bespoke `tab:`/`mode:` branches. They are pages now. */
    private fun build(key: String): List<CircularMenu.Child> =
        SectionPages.pagesFor(key).map { p ->
            val target = p.action.ifBlank { "page:$key/${p.id}" }
            CircularMenu.Child(p.label, p.iconName.ifBlank { p.id }, target, null, p.isAction)
        }
}
