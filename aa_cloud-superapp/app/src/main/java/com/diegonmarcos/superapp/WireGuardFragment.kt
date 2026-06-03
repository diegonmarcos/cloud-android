package com.diegonmarcos.superapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Configs → WireGuard — Interface + Peer form bound to [WireGuardPrefs].
 * Auto-saves on every text change. Extra controls: Generate Keypair
 * (fills private + public), Import .conf (parses upstream wg-quick
 * format), Export .conf (writes wg-quick), Connect/Disconnect Switch
 * (drives [GoBackend]).
 *
 * Mirrors [ProfileFragment]'s programmatic UI shape — no XML layouts.
 */
class WireGuardFragment : Fragment() {

    private lateinit var prefs: WireGuardPrefs
    private var goBackend: GoBackend? = null
    private val tunnel by lazy { WgTunnel { prefs.tunnelName.ifBlank { "wg-mesh" } } }

    /** Re-attach to redraw fields after Generate / Import. */
    private fun reattach() {
        parentFragmentManager.beginTransaction().detach(this).commitNow()
        parentFragmentManager.beginTransaction().attach(this).commitNow()
    }

    /** Read an imported .conf into prefs. */
    private val importLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                    val cfg = Config.parse(BufferedReader(InputStreamReader(stream)))
                    hydrateFromConfig(cfg)
                }
            }.onFailure { t ->
                toast("Import failed: ${t.message}")
            }.onSuccess {
                toast("Imported .conf")
                reattach()
            }
        }

    /** Save current state to a user-chosen .conf via Storage Access Framework. */
    private val exportLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                val cfg = prefs.toWgConfig()
                requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(cfg.toWgQuickString().toByteArray())
                }
            }.onFailure { t ->
                toast("Export failed: ${t.message}")
            }.onSuccess {
                toast("Exported .conf")
            }
        }

    /** First-ever Connect needs Android's VPN-consent dialog. */
    private val vpnConsentLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) bringTunnelUp() else {
                prefs.tunnelEnabled = false
                toast("VPN consent denied")
                reattach()
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        prefs = WireGuardPrefs(ctx)
        if (goBackend == null) goBackend = GoBackend(ctx.applicationContext)

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 18); setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        scroll.addView(col)

        col.addView(sectionHeader(ctx, "WireGuard"))
        col.addView(caption(ctx, "Edit your tunnel — auto-saved on change. Use Generate Keypair to mint a fresh private+public, Import to paste an existing .conf, or Connect to bring the tunnel up."))

        col.addView(label(ctx, "Tunnel name (≤15 chars: A-Z a-z 0-9 _ = + . -)"))
        col.addView(field(ctx, prefs.tunnelName) { prefs.tunnelName = it }.apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(Tunnel.NAME_MAX_LENGTH))
        })

        // ── Interface ────────────────────────────────────────────────
        col.addView(sectionHeader(ctx, "Interface"))

        col.addView(label(ctx, "Private key (base64, 32 bytes)"))
        col.addView(field(ctx, prefs.interfacePrivateKey) {
            prefs.interfacePrivateKey = it
            updatePublicKeyView()
        }.also { interfacePrivateKeyField = it })

        col.addView(label(ctx, "Public key (derived from private key — read-only)"))
        col.addView(readonly(ctx, prefs.derivedInterfacePublicKey()).also { interfacePublicKeyView = it })

        col.addView(rowOfButtons(ctx,
            "Generate Keypair" to { generateKeypair() },
            "Import .conf"     to { importLauncher.launch("*/*") },
            "Export .conf"     to { exportLauncher.launch("${prefs.tunnelName.ifBlank { "wg" }}.conf") },
        ))

        col.addView(label(ctx, "Address (CIDR, e.g. 10.0.0.6/32)"))
        col.addView(field(ctx, prefs.interfaceAddress) { prefs.interfaceAddress = it })

        col.addView(label(ctx, "DNS"))
        col.addView(field(ctx, prefs.interfaceDns) { prefs.interfaceDns = it })

        col.addView(label(ctx, "Listen port (optional)"))
        col.addView(field(ctx, prefs.interfaceListenPort) { prefs.interfaceListenPort = it }.apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        })

        col.addView(label(ctx, "MTU (optional, default 1420)"))
        col.addView(field(ctx, prefs.interfaceMtu) { prefs.interfaceMtu = it }.apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        })

        // ── Peer ─────────────────────────────────────────────────────
        col.addView(sectionHeader(ctx, "Peer"))

        col.addView(label(ctx, "Public key"))
        col.addView(field(ctx, prefs.peerPublicKey) { prefs.peerPublicKey = it })

        col.addView(label(ctx, "Pre-shared key (optional)"))
        col.addView(field(ctx, prefs.peerPresharedKey) { prefs.peerPresharedKey = it })

        col.addView(label(ctx, "Endpoint (host:port)"))
        col.addView(field(ctx, prefs.peerEndpoint) { prefs.peerEndpoint = it })

        col.addView(label(ctx, "Allowed IPs (comma-separated)"))
        col.addView(field(ctx, prefs.peerAllowedIps) { prefs.peerAllowedIps = it })

        col.addView(label(ctx, "Persistent keepalive (seconds, optional)"))
        col.addView(field(ctx, prefs.peerPersistentKeepalive) { prefs.peerPersistentKeepalive = it }.apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        })

        // ── Connect/Disconnect ──────────────────────────────────────
        col.addView(sectionHeader(ctx, "Status"))
        col.addView(Switch(ctx).apply {
            text = "Connect"
            isChecked = (goBackend?.getState(tunnel) == Tunnel.State.UP)
            val pad = dp(ctx, 6); setPadding(pad, pad, pad, pad)
            setOnCheckedChangeListener { _, checked ->
                if (checked) requestConnect() else requestDisconnect()
            }
        })

        return scroll
    }

    private var interfacePrivateKeyField: EditText? = null
    private var interfacePublicKeyView: TextView? = null

    private fun updatePublicKeyView() {
        interfacePublicKeyView?.text = prefs.derivedInterfacePublicKey()
    }

    private fun generateKeypair() {
        val kp = KeyPair()
        prefs.interfacePrivateKey = kp.privateKey.toBase64()
        interfacePrivateKeyField?.setText(prefs.interfacePrivateKey)
        updatePublicKeyView()
        toast("Generated keypair")
    }

    private fun hydrateFromConfig(cfg: Config) {
        // Call getters explicitly — Kotlin's `interface` keyword needs
        // backticks for property access, the explicit form is cleaner.
        val iface = cfg.getInterface()
        prefs.interfacePrivateKey = iface.keyPair.privateKey.toBase64()
        prefs.interfaceAddress    = iface.addresses.joinToString(", ")
        prefs.interfaceDns        = iface.dnsServers.joinToString(", ") { it.hostAddress ?: "" }
        prefs.interfaceListenPort = iface.listenPort.map { it.toString() }.orElse("")
        prefs.interfaceMtu        = iface.mtu.map { it.toString() }.orElse("")

        val peer = cfg.peers.firstOrNull() ?: return
        prefs.peerPublicKey            = peer.publicKey.toBase64()
        prefs.peerPresharedKey         = peer.preSharedKey.map { it.toBase64() }.orElse("")
        prefs.peerEndpoint             = peer.endpoint.map { it.toString() }.orElse("")
        prefs.peerAllowedIps           = peer.allowedIps.joinToString(", ")
        prefs.peerPersistentKeepalive  = peer.persistentKeepalive.map { it.toString() }.orElse("")
    }

    private fun requestConnect() {
        val intent = GoBackend.VpnService.prepare(requireContext().applicationContext)
        if (intent != null) {
            vpnConsentLauncher.launch(intent)
        } else {
            bringTunnelUp()
        }
    }

    private fun bringTunnelUp() {
        runCatching {
            val cfg = prefs.toWgConfig()
            goBackend?.setState(tunnel, Tunnel.State.UP, cfg)
            prefs.tunnelEnabled = true
        }.onFailure { t ->
            prefs.tunnelEnabled = false
            toast("Connect failed: ${t.message}")
            reattach()
        }
    }

    private fun requestDisconnect() {
        runCatching {
            goBackend?.setState(tunnel, Tunnel.State.DOWN, null)
            prefs.tunnelEnabled = false
        }.onFailure { t ->
            toast("Disconnect failed: ${t.message}")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    // ── widget factories ──

    private fun rowOfButtons(ctx: android.content.Context, vararg pairs: Pair<String, () -> Unit>): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dp(ctx, 6); setPadding(0, pad, 0, pad)
        }
        for ((idx, p) in pairs.withIndex()) {
            val btn = TextView(ctx).apply {
                text = p.first
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF7C3AED.toInt())
                setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
                gravity = android.view.Gravity.CENTER
                isClickable = true; isFocusable = true
                setOnClickListener { p.second() }
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (idx > 0) leftMargin = dp(ctx, 6)
            }
            btn.layoutParams = lp
            row.addView(btn)
        }
        return row
    }

    private fun sectionHeader(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setPadding(0, dp(ctx, 16), 0, dp(ctx, 4))
        }

    private fun label(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            alpha = 0.85f
            setPadding(0, dp(ctx, 12), 0, dp(ctx, 4))
        }

    private fun caption(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            alpha = 0.55f
            setPadding(0, 0, 0, dp(ctx, 8))
        }

    private fun field(ctx: android.content.Context, initial: String, save: (String) -> Unit): EditText =
        EditText(ctx).apply {
            setText(initial)
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { save(s?.toString().orEmpty()) }
            })
        }

    private fun readonly(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(0xFFDDDDDD.toInt())
            setPadding(dp(ctx, 8), dp(ctx, 10), dp(ctx, 8), dp(ctx, 10))
            setBackgroundColor(0x33000000)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    /** Minimal Tunnel impl — name from prefs, no callbacks needed. */
    private class WgTunnel(val nameProvider: () -> String) : Tunnel {
        override fun getName(): String = nameProvider()
        override fun onStateChange(newState: Tunnel.State) = Unit
    }

    companion object {
        fun newInstance(): WireGuardFragment = WireGuardFragment()
    }
}
