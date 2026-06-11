package com.diegonmarcos.superapp.kdeconnect

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Configs > KDE page. Surfaces the wg0-preferred KDE Connect link to the
 * Surface Pro and hands pairing off to the installed official client.
 *
 * On open it auto-probes the Surface over wg0; the "Connect" button re-runs
 * discovery and launches org.kde.kdeconnect_tp (which finishes pairing — over
 * the tunnel when reachable, or via normal LAN broadcast when it isn't). All
 * device/IP/port data comes from build.json::ui.kde_connect via
 * [KdeConnectConfig] — nothing here is hardcoded.
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
            text = "Connect over wg0"
            isAllCaps = false
            setOnClickListener { runDiscovery(launchOnResult = true) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        })

        root.addView(TextView(ctx).apply {
            text = "Prefers the WireGuard route to the Surface; falls back to " +
                "normal LAN discovery when the tunnel is down. Pairing completes " +
                "in the KDE Connect app."
            setTextColor(0x77FFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.START
            setPadding(0, dp(16), 0, 0)
        })

        // Auto-probe on open so the status reflects reality without a tap.
        runDiscovery(launchOnResult = false)
        return root
    }

    private fun runDiscovery(launchOnResult: Boolean) {
        statusView.text = "Probing the Surface over wg0…"
        viewLifecycleOwner.lifecycleScope.launch {
            val result = KdeConnectDiscovery.discover()
            val cfg = KdeConnectConfig.get()
            when (result) {
                is KdeConnectDiscovery.Result.Wg0Reachable -> {
                    statusView.text = "✓ Reachable over wg0 — ${result.device.label} (${result.device.wgIp})"
                    if (launchOnResult) launchClient(cfg.pkg, "Opening KDE Connect (wg0 link nudged)")
                }
                KdeConnectDiscovery.Result.LanFallback -> {
                    statusView.text = "wg0 tunnel down — using LAN discovery"
                    if (launchOnResult) launchClient(cfg.pkg, "Opening KDE Connect (LAN)")
                }
                KdeConnectDiscovery.Result.Unreachable -> {
                    statusView.text = "Surface unreachable and LAN fallback is off"
                    if (launchOnResult) toast("Surface unreachable")
                }
            }
        }
    }

    private fun launchClient(pkg: String, note: String) {
        val intent = requireContext().packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            toast(note)
        } else {
            toast("KDE Connect ($pkg) is not installed")
        }
    }

    private fun toast(m: String) = Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = KdeConnectFragment() }
}
