package com.diegonmarcos.cloudnav.configs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudnav.maps.MapsStopsFragment
import com.diegonmarcos.superapp.updater.AbiUpdateTag
import com.diegonmarcos.superapp.updater.BuildConfig as UpdBuildConfig
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.Updater
import com.google.android.material.button.MaterialButton

/**
 * Configs → Update tab. The in-app self-updater surface:
 *   • current installed version + the GHCR image:tag this device pulls
 *     (ABI-aware — arm64 → latest, x86_64 → latest-x86_64),
 *   • a live status line driven by [UpdateProgress] (checking → downloading
 *     % → installing → done / failed),
 *   • "Check for updates now" → [Updater.checkNow],
 *   • "Allow app installs" → the per-app unknown-sources settings screen
 *     (Android requires the user to permit install-from-this-app once).
 *
 * All config is data-driven from build.json::release.{ghcr,auto_update} baked
 * into the updater BuildConfig — nothing hardcoded here.
 */
class UpdateConfigFragment : Fragment() {

    private var statusView: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundColor(MapsStopsFragment.COL_SURFACE)
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16); setPadding(p, p, p, p)
        }
        scroll.addView(root)

        root.addView(header(ctx, "App update"))

        val version = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "?"
        root.addView(kv(ctx, "Installed version", version))
        root.addView(kv(ctx, "Package", ctx.packageName))
        root.addView(kv(ctx, "Channel", AbiUpdateTag.current()))
        root.addView(kv(ctx, "Source",
            "${UpdBuildConfig.GHCR_REGISTRY}/${UpdBuildConfig.GHCR_NAMESPACE}/${UpdBuildConfig.GHCR_IMAGE}"))
        root.addView(kv(ctx, "Auto-check",
            if (UpdBuildConfig.AUTO_UPDATE_ENABLED) "every ${UpdBuildConfig.AUTO_UPDATE_INTERVAL_HOURS}h" else "off"))

        root.addView(spacer(ctx, dp(16)))
        statusView = TextView(ctx).apply {
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            setTextColor(MapsStopsFragment.COL_PRIMARY)
            text = "Idle"
        }
        root.addView(statusView)

        root.addView(spacer(ctx, dp(16)))
        root.addView(MaterialButton(ctx).apply {
            text = "Check for updates now"
            setOnClickListener {
                Updater.checkNow(ctx)
                statusView?.text = "Checking…"
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        })
        root.addView(spacer(ctx, dp(8)))
        root.addView(MaterialButton(ctx).apply {
            text = "Allow app installs"
            setOnClickListener { openUnknownSourcesSettings() }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        })

        root.addView(spacer(ctx, dp(12)))
        root.addView(TextView(ctx).apply {
            text = "Updates are signed with the shared Cloud-constellation key, so SuperApp / Nav / Comms / IDE update each other without a signature conflict. The system shows an install prompt — Android can't bypass it without root."
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setTextColor(MapsStopsFragment.COL_SECONDARY)
        })

        return scroll
    }

    override fun onResume() {
        super.onResume()
        UpdateProgress.setListener { state -> ui { statusView?.text = render(state) } }
    }

    override fun onPause() {
        UpdateProgress.setListener(null)
        super.onPause()
    }

    private fun render(state: UpdateProgress.State): String = when (state) {
        is UpdateProgress.State.Idle             -> "Idle"
        is UpdateProgress.State.CheckingManifest -> "Checking GHCR for a newer build…"
        is UpdateProgress.State.Downloading      -> "Downloading… ${state.percent}%"
        is UpdateProgress.State.Installing       -> "Installing — confirm the system prompt…"
        is UpdateProgress.State.Done             -> "Up to date / installed ✓"
        is UpdateProgress.State.Failed           -> "Failed: ${state.message}"
    }

    private fun openUnknownSourcesSettings() {
        val ctx = context ?: return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)) }
        }
    }

    private fun header(ctx: android.content.Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextAppearance(android.R.style.TextAppearance_Material_Headline)
        setTextColor(MapsStopsFragment.COL_PRIMARY)
        setPadding(0, 0, 0, dp(12))
    }

    private fun kv(ctx: android.content.Context, k: String, v: String) = TextView(ctx).apply {
        text = "$k:  $v"
        setTextAppearance(android.R.style.TextAppearance_Material_Body2)
        setTextColor(MapsStopsFragment.COL_SECONDARY)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun spacer(ctx: android.content.Context, h: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, h)
    }

    private fun ui(block: () -> Unit) {
        if (!isAdded) return
        requireActivity().runOnUiThread { if (isAdded) block() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
