package com.diegonmarcos.superapp.network
import com.diegonmarcos.superapp.profile.ProfileFragment

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
 * Configs → WireGuard — Interface + a list of Peers form bound to
 * [WireGuardPrefs]. Auto-saves on every text change. Extra controls:
 * Generate Keypair (fills private + public), Import .conf (parses
 * upstream wg-quick format — multi-peer aware), Export .conf (writes
 * wg-quick), Connect/Disconnect Switch (drives [GoBackend]). Add Peer
 * / Remove buttons let the user grow or shrink the peer list at
 * runtime.
 *
 * Mirrors [ProfileFragment]'s programmatic UI shape — no XML layouts.
 */
class WireGuardFragment : Fragment() {

    private lateinit var prefs: WireGuardPrefs
    /** Shared process-wide GoBackend + Tunnel — see [WgState]. */
    private val goBackend: GoBackend? get() = context?.let { WgState.backend(it) }
    private val tunnel get() = WgState.tunnel

    /** Re-attach to redraw fields after structural changes (Generate,
     *  Import, Add Peer, Remove Peer). */
    private fun reattach() {
        parentFragmentManager.beginTransaction().detach(this).commitNow()
        parentFragmentManager.beginTransaction().attach(this).commitNow()
    }

    /** Read an imported .conf into prefs (multi-peer aware). */
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
        prefs = WgState.prefs(ctx)
        // Touch the backend so it's eagerly initialised — DevControl
        // queries getStatistics() against the same Tunnel object.
        WgState.backend(ctx)

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
        col.addView(caption(ctx, "Edit your tunnel — auto-saved on change. Use Generate Keypair to mint a fresh private+public, Import to load an existing .conf (multi-peer aware), or Connect to bring the tunnel up."))

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

        // ── Peers ────────────────────────────────────────────────────
        val peers = prefs.peers()
        for ((idx, p) in peers.withIndex()) {
            col.addView(peerCard(ctx, idx, p, peers))
        }
        col.addView(addPeerButton(ctx, peers))

        // ── Connect/Disconnect ──────────────────────────────────────
        // Status header carries a live colour dot (green = tunnel UP).
        val tunnelUp = (goBackend?.getState(tunnel) == Tunnel.State.UP)
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(sectionHeader(ctx, "Status"))
            addView(View(ctx).apply {
                val d = dp(ctx, 13)
                layoutParams = LinearLayout.LayoutParams(d, d).apply { leftMargin = dp(ctx, 10) }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (tunnelUp) 0xFF34C759.toInt() else 0xFF888888.toInt())
                }
            })
        })
        col.addView(Switch(ctx).apply {
            text = "Connect"
            isChecked = tunnelUp
            val pad = dp(ctx, 6); setPadding(pad, pad, pad, pad)
            setOnCheckedChangeListener { _, checked ->
                if (checked) requestConnect() else requestDisconnect()
            }
        })

        // ── Mesh — all infos folded onto this one page ───────────────
        // The canonical wg-mesh table (data/mesh.json) rendered inline via
        // the shared MeshView builder, so Configs → WireGuard is a single
        // page: config + import + status + the full mesh topology.
        col.addView(sectionHeader(ctx, "Mesh"))
        col.addView(caption(ctx, com.diegonmarcos.superapp.cloud.MeshView.statusText(ctx)))
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            com.diegonmarcos.superapp.cloud.MeshView.render(ctx, inflater, this)
        })

        return scroll
    }

    private var interfacePrivateKeyField: EditText? = null
    private var interfacePublicKeyView: TextView? = null

    /**
     * Renders one peer's full set of fields in a card-like container.
     * Edits mutate the in-memory `peers` list AND persist on every
     * keystroke. Remove button cuts the peer and reattaches.
     */
    private fun peerCard(
        ctx: android.content.Context,
        index: Int,
        peer: WireGuardPrefs.PeerData,
        peers: MutableList<WireGuardPrefs.PeerData>,
    ): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 10); setPadding(pad, pad, pad, pad)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(ctx, 16) }
            layoutParams = lp
            setBackgroundColor(0x22FFFFFF)
        }

        // Header row: "Peer N — <name>" + Remove button (right).
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(ctx).apply {
            text = "Peer ${index + 1}"
            setTextAppearance(android.R.style.TextAppearance_Material_Title)
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(TextView(ctx).apply {
            text = "Remove"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFB91C1C.toInt())
            setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
            isClickable = true; isFocusable = true
            setOnClickListener {
                peers.removeAt(index)
                prefs.savePeers(peers)
                toast("Removed peer ${index + 1}")
                reattach()
            }
        })
        card.addView(headerRow)

        card.addView(label(ctx, "Name (UI label, e.g. gcp-proxy)"))
        card.addView(peerField(ctx, peer.name, peers, index) { p, v -> p.copy(name = v) })

        card.addView(label(ctx, "Public key"))
        card.addView(peerField(ctx, peer.publicKey, peers, index) { p, v -> p.copy(publicKey = v) })

        card.addView(label(ctx, "Pre-shared key (optional)"))
        card.addView(peerField(ctx, peer.presharedKey, peers, index) { p, v -> p.copy(presharedKey = v) })

        card.addView(label(ctx, "Endpoint (host:port)"))
        card.addView(peerField(ctx, peer.endpoint, peers, index) { p, v -> p.copy(endpoint = v) })

        card.addView(label(ctx, "Allowed IPs (comma-separated)"))
        card.addView(peerField(ctx, peer.allowedIps, peers, index) { p, v -> p.copy(allowedIps = v) })

        card.addView(label(ctx, "Persistent keepalive (seconds, optional)"))
        card.addView(peerField(ctx, peer.persistentKeepalive, peers, index) { p, v -> p.copy(persistentKeepalive = v) }.apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        })

        return card
    }

    /**
     * Per-peer EditText. The `mutate` lambda gets the current
     * [WireGuardPrefs.PeerData] plus the new text value, and returns
     * an updated copy. The TextWatcher swaps it into the list and
     * persists on every keystroke.
     */
    private fun peerField(
        ctx: android.content.Context,
        initial: String,
        peers: MutableList<WireGuardPrefs.PeerData>,
        index: Int,
        mutate: (WireGuardPrefs.PeerData, String) -> WireGuardPrefs.PeerData,
    ): EditText = EditText(ctx).apply {
        setText(initial)
        setSingleLine()
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (index < peers.size) {
                    peers[index] = mutate(peers[index], s?.toString().orEmpty())
                    prefs.savePeers(peers)
                }
            }
        })
    }

    private fun addPeerButton(
        ctx: android.content.Context,
        peers: MutableList<WireGuardPrefs.PeerData>,
    ): View = TextView(ctx).apply {
        text = "+ Add Peer"
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xFF059669.toInt())
        gravity = android.view.Gravity.CENTER
        setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        isClickable = true; isFocusable = true
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 12) }
        layoutParams = lp
        setOnClickListener {
            peers.add(WireGuardPrefs.PeerData.EMPTY)
            prefs.savePeers(peers)
            toast("Added empty peer — fill it in")
            reattach()
        }
    }

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
        val iface = cfg.getInterface()
        prefs.interfacePrivateKey = iface.keyPair.privateKey.toBase64()
        prefs.interfaceAddress    = iface.addresses.joinToString(", ")
        prefs.interfaceDns        = iface.dnsServers.joinToString(", ") { it.hostAddress ?: "" }
        prefs.interfaceListenPort = iface.listenPort.map { it.toString() }.orElse("")
        prefs.interfaceMtu        = iface.mtu.map { it.toString() }.orElse("")

        val newPeers = cfg.peers.mapIndexed { i, p ->
            WireGuardPrefs.PeerData(
                name                = "peer-${i + 1}",
                publicKey           = p.publicKey.toBase64(),
                presharedKey        = p.preSharedKey.map { it.toBase64() }.orElse(""),
                endpoint            = p.endpoint.map { it.toString() }.orElse(""),
                allowedIps          = p.allowedIps.joinToString(", "),
                persistentKeepalive = p.persistentKeepalive.map { it.toString() }.orElse(""),
            )
        }
        prefs.savePeers(newPeers)
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

    companion object {
        fun newInstance(): WireGuardFragment = WireGuardFragment()
    }
}
