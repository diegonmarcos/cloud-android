package com.diegonmarcos.superapp

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment

/**
 * C3 · Health — service probe table.
 *
 * Data source: [Sections.servicesInventory] (baked from
 * build.json::ui.services_inventory) — declarative, always-available, no
 * 404s. Live probe overlay (status code / latency) lands later from the
 * cargo-ndk Rust cloud_url_health binary.
 *
 * Rows: status dot · name · public URL+auth · VM. Tapping a row opens
 * the public URL in the system browser.
 */
class C3HealthFragment : Fragment(R.layout.fragment_c3_health) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val table   = view.findViewById<LinearLayout>(R.id.health_table)
        val status  = view.findViewById<TextView>(R.id.health_status)
        val spinner = view.findViewById<ProgressBar>(R.id.health_loading)
        spinner.isVisible = false

        val services = Sections.servicesInventory()
        if (services.isEmpty()) {
            status.setText(R.string.c3_health_empty)
            return
        }
        val enabled = services.count { it.enabled }
        status.text = getString(R.string.c3_health_status, enabled, services.size)

        val inflater = LayoutInflater.from(requireContext())
        for (svc in services.sortedBy { it.name }) {
            val row = inflater.inflate(R.layout.item_c3_health_row, table, false)
            val isPublic = svc.publicUrl.isNotBlank() && !svc.publicUrl.startsWith("dns.")
            val color = when {
                !svc.enabled -> 0xFF9E9E9E.toInt()
                isPublic     -> 0xFF2E7D32.toInt()
                else         -> 0xFF1565C0.toInt()
            }
            row.findViewById<View>(R.id.h_status_dot).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            row.findViewById<TextView>(R.id.h_name).text   = svc.name
            row.findViewById<TextView>(R.id.h_domain).text =
                if (svc.publicUrl.isNotBlank()) "https://${svc.publicUrl}" else "(internal)"
            row.findViewById<TextView>(R.id.h_auth).text   = svc.auth.ifBlank { "—" }
            row.findViewById<TextView>(R.id.h_vm).text     = svc.vm

            if (svc.publicUrl.isNotBlank()) {
                row.setOnClickListener {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://${svc.publicUrl}")))
                    }
                }
            }
            table.addView(row)
        }
    }

    companion object {
        fun newInstance() = C3HealthFragment()
    }
}
