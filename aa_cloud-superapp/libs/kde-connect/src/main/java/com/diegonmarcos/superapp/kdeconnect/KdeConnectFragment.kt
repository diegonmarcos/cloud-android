package com.diegonmarcos.superapp.kdeconnect

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Configs > KDE page. Self-contained — finds the Surface Pro over the native
 * wg0 tunnel (preferred), then the default route, then a LAN broadcast. Does
 * NOT depend on or launch the installed org.kde.kdeconnect_tp app.
 *
 * All device/IP/port data comes from build.json::ui.kde_connect via
 * [KdeConnectConfig]; nothing here is hardcoded. Actual TLS pairing + plugin
 * messaging arrive with the upstream vendor phase.
 */
class KdeConnectFragment : Fragment() {

    private lateinit var statusView: TextView

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val cfg = KdeConnectConfig.get()
        val device = cfg.primaryDevice

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20); setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        root.addView(TextView(ctx).apply {
            text = "KDE Connect"
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(ctx).apply {
            text = if (device != null)
                "${device.label} · ${device.wgIp}:${cfg.discoveryPort} (wg0)"
            else "No device declared in build.json::ui.kde_connect"
            setTextColor(0x99FFFFFF.toInt())
            textSize = 13f
            setPadding(0, 0, 0, dp(20))
        })

        statusView = TextView(ctx).apply {
            text = "Checking the tunnel…"
            setTextColor(0xFFE9D8FD.toInt())
            textSize = 15f
            setPadding(0, 0, 0, dp(20))
        }
        root.addView(statusView)

        root.addView(Button(ctx).apply {
            text = "Find Surface over wg0"
            isAllCaps = false
            setOnClickListener { runDiscovery() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        })

        root.addView(TextView(ctx).apply {
            text = "Binds to the native WireGuard interface so the probe leaves " +
                "over wg0 (source IP the Surface can answer); falls back to the " +
                "default route, then a LAN broadcast. Self-contained — no " +
                "external app."
            setTextColor(0x77FFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.START
            setPadding(0, dp(16), 0, 0)
        })

        // Auto-probe on open so the status reflects reality without a tap.
        runDiscovery()
        return root
    }

    private fun runDiscovery() {
        statusView.text = "Probing the Surface over wg0…"
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = KdeConnectDiscovery.discover(requireContext())) {
                is KdeConnectDiscovery.Result.Wg0Reachable -> {
                    val via = if (result.viaVpnBind) "wg0 (VPN-bound)" else "wg0 (default route)"
                    statusView.text = "✓ Surface found over $via — ${result.device.label} (${result.device.wgIp})"
                }
                KdeConnectDiscovery.Result.LanFallback -> {
                    statusView.text = "Surface not reachable over wg0 — broadcast an identity on the LAN"
                }
                KdeConnectDiscovery.Result.Unreachable -> {
                    statusView.text = "Surface unreachable (wg0 down and LAN fallback off)"
                }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = KdeConnectFragment() }
}
