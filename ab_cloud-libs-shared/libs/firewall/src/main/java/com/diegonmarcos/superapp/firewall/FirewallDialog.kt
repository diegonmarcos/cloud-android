package com.diegonmarcos.superapp.firewall

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment

/**
 * Firewall control screen — opened from the About → Firewall gray button.
 *
 * A master switch enables/disables the no-root engine (requesting the
 * system VPN consent on first enable). Below it, a scrollable list of
 * launchable apps each with a per-app block toggle. Views are built
 * programmatically (no resource deps) to match EnergyUsageDialog's style.
 */
class FirewallDialog : DialogFragment() {

    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val ctx = context ?: return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) FirewallController.start(ctx)
        else FirewallController.stop(ctx)
        refreshHeader()
    }

    private lateinit var headerState: TextView
    private lateinit var masterSwitch: Switch

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(0xFF111317.toInt())
        }

        root.addView(TextView(ctx).apply {
            text = "Firewall"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
        })
        headerState = TextView(ctx).apply {
            setTextColor(0x99FFFFFF.toInt())
            textSize = 12f
            setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(headerState)

        // Master enable row.
        val masterRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        masterRow.addView(TextView(ctx).apply {
            text = "Firewall enabled"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        masterSwitch = Switch(ctx).apply {
            isChecked = FirewallController.isEnabled(ctx)
            setOnClickListener { onMasterToggle(isChecked) }
        }
        masterRow.addView(masterSwitch)
        root.addView(masterRow)

        root.addView(TextView(ctx).apply {
            text = "Per-app rules apply while the firewall is on. Enabling takes " +
                "the device VPN slot, so the WireGuard tunnel can't run at the " +
                "same time (the staged firestack merge unifies them)."
            setTextColor(0x77FFFFFF.toInt())
            textSize = 11f
            setPadding(0, dp(6), 0, dp(12))
        })

        // Totals, so the Fires column has a scale to read against.
        root.addView(TextView(ctx).apply {
            val total = FirewallStats.totalFires(ctx)
            val configured = FirewallRules.configured(ctx).size
            text = "$configured app(s) with a rule · $total activation(s) total"
            setTextColor(0x99FFFFFF.toInt())
            textSize = 11f
            setPadding(0, 0, 0, dp(6))
        })

        // Column header. Every axis is shown for every app, including the ones
        // that are ON: the old one-line summary listed only what was switched
        // OFF, so a rule that blocked nothing and a rule that had never been
        // configured rendered identically.
        root.addView(tableHeader(ctx, ::dp))

        // Per-app block list (launchable apps only, alphabetical).
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val pm = ctx.packageManager
        pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null && it.packageName != ctx.packageName }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
            .forEach { list.addView(appRow(ctx, it, ::dp)) }

        root.addView(ScrollView(ctx).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        refreshHeader()
        return root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.85f).toInt()
        )
    }

    /** Column weights, shared by the header and every row so they line up.
     *  App gets the slack because names are the only variable-width column. */
    private val COL_APP = 3.2f
    private val COL_AXIS = 1.0f
    private val COL_VPN = 1.4f
    private val COL_DIR = 1.2f
    private val COL_FIRES = 1.0f

    private fun cell(
        ctx: Context,
        text: String,
        weight: Float,
        colour: Int,
        dp: (Int) -> Int,
        bold: Boolean = false,
    ) = TextView(ctx).apply {
        this.text = text
        setTextColor(colour)
        textSize = 11f
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, dp(4), 0)
        layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, weight,
        )
    }

    /** The table's column header. */
    private fun tableHeader(ctx: Context, dp: (Int) -> Int): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
            val c = 0xAAB794F4.toInt()
            addView(cell(ctx, "App", COL_APP, c, dp, bold = true))
            addView(cell(ctx, "Wi-Fi", COL_AXIS, c, dp, bold = true))
            addView(cell(ctx, "Cell", COL_AXIS, c, dp, bold = true))
            addView(cell(ctx, "Bg", COL_AXIS, c, dp, bold = true))
            addView(cell(ctx, "VPN", COL_VPN, c, dp, bold = true))
            addView(cell(ctx, "Dir", COL_DIR, c, dp, bold = true))
            addView(cell(ctx, "Fires", COL_FIRES, c, dp, bold = true))
        }

    /**
     * One app row, as a table line; tap opens the axis editor.
     *
     * Reads every axis rather than summarising, because the summary it replaces
     * only mentioned axes that were OFF — so "allowed everywhere" and "no rule
     * set" looked the same, and there was no way to see that Wi-Fi was
     * deliberately left ON.
     */
    private fun appRow(ctx: Context, app: ApplicationInfo, dp: (Int) -> Int): View {
        val pm = ctx.packageManager
        val cells = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        fun render() {
            cells.removeAllViews()
            val r = FirewallRules.rule(ctx, app.packageName)
            val fires = FirewallStats.fires(ctx, app.packageName)
            // A blocking axis is the thing worth spotting, so it is the one
            // that gets colour; everything permissive stays muted.
            val off = 0xFFF6AD55.toInt()
            val on = 0x88FFFFFF.toInt()
            val dim = 0x55FFFFFF.toInt()

            cells.addView(cell(ctx, pm.getApplicationLabel(app).toString(),
                COL_APP, if (r.isDefault) dim else 0xFFFFFFFF.toInt(), dp))
            // The transport toggles are inert under a VPN-only override — the
            // decider ignores them — so they are dimmed rather than shown as
            // if they still applied.
            val axisMuted = r.vpnOnly
            cells.addView(cell(ctx, if (r.wifi) "on" else "OFF",
                COL_AXIS, if (axisMuted) dim else if (r.wifi) on else off, dp))
            cells.addView(cell(ctx, if (r.cellular) "on" else "OFF",
                COL_AXIS, if (axisMuted) dim else if (r.cellular) on else off, dp))
            cells.addView(cell(ctx, if (r.background) "on" else "OFF",
                COL_AXIS, if (r.background) on else off, dp))
            cells.addView(cell(ctx, when (r.vpnMode) {
                VpnMode.WG0_ONLY -> "wg0"
                VpnMode.WG_PUBLIC_ONLY -> "wg-pub"
                VpnMode.NONE -> "—"
            }, COL_VPN, if (r.vpnOnly) off else dim, dp))
            cells.addView(cell(ctx, when (r.direction) {
                Direction.ALL -> "ALL"
                Direction.IN -> "IN"
                Direction.OUT -> "OUT"
                Direction.NONE -> "—"
            }, COL_DIR, if (r.direction == Direction.NONE) dim else off, dp))
            cells.addView(cell(ctx, if (fires == 0) "—" else fires.toString(),
                COL_FIRES, if (fires == 0) dim else off, dp))
        }
        render()

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            isClickable = true
            setOnClickListener { editApp(ctx, app, dp) { render() } }
            addView(cells)
        }
    }

    /**
     * Per-app rule editor — the parallel axes as independent controls:
     * data on Wi-Fi / Cellular / Cloud-VPN, background data, and a direction
     * selector. All combine (blocked if any axis blocks).
     */
    private fun editApp(ctx: Context, app: ApplicationInfo, dp: (Int) -> Int, onDone: () -> Unit) {
        val r = FirewallRules.rule(ctx, app.packageName)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        fun axisRow(label: String, on: Boolean): Switch {
            val sw = Switch(ctx).apply { isChecked = on }
            col.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                addView(TextView(ctx).apply {
                    text = label; textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(sw)
            })
            return sw
        }

        col.addView(TextView(ctx).apply {
            text = "Data axes — turn OFF to block that path"; textSize = 11f
            setTextColor(0x88000000.toInt()); setPadding(0, 0, 0, dp(4))
        })
        val wifi = axisRow("Wi-Fi data", r.wifi)
        val cell = axisRow("Cellular data", r.cellular)
        val bg = axisRow("Background data", r.background)

        col.addView(TextView(ctx).apply {
            text = "Cloud VPN — a strong override: forces the app VPN-only " +
                "(ignores Wi-Fi/Cellular; app talks ONLY through the tunnel)"
            textSize = 11f
            setTextColor(0x88000000.toInt()); setPadding(0, dp(10), 0, dp(2))
        })
        val vpnModes = listOf(
            VpnMode.NONE to "None",
            VpnMode.WG0_ONLY to "wg0 VPN only",
            VpnMode.WG_PUBLIC_ONLY to "wg-public VPN only",
        )
        val vpnGroup = RadioGroup(ctx)
        vpnModes.forEachIndexed { i, (m, label) ->
            vpnGroup.addView(RadioButton(ctx).apply { id = i + 1; text = label; isChecked = m == r.vpnMode })
        }
        col.addView(vpnGroup)

        col.addView(TextView(ctx).apply {
            text = "Direction"; textSize = 11f
            setTextColor(0x88000000.toInt()); setPadding(0, dp(10), 0, dp(2))
        })
        val dirs = listOf(
            Direction.NONE to "None", Direction.ALL to "Block all",
            Direction.IN to "Block incoming", Direction.OUT to "Block outgoing",
        )
        val group = RadioGroup(ctx)
        dirs.forEachIndexed { i, (d, label) ->
            // ids offset by 1 so a checked button never collides with View "no id" (0/-1)
            group.addView(RadioButton(ctx).apply { id = i + 1; text = label; isChecked = d == r.direction })
        }
        col.addView(group)

        android.app.AlertDialog.Builder(ctx)
            .setTitle(ctx.packageManager.getApplicationLabel(app))
            .setView(ScrollView(ctx).apply { addView(col) })
            .setPositiveButton("Save") { _, _ ->
                val direction = dirs.getOrNull(group.checkedRadioButtonId - 1)?.first ?: Direction.NONE
                val vpnMode = vpnModes.getOrNull(vpnGroup.checkedRadioButtonId - 1)?.first ?: VpnMode.NONE
                val rule = AppRule(wifi.isChecked, cell.isChecked, bg.isChecked, vpnMode, direction)
                FirewallRules.setRule(ctx, app.packageName, rule)
                FirewallController.refresh(ctx)
                if (!rule.background && !FirewallConditions.hasUsageAccess(ctx)) promptUsageAccess(ctx)
                onDone(); refreshHeader()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** "No background data" needs usage-access to know the foreground app. */
    private fun promptUsageAccess(ctx: Context) {
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Usage access needed")
            .setMessage("\"No background data\" needs Usage Access to detect which app is in the foreground. Grant it now?")
            .setPositiveButton("Open settings") { _, _ ->
                runCatching { startActivity(android.content.Intent(FirewallConditions.USAGE_ACCESS_SETTINGS)) }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun onMasterToggle(enable: Boolean) {
        val ctx = requireContext()
        if (enable) {
            val consent = FirewallController.consentIntent(ctx)
            if (consent != null) vpnConsent.launch(consent) else FirewallController.start(ctx)
        } else {
            FirewallController.stop(ctx)
        }
        refreshHeader()
    }

    private fun refreshHeader() {
        val ctx = context ?: return
        val s = FirewallInfo.read(ctx)
        if (::headerState.isInitialized) {
            headerState.text = "${FirewallInfo.fmtState(s)} · ${s.blockedCount} blocked · ${s.transport}" +
                (if (s.systemVpnActive) " · VPN" else "")
        }
        if (::masterSwitch.isInitialized) masterSwitch.isChecked = s.enabled
    }

    companion object { const val TAG = "FirewallDialog" }
}
