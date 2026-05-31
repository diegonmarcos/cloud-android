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
        /** Streaming the APK blob — [percent] is 0..100. */
        data class Downloading(val percent: Int, val bytes: Long, val total: Long) : State()
        /** APK is on disk; PackageInstaller session in progress. */
        object Installing : State()
        /** Install handed off — system dialog is up OR install completed. */
        object Done : State()
        data class Failed(val message: String) : State()
    }

    @Volatile var state: State = State.Idle
        private set

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

    fun reset() { update(State.Idle) }
}
