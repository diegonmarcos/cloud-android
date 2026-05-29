package com.diegonmarcos.superapp

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * C3 / Health — live probe table sourced from the cloud-data repo's
 * _cloud-data-consolidated.json (via [CloudData]). One row per service
 * with status dot · name · public URL (+ auth method) · VM.
 *
 *  • Enabled services get a green status dot, disabled red, missing data grey
 *  • Public URL pulled from `service.proxy.primary.domain`
 *  • Auth method shown as a small subtitle (e.g. "two_factor", "none")
 *  • VM = `service.vm` (oci-mail / oci-apps / gcp-proxy / …)
 *
 * Future: tap a row → opens the URL in browser, or fires an HTTP HEAD
 * probe and updates the dot live. For now display-only.
 */
class C3HealthFragment : Fragment(R.layout.fragment_c3_health) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val table   = view.findViewById<LinearLayout>(R.id.health_table)
        val status  = view.findViewById<TextView>(R.id.health_status)
        val spinner = view.findViewById<ProgressBar>(R.id.health_loading)

        spinner.isVisible = true
        status.setText(R.string.c3_health_loading)

        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    Result.success(CloudData.load(ctx))
                } catch (t: Throwable) {
                    Result.failure<JSONObject>(t)
                }
            }
            spinner.isVisible = false
            outcome.fold(
                onSuccess = { root ->
                    val services = CloudData.services(root)
                    renderTable(table, status, services)
                },
                onFailure = { t ->
                    status.text = getString(R.string.c3_health_error, t.message ?: "?")
                },
            )
        }
    }

    private fun renderTable(container: LinearLayout, status: TextView, services: JSONObject) {
        container.removeAllViews()
        val names = services.keys().asSequence().toList().sorted()
        if (names.isEmpty()) {
            status.setText(R.string.c3_health_empty)
            return
        }
        val enabledCount = names.count { services.optJSONObject(it)?.optBoolean("enabled", false) == true }
        status.text = getString(R.string.c3_health_status, enabledCount, names.size)

        val inflater = LayoutInflater.from(requireContext())
        for (name in names) {
            val svc = services.optJSONObject(name) ?: continue
            val proxy = svc.optJSONObject("proxy")?.optJSONObject("primary")
            val domain = proxy?.optString("domain", "") ?: ""
            val auth   = proxy?.optString("auth", "") ?: ""
            val vm     = svc.optString("vm", "")
            val enabled = svc.optBoolean("enabled", false)
            val isPublic = domain.isNotBlank()

            val row = inflater.inflate(R.layout.item_c3_health_row, container, false)

            // Status dot — green=public enabled, blue=private enabled, grey=disabled
            val dot = row.findViewById<View>(R.id.h_status_dot)
            val color = when {
                !enabled  -> 0xFF9E9E9E.toInt()                 // grey  — disabled
                isPublic  -> 0xFF2E7D32.toInt()                 // green — public + on
                else      -> 0xFF1565C0.toInt()                 // blue  — private + on
            }
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }

            row.findViewById<TextView>(R.id.h_name).text   = name
            row.findViewById<TextView>(R.id.h_domain).text = if (domain.isNotBlank()) "https://$domain" else "(internal)"
            row.findViewById<TextView>(R.id.h_auth).text   = auth.ifBlank { "—" }
            row.findViewById<TextView>(R.id.h_vm).text     = vm

            container.addView(row)
        }
    }

    companion object {
        fun newInstance() = C3HealthFragment()
    }
}
