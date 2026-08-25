package com.diegonmarcos.superapp.cloud
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.R

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shared WG-mesh renderer — the canonical wg-mesh/v1 snapshot
 * (data/mesh.json via [Sections.mesh]) grouped by **transport**
 * (wg0 UDP direct vs wg0-tcp wstunnel over WSS).
 *
 * Extracted out of [C3MeshFragment] so the SAME rendering can be folded
 * into the single WireGuard page (Configs → WireGuard) — "all infos on one
 * page". Unlike the fragment, this appends into a caller-supplied
 * LinearLayout (no XML ScrollView root), so it nests cleanly inside any
 * existing ScrollView without scroll-in-scroll conflicts.
 */
object MeshView {

    /** One-line summary for the page header (nodes · peers · transports). */
    fun statusText(ctx: Context): String {
        val mesh = Sections.mesh()
        return ctx.getString(
            R.string.mesh_status_v1,
            mesh.nodes.size, mesh.peers.size, mesh.transports.size,
        )
    }

    /** Append the per-transport cards (nodes + peerings) into [root]. */
    fun render(ctx: Context, inflater: LayoutInflater, root: LinearLayout) {
        val mesh = Sections.mesh()

        // wstunnel users = clients that go via wg0-tcp (laptop/phone).
        // Everyone else uses wg0 direct UDP.
        val tcpNodeNames = mesh.nodes.filter { it.wstunnelClient }.map { it.name }.toSet()
        val tcpNodes     = mesh.nodes.filter { it.wstunnelClient }
        val udpNodes     = mesh.nodes.filter { !it.wstunnelClient }

        val tcpPeers = mesh.peers.filter { it.from in tcpNodeNames || it.to in tcpNodeNames }
        val udpPeers = mesh.peers.filterNot { it.from in tcpNodeNames || it.to in tcpNodeNames }

        for (transport in mesh.transports) {
            val (nodesForT, peersForT) = when (transport.name) {
                "wg0"     -> udpNodes to udpPeers
                "wg0-tcp" -> tcpNodes to tcpPeers
                else      -> mesh.nodes to mesh.peers
            }
            renderTransportCard(ctx, root, inflater, transport, nodesForT, peersForT)
        }
    }

    private fun renderTransportCard(
        ctx: Context,
        root: LinearLayout,
        inflater: LayoutInflater,
        t: Sections.MeshTransport,
        nodes: List<Sections.MeshNode>,
        peers: List<Sections.MeshPeer>,
    ) {
        val block = inflater.inflate(R.layout.item_c3_mesh_block, root, false) as LinearLayout

        block.findViewById<TextView>(R.id.mb_label).text = "${t.name} · ${t.label}"

        val role = when {
            t.primary  -> "primary"
            t.fallback -> "fallback"
            else       -> "—"
        }
        block.findViewById<TextView>(R.id.mb_meta).text = buildString {
            append(t.protocol.uppercase()).append('/').append(t.port)
            append("  →  ").append(t.endpoint).append('\n')
            append(role).append(" · ").append(t.activePeers).append(" active peers · ")
            append(nodes.size).append(" nodes · ").append(peers.size).append(" peerings\n")
            if (t.useCase.isNotBlank()) append(t.useCase)
        }

        val list = block.findViewById<LinearLayout>(R.id.mb_peers)

        // ── nodes ────────────────────────────────────────────────────
        if (nodes.isNotEmpty()) {
            addSubheader(ctx, list, ctx.getString(R.string.mesh_section_nodes) + " · ${nodes.size}")
            for (n in nodes) {
                val row = inflater.inflate(R.layout.item_c3_mesh_row, list, false)
                row.findViewById<View>(R.id.m_status_dot).background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(when (n.role) {
                        "hub"    -> 0xFF2E7D32.toInt()    // green
                        "client" -> 0xFFEF6C00.toInt()    // orange
                        else     -> 0xFF1565C0.toInt()    // blue
                    })
                }
                row.findViewById<TextView>(R.id.m_name).text     = "${n.name} · ${n.role}"
                row.findViewById<TextView>(R.id.m_region).text   =
                    "${n.alias} · ${n.provider}/${n.region}"
                row.findViewById<TextView>(R.id.m_wg_ip).text    = n.wgIp
                row.findViewById<TextView>(R.id.m_endpoint).text = n.publicIp
                row.findViewById<TextView>(R.id.m_role).text     = buildString {
                    if (n.portsPublic.isNotEmpty()) append(n.portsPublic.joinToString(" "))
                    if (n.wstunnelServer) append(" · wstunnel↑")
                    if (n.wstunnelClient) append(" · wstunnel↓")
                    if (n.os.isNotBlank()) append(" · ").append(n.os)
                }
                list.addView(row)
            }
        }

        // ── peers ────────────────────────────────────────────────────
        if (peers.isNotEmpty()) {
            addSubheader(ctx, list, ctx.getString(R.string.mesh_section_peers) + " · ${peers.size}")
            for (p in peers) {
                val r = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    val pad = (12 * ctx.resources.displayMetrics.density).toInt()
                    setPadding(pad, pad / 2, pad, pad / 2)
                }
                addLine(ctx, r, "${p.from}  →  ${p.to}", titleSize = true)
                addLine(ctx, r, "AllowedIPs: ${p.allowedIps.joinToString(", ")}")
                addLine(ctx, r, "keepalive ${p.keepalive}s", dim = true)
                list.addView(r)
            }
        }
        root.addView(block)
    }

    private fun addSubheader(ctx: Context, host: LinearLayout, text: String) {
        host.addView(TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setTextColor(ctx.resources.getColor(R.color.cloud_primary, ctx.theme))
            val pad = (10 * ctx.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad / 2)
            typeface = Typeface.DEFAULT_BOLD
        })
    }

    private fun addLine(ctx: Context, host: LinearLayout, text: String, titleSize: Boolean = false, dim: Boolean = false) {
        host.addView(TextView(ctx).apply {
            this.text = text
            setTextAppearance(
                if (titleSize) android.R.style.TextAppearance_Material_Body2
                else android.R.style.TextAppearance_Material_Caption,
            )
            if (dim) alpha = 0.6f
            typeface = Typeface.MONOSPACE
        })
    }
}
