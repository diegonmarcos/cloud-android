package com.diegonmarcos.superapp.kdeconnect

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Configs > KDE page. Discovery is self-contained — finds the Surface Pro over
 * the native wg0 tunnel (preferred), then the default route, then a LAN
 * broadcast, with no dependency on the official app. A separate, explicit
 * "Open KDE-Connect (App)" button hands off to the installed client only when
 * the user chooses to.
 *
 * Renders the full diagnostic trace from [KdeConnectDiscovery.diagnose] so the
 * wg0 detection, the ping RTT, and the route actually used are all visible.
 * TLS pairing + plugin messaging arrive with the upstream vendor phase.
 */
class KdeConnectFragment : Fragment() {

    private lateinit var headline: TextView
    private lateinit var trace: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val cfg = KdeConnectConfig.get()
        val device = cfg.primaryDevice

        val scroll = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20); setPadding(pad, pad, pad, pad)
        }
        scroll.addView(root)

        root.addView(TextView(ctx).apply {
            text = "KDE Connect"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(ctx).apply {
            text = if (device != null)
                "${device.label} · ${device.wgIp}:${cfg.discoveryPort} (wg0) · " +
                    "preferWg0=${cfg.preferWg0} lanFallback=${cfg.lanFallback}"
            else "No device declared in build.json::ui.kde_connect"
            setTextColor(0x99FFFFFF.toInt())
            textSize = 12f
            setPadding(0, 0, 0, dp(16))
        })

        headline = TextView(ctx).apply {
            text = "Probing…"
            setTextColor(0xFFE9D8FD.toInt())
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(headline)

        trace = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        root.addView(trace)

        root.addView(Button(ctx).apply {
            text = "Re-scan over wg0"
            isAllCaps = false
            setOnClickListener { runDiscovery() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        })
        // Explicit, user-chosen hand-off to the official client (distinct from
        // discovery, which stays self-contained). Opens it if installed.
        root.addView(Button(ctx).apply {
            text = "Open KDE-Connect (App)"
            isAllCaps = false
            setOnClickListener { openClient(cfg.pkg) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        })
        root.addView(TextView(ctx).apply {
            text = "Binds to the native WireGuard (VPN) interface so the probe " +
                "leaves over wg0 with a source the Surface can answer; then the " +
                "default route; then a LAN broadcast. Self-contained — no external app."
            setTextColor(0x77FFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.START
            setPadding(0, dp(16), 0, 0)
        })

        runDiscovery()
        return scroll
    }

    private fun runDiscovery() {
        headline.text = "Probing the Surface over wg0…"
        trace.removeAllViews()
        viewLifecycleOwner.lifecycleScope.launch {
            val report = KdeConnectDiscovery.diagnose(requireContext())
            headline.text = when (val r = report.result) {
                is KdeConnectDiscovery.Result.Wg0Reachable ->
                    "✓ Found over " + if (r.viaVpnBind) "wg0 (VPN-bound)" else "wg0 (default route)"
                KdeConnectDiscovery.Result.LanFallback ->
                    "wg0 unreachable — fell back to LAN broadcast"
                KdeConnectDiscovery.Result.Unreachable ->
                    "Surface unreachable"
            }
            trace.removeAllViews()
            for (step in report.steps) trace.addView(stepRow(step))
        }
    }

    private fun stepRow(step: KdeConnectDiscovery.Step): View {
        val ctx = requireContext()
        val mark = when (step.ok) { true -> "✓"; false -> "✗"; null -> "•" }
        val color = when (step.ok) {
            true  -> 0xFF8BE9A0.toInt()
            false -> 0xFFFF8B8B.toInt()
            null  -> 0xCCFFFFFF.toInt()
        }
        return TextView(ctx).apply {
            text = "$mark  ${step.label}: ${step.detail}"
            setTextColor(color)
            typeface = Typeface.MONOSPACE
            textSize = 12.5f
            setPadding(0, dp(3), 0, dp(3))
        }
    }

    /** Launch the installed official client, or toast if it's absent. */
    private fun openClient(pkg: String) {
        val intent = requireContext().packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            Toast.makeText(requireContext(), "KDE Connect ($pkg) is not installed",
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = KdeConnectFragment() }
}
