package com.diegonmarcos.superapp

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * C3 · WG Mesh — rich peer table for EVERY declared mesh
 * (build.json::ui.meshes). Today: wg-mesh (internal VM↔VM) and
 * wg-public (Android clients → cf-worker bridge → oci-analytics).
 *
 * Each mesh renders as: header card with subnet / port / MTU / topology,
 * then one row per peer with name+region, WG-IP, endpoint, allowed-ips,
 * keep-alive, role. Status dot grey until cloud_url_health overlay
 * lands from the cargo-ndk Rust binary in jniLibs/.
 */
class C3MeshFragment : Fragment(R.layout.fragment_c3_mesh) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val root = view.findViewById<LinearLayout>(R.id.mesh_root)
        val status = view.findViewById<TextView>(R.id.mesh_status)

        val meshes = Sections.meshes()
        val totalPeers = meshes.sumOf { it.peers.size }
        status.text = getString(R.string.mesh_status_multi, meshes.size, totalPeers)

        val inflater = LayoutInflater.from(ctx)
        for (mesh in meshes) {
            val meshBlock = inflater.inflate(R.layout.item_c3_mesh_block, root, false) as LinearLayout
            meshBlock.findViewById<TextView>(R.id.mb_label).text = mesh.label
            meshBlock.findViewById<TextView>(R.id.mb_meta).text =
                "${mesh.subnet} · port ${mesh.port} · MTU ${mesh.mtu}\n${mesh.topology}"

            val peersList = meshBlock.findViewById<LinearLayout>(R.id.mb_peers)
            for (peer in mesh.peers) {
                val row = inflater.inflate(R.layout.item_c3_mesh_row, peersList, false)
                val dot = row.findViewById<View>(R.id.m_status_dot)
                dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF9E9E9E.toInt())
                }
                row.findViewById<TextView>(R.id.m_name).text     = peer.name
                row.findViewById<TextView>(R.id.m_region).text   = peer.region
                row.findViewById<TextView>(R.id.m_wg_ip).text    = peer.wgIp
                row.findViewById<TextView>(R.id.m_endpoint).text = peer.endpoint
                row.findViewById<TextView>(R.id.m_role).text     = buildString {
                    append(peer.role)
                    if (peer.allowedIps.isNotBlank()) append(" · ").append(peer.allowedIps)
                    if (peer.keepalive > 0) append(" · ka ").append(peer.keepalive).append("s")
                }
                peersList.addView(row)
            }
            root.addView(meshBlock)
        }
    }

    companion object {
        fun newInstance() = C3MeshFragment()
    }
}
