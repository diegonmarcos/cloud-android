package com.diegonmarcos.superapp.mail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Slice A — minimal JMAP login + session display. Server / email / password
 * persisted via EncryptedSharedPreferences (JmapPrefs). On Connect: hits
 * `<server>/.well-known/jmap` with Basic auth, parses the session JSON, shows
 * the api / download / upload / event-source URLs and the primary account.
 *
 * Default server pulled from BuildConfig.JMAP_DEFAULT_SERVER which the gradle
 * eval injects from build.json::ui.sections[mail].jmap_default_server.
 */
class MailFragment : Fragment() {

    private val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_mail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val prefs = JmapPrefs(ctx)

        val etServer:   TextInputEditText = view.findViewById(R.id.et_server)
        val etEmail:    TextInputEditText = view.findViewById(R.id.et_email)
        val etPassword: TextInputEditText = view.findViewById(R.id.et_password)
        val btnConnect: MaterialButton    = view.findViewById(R.id.btn_connect)
        val btnLogout:  MaterialButton    = view.findViewById(R.id.btn_logout)
        val tvStatus:   TextView          = view.findViewById(R.id.tv_status)
        val tvSession:  TextView          = view.findViewById(R.id.tv_session)

        etServer.setText(prefs.server.ifEmpty { BuildConfig.JMAP_DEFAULT_SERVER })
        etEmail.setText(prefs.email)
        etPassword.setText(prefs.password)

        btnConnect.setOnClickListener {
            val server   = etServer.text.toString().trim()
            val email    = etEmail.text.toString().trim()
            val password = etPassword.text.toString()
            prefs.server   = server
            prefs.email    = email
            prefs.password = password

            tvStatus.setText(R.string.mail_status_connecting)
            tvSession.text = ""
            viewLifecycleOwner.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    JmapClient(server, email, password).discover()
                }
                when (result) {
                    is JmapClient.Result.Success -> {
                        val s = result.value
                        tvStatus.text = getString(R.string.mail_status_ok, s.primaryAccountId)
                        tvSession.text = buildString {
                            appendLine("${getString(R.string.mail_session_apiurl)}: ${s.apiUrl}")
                            appendLine("downloadUrl: ${s.downloadUrl}")
                            appendLine("uploadUrl:   ${s.uploadUrl}")
                            appendLine("eventSrcUrl: ${s.eventSourceUrl}")
                            appendLine()
                            appendLine(getString(R.string.mail_session_accounts) + ":")
                            s.accounts.forEach { (id, name) -> appendLine("  $id  → $name") }
                            appendLine()
                            appendLine("── full session ──")
                            appendLine(prettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), s.raw))
                        }
                    }
                    is JmapClient.Result.HttpError ->
                        tvStatus.text = getString(R.string.mail_status_http, result.code, result.body.take(200))
                    is JmapClient.Result.NetworkError ->
                        tvStatus.text = getString(R.string.mail_status_net, result.message)
                }
            }
        }

        btnLogout.setOnClickListener {
            prefs.clear()
            etServer.setText(BuildConfig.JMAP_DEFAULT_SERVER)
            etEmail.setText("")
            etPassword.setText("")
            tvStatus.text = ""
            tvSession.text = ""
        }
    }

    companion object { fun newInstance() = MailFragment() }
}
