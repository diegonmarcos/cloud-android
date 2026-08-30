package com.diegonmarcos.superapp.firewall

import android.util.Log
import com.celzero.firestack.backend.DNSOpts
import com.celzero.firestack.backend.DNSSummary
import com.celzero.firestack.backend.ServerSummary
import com.celzero.firestack.backend.Tab
import com.celzero.firestack.intra.Bridge
import com.celzero.firestack.intra.FlowListener
import com.celzero.firestack.intra.FlowSummary
import com.celzero.firestack.intra.Mark
import com.celzero.firestack.intra.PreMark

/**
 * The single [Bridge] `Intra.connect3` demands.
 *
 * firestack's `Bridge` is a UNION of five Go interfaces, and gomobile flattens
 * all of them onto one Java type — so it cannot be implemented partially. Only
 * the FlowListener half carries decisions; that is delegated to
 * [FirewallFlowBridge], which turns each flow into a [Mark] via
 * FirewallDecider. The other thirteen methods are telemetry and socket-protect
 * surfaces this firewall has no opinion on, so they are explicit no-ops rather
 * than absent.
 *
 * The union was derived from the vendored Go source rather than guessed, since
 * it is right there in libs/firewall/firestack:
 *
 *   FlowListener   intra/listener.go        preflow flow inflow flowing postflow
 *   DNSListener    backend/dnsx_listener.go onQuery onUpstreamAnswer onResponse
 *   ProxyListener  backend/ipn_proxies.go   onProxy{Added,Removed,Updated,Stopped} onProxiesStopped
 *   Controller     backend/protect.go       bind4 bind6 protect
 *   ServerListener backend/rnet_services.go svcRoute onSvcComplete
 *
 * Two gomobile mapping rules decide the signatures, and getting them wrong is
 * the whole difficulty of this file:
 *   - Go `int32` becomes Kotlin `Int`  (FlowListener's protocol/uid)
 *   - Go `int`   becomes Kotlin `Long` (Controller's fd, DNSListener's qtyp),
 *     because Go's int is 64-bit on every target gomobile emits for.
 * [FirewallFlowBridge] already relies on the first rule, which is what
 * confirms the reading.
 *
 * NOTE: `protect`/`bind4`/`bind6` are how firestack asks the app to keep a
 * socket OUT of the tun (otherwise the engine's own upstream traffic would
 * route back into itself). They are no-ops here ONLY because this service is
 * not yet the active engine; wiring them to VpnService.protect(fd) is required
 * before FirestackTunnelService replaces FirewallVpnService.
 */
class FirestackBridgeAssembly(
    private val flow: FirewallFlowBridge,
) : Bridge {

    // ── FlowListener — the half that decides ──────────────────────────────
    override fun preflow(protocol: Int, uid: Int, src: String, dst: String): PreMark? =
        flow.preflow(protocol, uid, src, dst)

    override fun flow(
        protocol: Int,
        uid: Int,
        src: String,
        dst: String,
        origdsts: String,
        domains: String,
        probableDomains: String,
        blocklists: String,
    ): Mark? = flow.flow(protocol, uid, src, dst, origdsts, domains, probableDomains, blocklists)

    override fun inflow(protocol: Int, uid: Int, src: String, dst: String): Mark? =
        flow.inflow(protocol, uid, src, dst)

    override fun flowing(m: Mark?) = flow.flowing(m)

    override fun postflow(s: FlowSummary?) = flow.postflow(s)

    // ── Controller — socket protection ────────────────────────────────────
    // TODO(phase3-activation): route these to VpnService.protect(fd) before
    // this service becomes the active engine. Left as no-ops deliberately:
    // FirewallController still drives FirewallVpnService, so nothing calls
    // them yet, and a wrong protect() silently loops the engine's own traffic.
    override fun bind4(who: String?, addrport: String?, fd: Long) = Unit
    override fun bind6(who: String?, addrport: String?, fd: Long) = Unit
    override fun protect(who: String?, fd: Long) = Unit

    // ── DNSListener — telemetry; null keeps firestack's own defaults ──────
    override fun onQuery(who: String?, uid: String?, domain: String?, qtyp: Long): DNSOpts? = null
    override fun onUpstreamAnswer(
        who: String?,
        smm: DNSSummary?,
        forPref: DNSOpts?,
        unmodifiedipcsv: String?,
    ): DNSOpts? = null
    override fun onResponse(s: DNSSummary?) = Unit

    // ── ProxyListener — lifecycle notifications ──────────────────────────
    override fun onProxyAdded(id: String?, handle: String?) = Unit
    override fun onProxyRemoved(id: String?, handle: String?) = Unit
    override fun onProxyUpdated(id: String?, handle: String?) = Unit
    override fun onProxyStopped(id: String?, handle: String?) {
        Log.i(TAG, "proxy stopped: $id")
    }
    override fun onProxiesStopped() {
        Log.i(TAG, "all proxies stopped")
    }

    // ── ServerListener — inbound services we do not run ──────────────────
    override fun svcRoute(
        sid: String?,
        pid: String?,
        network: String?,
        sipport: String?,
        dipport: String?,
    ): Tab? = null
    override fun onSvcComplete(s: ServerSummary?) = Unit

    private companion object { const val TAG = "Firewall/Bridge" }
}
