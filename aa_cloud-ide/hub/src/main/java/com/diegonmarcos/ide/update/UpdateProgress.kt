package com.diegonmarcos.ide.update

/**
 * Process-wide observable state for the in-app updater. The download/install
 * pipeline writes states here; the Configs UI observes via [setListener].
 * Plain singleton + callback so the updater doesn't pull in LiveData. Mirrors
 * ea_cloud-superapp's UpdateProgress.
 */
object UpdateProgress {

    sealed class State {
        object Idle : State()
        object CheckingManifest : State()
        data class UpToDate(val tag: String) : State()
        data class Downloading(val percent: Int, val bytes: Long, val total: Long) : State()
        object Installing : State()
        object Done : State()
        data class Failed(val message: String) : State()
    }

    @Volatile var state: State = State.Idle
        private set

    private var listener: ((State) -> Unit)? = null

    fun setListener(l: ((State) -> Unit)?) {
        listener = l
        if (l != null) l(state)   // replay current state for late subscribers
    }

    fun update(next: State) {
        state = next
        listener?.invoke(next)
    }

    fun reset() { update(State.Idle) }
}
