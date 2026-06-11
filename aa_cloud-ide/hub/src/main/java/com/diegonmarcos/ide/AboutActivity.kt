package com.diegonmarcos.ide

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * About — "full of info" screen, entirely data-driven from BuildConfig (baked
 * from build.json + contract), ForkRegistry (build.json::forks) and Endpoints
 * (data/ide-endpoints.json). Nothing hardcoded here that isn't a label. Same
 * Configs-subitem role as Cloud-SuperApp's About.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.about_title)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        section(col, getString(R.string.about_app))
        kv(col, "App", "${getString(R.string.app_name)} (hub)")
        kv(col, "Package", packageName)
        kv(col, "Version", "${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        kv(col, "Git", BuildConfig.GIT_SHORT_SHA)
        kv(col, "IPC contract", "v${BuildConfig.IPC_VERSION}")
        kv(col, "Authority", BuildConfig.IPC_AUTHORITY)
        kv(col, "Permission", "${BuildConfig.IPC_PERMISSION} (signature)")

        section(col, getString(R.string.about_updates))
        kv(col, "Channel", "${BuildConfig.GHCR_REGISTRY}/${BuildConfig.GHCR_NAMESPACE}/${BuildConfig.GHCR_IMAGE}")
        kv(col, "Tracking tag", BuildConfig.AUTO_UPDATE_TAG)
        kv(col, "Auto-update", if (BuildConfig.AUTO_UPDATE_ENABLED) "on (every ${BuildConfig.AUTO_UPDATE_INTERVAL_HOURS}h)" else "off")

        section(col, getString(R.string.about_forks))
        for (f in ForkRegistry.forks) {
            val state = when {
                f.blockedOn != null -> "blocked: ${f.blockedOn}"
                f.isInstalled(this) -> "installed"
                else -> "not installed"
            }
            kv(col, f.domain.replaceFirstChar { it.uppercase() },
                "${f.appId}\n${f.license} · ${f.runtime} · pin ${f.pinnedTag.ifEmpty { "—" }} · $state")
        }

        section(col, getString(R.string.about_endpoints))
        kv(col, "Workspace", Endpoints.workspaceRoot() ?: "—")
        kv(col, "Git remote", Endpoints.giteaBase() ?: "—")
        kv(col, "code-server", Endpoints.codeServerUrl() ?: "—")
        kv(col, "SFTP presets", "${Endpoints.sftpCount()} (WireGuard-only)")

        section(col, getString(R.string.about_links))
        link(col, "Source: https://github.com/diegonmarcos/unix")
        link(col, "Releases: https://github.com/diegonmarcos/unix/releases")

        setContentView(ScrollView(this).apply {
            addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
        })
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            alpha = 0.7f
            setPadding(0, dp(18), 0, dp(6))
        })
    }

    private fun kv(parent: LinearLayout, key: String, value: String) {
        parent.addView(TextView(this).apply {
            text = "$key:  $value"
            textSize = 14f
            setPadding(0, dp(3), 0, dp(3))
            setTextIsSelectable(true)
        })
    }

    private fun link(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(0, dp(3), 0, dp(3))
            autoLinkMask = Linkify.WEB_URLS
            movementMethod = LinkMovementMethod.getInstance()
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
