package com.diegonmarcos.superapp.mail

import android.os.Bundle

/**
 * Bridge between libs:mail and the app shell. The host (MainActivity)
 * implements this to receive in-app navigation requests from mail
 * Fragments without libs:mail needing to know about app/-side resource
 * ids like R.id.fragment_container.
 *
 * Examples:
 *  - Folders → tap a folder row → host opens MailMessagesFragment with
 *    args = {folder_id, folder_name}.
 */
interface MailHost {
    /** Open a mail page in the host's main content pane. */
    fun openMailPage(pageId: String, args: Bundle? = null)
}
