package com.diegonmarcos.superapp.wallet

/**
 * Contract the host activity implements so the wallet lib can ask
 * for cross-surface navigation without depending on app/.
 *
 * Today: only [onOpenVcard] (handled by MainActivity by pushing the
 * existing BusinessCardFragment onto the back-stack). Future
 * cross-surface entries — open the Virtual Business Card, open a
 * Maps activity, share a pass — land here too.
 */
interface WalletHost {
    /** User tapped the pinned vCard at the top of the deck — host
     *  should open the canonical Virtual Business Card surface. */
    fun onOpenVcard()
}
