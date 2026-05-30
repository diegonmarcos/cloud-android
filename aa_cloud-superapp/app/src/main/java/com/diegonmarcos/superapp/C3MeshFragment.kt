package com.diegonmarcos.superapp

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * C3 · WG Mesh — renders the canonical wg-mesh/v1 snapshot (data/mesh.json,
 * sourced from cloud/a_solutions/bb-net_wireguard-mesh/src/data/mesh.json).
 *
 *   Transports header (wg0 direct UDP + wg0-tcp wstunnel fallback)
 *   Nodes table       (hub / spokes / clients with WG-IP, public IP, role,
 *                      provider/region, OS, key fingerprint, public ports,
 *                      wstunnel flags)
 *   Peers table       (from → to · allowed-ips · keep-alive)
 *
 * Live status overlay (handshake / latency / OK) arrives when the
 * cargo-ndk Rust binary publishes cloud_url_health.json into jniLibs/.
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

        // ── Transports ────────────────────────────────────────────────
        if (mesh.transports.isNotEmpty()) {
            val block = inflater.inflate(R.layout.item_c3_mesh_block, root, false) as LinearLayout
            block.findViewById<TextView>(R.id.mb_label).text = getString(R.string.mesh_section_transports)
            block.findViewById<TextView>(R.id.mb_meta).text  =
                "${mesh.transports.size} transports declared"
            val list = block.findViewById<LinearLayout>(R.id.mb_peers)
            for (t in mesh.transports) {
                val r = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    val pad = (12 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad / 2, pad, pad / 2)
                }
                addLine(r, "${t.name} · ${t.label}", titleSize = true)
                addLine(r, "${t.protocol.uppercase()}/${t.port}  →  ${t.endpoint}")
                addLine(r, buildString {
                    append(if (t.primary) "primary" else if (t.fallback) "fallback" else "—")
                    append(" · ").append(t.activePeers).append(" active peers")
                })
                if (t.useCase.isNotBlank()) addLine(r, t.useCase, dim = true)
                list.addView(r)
            }
            root.addView(block)
        }

        // ── Nodes ────────────────────────────────────────────────────
        if (mesh.nodes.isNotEmpty()) {
            val block = inflater.inflate(R.layout.item_c3_mesh_block, root, false) as LinearLayout
            block.findViewById<TextView>(R.id.mb_label).text = getString(R.string.mesh_section_nodes)
            block.findViewById<TextView>(R.id.mb_meta).text  = "${mesh.nodes.size} nodes"
            val list = block.findViewById<LinearLayout>(R.id.mb_peers)
            for (node in mesh.nodes) {
                val row = inflater.inflate(R.layout.item_c3_mesh_row, list, false)
                row.findViewById<View>(R.id.m_status_dot).background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(when (node.role) {
                        "hub"    -> 0xFF2E7D32.toInt()
                        "client" -> 0xFFEF6C00.toInt()
                        else     -> 0xFF1565C0.toInt()
                    })
                }
                row.findViewById<TextView>(R.id.m_name).text     = "${node.name} · ${node.role}"
                row.findViewById<TextView>(R.id.m_region).text   =
                    "${node.alias} · ${node.provider}/${node.region}"
                row.findViewById<TextView>(R.id.m_wg_ip).text    = node.wgIp
                row.findViewById<TextView>(R.id.m_endpoint).text = node.publicIp
                row.findViewById<TextView>(R.id.m_role).text     = buildString {
                    if (node.portsPublic.isNotEmpty()) append(node.portsPublic.joinToString(" "))
                    if (node.wstunnelServer) append(" · wstunnel↑")
                    if (node.wstunnelClient) append(" · wstunnel↓")
                    if (node.os.isNotBlank())  append(" · ").append(node.os)
                }
                list.addView(row)
            }
            root.addView(block)
        }

        // ── Peers ────────────────────────────────────────────────────
        if (mesh.peers.isNotEmpty()) {
            val block = inflater.inflate(R.layout.item_c3_mesh_block, root, false) as LinearLayout
            block.findViewById<TextView>(R.id.mb_label).text = getString(R.string.mesh_section_peers)
            block.findViewById<TextView>(R.id.mb_meta).text  = "${mesh.peers.size} declared peerings"
            val list = block.findViewById<LinearLayout>(R.id.mb_peers)
            for (p in mesh.peers) {
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
            root.addView(block)
        }
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
            typeface = android.graphics.Typeface.MONOSPACE
        }
        host.addView(tv)
    }

    companion object { fun newInstance() = C3MeshFragment() }
}
