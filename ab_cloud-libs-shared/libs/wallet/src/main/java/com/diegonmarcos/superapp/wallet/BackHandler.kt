package com.diegonmarcos.superapp.wallet

/**
 * Fragments that want to intercept the Back gesture (toolbar action
 * AND system back) before the activity pops their hosting back-stack
 * entry. Implemented by [WalletFragment] so its Compose state machine
 * can unwind one step at a time. MainActivity casts the visible
 * fragment to this interface from its action_back handler.
 *
 * Lives in libs:wallet today because wallet is the only consumer; if
 * another lib needs the same dispatch, lift this to libs:core.
 */
interface BackHandler {
    /** Return true to consume the back gesture. */
    fun tryHandleBack(): Boolean
}
