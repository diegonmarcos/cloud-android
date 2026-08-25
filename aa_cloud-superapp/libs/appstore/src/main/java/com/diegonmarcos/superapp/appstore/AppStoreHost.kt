package com.diegonmarcos.superapp.appstore

/**
 * What the store needs from whatever app is hosting it.
 *
 * The store is a library now, so it cannot name the app's MainActivity or
 * reach the app's R — that coupling is exactly what moving it out of the app
 * exposed. The host supplies both once at startup instead.
 *
 * Deliberately not an interface with a single implementation: two plain
 * fields set in Application.onCreate is the whole contract, and it degrades
 * to "no notification tap target" rather than crashing if a host forgets.
 */
object AppStoreHost {

    /** Activity the update notification opens. Null = no content intent. */
    @Volatile var launchActivity: Class<*>? = null

    /**
     * Small icon for the update notification. Must be a drawable in the HOST's
     * resources; a library cannot supply one the host's notification manager
     * will resolve.
     */
    @Volatile var notificationIcon: Int = android.R.drawable.stat_sys_download

    /**
     * Extras put on the launch Intent, so the host decides how the tap is
     * routed. SuperApp uses its launcher's shortcut_action grammar
     * ("action:constellation"); another host can use its own.
     */
    @Volatile var launchExtras: Map<String, String> = emptyMap()

    /**
     * Host veto on the periodic fleet check, on top of the auto-update
     * toggles the store owns. SuperApp wires this to its "Battery Hunger
     * Ones" switch; the store must not reach into the app's prefs class to
     * read it.
     *
     * Defaults to allowed so a host that sets nothing behaves as it did
     * before — a default of "denied" would silently stop checking.
     */
    @Volatile var periodicCheckAllowed: (android.content.Context) -> Boolean = { true }
}
