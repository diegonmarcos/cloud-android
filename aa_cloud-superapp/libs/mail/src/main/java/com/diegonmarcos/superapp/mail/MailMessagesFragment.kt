package com.diegonmarcos.superapp.mail

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment

/**
 * Per-folder messages view — Slice C target. Today: shows the folder name
 * + id passed in args. Next step (Slice C) wires Email/query + Email/get
 * against the saved JMAP account and renders a RecyclerView of messages.
 */
class MailMessagesFragment : Fragment(R.layout.fragment_mail_messages) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = arguments ?: Bundle.EMPTY
        val folderName = args.getString(ARG_FOLDER_NAME, "(unknown)")
        val folderId   = args.getString(ARG_FOLDER_ID,   "?")
        view.findViewById<TextView>(R.id.messages_title).text = folderName
        view.findViewById<TextView>(R.id.messages_subtitle).text =
            "JMAP mailbox id: $folderId\n\nSlice C will fetch Email/query → Email/get here."
    }

    companion object {
        const val ARG_FOLDER_ID   = "folder_id"
        const val ARG_FOLDER_NAME = "folder_name"
        fun newInstance(folderId: String, folderName: String) = MailMessagesFragment().apply {
            arguments = bundleOf(ARG_FOLDER_ID to folderId, ARG_FOLDER_NAME to folderName)
        }
    }
}
