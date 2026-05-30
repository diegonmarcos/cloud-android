package com.diegonmarcos.superapp

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * C3 · WG Mesh — renders the canonical wg-mesh/v1 snapshot
 * (data/mesh.json) grouped by **transport**, matching the user's
 * mental model of "WG0 vs WG-PUBLIC":
 *
 *   wg0       UDP/51820 direct                  → VMs (hub + spokes)
 *   wg0-tcp   wstunnel TCP/443 over WSS         → roaming clients
 *
 * Each transport card shows:
 *   • Header  protocol/port + endpoint + primary/fallback + use case
 *   • Nodes   that use THIS transport (status dot · name · region
 *              · WG-IP · public IP · public ports · OS)
 *   • Peers   declared from→to relationships involving any node above
 *
 * Per the canonical spec, "wg-public" is NOT a separate WG network —
 * it's the wstunnel TCP/443 transport of the same wg0 network. The
 * UI groups by transport so it reads as two networks (which is how
 * the user perceives them).
 */
class C3MeshFragment : Fragment(R.layout.fragment_c3_mesh) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val root   = view.findViewById<LinearLayout>(R.id.mesh_root)
        val status = view.findViewById<TextView>(R.id.mesh_status)
        val inflater = LayoutInflater.from(requireContext())

        val mesh = Sections.mesh()
        status.text = getString(
            R.string.mesh_status_v1,
            mesh.nodes.size, mesh.peers.size, mesh.transports.size,
        )

        // wstunnel users = clients that go via wg0-tcp (laptop/phone).
        // Everyone else uses wg0 direct UDP.
        val tcpNodeNames = mesh.nodes.filter { it.wstunnelClient }.map { it.name }.toSet()
        val tcpNodes     = mesh.nodes.filter { it.wstunnelClient }
        val udpNodes     = mesh.nodes.filter { !it.wstunnelClient }

        // Peers map their from-node onto a transport — if from-node is
        // wstunnel-client, the relationship runs on wg0-tcp. Otherwise wg0.
        val tcpPeers = mesh.peers.filter { it.from in tcpNodeNames || it.to in tcpNodeNames }
        val udpPeers = mesh.peers.filterNot { it.from in tcpNodeNames || it.to in tcpNodeNames }

        for (transport in mesh.transports) {
            val (nodesForT, peersForT) = when (transport.name) {
                "wg0"     -> udpNodes to udpPeers
                "wg0-tcp" -> tcpNodes to tcpPeers
                else      -> mesh.nodes to mesh.peers
            }
            renderTransportCard(root, inflater, transport, nodesForT, peersForT)
        }
    }

    private fun renderTransportCard(
        root: LinearLayout,
        inflater: LayoutInflater,
        t: Sections.MeshTransport,
        nodes: List<Sections.MeshNode>,
        peers: List<Sections.MeshPeer>,
    ) {
        val block = inflater.inflate(R.layout.item_c3_mesh_block, root, false) as LinearLayout

        val label = block.findViewById<TextView>(R.id.mb_label)
        label.text = "${t.name} · ${t.label}"

        val meta = block.findViewById<TextView>(R.id.mb_meta)
        val role = when {
            t.primary  -> "primary"
            t.fallback -> "fallback"
            else       -> "—"
        }
        meta.text = buildString {
            append(t.protocol.uppercase()).append('/').append(t.port)
            append("  →  ").append(t.endpoint).append('\n')
            append(role).append(" · ").append(t.activePeers).append(" active peers · ")
            append(nodes.size).append(" nodes · ").append(peers.size).append(" peerings\n")
            if (t.useCase.isNotBlank()) append(t.useCase)
        }

        val list = block.findViewById<LinearLayout>(R.id.mb_peers)

        // ── nodes ────────────────────────────────────────────────────
        if (nodes.isNotEmpty()) {
            addSubheader(list, getString(R.string.mesh_section_nodes) + " · ${nodes.size}")
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
            addSubheader(list, getString(R.string.mesh_section_peers) + " · ${peers.size}")
            for (p in peers) {
                val r = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    val pad = (12 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad / 2, pad, pad / 2)
                }
                addLine(r, "${p.from}  →  ${p.to}", titleSize = true)
                addLine(r, "AllowedIPs: ${p.allowedIps.joinToString(", ")}")
                addLine(r, "keepalive ${p.keepalive}s", dim = true)
                list.addView(r)
            }
        }
        root.addView(block)
    }

    private fun addSubheader(host: LinearLayout, text: String) {
        val tv = TextView(requireContext()).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setTextColor(resources.getColor(R.color.cloud_primary, requireContext().theme))
            val pad = (10 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad / 2)
            typeface = Typeface.DEFAULT_BOLD
        }
        host.addView(tv)
    }

    private fun addLine(host: LinearLayout, text: String, titleSize: Boolean = false, dim: Boolean = false) {
        val tv = TextView(requireContext()).apply {
            this.text = text
            if (titleSize) {
                setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            } else {
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            }
            if (dim) alpha = 0.6f
            typeface = Typeface.MONOSPACE
        }
        host.addView(tv)
    }

    companion object { fun newInstance() = C3MeshFragment() }
}
