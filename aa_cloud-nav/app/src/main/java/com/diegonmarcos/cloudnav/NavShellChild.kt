package com.diegonmarcos.cloudnav

/**
 * Per-tab chrome contract (Cloud Nav's analogue of Cloud SuperApp's
 * ShellOverride). A content Fragment may implement this to tell the shell
 * whether it wants the top search bar + island row above it.
 *
 *   • Routes / Navigation / Places → search-driven, keep the top island.
 *   • Timeline / Configs           → list/settings pages, hide it.
 *
 * Fragments that don't implement this default to showing the search island.
 */
interface NavShellChild {
    /** True → shell shows the top search bar. */
    fun wantsSearchBar(): Boolean = true

    /** True → shell shows the island shortcut chips under the search bar. */
    fun wantsIslands(): Boolean = true

    /** Called when the user submits a query in the shell search bar. */
    fun onSearchSubmit(query: String) {}

    /** Called when the user taps a search-island chip. */
    fun onIslandTap(island: SearchIsland) {}
}
