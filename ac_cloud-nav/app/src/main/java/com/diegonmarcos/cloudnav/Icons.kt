package com.diegonmarcos.cloudnav

import android.content.Context

/**
 * Resolve data-driven icon names (from build.json) to R.drawable ids.
 * Names map to `ic_<scope>_<name>` vector drawables; missing names fall back
 * to a generic glyph so a new build.json entry never crashes the shell.
 */
object Icons {
    @Suppress("DiscouragedApi")
    private fun res(ctx: Context, name: String, fallback: Int): Int {
        val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
        return if (id != 0) id else fallback
    }

    fun nav(ctx: Context, icon: String): Int =
        res(ctx, "ic_nav_$icon", R.drawable.ic_nav_routes)

    fun island(ctx: Context, icon: String): Int =
        res(ctx, "ic_island_$icon", R.drawable.ic_island_star)

    fun place(ctx: Context, icon: String): Int =
        res(ctx, "ic_place_$icon", R.drawable.ic_place_generic)
}
