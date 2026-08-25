package com.diegonmarcos.superapp.system
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R

/**
 * Per-section take-over contract. A section's CONTENT Fragment (the one
 * MainActivity puts in R.id.fragment_container) may implement this interface
 * to declare it owns its own chrome. The shell consults it on every fragment
 * swap (see MainActivity.applyChrome).
 *
 * The intent is **minimal plumbing for FOSS-app cherry-picks**:
 *   - FairEmail's main fragment will return `ownsToolbar = true` — it already
 *     renders its own MaterialToolbar inside its layout, so our shell hides
 *     its AppBarLayout to avoid a double toolbar.
 *   - The drawer is always shell-owned (swipe from left edge) — its [Home]
 *     tab gives the user a guaranteed way back to the SuperApp home.
 *   - The bottom nav stays visible by default so the user can switch sections
 *     without going Home first; set `ownsBottomNav = true` only if the
 *     section app insists on full-screen real estate.
 *
 * Fragments that DON'T implement this interface use shell defaults (toolbar
 * visible, bottom nav visible).
 */
interface ShellOverride {
    /** True → hide shell's AppBarLayout; the fragment ships its own toolbar. */
    fun ownsToolbar(): Boolean = false

    /** True → hide shell's BottomNavigationView; the fragment wants full screen. */
    fun ownsBottomNav(): Boolean = false
}
