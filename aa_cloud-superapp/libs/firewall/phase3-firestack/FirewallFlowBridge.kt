package com.diegonmarcos.superapp.firewall

import android.content.Context
import android.util.Log
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.intra.FlowListener
import com.celzero.firestack.intra.Mark
import com.celzero.firestack.intra.PreMark
import com.celzero.firestack.intra.FlowSummary

/**
 * Phase-3 decision bridge — the firestack `FlowListener` half of the Bridge
 * passed to `Intra.connect`. Firestack invokes this per connection; we resolve
 * the owning app (uid → package), read its policy + live conditions, and hand
 * the decision to the pure [FirewallFlowPolicy], returning a firestack [Mark]
 * that names a proxy id: Backend.Block (drop) / Backend.Exit (direct) / the
 * WG proxy id (route via cloud VPN).
 *
 * ─────────────────────────────────────────────────────────────────────────
 * RUNNER-COMPLETION NOTES (this file compiles only against the built
 * firestack.aar — `build.sh firestack` — and its exact gomobile signatures):
 *  - `Mark` field access: the Go struct field is `PIDCSV`; gomobile emits
 *    `getPIDCSV()/setPIDCSV()`. [mark] centralises construction so any
 *    generated-name fix touches ONE place. `Mark` has more fields (see
 *    intra/listener.go) — defaults are fine; only the proxy id matters here.
 *  - The full `Bridge` interface is a union (Console/Controller/DNSListener/
 *    ProxyListener/FlowListener). [FirestackTunnelService] assembles the rest;
 *    THIS class is the FlowListener portion only.
 * ─────────────────────────────────────────────────────────────────────────
 */
class FirewallFlowBridge(
    private val appContext: Context,
    private val cloudVpn: CloudVpnProvider,
) : FlowListener {

    /** Outbound connection → OUT verdict. */
    override fun flow(
        protocol: Int, uid: Int,
        src: String, dst: String,
        origdsts: String, domains: String, probableDomains: String, blocklists: String,
    ): Mark = decide(uid, Direction.OUT)

    /** Inbound connection → IN verdict. This is what finally enforces the
     *  "block incoming" rules the interim drain-engine could never do. */
    override fun inflow(protocol: Int, uid: Int, src: String, dst: String): Mark =
        decide(uid, Direction.IN)

    /** We don't override flow ownership; let firestack keep the reported uid. */
    override fun preflow(protocol: Int, uid: Int, src: String, dst: String): PreMark? = null

    override fun flowing(m: Mark?) { /* no-op: final-mark notification */ }
    override fun postflow(s: FlowSummary?) { /* no-op: per-flow summary/telemetry */ }

    private fun decide(uid: Int, direction: Direction): Mark {
        val pkg = packageForUid(uid) ?: return mark(Backend.Exit) // unknown → allow direct
        val policy = FirewallRules.policy(appContext, pkg)
        if (policy.isEmpty()) return mark(Backend.Exit)

        val transport = FirewallConditions.transport(appContext)
        val energy = FirewallConditions.energy(appContext, pkg)
        val verdict = FirewallFlowPolicy.verdict(policy, direction, transport, energy, cloudVpn.isUp())

        return when (verdict) {
            FlowVerdict.BLOCK -> mark(Backend.Block)
            FlowVerdict.ALLOW_DIRECT -> mark(Backend.Exit)
            FlowVerdict.ALLOW_VPN -> mark(cloudVpn.proxyId() ?: Backend.Block) // up-check already passed; guard anyway
        }
    }

    /** uid → package. A shared uid can map to several packages; any of them
     *  having a policy is enough to decide (they share the sandbox). */
    private fun packageForUid(uid: Int): String? =
        runCatching { appContext.packageManager.getPackagesForUid(uid)?.firstOrNull() }.getOrNull()

    /** Single place that builds a firestack Mark from a proxy id. */
    private fun mark(proxyId: String): Mark = Mark().apply {
        // RUNNER: gomobile emits setPIDCSV for the Go field PIDCSV.
        pidcsv = proxyId
    }

    companion object { const val TAG = "FwFlowBridge" }
}
