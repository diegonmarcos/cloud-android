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
 * Configs ▸ KDE page — driver for our SELF-CONTAINED KDE-Connect client
 * ([KdeConnectManager]). No org.kde app, no middleman: each declared device
 * (build.json::ui.kde_connect.devices) gets Connect → Pair → Ping over the
 * wg0 mesh, with live state. Pairing remembers the peer's TLS cert; reconnects
 * are cert-pinned.
 */
class KdeConnectFragment : Fragment(), KdeConnectManager.Listener {

    private class Row(
        val device: KdeConnectConfig.Device,
        val status: TextView,
        val connect: Button,
        val pair: Button,
        val ping: Button,
        val unpair: Button,
        var deviceId: String? = null,
    )

    private val rows = mutableListOf<Row>()

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        KdeConnectManager.init(ctx)
        val cfg = KdeConnectConfig.get()

        val scroll = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20); setPadding(pad, pad, pad, pad)
        }
        scroll.addView(root)

        root.addView(TextView(ctx).apply {
            text = "KDE Connect"
            textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt()); setPadding(0, 0, 0, dp(2))
        })
        root.addView(TextView(ctx).apply {
            text = "Self-contained client · this device = ${KdeIdentity.deviceName(ctx)} " +
                "(${KdeConnectManager.ownDeviceId().take(14)}…)"
            setTextColor(0x99FFFFFF.toInt()); textSize = 12f; setPadding(0, 0, 0, dp(16))
        })

        if (cfg.devices.isEmpty()) {
            root.addView(TextView(ctx).apply {
                text = "No devices declared in build.json::ui.kde_connect.devices"
                setTextColor(0xFFFF8B8B.toInt())
            })
        }
        for (device in cfg.devices) root.addView(buildCard(ctx, device))

        // ── Probe section — wg0 / hosts / peers / LAN reachability ──────────
        root.addView(TextView(ctx).apply {
            text = "Probe"
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt()); setPadding(0, dp(8), 0, dp(4))
        })
        val probeBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(Button(ctx).apply {
            text = "Probe wg0 · hosts · peers · LAN"; isAllCaps = false; textSize = 12f
            setOnClickListener { runProbe(probeBox) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        root.addView(probeBox)

        // Explicit hand-off to the official client (separate from our own
        // self-contained pairing) — opens it if installed, else a toast.
        root.addView(Button(ctx).apply {
            text = "Open KDE Connect (App)"; isAllCaps = false; textSize = 12f
            setOnClickListener { openClient(cfg.pkg) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        })

        root.addView(TextView(ctx).apply {
            text = "Connect dials <device>:${cfg.discoveryPort} over wg0, runs the KDE Connect " +
                "TLS handshake (we are the TLS server), and re-exchanges identity encrypted. " +
                "Pair once to trust the device's certificate; later connections are cert-pinned."
            setTextColor(0x77FFFFFF.toInt()); textSize = 11f
            gravity = Gravity.START; setPadding(0, dp(16), 0, 0)
        })
        return scroll
    }

    private fun buildCard(ctx: android.content.Context, device: KdeConnectConfig.Device): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(14); setPadding(p, p, p, p)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(0x22FFFFFF)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
        card.addView(TextView(ctx).apply {
            text = "${device.label}  ·  ${device.wgIp}:${KdeConnectConfig.get().discoveryPort}"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        })
        val status = TextView(ctx).apply {
            text = if (KdeConnectManager.pairedDeviceIds().isNotEmpty()) "paired (tap Connect)" else "not connected"
            setTextColor(0xFFE9D8FD.toInt()); textSize = 12.5f; typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(10))
        }
        card.addView(status)

        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun mkBtn(label: String): Button = Button(ctx).apply {
            text = label; isAllCaps = false; textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val connect = mkBtn("Connect")
        val pair = mkBtn("Pair").apply { isEnabled = false }
        val ping = mkBtn("Ping").apply { isEnabled = false }
        val unpair = mkBtn("Unpair")
        btnRow.addView(connect); btnRow.addView(pair); btnRow.addView(ping); btnRow.addView(unpair)
        card.addView(btnRow)

        val row = Row(device, status, connect, pair, ping, unpair)
        rows += row

        connect.setOnClickListener {
            status.text = "connecting…"
            // Direct dial over wg0 (the Surface does NOT dial back to a UDP
            // nudge — proven by live probing); result arrives via onState.
            viewLifecycleOwner.lifecycleScope.launch {
                KdeConnectManager.connect(device.wgIp, KdeConnectConfig.get().discoveryPort)
                    .onFailure { status.text = "✗ ${it.message}" }
            }
        }
        pair.setOnClickListener {
            val id = row.deviceId ?: return@setOnClickListener
            if (!KdeConnectManager.requestPair(id)) toast("Not connected")
            else status.text = "pair request sent — accept on ${device.label}"
        }
        ping.setOnClickListener {
            val id = row.deviceId ?: return@setOnClickListener
            if (!KdeConnectManager.sendPing(id, "Ping from ${KdeIdentity.deviceName(requireContext())}"))
                toast("Not connected")
        }
        unpair.setOnClickListener {
            row.deviceId?.let { KdeConnectManager.unpair(it) }
        }
        return card
    }

    /** Run the read-only reachability probe and render its step trace. */
    private fun runProbe(box: LinearLayout) {
        val ctx = requireContext()
        box.removeAllViews()
        box.addView(TextView(ctx).apply {
            text = "• probing…"; setTextColor(0xCCFFFFFF.toInt())
            typeface = Typeface.MONOSPACE; textSize = 12.5f
        })
        viewLifecycleOwner.lifecycleScope.launch {
            val steps = KdeConnectDiscovery.probeAll(ctx)
            box.removeAllViews()
            for (s in steps) box.addView(stepRow(s))
        }
    }

    private fun stepRow(step: KdeConnectDiscovery.Step): View {
        val mark = when (step.ok) { true -> "✓"; false -> "✗"; null -> "•" }
        val color = when (step.ok) {
            true -> 0xFF8BE9A0.toInt(); false -> 0xFFFF8B8B.toInt(); null -> 0xCCFFFFFF.toInt()
        }
        return TextView(requireContext()).apply {
            text = "$mark  ${step.label}: ${step.detail}"
            setTextColor(color); typeface = Typeface.MONOSPACE; textSize = 12.5f
            setPadding(0, dp(3), 0, dp(3))
        }
    }

    /** Open the installed official client, or toast if absent. */
    private fun openClient(pkg: String) {
        val intent = requireContext().packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else toast("KDE Connect ($pkg) is not installed")
    }

    override fun onResume() {
        super.onResume()
        KdeConnectManager.listener = this
    }

    override fun onPause() {
        super.onPause()
        if (KdeConnectManager.listener === this) KdeConnectManager.listener = null
    }

    /** State callbacks arrive on the main thread (manager posts them). Keyed on
     *  the peer wg IP ([host]) — known from the first CONNECTING event — and on
     *  the discovered deviceId once the handshake yields one. */
    override fun onState(deviceId: String, host: String?, state: KdeConnectManager.State, detail: String) {
        val row = rows.firstOrNull { host != null && it.device.wgIp == host }
            ?: rows.firstOrNull { deviceId.isNotBlank() && it.deviceId == deviceId }
            ?: return
        if (deviceId.isNotBlank()) row.deviceId = deviceId
        val paired = row.deviceId?.let { KdeConnectManager.isPaired(it) } == true
        row.status.text = when (state) {
            KdeConnectManager.State.CONNECTING   -> "connecting… ($detail)"
            KdeConnectManager.State.HANDSHAKING  -> "TLS handshake…"
            KdeConnectManager.State.NEEDS_PAIRING -> "✓ connected to $detail — not paired"
            KdeConnectManager.State.PAIRED       -> "✓ paired & connected — $detail"
            KdeConnectManager.State.DISCONNECTED -> "disconnected ($detail)"
            KdeConnectManager.State.ERROR        -> "✗ $detail"
        }
        val connected = state == KdeConnectManager.State.NEEDS_PAIRING ||
            state == KdeConnectManager.State.PAIRED
        row.pair.isEnabled = connected && !paired
        row.ping.isEnabled = state == KdeConnectManager.State.PAIRED
        row.unpair.isEnabled = paired
    }

    private fun toast(m: String) = Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = KdeConnectFragment() }
}
