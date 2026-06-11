package com.diegonmarcos.ide

import android.net.Uri

/**
 * Kotlin mirror of `contract/ide-ipc-v1.json`. The authority/permission/version
 * come from BuildConfig (baked from the same contract file at build time, so they
 * can't drift); the table paths + column orders are the stable wire shape every
 * fork exporter and Cloud-SuperApp must agree on.
 *
 * The Phase-5 conformance suite asserts these constants against the JSON.
 */
object IdeContract {
    const val VERSION: Int = BuildConfig.IPC_VERSION
    const val AUTHORITY: String = BuildConfig.IPC_AUTHORITY
    const val PERMISSION: String = BuildConfig.IPC_PERMISSION

    /** Per-fork exporter authority for domain d, e.g. editor → ...ide.editor.provider. */
    fun forkAuthority(domain: String): String = "com.diegonmarcos.ide.$domain.provider"

    val DOMAINS = listOf("editor", "files", "utils")

    object Tables {
        const val WORKSPACES = "workspaces"
        const val RECENT_FILES = "recent_files"
        const val GIT_REPOS = "git_repos"
        const val STORAGE_SUMMARY = "storage_summary"
        const val TRANSFERS = "transfers"
        val ALL = listOf(WORKSPACES, RECENT_FILES, GIT_REPOS, STORAGE_SUMMARY, TRANSFERS)
    }

    /** Column orders MUST match contract/ide-ipc-v1.json::tables.<t>.columns. */
    object Columns {
        val WORKSPACES = arrayOf("domain", "workspace_id", "name", "root_uri", "kind", "last_opened_ts")
        val RECENT_FILES = arrayOf("domain", "workspace_id", "file_uri", "display_name", "mime", "last_opened_ts")
        val GIT_REPOS = arrayOf("workspace_id", "remote_url", "branch", "dirty", "ahead", "behind", "last_fetch_ts")
        val STORAGE_SUMMARY = arrayOf("volume", "total_bytes", "used_bytes", "junk_bytes", "duplicate_bytes", "scanned_ts")
        val TRANSFERS = arrayOf("transfer_id", "direction", "peer", "file_uri", "state", "bytes_done", "bytes_total")

        fun forTable(table: String): Array<String> = when (table) {
            Tables.WORKSPACES -> WORKSPACES
            Tables.RECENT_FILES -> RECENT_FILES
            Tables.GIT_REPOS -> GIT_REPOS
            Tables.STORAGE_SUMMARY -> STORAGE_SUMMARY
            Tables.TRANSFERS -> TRANSFERS
            else -> emptyArray()
        }
    }

    /** Allowed transfer states (contract::states.transfer). */
    object TransferState {
        const val QUEUED = "queued"
        const val RUNNING = "running"
        const val PAUSED = "paused"
        const val DONE = "done"
        const val ERROR = "error"
    }

    /** Allowed storage-scan states (contract::states.scan). */
    object ScanState {
        const val IDLE = "idle"
        const val SCANNING = "scanning"
        const val DONE = "done"
        const val ERROR = "error"
    }

    fun hubUri(table: String): Uri =
        Uri.parse("content://$AUTHORITY/$table")

    fun forkUri(domain: String, table: String): Uri =
        Uri.parse("content://${forkAuthority(domain)}/$table")
}
