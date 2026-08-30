package com.diegonmarcos.superapp.firewall

import com.celzero.firestack.intra.Bridge
import com.celzero.firestack.intra.FlowListener

/**
 * RUNNER-COMPLETION SEAM (compiles only against the built firestack.aar).
 *
 * `Intra.connect` takes a single `Bridge` — a UNION of firestack's listener
 * interfaces (Console, Controller, DNSListener, ProxyListener, FlowListener,
 * …). We only have a decision-bearing implementation for the FlowListener
 * half ([FirewallFlowBridge]); the rest are telemetry/no-op surfaces.
 *
 * The exact union membership + method signatures are emitted by gomobile into
 * the aar, so this class CANNOT be finished from GitHub source alone — it must
 * be completed on the build runner against the generated `Bridge` type,
 * mirroring RethinkDNS's `GoIntraListener` / `GoVpnAdapter`. Until then this is
 * the one file that legitimately won't compile; everything else in the merge
 * is source-grounded.
 *
 * TODO(runner): `class FirestackBridgeAssembly(flow) : Bridge { ... }`
 *   - delegate the FlowListener methods (flow/inflow/preflow/flowing/postflow)
 *     to [flow];
 *   - stub the remaining union methods as no-op/log (copy the generated
 *     signatures from the aar / RethinkDNS reference).
 */
class FirestackBridgeAssembly(
    @Suppress("unused") private val flow: FlowListener,
) {
    // Intentionally NOT `: Bridge` yet — see header. Completing this (and only
    // this) against the aar makes the merged service build. The decision logic
    // it will expose is already done + tested in FirewallFlowPolicy/-Bridge.
    fun asBridge(): Bridge =
        throw NotImplementedError(
            "FirestackBridgeAssembly must be completed against the built firestack.aar Bridge union — see class header."
        )
}
