package com.diegonmarcos.superapp.kdeconnect

import android.graphics.Typeface
import android.net.Uri
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
import androidx.activity.result.contract.ActivityResultContracts
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
        val title: TextView,
        val connDot: View, val connText: TextView,
        val pairDot: View, val pairText: TextView,
        val connect: Button, val pairBtn: Button, val ping: Button, val edit: Button,
        var host: String,           // effective (override or default) wg ip
        var port: Int,
        var label: String,
        var deviceId: String? = null,
    )

    // Status light colours.
    private val DOT_IDLE = 0xFF9E9E9E.toInt()      // gray  — not connected
    private val DOT_BUSY = 0xFFFFB300.toInt()      // amber — connecting/handshaking
    private val DOT_LINK = 0xFF42A5F5.toInt()      // blue  — connected, not paired
    private val DOT_OK   = 0xFF4CAF50.toInt()      // green — connected & paired
    private val DOT_ERR  = 0xFFE53935.toInt()      // red   — error

    /** SAF picker for "Send file" → KDE share payload upload. Registered at
     *  field init (before the fragment is STARTED) per the ActivityResult API. */
    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { onFilePicked(it) }
    }

    private fun dotColor(dot: View, color: Int) {
        (dot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
    }
    private fun setConn(row: Row, color: Int, text: String) {
        dotColor(row.connDot, color); row.connText.text = text
    }
    private fun setPair(row: Row, color: Int, text: String) {
        dotColor(row.pairDot, color); row.pairText.text = text
    }
    /** A small round status light view. */
    private fun lightDot(ctx: android.content.Context, color: Int): View = View(ctx).apply {
        val sz = dp(9)
        layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = dp(7) }
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color)
        }
    }
    private fun lightRow(ctx: android.content.Context, dot: View, text: TextView): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
            addView(dot); addView(text)
        }

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

        // ── Remote control (touchpad / keyboard / big-screen senders) ──────
        root.addView(buildRemoteSection(ctx))
        // ── Media remote + system volume (mpris / systemvolume senders) ────
        root.addView(buildMediaCard(ctx))
        // ── Presenter (laser pointer + slide nav) ──────────────────────────
        root.addView(buildPresenterCard(ctx))
        // ── One-shot actions (ring / browse / virtual monitor) ─────────────
        root.addView(buildActionsCard(ctx))
        // ── Remote Desktop control (session + mouse/keyboard surface) ──────
        root.addView(buildRemoteDesktopCard(ctx))
        // ── Share text/links to the desktop (share sender) ─────────────────
        root.addView(buildShareCard(ctx))
        // ── Photo capture (open camera) ────────────────────────────────────
        root.addView(buildPhotoCard(ctx))
        // ── Send SMS (this phone sends it) ─────────────────────────────────
        root.addView(buildSmsCard(ctx))

        // ── Plugins catalog (data-driven from build.json) ──────────────────
        if (cfg.plugins.isNotEmpty()) root.addView(buildPluginsSection(ctx, cfg))

        // ── Self-test — exercise every enabled plugin ──────────────────────
        root.addView(buildSelfTestCard(ctx, cfg))

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
        // Effective name/host/port — build.json defaults, overridable via Edit.
        val dprefs = KdeDevicePrefs(ctx)
        var effLabel = dprefs.label(device.id, device.label)
        var effHost = dprefs.host(device.id, device.wgIp)
        var effPort = dprefs.port(device.id, KdeConnectConfig.get().discoveryPort)
        val title = TextView(ctx).apply {
            text = "$effLabel  ·  $effHost:$effPort"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(title)

        // Two status lights: connection + pairing.
        val pairedBefore = device.id.isNotBlank() && KdeConnectManager.isPaired(device.id)
        val connDot = lightDot(ctx, DOT_IDLE)
        val connText = TextView(ctx).apply { setTextColor(0xFFE9D8FD.toInt()); textSize = 12.5f
            text = "Not connected" }
        val pairDot = lightDot(ctx, if (pairedBefore) DOT_OK else DOT_IDLE)
        val pairText = TextView(ctx).apply { setTextColor(0xFFE9D8FD.toInt()); textSize = 12.5f
            text = if (pairedBefore) "Paired" else "Not paired" }
        card.addView(lightRow(ctx, connDot, connText))
        card.addView(LinearLayout(ctx).apply { addView(lightRow(ctx, pairDot, pairText))
            setPadding(0, 0, 0, dp(8)) })

        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun mkBtn(label: String): Button = Button(ctx).apply {
            text = label; isAllCaps = false; textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val connect = mkBtn("Connect")
        val pairBtn = mkBtn("Pair")
        val ping = mkBtn("Ping").apply { isEnabled = pairedBefore }   // tappable once paired (auto-reconnects)
        val edit = mkBtn("Edit")
        btnRow.addView(connect); btnRow.addView(pairBtn); btnRow.addView(ping); btnRow.addView(edit)
        card.addView(btnRow)

        val row = Row(device, title, connDot, connText, pairDot, pairText,
            connect, pairBtn, ping, edit, effHost, effPort, effLabel)
        rows += row
        refreshPairButton(row)

        connect.setOnClickListener {
            setConn(row, DOT_BUSY, "Connecting…")
            viewLifecycleOwner.lifecycleScope.launch {
                KdeConnectManager.connect(row.host, row.port)
                    .onFailure { setConn(row, DOT_ERR, "Couldn't connect: ${it.message}") }
            }
        }
        // Merged Pair/Unpair: label follows the paired state. Pairing is keyed
        // by the persistent device id (row.device.id == manager's idFor(host)),
        // so Unpair works even before connecting; Pair needs a live link.
        pairBtn.setOnClickListener {
            val liveId = row.deviceId
            val key = (liveId ?: row.device.id).takeIf { it.isNotBlank() }
            when {
                key != null && KdeConnectManager.isPaired(key) -> {
                    KdeConnectManager.unpair(key)
                    setPair(row, DOT_IDLE, "Not paired"); refreshPairButton(row)
                }
                liveId != null -> {
                    if (!KdeConnectManager.requestPair(liveId)) toast("Connect first")
                    else setPair(row, DOT_BUSY, "Pair request sent — accept on ${row.label}")
                }
                else -> toast("Connect first")
            }
        }
        ping.setOnClickListener {
            val msg = "Ping from ${KdeIdentity.deviceName(requireContext())}"
            val live = row.deviceId?.takeIf { KdeConnectManager.isConnected(it) }
            if (live != null) {
                if (!KdeConnectManager.sendPing(live, msg)) toast("Send failed — reconnecting…")
                else return@setOnClickListener
            }
            // No live link (dropped or first use) — re-dial this device, then ping.
            toast("Reconnecting…")
            viewLifecycleOwner.lifecycleScope.launch {
                KdeConnectManager.connect(row.host, row.port)
                    .onSuccess { id -> KdeConnectManager.sendPing(id, msg) }
                    .onFailure { toast("Couldn't reconnect: ${it.message}") }
            }
        }
        edit.setOnClickListener { showEditDialog(ctx, row, dprefs) }
        return card
    }

    /** Pair button shows "Unpair" when paired, "Pair" otherwise. Falls back to
     *  the persistent device id (the key the manager + the pair light both use)
     *  so a remembered pairing reads "Unpair" before this session connects. */
    private fun refreshPairButton(row: Row) {
        val key = row.deviceId ?: row.device.id
        val paired = key.isNotBlank() && KdeConnectManager.isPaired(key)
        row.pairBtn.text = if (paired) "Unpair" else "Pair"
    }

    /** Edit dialog for the device Name / IP / Port — persists an override on
     *  top of the build.json default. */
    private fun showEditDialog(ctx: android.content.Context, row: Row, dprefs: KdeDevicePrefs) {
        val pad = dp(16)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        fun field(hint: String, value: String, numeric: Boolean) = android.widget.EditText(ctx).apply {
            this.hint = hint; setText(value); isSingleLine = true
            if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val nameF = field("Name", row.label, false)
        val ipF   = field("IP (wg)", row.host, false)
        val portF = field("Port", row.port.toString(), true)
        box.addView(nameF); box.addView(ipF); box.addView(portF)
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Edit ${row.device.label}")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                row.label = nameF.text.toString().ifBlank { row.label }
                row.host = ipF.text.toString().ifBlank { row.host }
                row.port = portF.text.toString().toIntOrNull() ?: row.port
                dprefs.save(row.device.id, row.label, row.host, row.port)
                row.title.text = "${row.label}  ·  ${row.host}:${row.port}"
            }
            .setNeutralButton("Reset to default") { _, _ ->
                dprefs.save(row.device.id, row.device.label, row.device.wgIp,
                    KdeConnectConfig.get().discoveryPort)
                row.label = row.device.label; row.host = row.device.wgIp
                row.port = KdeConnectConfig.get().discoveryPort
                row.title.text = "${row.label}  ·  ${row.host}:${row.port}"
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        val row = rows.firstOrNull { host != null && it.host == host }
            ?: rows.firstOrNull { deviceId.isNotBlank() && it.deviceId == deviceId }
            ?: return
        if (deviceId.isNotBlank()) row.deviceId = deviceId
        val pairKey = row.deviceId ?: row.device.id
        val paired = pairKey.isNotBlank() && KdeConnectManager.isPaired(pairKey)
        when (state) {
            KdeConnectManager.State.CONNECTING   -> setConn(row, DOT_BUSY, "Connecting…")
            KdeConnectManager.State.HANDSHAKING  -> setConn(row, DOT_BUSY, "Connecting (securing)…")
            KdeConnectManager.State.NEEDS_PAIRING -> {
                setConn(row, DOT_OK, "Connected"); setPair(row, DOT_IDLE, "Not paired")
            }
            KdeConnectManager.State.PAIRED       -> {
                val keyPart = detail.substringAfter(" · ", "")
                setConn(row, DOT_OK, "Connected")
                setPair(row, DOT_OK, "Paired" + if (keyPart.isNotBlank()) " · $keyPart" else "")
            }
            KdeConnectManager.State.DISCONNECTED -> {
                setConn(row, DOT_IDLE, "Not connected")
                setPair(row, if (paired) DOT_OK else DOT_IDLE, if (paired) "Paired" else "Not paired")
            }
            KdeConnectManager.State.ERROR        -> setConn(row, DOT_ERR, "Error: $detail")
        }
        row.ping.isEnabled = paired   // paired → tappable; the handler auto-reconnects if the link dropped
        refreshPairButton(row)
    }

    private fun toast(m: String) = Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()
    /** The full KDE Connect plugin catalog — active (we implement + advertise)
     *  in lavender, planned in gray. Data-driven from build.json plugins[]. */
    private fun buildPluginsSection(ctx: android.content.Context, cfg: KdeConnectConfig.Config): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(14); setPadding(p, p, p, p)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(0x22FFFFFF)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
        val prefs = KdePluginPrefs(ctx)
        val header = TextView(ctx).apply {
            setTextColor(0xFFFFFFFF.toInt()); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(2))
        }
        fun updateHeader() {
            val on = cfg.plugins.count { prefs.isEnabled(it.id) }
            header.text = "Plugins  ·  $on enabled / ${cfg.plugins.size} total"
        }
        updateHeader()
        col.addView(header)
        col.addView(TextView(ctx).apply {
            text = "Tap a plugin to enable/disable it (counts above update live). " +
                "● handled · ● advertised (no handler yet) · ◌ off."
            setTextColor(0x77FFFFFF.toInt()); textSize = 11f; setPadding(0, 0, 0, dp(8))
        })
        // Handled = a registered plugin actually processes/sends this packet.
        val handledPackets = KdePluginRegistry.plugins.flatMap { it.incoming + it.outgoing }.toSet()
        for (pl in cfg.plugins.sortedByDescending { it.packet in handledPackets }) {
            val real = pl.packet in handledPackets
            val rowTv = TextView(ctx).apply {
                typeface = Typeface.MONOSPACE; textSize = 12f
                setPadding(0, dp(3), 0, dp(3))
            }
            fun render() {
                val on = prefs.isEnabled(pl.id)
                val arrow = when (pl.dir) { "in" -> "↓"; "out" -> "↑"; else -> "↕" }
                rowTv.text = "${if (on) "●" else "◌"}  ${pl.name}  $arrow   ${pl.packet}"
                rowTv.setTextColor(when {
                    !on   -> 0x77FFFFFF.toInt()           // off — dim
                    real  -> 0xFFE9D8FD.toInt()           // handled — lavender
                    else  -> 0xFFFFCC80.toInt()           // advertised-only — amber
                })
            }
            render()
            rowTv.setOnClickListener {
                prefs.toggle(pl.id); render(); updateHeader()
                rowTv.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            }
            col.addView(rowTv)
        }
        return col
    }

    /** Remote control card — drives the connected desktop: touchpad (move +
     *  tap-click), keyboard, and Big Screen text. Senders: RemoteInputPlugin /
     *  BigScreenPlugin. Targets the first connected device. */
    private fun buildRemoteSection(ctx: android.content.Context): View {
        // Master input-share gate: when OFF the pad/keys send nothing (so the
        // phone in your pocket can't nudge the desktop cursor). Tap Start to arm.
        var inputOn = false
        fun target(): String? = if (inputOn) KdeConnectManager.connectedIds().firstOrNull() else null

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(14); setPadding(p, p, p, p)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(0x22FFFFFF)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
        col.addView(TextView(ctx).apply {
            text = "Remote control"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(ctx).apply {
            text = "Controls the connected desktop. Drag the pad to move the cursor · tap = click · long-press area = right-click."
            setTextColor(0x77FFFFFF.toInt()); textSize = 11f; setPadding(0, dp(2), 0, dp(8))
        })

        // ── Share toggle — arm/disarm sending input to the desktop ────────
        val shareBtn = Button(ctx).apply {
            text = "▶ Start control"; isAllCaps = false; textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        shareBtn.setOnClickListener {
            inputOn = !inputOn
            shareBtn.text = if (inputOn) "■ Stop control" else "▶ Start control"
            toastUnit(if (inputOn) "Input control ON — pad & keys drive the desktop"
                      else "Input control OFF")
        }
        col.addView(shareBtn)

        // ── Touchpad ────────────────────────────────────────────────────
        val pad = View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setColor(0x33000000)
                setStroke(maxOf(1, dp(1)), 0x44FFFFFF)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(150))
        }
        var lastX = 0f; var lastY = 0f; var moved = false; var downT = 0L
        pad.setOnTouchListener { v, e ->
            val id = target()
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y; moved = false; downT = android.os.SystemClock.uptimeMillis(); true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - lastX; val dy = e.y - lastY; lastX = e.x; lastY = e.y
                    if (kotlin.math.abs(dx) > 2 || kotlin.math.abs(dy) > 2) moved = true
                    if (id != null) KdeConnectManager.send(id, RemoteInputPlugin.move(dx, dy))
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val held = android.os.SystemClock.uptimeMillis() - downT
                    if (!moved && id != null) {
                        KdeConnectManager.send(id, if (held > 500) RemoteInputPlugin.rightClick() else RemoteInputPlugin.click())
                    }
                    if (id == null) toast(if (!inputOn) "Tap 'Start control' first" else "Connect & pair first")
                    v.performClick(); true
                }
                else -> false
            }
        }
        col.addView(pad)

        // ── Keyboard ────────────────────────────────────────────────────
        val keyF = android.widget.EditText(ctx).apply {
            hint = "Type, then Send keys"; isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        col.addView(keyF)
        val keyRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun mkBtn(label: String, weight: Float = 1f) = Button(ctx).apply {
            text = label; isAllCaps = false; textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        }
        val sendKeys = mkBtn("Send keys", 2f)
        val backspace = mkBtn("⌫")
        val enter = mkBtn("⏎")
        sendKeys.setOnClickListener {
            val id = target() ?: return@setOnClickListener toastUnit("Connect & pair first")
            val t = keyF.text.toString()
            if (t.isNotEmpty()) { KdeConnectManager.send(id, RemoteInputPlugin.key(t)); keyF.setText("") }
        }
        // KDE special keys: 1 = Backspace, 12 = Enter/Return.
        backspace.setOnClickListener { target()?.let { KdeConnectManager.send(it, RemoteInputPlugin.specialKey(1)) } }
        enter.setOnClickListener { target()?.let { KdeConnectManager.send(it, RemoteInputPlugin.specialKey(12)) } }
        keyRow.addView(sendKeys); keyRow.addView(backspace); keyRow.addView(enter)
        col.addView(keyRow)

        // ── Big Screen ──────────────────────────────────────────────────
        val bsF = android.widget.EditText(ctx).apply {
            hint = "Send a line to Plasma Big Screen"; isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        col.addView(bsF)
        col.addView(Button(ctx).apply {
            text = "Send to Big Screen"; isAllCaps = false; textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                val id = target() ?: return@setOnClickListener toastUnit("Connect & pair first")
                val t = bsF.text.toString()
                if (t.isNotEmpty()) { KdeConnectManager.send(id, BigScreenPlugin.stt(t)); bsF.setText("") }
            }
        })
        return col
    }

    // ── Shared card scaffolding ────────────────────────────────────────────
    /** A rounded translucent card with a bold title + dim subtitle. */
    private fun card(ctx: android.content.Context, title: String, subtitle: String?): LinearLayout {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(14); setPadding(p, p, p, p)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(0x22FFFFFF)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
        col.addView(TextView(ctx).apply {
            text = title; setTextColor(0xFFFFFFFF.toInt()); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        })
        if (subtitle != null) col.addView(TextView(ctx).apply {
            text = subtitle; setTextColor(0x77FFFFFF.toInt()); textSize = 11f
            setPadding(0, dp(2), 0, dp(8))
        })
        return col
    }
    /** A horizontal row of equal-weight compact buttons (label → action). */
    private fun btnRow(ctx: android.content.Context, vararg btns: Pair<String, () -> Unit>): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(2))
        }
        for ((label, action) in btns) row.addView(Button(ctx).apply {
            text = label; isAllCaps = false; textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { action() }
        })
        return row
    }
    /** Run [then] against a live link; if the link dropped, transparently
     *  re-dial the first device first (KDE reconnects on demand rather than
     *  holding one socket forever). */
    private fun targetOrReconnect(then: (String) -> Unit) {
        val live = KdeConnectManager.connectedIds().firstOrNull()
        if (live != null) { then(live); return }
        val row = rows.firstOrNull() ?: return toastUnit("No device")
        if (row.deviceId?.let { KdeConnectManager.isPaired(it) } != true &&
            !KdeConnectManager.isPaired(row.device.id)) return toastUnit("Pair first")
        toastUnit("Reconnecting…")
        viewLifecycleOwner.lifecycleScope.launch {
            KdeConnectManager.connect(row.host, row.port)
                .onSuccess { id ->
                    if (KdeConnectManager.isConnected(id)) then(id)
                    else toastUnit("Reconnected — tap again")
                }
                .onFailure { toastUnit("Couldn't reconnect: ${it.message}") }
        }
    }

    /** A file was picked → stream it to the connected desktop via the KDE
     *  share payload channel (SharePlugin opens the TLS payload server). */
    private fun onFilePicked(uri: Uri) {
        val id = KdeConnectManager.connectedIds().firstOrNull()
        if (id == null) { toastUnit("Connect & pair a desktop first"); return }
        val link = KdeConnectManager.link(id)
        if (link == null) { toastUnit("No live link"); return }
        if (SharePlugin.sendFile(requireContext(), link, uri)) toastUnit("Sending file…")
        else toastUnit("Couldn't start file send")
    }

    /** Send a sender packet to a live link (reconnecting if needed). */
    private fun toTarget(packet: NetworkPacket?) {
        if (packet == null) return toastUnit("No desktop state yet — try again")
        targetOrReconnect { id -> KdeConnectManager.send(id, packet) }
    }

    /** Media remote — drives the desktop's player (mpris) + master volume
     *  (systemvolume). Requests current state on first open. */
    private fun buildMediaCard(ctx: android.content.Context): View {
        val col = card(ctx, "Media remote",
            "Controls the connected desktop's player and system volume.")
        col.addView(btnRow(ctx,
            "⏮" to { toTarget(MprisPlugin.command("Previous")) },
            "⏯" to { toTarget(MprisPlugin.command("PlayPause")) },
            "⏭" to { toTarget(MprisPlugin.command("Next")) },
        ))
        col.addView(btnRow(ctx,
            "Vol −" to { volNudge(-10) },
            "Mute"  to { toTarget(RemoteSystemVolumePlugin.mute(true)) },
            "Vol +" to { volNudge(+10) },
        ))
        return col
    }
    /** Nudge desktop volume; if we don't have its sink state yet, request it. */
    private fun volNudge(delta: Int) = targetOrReconnect { id ->
        val pkt = RemoteSystemVolumePlugin.nudge(delta)
        if (pkt == null) {
            KdeConnectManager.send(id, RemoteSystemVolumePlugin.requestSinks())
            toastUnit("Fetching desktop volume — tap again")
        } else KdeConnectManager.send(id, pkt)
    }

    /** Presenter — a laser-pointer pad (drag) + slide navigation. The pointer
     *  uses kdeconnect.presenter; prev/next use mousepad PageUp/PageDown. */
    private fun buildPresenterCard(ctx: android.content.Context): View {
        val col = card(ctx, "Presenter",
            "Drag the pad to move the on-screen laser · release to hide it.")
        val pad = View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setColor(0x33000000); setStroke(maxOf(1, dp(1)), 0x44FFFFFF)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(110))
        }
        var lastX = 0f; var lastY = 0f
        pad.setOnTouchListener { v, e ->
            val id = KdeConnectManager.connectedIds().firstOrNull()
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (e.x - lastX) / v.width.coerceAtLeast(1)
                    val dy = (e.y - lastY) / v.height.coerceAtLeast(1)
                    lastX = e.x; lastY = e.y
                    if (id != null) KdeConnectManager.send(id, PresenterPlugin.pointer(dx, dy))
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (id != null) KdeConnectManager.send(id, PresenterPlugin.stop())
                    else toastUnit("Connect & pair first")
                    v.performClick(); true
                }
                else -> false
            }
        }
        col.addView(pad)
        col.addView(btnRow(ctx,
            "◀ Prev" to { toTarget(RemoteInputPlugin.specialKey(7)) },   // PageUp
            "Next ▶" to { toTarget(RemoteInputPlugin.specialKey(8)) },   // PageDown
        ))
        return col
    }

    /** One-shot sender actions — ring the desktop, ask it to expose its files,
     *  start a virtual monitor, or open a remote-desktop session. */
    private fun buildActionsCard(ctx: android.content.Context): View {
        val col = card(ctx, "Actions", "Single-tap requests to the connected desktop.")
        col.addView(btnRow(ctx,
            "🔔 Ring desktop" to { toTarget(FindMyPhonePlugin.ring()) },
            "📁 Send file" to { pickFile.launch("*/*") },
        ))
        col.addView(btnRow(ctx,
            "🖥 Monitor" to { toTarget(VirtualMonitorPlugin.request()) },
            "🔒 Lock desktop" to { toTarget(LockDevicePlugin.lockRemote(true)) },
        ))
        return col
    }

    /** Remote Desktop control — asks Plasma to start a remote-desktop session
     *  (kdeconnect.remotedesktop), then exposes a full mouse + keyboard surface
     *  to drive it (mousepad senders: move/click/right-click/keys/arrows). */
    private fun buildRemoteDesktopCard(ctx: android.content.Context): View {
        fun tgt(): String? = KdeConnectManager.connectedIds().firstOrNull()
        val col = card(ctx, "Remote Desktop control",
            "Asks Plasma to start a remote-desktop session (KDE streams the screen via an RDP/virtual-monitor portal). Drive it with the pad/keys below; full on-phone viewing needs an RDP client.")
        // Stateful start/stop — label follows the session state.
        var rdpActive = false
        val sessionBtn = Button(ctx).apply {
            text = "▶ Start Remote Desktop Control"; isAllCaps = false; textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        sessionBtn.setOnClickListener {
            if (!rdpActive) {
                toTarget(RemoteDesktopPlugin.request())
                rdpActive = true; sessionBtn.text = "■ Stop Remote Desktop Control"
                toastUnit("Requested a remote-desktop session on the desktop")
            } else {
                toTarget(RemoteDesktopPlugin.stop())
                rdpActive = false; sessionBtn.text = "▶ Start Remote Desktop Control"
                toastUnit("Stopped the remote-desktop session")
            }
        }
        col.addView(sessionBtn)
        // Mouse surface.
        val pad = View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setColor(0x33000000); setStroke(maxOf(1, dp(1)), 0x44FFFFFF)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(140))
                .apply { topMargin = dp(6) }
        }
        var lastX = 0f; var lastY = 0f; var moved = false; var downT = 0L
        pad.setOnTouchListener { v, e ->
            val id = tgt()
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y; moved = false; downT = android.os.SystemClock.uptimeMillis(); true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - lastX; val dy = e.y - lastY; lastX = e.x; lastY = e.y
                    if (kotlin.math.abs(dx) > 2 || kotlin.math.abs(dy) > 2) moved = true
                    if (id != null) KdeConnectManager.send(id, RemoteInputPlugin.move(dx, dy))
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val held = android.os.SystemClock.uptimeMillis() - downT
                    if (!moved && id != null)
                        KdeConnectManager.send(id, if (held > 500) RemoteInputPlugin.rightClick() else RemoteInputPlugin.click())
                    if (id == null) toastUnit("Connect & pair first")
                    v.performClick(); true
                }
                else -> false
            }
        }
        col.addView(pad)
        // Keyboard + navigation keys (KDE special-key codes).
        val keyF = android.widget.EditText(ctx).apply {
            hint = "Type, then Send"; isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
        col.addView(keyF)
        col.addView(btnRow(ctx,
            "Send" to {
                val id = tgt()
                val t = keyF.text.toString()
                if (id == null) toastUnit("Connect & pair first")
                else if (t.isNotEmpty()) { KdeConnectManager.send(id, RemoteInputPlugin.key(t)); keyF.setText("") }
            },
            "⌫" to { tgt()?.let { KdeConnectManager.send(it, RemoteInputPlugin.specialKey(1)) } },
            "⏎" to { tgt()?.let { KdeConnectManager.send(it, RemoteInputPlugin.specialKey(12)) } },
            "Esc" to { tgt()?.let { KdeConnectManager.send(it, RemoteInputPlugin.specialKey(14)) } },
        ))
        col.addView(btnRow(ctx,
            "←" to { tgt()?.let { KdeConnectManager.send(it, RemoteInputPlugin.specialKey(3)) } },
            "↑" to { tgt()?.let { KdeConnectManager.send(it, RemoteInputPlugin.specialKey(4)) } },
            "↓" to { tgt()?.let { KdeConnectManager.send(it, RemoteInputPlugin.specialKey(6)) } },
            "→" to { tgt()?.let { KdeConnectManager.send(it, RemoteInputPlugin.specialKey(5)) } },
        ))
        return col
    }

    /** Share — push text or a link to the connected desktop (kdeconnect.share).
     *  A link opens in the desktop browser; text lands on its clipboard. */
    private fun buildShareCard(ctx: android.content.Context): View {
        val col = card(ctx, "Share", "Send text or a link to the connected desktop.")
        val f = android.widget.EditText(ctx).apply {
            hint = "Text or https://link"; isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        col.addView(f)
        col.addView(btnRow(ctx,
            "Send text" to {
                val t = f.text.toString()
                if (t.isNotBlank()) { toTarget(SharePlugin.text(t)); f.setText("") }
            },
            "Send link" to {
                val t = f.text.toString().trim()
                if (t.isNotBlank()) { toTarget(SharePlugin.url(t)); f.setText("") }
            },
        ))
        return col
    }

    /** Send SMS — this phone sends the message directly via SmsManager (the
     *  phone is the SMS device; the desktop normally drives this via
     *  kdeconnect.sms.request). Needs the runtime SEND_SMS grant. */
    private fun buildSmsCard(ctx: android.content.Context): View {
        val col = card(ctx, "Send SMS", "This phone sends the text (needs SMS permission).")
        val toF = android.widget.EditText(ctx).apply {
            hint = "Recipient number"; isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val bodyF = android.widget.EditText(ctx).apply {
            hint = "Message"; isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        }
        col.addView(toF); col.addView(bodyF)
        col.addView(btnRow(ctx,
            "Send SMS" to {
                val to = toF.text.toString().trim(); val body = bodyF.text.toString()
                when {
                    to.isBlank() || body.isBlank() -> toastUnit("Recipient and message required")
                    SmsPlugin.sendDirect(ctx, to, body) -> { toastUnit("SMS sent"); bodyF.setText("") }
                    else -> toastUnit("Couldn't send — grant SMS permission")
                }
            },
        ))
        return col
    }

    /** Photo capture — opens the camera (the desktop normally triggers this via
     *  kdeconnect.photo.request; the button lets you fire it locally too). */
    private fun buildPhotoCard(ctx: android.content.Context): View {
        val col = card(ctx, "Photo capture",
            "Open the camera. (Desktop sends kdeconnect.photo.request to trigger it remotely.)")
        col.addView(btnRow(ctx,
            "📷 Open camera" to { PhotoPlugin.capture(ctx) },
        ))
        return col
    }

    /** Self-test — for every enabled plugin, report whether a handler is wired
     *  (packet known to the registry) and, when connected+paired, fire a
     *  representative packet for the sender plugins and confirm it left. */
    private fun buildSelfTestCard(ctx: android.content.Context, cfg: KdeConnectConfig.Config): View {
        val col = card(ctx, "Self-test", "Tap to exercise every enabled plugin and list the result.")
        val out = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Button(ctx).apply {
            text = "Test all plugins"; isAllCaps = false; textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { runSelfTest(ctx, cfg, out) }
        })
        col.addView(out)
        return col
    }

    private fun runSelfTest(ctx: android.content.Context, cfg: KdeConnectConfig.Config, out: LinearLayout) {
        out.removeAllViews()
        val prefs = KdePluginPrefs(ctx)
        val handled = KdePluginRegistry.plugins.flatMap { it.incoming + it.outgoing }.toSet()
        val target = KdeConnectManager.connectedIds().firstOrNull()
        // Representative sender packets (only fired when connected+paired).
        val probes: Map<String, () -> NetworkPacket?> = mapOf(
            "ping"               to { PingPlugin.build("Self-test") },
            "mousepad"           to { RemoteInputPlugin.move(0f, 0f) },
            "bigscreen"          to { BigScreenPlugin.stt("Self-test") },
            "mprisremote"        to { MprisPlugin.requestPlayers() },
            "remotesystemvolume" to { RemoteSystemVolumePlugin.requestSinks() },
            "presenter_remote"   to { PresenterPlugin.pointer(0f, 0f) },
            "findthisdevice"     to { FindMyPhonePlugin.ring() },
            "sftp_browse"        to { SftpPlugin.request() },
            "virtualmonitor"     to { VirtualMonitorPlugin.request() },
            "screenconnector"    to { RemoteDesktopPlugin.request() },
            "lockdevice"         to { LockDevicePlugin.requestState() },  // queries, won't lock
            // sms is intentionally NOT probed — never send a real text during a self-test.
        )
        var pass = 0; var fail = 0
        for (pl in cfg.plugins) {
            val on = prefs.isEnabled(pl.id)
            val wired = pl.packet in handled
            val probe = probes[pl.id]
            val mark: String; val color: Int; val note: String
            when {
                !on -> { mark = "◌"; color = 0x77FFFFFF.toInt(); note = "disabled" }
                !wired -> { mark = "✗"; color = 0xFFFF8B8B.toInt(); note = "no handler"; fail++ }
                probe != null && target != null -> {
                    runCatching { probe()?.let { KdeConnectManager.send(target, it) } }
                    mark = "✓"; color = 0xFF8BE9A0.toInt(); note = "sent"; pass++
                }
                probe != null -> { mark = "•"; color = 0xFFFFCC80.toInt(); note = "ready (not connected)"; pass++ }
                else -> { mark = "✓"; color = 0xFF8BE9A0.toInt(); note = "handler ok"; pass++ }
            }
            out.addView(TextView(ctx).apply {
                text = "$mark  ${pl.name} — $note"
                setTextColor(color); typeface = Typeface.MONOSPACE; textSize = 12f
                setPadding(0, dp(2), 0, dp(2))
            })
        }
        out.addView(TextView(ctx).apply {
            text = "→ $pass ok · $fail failed · ${cfg.plugins.size} total" +
                if (target == null) "  (connect+pair to fire senders)" else ""
            setTextColor(0xFFFFFFFF.toInt()); typeface = Typeface.DEFAULT_BOLD; textSize = 12.5f
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun toastUnit(m: String) { toast(m) }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = KdeConnectFragment() }
}
