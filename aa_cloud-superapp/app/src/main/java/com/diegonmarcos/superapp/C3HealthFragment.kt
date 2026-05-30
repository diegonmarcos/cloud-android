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
 * C3 · Health — two fully separate tables fed from the data/ snapshots:
 *   Public  services  (data/services_public.json,  32 containers today)
 *   Private services  (data/services_private.json, 52 containers today)
 *
 * Both come from cloud-data's _cloud-data-consolidated.json via
 * data/regen.sh — the upstream is the single source of truth. Public
 * rows are tappable (opens the URL); private rows are display-only since
 * they're internal compose-network targets.
 */
class C3HealthFragment : Fragment(R.layout.fragment_c3_health) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val root    = view.findViewById<LinearLayout>(R.id.health_root)
        val status  = view.findViewById<TextView>(R.id.health_status)
        val spinner = view.findViewById<ProgressBar>(R.id.health_loading)
        spinner.isVisible = false

        val pub  = Sections.publicServices()
        val priv = Sections.privateServices()
        status.text = getString(R.string.c3_health_status_split, pub.size, priv.size)

        val inflater = LayoutInflater.from(requireContext())

        if (pub.isNotEmpty()) {
            addSectionHeader(root, getString(R.string.c3_health_section_public, pub.size))
            for (svc in pub) {
                val row = inflater.inflate(R.layout.item_c3_health_row, root, false)
                row.findViewById<View>(R.id.h_status_dot).background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF2E7D32.toInt())   // green = public
                }
                row.findViewById<TextView>(R.id.h_name).text    = svc.name
                row.findViewById<TextView>(R.id.h_auth).text    = "auth: ${svc.auth.ifBlank { "—" }}"
                row.findViewById<TextView>(R.id.h_domain).text  = "🌐 https://${svc.publicUrl}"
                row.findViewById<TextView>(R.id.h_private).text =
                    if (svc.privateDns.isNotBlank()) "🔒 ${svc.privateDns}" else "—"
                row.findViewById<TextView>(R.id.h_vm).text      = svc.vm
                row.setOnClickListener {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://${svc.publicUrl}")))
                    }
                }
                root.addView(row)
            }
        }

        if (priv.isNotEmpty()) {
            addSectionHeader(root, getString(R.string.c3_health_section_private, priv.size))
            for (svc in priv) {
                val row = inflater.inflate(R.layout.item_c3_health_row, root, false)
                row.findViewById<View>(R.id.h_status_dot).background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF1565C0.toInt())   // blue = private
                }
                row.findViewById<TextView>(R.id.h_name).text    = svc.name
                row.findViewById<TextView>(R.id.h_auth).text    = buildString {
                    append(svc.protocol)
                    if (svc.dbEngine.isNotBlank()) { append(" · "); append(svc.dbEngine) }
                }
                row.findViewById<TextView>(R.id.h_domain).text  = svc.service.ifBlank { "—" }
                row.findViewById<TextView>(R.id.h_private).text = "🔒 ${svc.privateDns}"
                row.findViewById<TextView>(R.id.h_vm).text      = svc.vm
                root.addView(row)
            }
        }
    }

    private fun addSectionHeader(parent: LinearLayout, label: String) {
        val tv = TextView(requireContext()).apply {
            text = label
            setTextAppearance(android.R.style.TextAppearance_Material_Title)
            setTextColor(resources.getColor(R.color.cloud_primary, requireContext().theme))
            val pad = (10 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad * 2, pad, pad / 2)
        }
        parent.addView(tv)
    }

    companion object { fun newInstance() = C3HealthFragment() }
}
