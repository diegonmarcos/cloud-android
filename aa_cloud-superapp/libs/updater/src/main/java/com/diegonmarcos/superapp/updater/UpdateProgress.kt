package com.diegonmarcos.superapp.updater

/**
 * Process-wide observable state for the in-app updater. The
 * download/install pipeline writes states here as it advances;
 * the UI shell observes via [setListener] and shows / dismisses
 * an overlay accordingly.
 *
 * Kept as a plain singleton + callback so the updater module
 * doesn't pull in Coroutines / LiveData. The single subscriber is
 * always the main Activity.
 */
object UpdateProgress {

    sealed class State {
        object Idle : State()
        /** Pulling the manifest from GHCR (small, near-instant). */
        object CheckingManifest : State()
        /** An update exists but we're on a metered network, so auto-download was
         *  held back. The overlay prompts the user (Update now / Later) — tapping
         *  Update now calls Updater.downloadNow to fetch over data with consent. */
        data class UpdateAvailable(val totalBytes: Long) : State()
        /** Streaming the APK blob — [percent] is 0..100. */
        data class Downloading(val percent: Int, val bytes: Long, val total: Long) : State()
        /** APK is on disk; PackageInstaller session in progress. */
        object Installing : State()
        /** Install handed off — system dialog is up OR install completed. */
        object Done : State()
        data class Failed(val message: String) : State()
        /** User hit Cancel — overlay dismisses, worker is being torn down. */
        object Cancelled : State()
    }

    @Volatile var state: State = State.Idle
        private set

    /**
     * Process-wide cancel flag polled by every download loop — the WorkManager
     * self-update worker AND the raw fleet-install threads (ConstellationFragment
     * uses plain `thread {}`, which WorkManager.cancel can't reach). Armed by the
     * Cancel button via [requestCancel]; stays armed until the next download
     * session clears it with [beginDownload]. That "sticky until next session"
     * shape avoids a race where the overlay's reset clears the flag before a
     * background thread has polled it.
     */
    @Volatile var cancelRequested: Boolean = false
        private set

    /** Cancel button → arm cancellation for any in-flight download loop. */
    fun requestCancel() { cancelRequested = true }

    /** Start of a fresh user-initiated download session — disarm any stale cancel. */
    fun beginDownload() { cancelRequested = false }

    /**
     * During a multi-app "Update all", names the current app + position
     * ("Chat · 2/5") so the overlay shows a stable header instead of a bar that
     * silently resets 0→100 per app — the "messy scrambling progress" bug.
     * null for single installs (nothing extra rendered).
     */
    @Volatile var batchLabel: String? = null
        private set

    /** Set before each app in a batch; total==1 ⇒ no prefix (single install). */
    fun beginBatch(label: String, index: Int, total: Int) {
        batchLabel = if (total > 1) "$label · $index/$total" else null
    }

    fun endBatch() { batchLabel = null }

    private var listener: ((State) -> Unit)? = null

    fun setListener(l: ((State) -> Unit)?) {
        listener = l
        // Replay current state so a late subscriber catches up.
        if (l != null) l(state)
    }

    fun update(next: State) {
        state = next
        listener?.invoke(next)
    }

    fun reset() { batchLabel = null; update(State.Idle) }
}
