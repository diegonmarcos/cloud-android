package com.diegonmarcos.superapp.mail

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Mail section drawer page — placeholder. Reads JmapPrefs to show the
 * currently-configured account; folder list is hardcoded for now and will
 * grow into a Mailbox/get-driven RecyclerView once Slice B lands.
 *
 * This is the slot where FairEmail's account/folder tree will eventually
 * cherry-pick; the drawer-pagination plumbing is ready for it.
 */
class MailDrawerFragment : Fragment(R.layout.fragment_mail_drawer) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = JmapPrefs(requireContext())
        val accountLabel = prefs.email.ifEmpty { "(not signed in)" }
        view.findViewById<TextView>(R.id.tv_account_header).text = accountLabel
        view.findViewById<TextView>(R.id.tv_server).text = prefs.server.ifEmpty {
            BuildConfig.JMAP_DEFAULT_SERVER
        }
    }

    companion object { fun newInstance() = MailDrawerFragment() }
}
