package com.diegonmarcos.ide

import android.util.Base64
import org.json.JSONObject

/**
 * Read-only accessor over `data/ide-endpoints.json`, baked into
 * BuildConfig.ENDPOINTS_JSON_B64 at build time — no hardcoded URLs in Kotlin
 * (FIRE rule 4). The forks consume this same file for their pre-provisioned
 * defaults; the hub only needs the deep-link targets (code-server, workspace
 * root). Contains NO secrets.
 */
object Endpoints {
    private val root: JSONObject by lazy {
        JSONObject(String(Base64.decode(BuildConfig.ENDPOINTS_JSON_B64, Base64.DEFAULT)))
    }

    /** code-server browser URL (browser-only deep-link tile). */
    fun codeServerUrl(): String? =
        root.optJSONObject("editor")?.optString("code_server_url")?.takeIf { it.isNotEmpty() }

    /** The hub-owned shared workspace root path. */
    fun workspaceRoot(): String? =
        root.optJSONObject("workspace")?.optString("root_path")?.takeIf { it.isNotEmpty() }

    /** gitea HTTPS base (public :443). */
    fun giteaBase(): String? =
        root.optJSONObject("editor")?.optString("gitea_https_base")?.takeIf { it.isNotEmpty() }

    /** Count of configured SFTP presets (all WireGuard-only). */
    fun sftpCount(): Int = root.optJSONArray("sftp")?.length() ?: 0
}
