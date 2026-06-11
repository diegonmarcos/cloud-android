package com.diegonmarcos.ide

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log

/**
 * The AIDL action + push broker. Implements IIdeService (see the .aidl, which
 * mirrors contract/ide-ipc-v1.json::aidl). Cloud-SuperApp binds this to
 * openFile / openWorkspace / revealInFiles / analyzeStorage / openRemote and to
 * register for push callbacks.
 *
 * Phase 1 wiring: the broker resolves each call to the owning fork via
 * ForkRegistry and forwards it (deep-link launch already works; provider/exporter
 * forwarding lands in Phase 2). Actions on an uninstalled/blocked domain return
 * false / no-op rather than throwing, and callbacks are held in a
 * RemoteCallbackList ready for the forks to drive.
 *
 * Exported with the signature IPC permission (see AndroidManifest) — a caller
 * signed with a different key gets SecurityException at bind time.
 */
class IdeService : Service() {

    private val callbacks = RemoteCallbackList<IIdeCallback>()

    private val binder = object : IIdeService.Stub() {
        override fun openFile(fileUri: String): Boolean {
            val fork = available(DOMAIN_EDITOR) ?: return false
            Log.d(TAG, "openFile → ${fork.domain}: $fileUri")
            return launchFork(fork, putUri(EXTRA_FILE_URI, fileUri))
        }

        override fun openWorkspace(workspaceId: String): Boolean {
            val fork = available(DOMAIN_EDITOR) ?: return false
            Log.d(TAG, "openWorkspace → ${fork.domain}: $workspaceId")
            return launchFork(fork, putUri(EXTRA_WORKSPACE_ID, workspaceId))
        }

        override fun revealInFiles(fileUri: String) {
            val fork = available(DOMAIN_FILES) ?: return
            Log.d(TAG, "revealInFiles → ${fork.domain}: $fileUri")
            launchFork(fork, putUri(EXTRA_FILE_URI, fileUri))
        }

        override fun analyzeStorage(volume: String) {
            val fork = available(DOMAIN_UTILS) ?: return
            Log.d(TAG, "analyzeStorage → ${fork.domain}: $volume")
            // TODO(Phase 2): forward to the utils exporter's scan entry point;
            // completion comes back via dispatchStorageScanComplete().
            launchFork(fork, putUri(EXTRA_VOLUME, volume))
        }

        override fun openRemote(endpointId: String): Boolean {
            val fork = available(DOMAIN_EDITOR) ?: return false
            Log.d(TAG, "openRemote → ${fork.domain}: $endpointId")
            // TODO(Phase 2c): the Acode plugin resolves endpointId against the
            // baked ide-endpoints.json (SFTP preset / gitea base). SFTP is WG-only.
            return launchFork(fork, putUri(EXTRA_ENDPOINT_ID, endpointId))
        }

        override fun registerCallback(cb: IIdeCallback) { callbacks.register(cb) }
        override fun unregisterCallback(cb: IIdeCallback) { callbacks.unregister(cb) }
    }

    private fun putUri(key: String, value: String): (Intent) -> Unit = { it.putExtra(key, value) }

    /** Launch the owning fork app, applying any extra. Delegates to ForkLauncher
        so the icon-less one-icon model works (launch by OPEN_FORK action, not a
        launcher intent). Deep-link extras become contractual once the fork
        exporters declare their VIEW intents (Phase 2). */
    private fun launchFork(fork: Fork, extra: (Intent) -> Unit): Boolean {
        val ok = ForkLauncher.launch(this, fork, extra)
        if (!ok) Log.w(TAG, "launchFork: ${fork.appId} not launchable (not installed?)")
        return ok
    }

    /** A fork is "available" for actions only if installed and not blocked. */
    private fun available(domain: String): Fork? =
        ForkRegistry.byDomain(domain)?.takeIf { it.blockedOn == null && it.isInstalled(this) }

    /** Fan a push out to every registered SuperApp callback. Driven by the fork
        exporters once they report events (Phase 2). */
    fun dispatchRecentChanged(domain: String) {
        val n = callbacks.beginBroadcast()
        for (i in 0 until n) {
            try { callbacks.getBroadcastItem(i).onRecentChanged(domain) } catch (_: Throwable) {}
        }
        callbacks.finishBroadcast()
    }

    fun dispatchStorageScanComplete(volume: String) {
        val n = callbacks.beginBroadcast()
        for (i in 0 until n) {
            try { callbacks.getBroadcastItem(i).onStorageScanComplete(volume) } catch (_: Throwable) {}
        }
        callbacks.finishBroadcast()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        callbacks.kill()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "IdeService"
        private const val DOMAIN_EDITOR = "editor"
        private const val DOMAIN_FILES = "files"
        private const val DOMAIN_UTILS = "utils"
        const val EXTRA_FILE_URI = "com.diegonmarcos.ide.extra.FILE_URI"
        const val EXTRA_WORKSPACE_ID = "com.diegonmarcos.ide.extra.WORKSPACE_ID"
        const val EXTRA_VOLUME = "com.diegonmarcos.ide.extra.VOLUME"
        const val EXTRA_ENDPOINT_ID = "com.diegonmarcos.ide.extra.ENDPOINT_ID"
    }
}
