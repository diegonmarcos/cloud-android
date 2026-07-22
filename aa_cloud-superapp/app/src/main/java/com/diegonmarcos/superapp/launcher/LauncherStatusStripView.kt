package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.SystemInfoPopup
import com.diegonmarcos.superapp.settings.LauncherTheme
import com.diegonmarcos.superapp.cloud.CalendarAgendaPopup
import com.diegonmarcos.superapp.battery.BatteryIconView
import com.diegonmarcos.superapp.battery.BatteryEstimatePopup
import com.diegonmarcos.superapp.network.NetworkInfoPopup
import com.diegonmarcos.superapp.zoomies.PetStrengthView

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Base64
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Top status strip rendered when the SuperApp is the active default
 * Android launcher AND the active LauncherTheme is Cloud. Replaces the
 * hidden system status bar with our own 3-cluster row + bottom hairline:
 *
 *   ┌───────────────────────────────────────────────────────────────┐
 *   │ [5G][WiFi][WG]   dd-MM-yyyy HH:mm EEE   [R N%][S N%][Battery] │
 *   │ ─────────────────────────────────────────────────────────────  │  hairline
 *   └───────────────────────────────────────────────────────────────┘
 *
 *   LEFT  — 5G / WiFi / WG labels. Color-tinted by current state read
 *           from ConnectivityManager (no runtime permission needed —
 *           ACCESS_NETWORK_STATE is install-time). 5G label tracks any
 *           cellular transport (we can't read the exact subtype without
 *           READ_PHONE_STATE; the label is the user-spec'd icon name).
 *           WG label tracks any VPN transport (works for our wg as well
 *           as Tailscale / generic VPN).
 *   CENTER — Date + time, monospace, centred. Updated every minute via
 *           ACTION_TIME_TICK + immediate refresh on TIMEZONE_CHANGED /
 *           TIME_CHANGED.
 *   RIGHT — RAM% used (ActivityManager.MemoryInfo) · Storage% used on
 *           /data (StatFs) · BatteryIconView (existing). Polled every
 *           10s on the main-thread ticker + on every time tick.
 *
 * MainActivity.applyLauncherChrome pushes the toolbar island down by
 * `topSystemInset + 6dp` in this theme so the strip's hairline has
 * breathing room above the dynamic island (the user pointed out they
 * were touching).
 */
class LauncherStatusStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : LinearLayout(context, attrs, defStyle) {

    private val signal5gView: TextView
    private val wifiView: TextView
    private val wgView: TextView
    private val btView: TextView
    private val usbView: TextView
    private val kdeView: TextView
    private val dateTimeView: TextView
    private val ramView: TextView
    private val storageView: TextView
    private val cpuView: TextView
    private val batteryView: BatteryIconView

    // Line 0 — one animated pet per Line-1 tool (vendored zoomies sprites).
    // Data-driven from BuildConfig.STATUS_PETS_B64 (build.json::status_pets):
    // each tool gets a FIXED animal; its strength maps to a gait level
    // (idle/walk/walk_fast/run). The pet sits in a vertical [pet, icon]
    // column so it's always directly above its icon.
    private data class ToolPet(
        val animal: String, val variant: String,
        val type: String, val onLevel: Int, val buckets: List<Int>,
    )
    private var petsEnabled = false
    private var petPx = 0
    private var gaits: List<String> = listOf("idle", "walk", "walk_fast", "run")
    private val toolCfg = HashMap<String, ToolPet>()
    private val petViews = HashMap<String, PetStrengthView>()

    private var hasWifi = false
    private var hasCellular = false
    private var hasVpn = false
    private var hasBluetooth = false
    private var hasUsbData = false
    private var hasKde = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val metricsTicker = object : Runnable {
        override fun run() {
            refreshMetrics()
            // Reschedule only while live system stats are ON (Configs → Launcher →
            // "Live system stats"). OFF = frozen numbers, no 10s wakeup → leaner.
            if (com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(context).toggle("status_live"))
                mainHandler.postDelayed(this, 10_000)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(0, 0, 0, 0)
        // Transparent background — galaxy backdrop reads through the
        // strip so the camera-cutout area + the strip read as ONE
        // continuous galaxy band.
        setBackgroundColor(0x00000000)

        parsePetsConfig()

        val hpad = (10 * resources.displayMetrics.density).toInt()
        // FrameLayout (NOT horizontal LinearLayout) — lets the date/time
        // sit at gravity=CENTER which is TRUE screen-centre, aligned with
        // the dynamic-island pill below. A LinearLayout with weighted
        // columns would centre the date/time inside its column only —
        // and since LEFT cluster (~60dp) is narrower than RIGHT cluster
        // (~110dp), that "column centre" is biased rightward of screen
        // centre. FrameLayout positions each child independently via
        // layout_gravity, so LEFT anchors start, RIGHT anchors end, and
        // the centre child stays glued to screen midpoint.
        // Line 1 — the system-info row. WRAP_CONTENT (was 0+weight) so the
        // VERTICAL strip now stacks Line 0 (fixed) + Line 1 (content) + the
        // hairline instead of one weighted row filling a fixed barH. A small
        // vertical pad gives the icons breathing room.
        val vpad = (3 * resources.displayMetrics.density).toInt()
        val innerRow = FrameLayout(context).apply {
            setPadding(hpad, vpad, hpad, vpad)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        // ── LEFT cluster: 5G · WiFi · WG (anchored to START) ────────
        val leftCluster = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL,
            )
        }
        signal5gView = makeIconLabel("5G")
        wifiView     = makeIconLabel("WiFi")
        wgView       = makeIconLabel("WG")
        btView       = makeIconLabel("BT")
        // USB data indicator — lit only when a cable is connected in a
        // DATA-transfer mode (MTP/PTP/RNDIS/NCM/MIDI or OTG host), dim on
        // charge-only or unplugged. Tracks ACTION_USB_STATE.
        usbView      = makeIconLabel("USB")
        // KDE Connect — lit when ≥1 paired device is connected over the mesh.
        kdeView      = makeIconLabel("KDE")
        // Any of the left-cluster icons → NetworkInfoPopup (shared
        // popup per cluster, per Diego's "yes click any, they are a
        // cluster" answer). Reusing the same anchor (the tapped icon)
        // keeps the bubble close to where the user tapped.
        val openNetworkPopup = OnClickListener { v -> NetworkInfoPopup.show(context, v) }
        for (v in listOf(signal5gView, wifiView, wgView, btView, usbView, kdeView)) {
            v.isClickable = true
            v.setOnClickListener(openNetworkPopup)
        }
        // Each tool becomes a vertical [pet, icon] column → Line 0 pet sits
        // directly above its Line 1 icon. makeToolColumn falls back to the
        // bare icon when pets are disabled or unconfigured for that tool.
        leftCluster.addView(makeToolColumn("cellular", signal5gView))
        leftCluster.addView(makeToolColumn("wifi", wifiView))
        leftCluster.addView(makeToolColumn("vpn", wgView))
        leftCluster.addView(makeToolColumn("kde", kdeView))   // mesh (WG) → KDE → BT
        leftCluster.addView(makeToolColumn("bluetooth", btView))
        leftCluster.addView(makeToolColumn("usb", usbView))
        innerRow.addView(leftCluster)

        // ── CENTER: date + time, true screen-centre ────────────────
        dateTimeView = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
            // Tap → CalendarAgendaPopup (next-7-days mini-list, same
            // data source as section:cal/agenda — placeholder for now,
            // wires to libs:cal once CalDAV slice D lands).
            isClickable = true
            setOnClickListener { CalendarAgendaPopup.show(context, this) }
        }
        // Center column: date/time on Line 1. A spacer reserves the Line 0
        // (pet) height so the clock stays aligned with the icon row instead
        // of floating in the vertical middle of the taller two-line strip.
        // No pet over the centre — that's the camera punch-hole.
        val centerCol = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        if (petsEnabled) centerCol.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, petPx)
        })
        dateTimeView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        centerCol.addView(dateTimeView)
        innerRow.addView(centerCol)

        // ── RIGHT cluster: RAM% · Storage% · Battery (anchored END) ─
        val rightCluster = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            )
        }
        ramView     = makeIconLabel("R 0%")
        storageView = makeIconLabel("S 0%")
        cpuView     = makeIconLabel("C 0s")
        // RAM + Storage + CPU → shared SystemInfoPopup (per Diego's cluster
        // rule). Same anchor (tapped icon) as battery / network popups.
        // CPU = 1-min /proc/loadavg normalised by core count (the popup
        // already breaks out the 1m·5m·15m windows).
        val openSystemPopup = OnClickListener { v -> SystemInfoPopup.show(context, v) }
        for (v in listOf(ramView, storageView, cpuView)) {
            v.isClickable = true
            v.setOnClickListener(openSystemPopup)
        }
        batteryView = BatteryIconView(context).apply {
            val mlp = (4 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = mlp }
            // Tap → popup with "Estimated battery last" + session
            // metrics (same numbers Configs/About/Battery & Usage
            // shows, just one-glance accessible from the strip).
            isClickable = true
            setOnClickListener { BatteryEstimatePopup.show(context, this) }
        }
        rightCluster.addView(makeToolColumn("ram", ramView))
        rightCluster.addView(makeToolColumn("storage", storageView))
        rightCluster.addView(makeToolColumn("cpu", cpuView))
        rightCluster.addView(makeToolColumn("battery", batteryView))
        innerRow.addView(rightCluster)

        // innerRow IS the two lines now: each tool's column stacks its pet
        // (Line 0) over its icon (Line 1). No separate band.
        addView(innerRow)

        // ── Bottom hairline ───────────────────────────────────────
        // Faint white separator pinned to the bottom of the strip.
        // MainActivity nudges the toolbar island down so there's a
        // 6dp gap between THIS hairline and the dynamic island below.
        addView(View(context).apply {
            setBackgroundColor(0x33FFFFFF.toInt())
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                maxOf(1, (resources.displayMetrics.density * 0.75f).toInt()),
            )
        })
    }

    /** Decode BuildConfig.STATUS_PETS_B64 (build.json::status_pets) → per-tool
     *  animal config. Empty/disabled-safe: leaves petsEnabled=false so the
     *  strip renders as the plain icon row. */
    private fun parsePetsConfig() {
        runCatching {
            val b64 = BuildConfig.STATUS_PETS_B64
            if (b64.isBlank()) return
            val o = JSONObject(String(Base64.decode(b64, Base64.DEFAULT)))
            petsEnabled = o.optBoolean("enabled", false)
            petPx = (o.optInt("px", 20) * resources.displayMetrics.density).toInt()
            o.optJSONArray("gaits")?.let { g -> gaits = (0 until g.length()).map { g.getString(it) } }
            val tools = o.optJSONObject("tools") ?: return
            for (k in tools.keys()) {
                val t = tools.getJSONObject(k)
                val buckets = t.optJSONArray("buckets")
                    ?.let { b -> (0 until b.length()).map { b.getInt(it) } } ?: emptyList()
                toolCfg[k] = ToolPet(
                    t.optString("animal"), t.optString("variant"),
                    t.optString("type", "bool"), t.optInt("on_level", gaits.lastIndex), buckets,
                )
            }
        }
    }

    /** Wrap a tool's [iconView] in a vertical [pet, icon] column so its pet
     *  sits on Line 0 directly above the icon on Line 1. Returns the bare
     *  iconView unchanged when pets are off / unconfigured for this tool. */
    private fun makeToolColumn(toolId: String, iconView: View): View {
        val cfg = toolCfg[toolId]
        if (!petsEnabled || cfg == null) return iconView
        val pet = PetStrengthView(context).apply {
            layoutParams = LinearLayout.LayoutParams(petPx, petPx)
            // Configs → Launcher → Others → "Animal animations" gate. Read once
            // (the strip re-inits when the toggle flips via chrome re-render).
            animate = runCatching {
                com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(context).toggle("pets_anim")
            }.getOrDefault(true)
            setAnimal(cfg.animal, cfg.variant)
            setGait(gaits.firstOrNull() ?: "idle")
        }
        petViews[toolId] = pet
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(pet)
            addView(iconView)
        }
    }

    private fun gaitForLevel(level: Int): String =
        gaits.getOrElse(level.coerceIn(0, gaits.lastIndex)) { gaits.last() }

    /** percent → level by counting how many ascending [buckets] it clears. */
    private fun bucketLevel(pct: Int, buckets: List<Int>): Int =
        buckets.count { pct >= it }

    private fun updatePet(toolId: String, level: Int) {
        petViews[toolId]?.setGait(gaitForLevel(level))
    }

    private fun updateBoolPet(toolId: String, on: Boolean) {
        val cfg = toolCfg[toolId] ?: return
        updatePet(toolId, if (on) cfg.onLevel else 0)
    }

    /** Configs → Launcher → Others → "Animal animations". Re-read the pref and
     *  freeze/resume every pet. Public so MainActivity can re-apply LIVE — the
     *  strip lives in the activity shell and isn't recreated on a chrome
     *  re-render, so the init-time read wouldn't pick up a toggle flip. */
    fun applyPetsPref() {
        val on = runCatching {
            com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(context).toggle("pets_anim")
        }.getOrDefault(true)
        petViews.values.forEach { it.animate = on }
    }

    /** Shared small monospace label used by left + right cluster
     *  members. Color set per-state via [applyIconTints] /
     *  [refreshMetrics]. */
    private fun makeIconLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(0x66FFFFFF.toInt())
        textSize = 10f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(3f, 0f, 1f, 0xCC000000.toInt())
        val px = (3 * resources.displayMetrics.density).toInt()
        setPadding(px, 0, px, 0)
        maxLines = 1
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            refreshTime()
            // Piggy-back metrics on the once-a-minute tick (cheap vs the 10s
            // poll) — but ONLY while live stats are on, so "Live system stats"
            // OFF truly freezes RAM/CPU/storage. The clock keeps ticking.
            if (com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(context).toggle("status_live"))
                refreshMetrics()
        }
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { refreshBattery(i) }
    }
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { refreshUsb(i) }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            post { refreshNetworkFromConnectivity() }
        }
        override fun onLost(network: Network) {
            post { refreshNetworkFromConnectivity() }
        }
        override fun onAvailable(network: Network) {
            post { refreshNetworkFromConnectivity() }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        runCatching {
            context.registerReceiver(timeReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
            })
        }
        runCatching {
            val battery = context.registerReceiver(
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            if (battery != null) refreshBattery(battery)
        }
        runCatching {
            // ACTION_USB_STATE is a sticky broadcast — registering returns
            // the current USB state intent, so the icon is correct on attach.
            val usb = context.registerReceiver(
                usbReceiver,
                IntentFilter("android.hardware.usb.action.USB_STATE"),
            )
            if (usb != null) refreshUsb(usb)
        }
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                networkCallback,
            )
        }
        refreshTime()
        refreshNetworkFromConnectivity()
        refreshMetrics()   // one render → static numbers even when live stats are off
        // Start the 10s RAM/CPU/storage refresh loop only when enabled. Off keeps
        // the frozen values on screen (UI stays intact) but skips the polling.
        if (com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(context).toggle("status_live"))
            mainHandler.post(metricsTicker)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runCatching { context.unregisterReceiver(timeReceiver) }
        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching { context.unregisterReceiver(usbReceiver) }
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(networkCallback)
        }
        mainHandler.removeCallbacks(metricsTicker)
    }

    private fun refreshTime() {
        dateTimeView.text = SimpleDateFormat("dd-MM-yyyy HH:mm EEE", Locale.US).format(Date())
    }

    private fun refreshBattery(intent: Intent) {
        val level    = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale    = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status   = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        batteryView.setBattery(pct, charging)
        if (pct >= 0) toolCfg["battery"]?.let { updatePet("battery", bucketLevel(pct, it.buckets)) }
    }

    /** USB cable DATA-transfer state from ACTION_USB_STATE. "Data" = cable
     *  connected AND a data function active (MTP/PTP/RNDIS/NCM/MIDI), or
     *  we're the OTG host — i.e. NOT charge-only (charge-only = connected
     *  with no data function → stays dim). Keys are the stable AOSP
     *  ACTION_USB_STATE extras. */
    private fun refreshUsb(intent: Intent) {
        val connected = intent.getBooleanExtra("connected", false)
        val host      = intent.getBooleanExtra("host_connected", false)
        val dataFn = intent.getBooleanExtra("mtp", false) ||
            intent.getBooleanExtra("ptp", false) ||
            intent.getBooleanExtra("rndis", false) ||
            intent.getBooleanExtra("ncm", false) ||
            intent.getBooleanExtra("midi", false)
        hasUsbData = host || (connected && dataFn)
        applyIconTints()
    }

    /** Walk all known networks via ConnectivityManager and decide
     *  which of the 3 LEFT labels should light up. VPN is checked
     *  across ALL networks (a VPN can co-exist with the active
     *  Wi-Fi / cellular network, and we want the WG indicator
     *  bright in that case). */
    private fun refreshNetworkFromConnectivity() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        var wifi = false; var cell = false; var vpn = false
        runCatching {
            cm?.allNetworks?.forEach { n ->
                cm.getNetworkCapabilities(n)?.let { c ->
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))     wifi = true
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cell = true
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN))      vpn  = true
                }
            }
        }
        hasWifi = wifi; hasCellular = cell; hasVpn = vpn
        hasBluetooth = readBluetoothEnabled()
        applyIconTints()
    }

    /** Bluetooth adapter on/off via BluetoothManager. Wrapped in
     *  runCatching since BluetoothManager.getAdapter requires
     *  BLUETOOTH_CONNECT on API 31+ — already declared in the manifest
     *  but a runtime check never hurts. Off = adapter null OR disabled. */
    private fun readBluetoothEnabled(): Boolean = runCatching {
        val mgr = context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager ?: return@runCatching false
        mgr.adapter?.isEnabled == true
    }.getOrDefault(false)

    private fun applyIconTints() {
        val on  = 0xFFFFFFFF.toInt()
        val off = 0x44FFFFFF.toInt()
        signal5gView.setTextColor(if (hasCellular)  on else off)
        wifiView    .setTextColor(if (hasWifi)      on else off)
        wgView      .setTextColor(if (hasVpn)       on else off)
        btView      .setTextColor(if (hasBluetooth) on else off)
        usbView     .setTextColor(if (hasUsbData)   on else off)
        hasKde = runCatching {
            com.diegonmarcos.superapp.kdeconnect.KdeConnectManager.connectedIds().isNotEmpty()
        }.getOrDefault(false)
        kdeView     .setTextColor(if (hasKde)       on else off)
        // Pets: on → run (energetic), off → idle. on_level is data-driven.
        updateBoolPet("cellular", hasCellular)
        updateBoolPet("wifi", hasWifi)
        updateBoolPet("vpn", hasVpn)
        updateBoolPet("bluetooth", hasBluetooth)
        updateBoolPet("usb", hasUsbData)
        updateBoolPet("kde", hasKde)
    }

    /** Read RAM + /data storage utilisation and update the right
     *  cluster labels. Both reads are cheap (no IO) and safe to call
     *  on the main thread. */
    private fun refreshMetrics() {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.let {
                val info = ActivityManager.MemoryInfo()
                it.getMemoryInfo(info)
                val pct = ((1.0 - info.availMem.toDouble() / info.totalMem.toDouble()) * 100).toInt()
                ramView.text = "R$pct%"
                toolCfg["ram"]?.let { updatePet("ram", bucketLevel(pct, it.buckets)) }
            }
        }
        runCatching {
            val path = Environment.getDataDirectory().absolutePath
            val stat = StatFs(path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val avail = stat.availableBlocksLong * stat.blockSizeLong
            val pct = ((1.0 - avail.toDouble() / total.toDouble()) * 100).toInt()
            storageView.text = "S$pct%"
            toolCfg["storage"]?.let { updatePet("storage", bucketLevel(pct, it.buckets)) }
        }
        runCatching {
            // CPU load — the raw 1-minute /proc/loadavg figure, SAME source +
            // value the SystemInfoPopup shows (SysfsProc.cpuLoad().loadAvg1m).
            // Suffixed "s" per Diego's spec; NOT a core-normalised percentage
            // (that rounded to 0 on an idle phone — the bug being fixed).
            com.diegonmarcos.superapp.battery.SysfsProc.cpuLoad()?.let { c ->
                cpuView.text = "C%.2fs".format(c.loadAvg1m)
                // pet bucket still wants an int "level"; reuse the normalised
                // load% (0-100+) purely for that, independent of the label.
                val lvlPct = (c.loadAvg1m / c.cores.coerceAtLeast(1) * 100).toInt().coerceAtLeast(0)
                toolCfg["cpu"]?.let { updatePet("cpu", bucketLevel(lvlPct, it.buckets)) }
            }
        }
    }
}
