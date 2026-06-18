package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.SystemInfoPopup
import com.diegonmarcos.superapp.settings.LauncherTheme
import com.diegonmarcos.superapp.cloud.CalendarAgendaPopup
import com.diegonmarcos.superapp.battery.BatteryIconView
import com.diegonmarcos.superapp.battery.BatteryEstimatePopup
import com.diegonmarcos.superapp.network.NetworkInfoPopup

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
    private val dateTimeView: TextView
    private val ramView: TextView
    private val storageView: TextView
    private val batteryView: BatteryIconView

    private var hasWifi = false
    private var hasCellular = false
    private var hasVpn = false
    private var hasBluetooth = false
    private var hasUsbData = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val metricsTicker = object : Runnable {
        override fun run() {
            refreshMetrics()
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
        val innerRow = FrameLayout(context).apply {
            setPadding(hpad, 0, hpad, 0)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
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
        // Any of the left-cluster icons → NetworkInfoPopup (shared
        // popup per cluster, per Diego's "yes click any, they are a
        // cluster" answer). Reusing the same anchor (the tapped icon)
        // keeps the bubble close to where the user tapped.
        val openNetworkPopup = OnClickListener { v -> NetworkInfoPopup.show(context, v) }
        for (v in listOf(signal5gView, wifiView, wgView, btView, usbView)) {
            v.isClickable = true
            v.setOnClickListener(openNetworkPopup)
        }
        leftCluster.addView(signal5gView)
        leftCluster.addView(wifiView)
        leftCluster.addView(wgView)
        leftCluster.addView(btView)
        leftCluster.addView(usbView)
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
        innerRow.addView(dateTimeView)

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
        // RAM + Storage → shared SystemInfoPopup (per Diego's cluster
        // rule). Same anchor (tapped icon) as battery / network popups.
        val openSystemPopup = OnClickListener { v -> SystemInfoPopup.show(context, v) }
        for (v in listOf(ramView, storageView)) {
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
        rightCluster.addView(ramView)
        rightCluster.addView(storageView)
        rightCluster.addView(batteryView)
        innerRow.addView(rightCluster)

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
            // Piggy-back metrics on the once-a-minute tick — cheap
            // compared to the per-10s Handler poll and keeps RAM /
            // storage from drifting if the Handler is delayed.
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
        refreshMetrics()
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
            }
        }
        runCatching {
            val path = Environment.getDataDirectory().absolutePath
            val stat = StatFs(path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val avail = stat.availableBlocksLong * stat.blockSizeLong
            val pct = ((1.0 - avail.toDouble() / total.toDouble()) * 100).toInt()
            storageView.text = "S$pct%"
        }
    }
}
