package com.diegonmarcos.devcontrol

import android.content.Context

/**
 * The UI/process capabilities the loopback [DevControlServer] reaches into the
 * foreground app for. This is the app-agnostic contract that replaces the old
 * app-local `DevControlBridge.ActivityHost`: the lib knows nothing about
 * MainActivity, it only knows this interface.
 *
 * The app's Activity implements it and registers itself via
 * [DevControl.host] = this in onCreate/onResume, clearing it (== this) in
 * onDestroy/onPause. When no host is registered every call is a no-op and the
 * server's HTTP reply still completes (the user re-launches to finish the action).
 *
 * Derived 1:1 from the real server call sites — do not grow it speculatively.
 */
interface DevControlHost {
    /** POST /nav/goto?target=… — navigate to a tile target. */
    fun onTileFromServer(target: String)

    /** POST /nav/action?type=… and POST /system/update ("check_updates"). */
    fun onActionFromServer(actionType: String)

    /** POST /haptic?preset=… — fire a named haptic preset. */
    fun firePresetHaptic(preset: String)

    /** GET /state — snapshot of the live UI state map (section/label/mode/…). */
    fun stateSnapshot(): Map<String, String>
}

/**
 * App-supplied HTTP endpoints for the loopback [DevControlServer].
 *
 * The generic endpoints (system/diagnostics/nav/haptic/state/restart) live in
 * the lib. Everything app-specific — battery, phone-classify, energy, sysfs,
 * adb — is provided by the app through this registry so the lib carries ZERO
 * `com.diegonmarcos.superapp.*` references and stays symlinkable verbatim.
 *
 * The app registers one provider from its own code (keeping its subsystem refs
 * in the app); the server dispatches any authed op it doesn't itself handle to
 * [handle]. Return null for an unknown op → the server replies 404.
 */
fun interface DevControlEndpoints {
    /**
     * @param ctx application context
     * @param op canonical op name, e.g. "battery/state", "adb/exec"
     * @param query parsed query params
     * @return the reply, or null if this provider doesn't own [op].
     */
    fun handle(ctx: Context, op: String, query: Map<String, String>): DevControlReply?
}

/** A ready-to-send HTTP reply from a [DevControlEndpoints] provider. */
data class DevControlReply(
    val status: String = "200 OK",
    val body: String,
    val contentType: String = "application/json",
)
