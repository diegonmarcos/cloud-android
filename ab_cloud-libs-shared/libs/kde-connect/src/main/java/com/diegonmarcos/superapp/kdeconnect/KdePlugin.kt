package com.diegonmarcos.superapp.kdeconnect

import android.content.Context

/**
 * A KDE-Connect plugin: declares which packet [incoming] types it handles and
 * which [outgoing] types it may emit (these feed our identity's capability
 * lists), and processes inbound packets it owns. Original implementation of
 * the documented packet contracts.
 */
interface KdePlugin {
    /** Catalog id (build.json plugins[].id) — the per-plugin enable/disable key. */
    val id: String
    val incoming: Set<String>
    val outgoing: Set<String>
    /** Handle a packet whose type is in [incoming]. Returns true if consumed. */
    fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean
    /** Called once a link is established + paired — for plugins that PUSH state
     *  proactively (e.g. battery reports its level on connect). Default no-op. */
    fun onLinkReady(ctx: Context, link: KdeLink) {}
}
