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

    /** One app row: label + current-rule summary; tap opens the axis editor. */
    private fun appRow(ctx: Context, app: ApplicationInfo, dp: (Int) -> Int): View {
        val pm = ctx.packageManager
        val summary = TextView(ctx).apply {
            setTextColor(0x88FFFFFF.toInt())
            textSize = 11f
        }
        fun renderSummary() { summary.text = summarize(FirewallRules.rule(ctx, app.packageName)) }
        renderSummary()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            isClickable = true
            setOnClickListener { editApp(ctx, app, dp) { renderSummary() } }
            addView(TextView(ctx).apply {
                text = pm.getApplicationLabel(app).toString()
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
            })
            addView(summary)
        }
    }

    /** Compact human summary of an [AppRule] for the list row. */
    private fun summarize(r: AppRule): String {
        if (r.isDefault) return "Allowed"
        val parts = mutableListOf<String>()
        when (r.vpnMode) {
            VpnMode.WG0_ONLY -> parts += "wg0 VPN only"
            VpnMode.WG_PUBLIC_ONLY -> parts += "wg-public VPN only"
            VpnMode.NONE -> { // transport toggles only matter without the override
                if (!r.wifi) parts += "No Wi-Fi"
                if (!r.cellular) parts += "No cellular"
            }
        }
        if (!r.background) parts += "No background"
        when (r.direction) {
            Direction.ALL -> parts += "Block all"
            Direction.IN -> parts += "Block incoming"
            Direction.OUT -> parts += "Block outgoing"
            Direction.NONE -> {}
        }
        return parts.joinToString(" · ")
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
