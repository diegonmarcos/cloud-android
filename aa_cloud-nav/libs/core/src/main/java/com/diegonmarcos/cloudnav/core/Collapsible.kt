package com.diegonmarcos.cloudnav.core

/**
 * Marker for fragments that host collapsable content (stack panels,
 * accordion sections, etc.). MainActivity asks the active fragment
 * to toggle collapse-all when the user re-taps a bottom-nav slot
 * they're already on.
 *
 * Lives in libs:core so both app/ (AggregatorStackFragment,
 * TabbedSectionFragment, MainActivity dispatcher) and feature
 * modules (libs:browser's BrowserHostFragment, future libs:&lt;x&gt;
 * hosts) can implement / use the same interface without a
 * back-reference from libs into app.
 */
interface Collapsible {
    /** Collapse all panels if any are expanded; expand all if all are
     *  collapsed. Returns true if the fragment handled the toggle. */
    fun toggleAllCollapsed(): Boolean
}
