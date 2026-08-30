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

/**
 * Slice B — Mailbox/get against the saved JMAP account.
 *
 * Reads `JmapPrefs` (set by [MailFragment]'s Connect button), runs the
 * RFC 8621 `Mailbox/get` method against `session.apiUrl`, and renders the
 * resulting tree as an indented LinearLayout list (no RecyclerView needed
 * for ~10s of mailboxes — adds zero new gradle deps).
 *
 * If creds are absent the user is told to log in via the Mail bottom-nav.
 */
class MailFoldersFragment : Fragment(R.layout.fragment_mail_folders) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = JmapPrefs(requireContext())

        val tvStatus = view.findViewById<TextView>(R.id.tv_folders_status)
        val pbLoad   = view.findViewById<ProgressBar>(R.id.pb_folders_loading)
        val list     = view.findViewById<LinearLayout>(R.id.list_folders)

        if (prefs.email.isEmpty() || prefs.password.isEmpty()) {
            pbLoad.isVisible = false
            tvStatus.setText(R.string.mail_folders_login_first)
            return
        }

        tvStatus.setText(R.string.mail_folders_loading)
        pbLoad.isVisible = true

        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val client = JmapClient(prefs.server, prefs.email, prefs.password)
                when (val s = client.discover()) {
                    is JmapClient.Result.Success     -> client.mailboxes(s.value) to null
                    is JmapClient.Result.HttpError   -> null to "HTTP ${s.code}: ${s.body.take(200)}"
                    is JmapClient.Result.NetworkError -> null to "net: ${s.message}"
                }
            }

            pbLoad.isVisible = false
            val (mailboxesResult, sessionErr) = outcome
            if (sessionErr != null) {
                tvStatus.text = getString(R.string.mail_folders_error, sessionErr)
                return@launch
            }
            when (val r = mailboxesResult!!) {
                is JmapClient.Result.Success -> renderTree(list, tvStatus, r.value)
                is JmapClient.Result.HttpError   ->
                    tvStatus.text = getString(R.string.mail_folders_error, "HTTP ${r.code}: ${r.body.take(200)}")
                is JmapClient.Result.NetworkError ->
                    tvStatus.text = getString(R.string.mail_folders_error, r.message)
            }
        }
    }

    /** Render the mailbox list as a top-down tree (parentId → child rows). */
    private fun renderTree(
        container: LinearLayout,
        status: TextView,
        mailboxes: List<JmapClient.Mailbox>,
    ) {
        container.removeAllViews()
        if (mailboxes.isEmpty()) {
            status.setText(R.string.mail_folders_empty)
            return
        }
        status.text = resources.getQuantityString(
            R.plurals.mail_folders_count, mailboxes.size, mailboxes.size,
        )

        val byParent = mailboxes.groupBy { it.parentId }
        val inflater = LayoutInflater.from(requireContext())

        fun emit(parentId: String?, depth: Int) {
            for (mb in byParent[parentId].orEmpty()) {
                val row = inflater.inflate(R.layout.item_mail_folder, container, false)
                row.findViewById<TextView>(R.id.tv_folder_icon).text   = iconFor(mb.role)
                row.findViewById<TextView>(R.id.tv_folder_name).text   = mb.name
                row.findViewById<TextView>(R.id.tv_folder_counts).text =
                    if (mb.unreadEmails > 0) "${mb.unreadEmails} / ${mb.totalEmails}"
                    else mb.totalEmails.toString()
                row.setPadding(
                    (12 + depth * 20).dp(), row.paddingTop,
                    row.paddingRight,        row.paddingBottom,
                )
                // Tap a folder → host swaps the content pane to the
                // per-folder messages view. Host (MainActivity) implements
                // MailHost; we don't reach into app/-side R.ids ourselves.
                row.setOnClickListener {
                    (activity as? MailHost)?.openMailPage(
                        pageId = MailPages.MESSAGES,
                        args = bundleOf(
                            MailMessagesFragment.ARG_FOLDER_ID   to mb.id,
                            MailMessagesFragment.ARG_FOLDER_NAME to mb.name,
                        ),
                    )
                }
                container.addView(row)
                emit(mb.id, depth + 1)
            }
        }
        emit(parentId = null, depth = 0)
    }

    private fun iconFor(role: String?): String = when (role?.lowercase()) {
        "inbox"   -> "✉"   // ✉
        "drafts"  -> "✎"   // ✎
        "sent"    -> "➤"   // ➤
        "trash"   -> "✖"   // ✖
        "junk", "spam" -> "⚠"  // ⚠
        "archive" -> "⎘"   // ⎘ (printer-feed) — closest to a box glyph in stdlib
        else      -> "◯"   // ◯
    }

    private fun Int.dp(): Int =
        (this * resources.displayMetrics.density).toInt()

    companion object {
        fun newInstance() = MailFoldersFragment()
    }
}
