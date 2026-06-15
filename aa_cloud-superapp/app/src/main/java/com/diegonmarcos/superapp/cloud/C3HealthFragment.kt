package com.diegonmarcos.superapp.cloud
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.launcher.AggregatorStackFragment
import com.diegonmarcos.superapp.R

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
 * C3 · Health — fed from data/services_*.json snapshots
 * (data/regen.sh derives them from cloud-data's
 * _cloud-data-consolidated.json).
 *
 * `scope` controls which tables render:
 *   - "all"     → both Public and Private (default — standalone page)
 *   - "public"  → only Public
 *   - "private" → only Private
 *
 * Used standalone (drawer / page:c3/health) AND embedded inside
 * [AggregatorStackFragment] cards where each card is a single-scope view.
 */
class C3HealthFragment : Fragment(R.layout.fragment_c3_health) {

    private val scope: String
        get() = arguments?.getString(ARG_SCOPE) ?: SCOPE_ALL

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val root    = view.findViewById<LinearLayout>(R.id.health_root)
        val status  = view.findViewById<TextView>(R.id.health_status)
        val spinner = view.findViewById<ProgressBar>(R.id.health_loading)
        spinner.isVisible = false

        val showPub  = scope == SCOPE_ALL || scope == SCOPE_PUBLIC
        val showPriv = scope == SCOPE_ALL || scope == SCOPE_PRIVATE

        val pub  = if (showPub)  Sections.publicServices()  else emptyList()
        val priv = if (showPriv) Sections.privateServices() else emptyList()

        status.text = when (scope) {
            SCOPE_PUBLIC  -> getString(R.string.c3_health_section_public,  pub.size)
            SCOPE_PRIVATE -> getString(R.string.c3_health_section_private, priv.size)
            else          -> getString(R.string.c3_health_status_split, pub.size, priv.size)
        }
        // When embedded as a single-scope card the bare status line above
        // a section header is redundant — hide it.
        status.isVisible = scope == SCOPE_ALL

        val inflater = LayoutInflater.from(requireContext())

        if (pub.isNotEmpty()) {
            if (scope == SCOPE_ALL) {
                addSectionHeader(root, getString(R.string.c3_health_section_public, pub.size))
            }
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
            if (scope == SCOPE_ALL) {
                addSectionHeader(root, getString(R.string.c3_health_section_private, priv.size))
            }
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

    companion object {
        const val SCOPE_ALL     = "all"
        const val SCOPE_PUBLIC  = "public"
        const val SCOPE_PRIVATE = "private"
        private const val ARG_SCOPE = "scope"

        fun newInstance(scope: String = SCOPE_ALL): C3HealthFragment =
            C3HealthFragment().apply {
                arguments = Bundle().apply { putString(ARG_SCOPE, scope) }
            }
    }
}
