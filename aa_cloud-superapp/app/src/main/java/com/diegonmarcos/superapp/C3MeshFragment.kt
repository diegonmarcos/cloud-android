package com.diegonmarcos.superapp

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * C3 / WG Mesh — merged Tunnels+Status table. Data-driven from
 * build.json::ui.mesh_topology (parsed via [Sections.meshTopology]).
 *
 * Each row: name · WG-IP · public endpoint · region · role · status dot.
 * The status dot is grey until the cloud_url_health.json overlay lands
 * (cargo-ndk build → APK jniLibs/ → in-app probe). Until then, all peers
 * shown as "declared / no live probe yet".
 */
class C3MeshFragment : Fragment(R.layout.fragment_c3_mesh) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val table = view.findViewById<LinearLayout>(R.id.mesh_table)
        val status = view.findViewById<TextView>(R.id.mesh_status)

        val peers = Sections.meshTopology()
        status.text = getString(R.string.mesh_status, peers.size)

        val inflater = LayoutInflater.from(ctx)
        for (peer in peers) {
            val row = inflater.inflate(R.layout.item_c3_mesh_row, table, false)

            // Status dot: grey (no live probe yet); later this turns green
            // when the cloud_url_health overlay says ok=true.
            val dot = row.findViewById<View>(R.id.m_status_dot)
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF9E9E9E.toInt())
            }

            row.findViewById<TextView>(R.id.m_name).text     = peer.name
            row.findViewById<TextView>(R.id.m_wg_ip).text    = peer.wgIp
            row.findViewById<TextView>(R.id.m_endpoint).text = peer.endpoint
            row.findViewById<TextView>(R.id.m_region).text   = peer.region
            row.findViewById<TextView>(R.id.m_role).text     = peer.role

            table.addView(row)
        }
    }

    companion object {
        fun newInstance() = C3MeshFragment()
    }
}
