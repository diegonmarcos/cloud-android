package com.diegonmarcos.superapp.cloud
import com.diegonmarcos.superapp.R

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
        // All rendering lives in the shared [MeshView] builder so the same
        // table can be folded into the single WireGuard page.
        status.text = MeshView.statusText(requireContext())
        MeshView.render(requireContext(), LayoutInflater.from(requireContext()), root)
    }

    companion object { fun newInstance() = C3MeshFragment() }
}
