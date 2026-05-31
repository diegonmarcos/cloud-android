package com.diegonmarcos.superapp

/**
 * Marker for fragments that host collapsable content (stack panels,
 * accordion sections, etc.). MainActivity asks the active fragment
 * to toggle collapse-all when the user re-taps a bottom-nav slot
 * they're already on.
 */
interface Collapsible {
    /** Collapse all panels if any are expanded; expand all if all are
     *  collapsed. Returns true if the fragment handled the toggle. */
    fun toggleAllCollapsed(): Boolean
}
