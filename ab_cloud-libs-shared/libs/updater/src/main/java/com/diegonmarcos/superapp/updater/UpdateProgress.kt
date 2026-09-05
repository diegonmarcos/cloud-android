package com.diegonmarcos.superapp.updater

import android.content.Context

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
        /**
         * [appId]/[pkg]/[apkPath] are optional context for the Diagnose screen:
         * a fleet install knows which app it was and which file it staged, and
         * without that the report can only describe the host app. Defaulted so
         * every existing `Failed("...")` call site keeps compiling and simply
         * produces a report about this app instead of the target.
         */
        data class Failed(
            val message: String,
            val appId: String = "",
            val pkg: String = "",
            val apkPath: String = "",
        ) : State()
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

    /**
     * Start of a fresh user-initiated download session — disarm any stale
     * cancel, and un-minimize: the user asking for an update again is the
     * clearest possible request to see it.
     */
    fun beginDownload() { cancelRequested = false; minimized = false }

    /**
     * Manual pass only: the user hit Minimize and wants the screen back.
     *
     * A latch rather than a one-off fragment removal, because the host
     * re-attaches the overlay on the very next progress tick — removing the
     * view alone would put it straight back a fraction of a second later.
     * It suppresses the UI and NOTHING ELSE: no cancel, no pause, the pass
     * never learns this happened. Cleared by [restore], by [beginDownload]
     * and by [reset], so it can never outlive the pass that set it.
     */
    @Volatile var minimized: Boolean = false
        private set

    fun minimize() { minimized = true }

    /**
     * Bring a minimized overlay back showing LIVE state — the replay below
     * reads the current [state] field, so what returns is where the pass is
     * now, not a snapshot from when it was minimized.
     */
    fun restore() {
        minimized = false
        if (!quiet) listener?.invoke(state)
    }

    /**
     * True when [state] must not reach the screen.
     *
     * Two independent reasons, one question, asked at the point that actually
     * draws: an unattended pass is in flight (nobody asked to watch), or the
     * user minimized a manual one (they asked NOT to watch).
     *
     * The unattended half reads the PERSISTED flag rather than [quiet].
     * [quiet] is a plain field, so it is only observable inside the process
     * that set it; the overlay is attached by an Activity that is a different
     * process for every satellite app, and a field gate there is not a gate at
     * all — it silently does nothing, which is exactly how this bug survived
     * being "fixed" more than once.
     *
     * [State.Failed] is deliberately never suppressed. Suppress progress,
     * never errors — silence about work that did not happen is hiding, not
     * quietness. [NotificationStore] push stays unconditional either way.
     */
    fun suppressed(ctx: Context, state: State): Boolean =
        state !is State.Failed &&
            (minimized || AutoUpdatePrefs.unattendedPass(ctx))

    /**
     * During a multi-app "Update all", names the current app + position
     * ("Chat · 2/5") so the overlay shows a stable header instead of a bar that
     * silently resets 0→100 per app — the "messy scrambling progress" bug.
     * null for single installs (nothing extra rendered).
     */
    @Volatile var batchLabel: String? = null
        private set

    /**
     * Unattended passes set this for their duration.
     *
     * "Silent" means NO VISIBLE PROGRESS: no overlay, no bar, no dialog. It
     * does NOT mean quiet — the pipeline keeps writing [state] and keeps
     * logging every event to logcat exactly as before, so a silent pass is
     * still fully readable with `logcat -s Fleet`. Only the listener (the
     * Activity's overlay) is left undriven, because an auto-update that pops a
     * progress bar over whatever the user is doing is not unattended.
     */
    @Volatile var quiet: Boolean = false

    /** Set before each app in a batch; total==1 ⇒ no prefix (single install). */
    fun beginBatch(label: String, index: Int, total: Int) {
        if (quiet) return
        batchLabel = if (total > 1) "$label · $index/$total" else null
    }

    fun endBatch() { if (quiet) return; batchLabel = null }

    private var listener: ((State) -> Unit)? = null

    fun setListener(l: ((State) -> Unit)?) {
        listener = l
        // Replay current state so a late subscriber catches up — but honour
        // [quiet], exactly as update() does.
        //
        // THIS WAS THE HOLE. update() has always refused to drive the overlay
        // during an unattended pass, so the pass itself was silent; but the
        // replay below was not gated, and an Activity re-subscribes on every
        // onResume. So a background pass stayed invisible right up until the
        // user opened the app for any reason, at which point the current
        // non-Idle state was replayed straight into a full-screen overlay —
        // once per return to the app, for work nobody asked to watch. The
        // gate has to be on BOTH paths or it is not a gate.
        if (l != null && !quiet) l(state)
    }

    fun update(next: State) {
        state = next
        if (!quiet) listener?.invoke(next)
    }

    fun reset() { batchLabel = null; minimized = false; update(State.Idle) }
}
