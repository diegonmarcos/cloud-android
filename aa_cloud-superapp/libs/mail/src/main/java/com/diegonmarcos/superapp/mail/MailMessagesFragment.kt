package com.diegonmarcos.superapp.mail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Slice C — per-folder message list via JMAP Email/query → Email/get
 * back-reference (one HTTP round-trip). Last 50 messages, newest first.
 *
 * Args (set by [MailFoldersFragment] click handler):
 *  - ARG_FOLDER_ID    JMAP mailbox id passed into Email/query.filter.inMailbox
 *  - ARG_FOLDER_NAME  display title only
 */
class MailMessagesFragment : Fragment(R.layout.fragment_mail_messages) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = arguments ?: Bundle.EMPTY
        val folderName = args.getString(ARG_FOLDER_NAME, "(unknown)")
        val folderId   = args.getString(ARG_FOLDER_ID,   "")

        val titleTv  = view.findViewById<TextView>(R.id.messages_title)
        val statusTv = view.findViewById<TextView>(R.id.messages_subtitle)
        val list     = view.findViewById<LinearLayout>(R.id.messages_list)
        val spinner  = view.findViewById<ProgressBar>(R.id.messages_loading)

        titleTv.text = folderName

        val prefs = JmapPrefs(requireContext())
        if (prefs.email.isEmpty() || prefs.password.isEmpty()) {
            spinner.isVisible = false
            statusTv.setText(R.string.mail_messages_login_first)
            return
        }
        if (folderId.isEmpty()) {
            spinner.isVisible = false
            statusTv.setText(R.string.mail_messages_no_folder)
            return
        }

        spinner.isVisible = true
        statusTv.setText(R.string.mail_messages_loading)

        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val client = JmapClient(prefs.server, prefs.email, prefs.password)
                when (val s = client.discover()) {
                    is JmapClient.Result.Success     -> client.emails(s.value, folderId) to null
                    is JmapClient.Result.HttpError   -> null to "HTTP ${s.code}: ${s.body.take(200)}"
                    is JmapClient.Result.NetworkError -> null to "net: ${s.message}"
                }
            }
            spinner.isVisible = false
            val (result, sessionErr) = outcome
            if (sessionErr != null) {
                statusTv.text = getString(R.string.mail_messages_error, sessionErr)
                return@launch
            }
            when (val r = result!!) {
                is JmapClient.Result.Success ->
                    renderMessages(list, statusTv, r.value)
                is JmapClient.Result.HttpError ->
                    statusTv.text = getString(R.string.mail_messages_error, "HTTP ${r.code}: ${r.body.take(200)}")
                is JmapClient.Result.NetworkError ->
                    statusTv.text = getString(R.string.mail_messages_error, r.message)
            }
        }
    }

    private fun renderMessages(
        container: LinearLayout,
        status: TextView,
        emails: List<JmapClient.Email>,
    ) {
        container.removeAllViews()
        if (emails.isEmpty()) {
            status.setText(R.string.mail_messages_empty)
            return
        }
        status.text = resources.getQuantityString(
            R.plurals.mail_messages_count, emails.size, emails.size,
        )
        val inflater = LayoutInflater.from(requireContext())
        for (email in emails) {
            val row = inflater.inflate(R.layout.item_mail_message, container, false)
            row.findViewById<TextView>(R.id.tv_msg_from).text =
                if (email.from.isNotBlank()) email.from else "(no sender)"
            row.findViewById<TextView>(R.id.tv_msg_subject).text =
                if (email.subject.isNotBlank()) email.subject else "(no subject)"
            row.findViewById<TextView>(R.id.tv_msg_preview).text = email.preview
            row.findViewById<TextView>(R.id.tv_msg_date).text = formatDate(email.receivedAt)
            container.addView(row)
        }
    }

    /** Format "2026-05-29T19:01:23Z" → "May 29 19:01" in the device tz.
     *  Falls back to raw on any parse failure. */
    private fun formatDate(iso: String): String = try {
        val instant = Instant.parse(iso)
        DateTimeFormatter.ofPattern("MMM d HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(instant)
    } catch (_: Throwable) {
        iso
    }

    companion object {
        const val ARG_FOLDER_ID   = "folder_id"
        const val ARG_FOLDER_NAME = "folder_name"
        fun newInstance(folderId: String, folderName: String) = MailMessagesFragment().apply {
            arguments = bundleOf(ARG_FOLDER_ID to folderId, ARG_FOLDER_NAME to folderName)
        }
    }
}
