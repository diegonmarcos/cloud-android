package com.diegonmarcos.superapp.mail

import android.os.Bundle
import android.view.View
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
 * Slice C2 — single-message view. Fetches Email/get with bodyValues and
 * fetchTextBodyValues=true (one round-trip) and renders the plain-text
 * body. HTML body deferred (needs WebView); `hasHtml` is exposed so the
 * UI can later add a "view HTML" toggle.
 */
class MailMessageBodyFragment : Fragment(R.layout.fragment_mail_message_body) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = arguments ?: Bundle.EMPTY
        val emailId = args.getString(ARG_EMAIL_ID, "")

        val subjectTv = view.findViewById<TextView>(R.id.tv_msg_subject_full)
        val fromTv    = view.findViewById<TextView>(R.id.tv_msg_from_full)
        val toTv      = view.findViewById<TextView>(R.id.tv_msg_to_full)
        val dateTv    = view.findViewById<TextView>(R.id.tv_msg_date_full)
        val bodyTv    = view.findViewById<TextView>(R.id.tv_msg_body)
        val statusTv  = view.findViewById<TextView>(R.id.tv_msg_status)
        val spinner   = view.findViewById<ProgressBar>(R.id.pb_msg_loading)

        // Use args as fast initial render so the user sees headers immediately
        // while we fetch the body.
        subjectTv.text = args.getString(ARG_SUBJECT, "")
        fromTv.text    = args.getString(ARG_FROM, "")
        dateTv.text    = args.getString(ARG_DATE, "")

        val prefs = JmapPrefs(requireContext())
        if (prefs.email.isEmpty() || prefs.password.isEmpty() || emailId.isEmpty()) {
            spinner.isVisible = false
            statusTv.setText(R.string.mail_message_login_first)
            return
        }
        statusTv.setText(R.string.mail_message_loading)
        spinner.isVisible = true

        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val client = JmapClient(prefs.server, prefs.email, prefs.password)
                when (val s = client.discover()) {
                    is JmapClient.Result.Success     -> client.emailBody(s.value, emailId) to null
                    is JmapClient.Result.HttpError   -> null to "HTTP ${s.code}: ${s.body.take(200)}"
                    is JmapClient.Result.NetworkError -> null to "net: ${s.message}"
                }
            }
            spinner.isVisible = false
            val (result, sessionErr) = outcome
            if (sessionErr != null) {
                statusTv.text = getString(R.string.mail_message_error, sessionErr)
                return@launch
            }
            when (val r = result!!) {
                is JmapClient.Result.Success -> {
                    val body = r.value
                    // Refresh headers with the server-canonical values
                    if (body.subject.isNotBlank()) subjectTv.text = body.subject
                    if (body.from.isNotBlank())    fromTv.text    = body.from
                    if (body.to.isNotBlank())      toTv.text      = "to: " + body.to
                    dateTv.text = formatDate(body.receivedAt)
                    bodyTv.text = body.text
                    statusTv.text = if (body.hasHtml)
                        getString(R.string.mail_message_has_html) else ""
                }
                is JmapClient.Result.HttpError ->
                    statusTv.text = getString(R.string.mail_message_error, "HTTP ${r.code}: ${r.body.take(200)}")
                is JmapClient.Result.NetworkError ->
                    statusTv.text = getString(R.string.mail_message_error, r.message)
            }
        }
    }

    private fun formatDate(iso: String): String = try {
        DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(iso))
    } catch (_: Throwable) { iso }

    companion object {
        const val ARG_EMAIL_ID = "email_id"
        const val ARG_SUBJECT  = "subject"
        const val ARG_FROM     = "from"
        const val ARG_DATE     = "date"

        fun newInstance(emailId: String, subject: String, from: String, date: String) =
            MailMessageBodyFragment().apply {
                arguments = bundleOf(
                    ARG_EMAIL_ID to emailId,
                    ARG_SUBJECT  to subject,
                    ARG_FROM     to from,
                    ARG_DATE     to date,
                )
            }
    }
}
