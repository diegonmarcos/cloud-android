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
            text = "Blocked apps lose internet while the firewall is on. " +
                "Enabling takes the device VPN slot, so the WireGuard tunnel " +
                "can't run at the same time."
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

    private fun appRow(ctx: Context, app: ApplicationInfo, dp: (Int) -> Int): View {
        val pm = ctx.packageManager
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(ctx).apply {
                text = pm.getApplicationLabel(app).toString()
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Switch(ctx).apply {
                isChecked = FirewallPrefs.isBlocked(ctx, app.packageName)
                setOnClickListener {
                    FirewallPrefs.setBlocked(ctx, app.packageName, isChecked)
                    FirewallController.refresh(ctx) // re-apply if running
                    refreshHeader()
                }
            })
        }
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
